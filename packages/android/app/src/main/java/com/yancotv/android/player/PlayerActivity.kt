package com.yancotv.android.player

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.yancotv.android.R
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.EpgProgramme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import kotlin.math.roundToInt

/**
 * Fullscreen player. Attaches the shared [PlaybackController.player]
 * ExoPlayer to the local [PlayerView] so the decoder handed off from the
 * mini preview keeps running without a rebuffer.
 *
 * Overlays on top of the default Media3 controller:
 *  - **Zap bar** (top-start): channel name + LIVE pill + now/next programme,
 *    auto-surfaces on channel change and fades with the built-in controller.
 *  - **Quick info** (top-end): stream resolution / codec / bitrate / buffer,
 *    toggled by KEYCODE_INFO or KEYCODE_GUIDE. Auto-hides after 10 s.
 *  - **Program progress** (bottom, above controls): where we are in the
 *    current live EPG programme. Visible only when Media3 controller is
 *    visible AND the channel is LIVE with a "now" programme.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "YancoPlayerActivity"
        private const val CONTROLLER_TIMEOUT_MS = 4000
        private const val QUICK_INFO_AUTO_HIDE_MS = 10_000L
        private const val PROGRESS_TICK_MS = 15_000L
    }

    private val controller: PlaybackController by inject()
    private val epg: EpgRepository by inject()

    private lateinit var playerView: PlayerView

    // Zap bar
    private lateinit var zapBar: View
    private lateinit var zapLiveDot: View
    private lateinit var zapLiveLabel: TextView
    private lateinit var zapChannelName: TextView
    private lateinit var zapNow: TextView
    private lateinit var zapNext: TextView

    // Quick info
    private lateinit var quickInfo: View
    private lateinit var qiResolution: TextView
    private lateinit var qiCodec: TextView
    private lateinit var qiBitrate: TextView
    private lateinit var qiBuffer: TextView

    // Program progress
    private lateinit var progressRow: View
    private lateinit var ppTitle: TextView
    private lateinit var ppTime: TextView
    private lateinit var ppBar: ProgressBar

    private var listenerAttached = false
    private var controllerVisible = false
    private var currentProgramme: EpgProgramme? = null
    private var progressTickerJob: Job? = null
    private var quickInfoHideJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "onPlayerError ${error.errorCodeName}", error)
        }

        override fun onPlaybackStateChanged(state: Int) {
            val name = when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            Log.i(TAG, "onPlaybackStateChanged=$name")
            if (quickInfo.visibility == View.VISIBLE) refreshQuickInfo()
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (quickInfo.visibility == View.VISIBLE) refreshQuickInfo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)

        zapBar = findViewById(R.id.zap_bar)
        zapLiveDot = findViewById(R.id.zap_live_dot)
        zapLiveLabel = findViewById(R.id.zap_live_label)
        zapChannelName = findViewById(R.id.zap_channel_name)
        zapNow = findViewById(R.id.zap_now)
        zapNext = findViewById(R.id.zap_next)

        quickInfo = findViewById(R.id.quick_info)
        qiResolution = findViewById(R.id.qi_resolution)
        qiCodec = findViewById(R.id.qi_codec)
        qiBitrate = findViewById(R.id.qi_bitrate)
        qiBuffer = findViewById(R.id.qi_buffer)

        progressRow = findViewById(R.id.program_progress_row)
        ppTitle = findViewById(R.id.pp_title)
        ppTime = findViewById(R.id.pp_time)
        ppBar = findViewById(R.id.pp_bar)

        playerView.useController = true
        playerView.controllerAutoShow = true
        playerView.controllerHideOnTouch = true
        playerView.setControllerShowTimeoutMs(CONTROLLER_TIMEOUT_MS)

        // Tie our zap bar + program progress to Media3's own controller
        // visibility — one clock governs all overlays so they fade together
        // and don't linger after the user stops interacting.
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                controllerVisible = (visibility == View.VISIBLE)
                applyOverlayVisibility()
            },
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.currentItem.collect { item -> onItemChanged(item) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        attachShared()
        startProgressTicker()
    }

    override fun onPause() {
        super.onPause()
        controller.persistResumePoint()
    }

    override fun onStop() {
        super.onStop()
        progressTickerJob?.cancel()
        progressTickerJob = null
        quickInfoHideJob?.cancel()
        quickInfoHideJob = null
        playerView.player = null
    }

    override fun onDestroy() {
        if (listenerAttached) {
            controller.player.removeListener(playerListener)
            listenerAttached = false
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        attachShared()
    }

    private fun attachShared() {
        val target = controller.player
        if (!listenerAttached) {
            target.addListener(playerListener)
            listenerAttached = true
        }
        playerView.player = target
        playerView.requestFocus()
    }

    // ───── Zap bar / EPG ─────

    private fun onItemChanged(item: ContentItem?) {
        currentProgramme = null
        if (item == null) {
            zapBar.visibility = View.GONE
            progressRow.visibility = View.GONE
            return
        }
        val displayTitle = item.cleanTitle?.ifBlank { null } ?: item.title
        zapChannelName.text = displayTitle
        val isLive = item.type == ContentType.LIVE
        zapLiveDot.visibility = if (isLive) View.VISIBLE else View.GONE
        zapLiveLabel.visibility = if (isLive) View.VISIBLE else View.GONE
        zapNow.visibility = View.GONE
        zapNext.visibility = View.GONE
        zapBar.visibility = View.VISIBLE
        // Force the controller visible for a moment on channel change — the
        // built-in auto-hide takes it away after CONTROLLER_TIMEOUT_MS and
        // our listener fades the zap bar with it.
        playerView.showController()

        val tvgId = item.tvgId?.takeIf { it.isNotBlank() }
        if (!isLive || tvgId == null) {
            applyOverlayVisibility()
            return
        }
        lifecycleScope.launch {
            val nn = try {
                withContext(Dispatchers.IO) { epg.getNowNext(tvgId) }
            } catch (t: Throwable) {
                Log.w(TAG, "EPG lookup failed for tvgId=$tvgId", t)
                null
            }
            // Dropped stale response if the user zapped again mid-flight.
            if (controller.currentId != item.id) return@launch
            val now = nn?.now
            val next = nn?.next
            currentProgramme = now
            zapNow.text = now?.title?.let { "Now: $it" }.orEmpty()
            zapNow.visibility = if (now != null) View.VISIBLE else View.GONE
            zapNext.text = next?.title?.let { "Next: $it" }.orEmpty()
            zapNext.visibility = if (next != null) View.VISIBLE else View.GONE
            renderProgramProgress()
            applyOverlayVisibility()
        }
    }

    private fun applyOverlayVisibility() {
        zapBar.visibility = if (controllerVisible && controller.currentId != null) View.VISIBLE else View.GONE
        val liveWithEpg = currentProgramme != null &&
            controller.currentItem.value?.type == ContentType.LIVE
        progressRow.visibility = if (controllerVisible && liveWithEpg) View.VISIBLE else View.GONE
    }

    // ───── Program progress ─────

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = lifecycleScope.launch {
            while (isActive) {
                renderProgramProgress()
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    private fun renderProgramProgress() {
        val prog = currentProgramme ?: run {
            progressRow.visibility = View.GONE
            return
        }
        val nowSec = System.currentTimeMillis() / 1000L
        val total = (prog.endTime - prog.startTime).coerceAtLeast(1)
        val elapsed = (nowSec - prog.startTime).coerceIn(0, total)
        val remainingMin = ((prog.endTime - nowSec).coerceAtLeast(0) / 60L).toInt()
        val totalMin = ((total + 59L) / 60L).toInt()
        val pct = ((elapsed.toDouble() / total) * 1000.0).roundToInt().coerceIn(0, 1000)
        ppTitle.text = prog.title
        ppTime.text = "${remainingMin} min left • ${totalMin} min"
        ppBar.progress = pct
        if (nowSec >= prog.endTime) {
            // Programme rolled over — clear so next poll or next/next refresh
            // can replace it. Cheap safety; a proper refresh happens on the
            // next channel change or when the user re-opens EPG.
            currentProgramme = null
            progressRow.visibility = View.GONE
        }
    }

    // ───── Quick info ─────

    private fun toggleQuickInfo() {
        if (quickInfo.visibility == View.VISIBLE) {
            quickInfo.visibility = View.GONE
            quickInfoHideJob?.cancel()
            quickInfoHideJob = null
        } else {
            refreshQuickInfo()
            quickInfo.visibility = View.VISIBLE
            quickInfoHideJob?.cancel()
            quickInfoHideJob = lifecycleScope.launch {
                delay(QUICK_INFO_AUTO_HIDE_MS)
                quickInfo.visibility = View.GONE
            }
        }
    }

    private fun refreshQuickInfo() {
        val p = controller.player
        val videoFmt = p.videoFormat
        val audioFmt = p.audioFormat
        qiResolution.text = "res:    " + formatResolution(videoFmt)
        qiCodec.text = "codec:  " + formatCodec(videoFmt, audioFmt)
        qiBitrate.text = "bitrate:" + formatBitrate(videoFmt, audioFmt)
        qiBuffer.text = "buffer: " + formatBuffer(p)
    }

    private fun formatResolution(f: Format?): String {
        if (f == null || f.width <= 0 || f.height <= 0) return "—"
        val fps = f.frameRate.takeIf { it > 0f }?.let { String.format("%.0f", it) }
        return buildString {
            append(f.width).append('x').append(f.height)
            if (fps != null) append(" @").append(fps).append("fps")
        }
    }

    private fun formatCodec(v: Format?, a: Format?): String {
        val vc = v?.sampleMimeType?.substringAfter('/')?.uppercase()
        val ac = a?.sampleMimeType?.substringAfter('/')?.uppercase()
        return listOfNotNull(vc, ac).joinToString(" / ").ifEmpty { "—" }
    }

    private fun formatBitrate(v: Format?, a: Format?): String {
        val total = listOfNotNull(v?.bitrate, a?.bitrate).filter { it > 0 }.sum()
        if (total <= 0) return "—"
        return if (total >= 1_000_000) String.format("%.1f Mbps", total / 1_000_000.0)
        else String.format("%d kbps", total / 1000)
    }

    private fun formatBuffer(p: Player): String {
        val ahead = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
        return "${ahead / 1000}s"
    }

    // ───── Keys ─────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                finish()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                playerView.showController()
                return true
            }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                toggleQuickInfo()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (controller.previous()) return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (controller.next()) return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                val p = controller.player
                p.playWhenReady = !p.playWhenReady
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
