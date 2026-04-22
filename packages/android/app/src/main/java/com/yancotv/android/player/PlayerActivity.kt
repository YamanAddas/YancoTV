package com.yancotv.android.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
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
import androidx.media3.ui.AspectRatioFrameLayout
import com.yancotv.android.R
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.ResizeMode
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
        // MK.8.2 — live-edge poll cadence and "you're behind live" floor.
        // 1 s is fine; the subsequent UI write is a single TextView update.
        // 8 s threshold ignores the normal ExoPlayer live latency (a few
        // seconds behind the true edge by design) so the button only
        // appears after an actual pause/rewind.
        private const val LIVE_OFFSET_TICK_MS = 1_000L
        private const val LIVE_BEHIND_THRESHOLD_MS = 8_000L
    }

    private val controller: PlaybackController by inject()
    private val epg: EpgRepository by inject()
    private val prefs: AppPreferences by inject()

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

    // Jump-to-LIVE (MK.8.2 timeshift)
    private lateinit var liveJumpBar: View
    private lateinit var liveOffsetLabel: TextView
    private lateinit var liveJumpButton: Button

    private var listenerAttached = false
    private var controllerVisible = false
    private var currentProgramme: EpgProgramme? = null
    private var progressTickerJob: Job? = null
    private var quickInfoHideJob: Job? = null
    private var liveOffsetTickerJob: Job? = null
    private var inPip = false

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

        liveJumpBar = findViewById(R.id.live_jump_bar)
        liveOffsetLabel = findViewById(R.id.live_offset_label)
        liveJumpButton = findViewById(R.id.live_jump_button)
        liveJumpButton.setOnClickListener { jumpToLive() }

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
        // Settings → Playback → Aspect. Re-applies whenever the user flips
        // the chip row so a fullscreen session can change between Fit / Fill
        // / Zoom without restarting. PlayerView reads the mode on the next
        // frame layout pass.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                prefs.playbackFlow.collect { playerView.resizeMode = it.resizeMode.toPlayerViewMode() }
            }
        }
    }

    private fun ResizeMode.toPlayerViewMode(): Int = when (this) {
        ResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        ResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        ResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

    override fun onStart() {
        super.onStart()
        attachShared()
        startProgressTicker()
        startLiveOffsetTicker()
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
        liveOffsetTickerJob?.cancel()
        liveOffsetTickerJob = null
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

    // ───── PIP (MK.11.1 — phone-only) ─────

    /**
     * Called when the user presses HOME (or the recents gesture). On
     * phones with PIP support, shrink the player into a floating window
     * instead of backgrounding audio-only. TV (Fire TV, Google TV) lacks
     * the feature and the check gates out gracefully.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (shouldEnterPip()) {
            enterPipSafely()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        // While in PIP, the shrunken surface only needs the video — hide
        // zap bar, quick info, program progress, live-jump bar. Media3's
        // built-in PlayerView controller also hides itself in PIP mode
        // automatically via useController behaviour.
        if (inPip) {
            playerView.useController = false
            zapBar.visibility = View.GONE
            quickInfo.visibility = View.GONE
            progressRow.visibility = View.GONE
            liveJumpBar.visibility = View.GONE
        } else {
            playerView.useController = true
            // Let the next poll tick / state change re-surface the
            // overlays that should be visible in fullscreen. controller
            // visibility callback handles zapBar + progressRow.
            applyOverlayVisibility()
        }
    }

    private fun shouldEnterPip(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        val p = controller.player
        // Don't hijack a stopped / idle state — only enter PIP if we're
        // actually playing something the user wants to keep watching.
        return p.playWhenReady && p.playbackState != androidx.media3.common.Player.STATE_IDLE
    }

    private fun enterPipSafely() {
        try {
            val params = pipParams()
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPictureInPictureMode(params)
            } else {
                // Build gate in shouldEnterPip already prevents this, but
                // keep the compiler happy.
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "enterPictureInPictureMode failed: ${t.message}")
        }
    }

    private fun pipParams(): PictureInPictureParams {
        val fmt = controller.player.videoFormat
        val builder = PictureInPictureParams.Builder()
        // Aspect ratio from the stream's video format — falls back to 16:9
        // if we haven't got frame metadata yet. System clamps anything
        // outside [0.42, 2.39] so we don't need to police the numbers.
        val w = fmt?.width ?: 16
        val h = fmt?.height ?: 9
        if (w > 0 && h > 0) {
            builder.setAspectRatio(Rational(w.coerceAtMost(239), h.coerceAtMost(100)))
        }
        return builder.build()
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

    // ───── Live-edge timeshift (MK.8.2) ─────

    /**
     * Poll the player every second for its distance from the live edge.
     * Show the "Jump to LIVE" affordance whenever that distance crosses
     * [LIVE_BEHIND_THRESHOLD_MS] — a small floor so a quick pause/resume
     * or network wobble that nudges us a few seconds back doesn't flicker
     * the overlay in and out.
     */
    private fun startLiveOffsetTicker() {
        liveOffsetTickerJob?.cancel()
        liveOffsetTickerJob = lifecycleScope.launch {
            while (isActive) {
                renderLiveOffset()
                delay(LIVE_OFFSET_TICK_MS)
            }
        }
    }

    private fun renderLiveOffset() {
        val p = controller.player
        val item = controller.currentItem.value
        if (item?.type != ContentType.LIVE || !p.isCurrentMediaItemLive) {
            liveJumpBar.visibility = View.GONE
            return
        }
        // currentLiveOffset can return C.TIME_UNSET (Long.MIN_VALUE) before
        // the timeline resolves; clamp to 0 so we don't flash a bogus
        // "-99h behind" label while the manifest loads.
        val raw = p.currentLiveOffset
        val offsetMs = if (raw == androidx.media3.common.C.TIME_UNSET) 0L else raw.coerceAtLeast(0L)
        if (offsetMs < LIVE_BEHIND_THRESHOLD_MS) {
            liveJumpBar.visibility = View.GONE
            return
        }
        liveOffsetLabel.text = formatLiveOffset(offsetMs)
        liveJumpBar.visibility = View.VISIBLE
    }

    private fun formatLiveOffset(ms: Long): String {
        val totalSec = ms / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return when {
            h > 0 -> String.format("-%d:%02d:%02d", h, m, s)
            else -> String.format("-%d:%02d", m, s)
        }
    }

    /**
     * Snap the player back to the live edge. Uses [Player.seekToDefaultPosition]
     * which for a live window jumps to the stream's current live edge —
     * the same behavior as the built-in "GO TO LIVE" button that ships
     * with Media3's default controller (we hide that button and route
     * through our accent-tinted overlay for discoverability).
     */
    private fun jumpToLive() {
        val p = controller.player
        if (p.isCurrentMediaItemLive) {
            p.seekToDefaultPosition()
            p.playWhenReady = true
            liveJumpBar.visibility = View.GONE
        }
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
            // MK.8.2 — MEDIA_STOP on TV remotes doubles as "snap to live"
            // while on a live channel. Stopping the stream (the default
            // interpretation) makes no sense for live TV — there's
            // nothing to resume to. Jumping to live is what the user
            // actually wants.
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (controller.player.isCurrentMediaItemLive) {
                    jumpToLive()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
