package com.yancotv.android.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.yancotv.android.R
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.ResizeMode
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.EpgProgramme
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

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

        // MK.10.4 — auto-commit window for numeric channel-jump entry.
        // 1.2 s is long enough to type "503" comfortably but short enough
        // that a single-press digit still feels responsive.
        private const val CHANNEL_ZAP_COMMIT_MS = 1200L
        private const val LONG_PRESS_TIMEOUT_MS = 500L
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
    private val recordings: com.yancotv.shared.recording.RecordingsRepository by inject()
    private val favoritesRepo: com.yancotv.shared.favorites.FavoritesRepository by inject()

    // MK.10.4 — numeric channel-jump state. Activity-scoped (a fresh
    // PlayerActivity gets a fresh entry buffer); not Koin since nothing
    // outside the activity needs it.
    private val channelZapState = com.yancotv.android.player.zap.ChannelZapNumericState()
    private val channelZapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val channelZapCommitRunnable = Runnable { commitChannelDigits() }
    private var channelZapOverlayInflated = false

    // MK.options.redesign — new lightweight options menu state. Replaces
    // the MK.16.sheet entry path for the slice-1 categories (Audio /
    // Subtitles / Aspect); other categories still fall through to the
    // legacy sheet via row.onPick until the slice-2/3 panels land.
    private val optionsV2State = com.yancotv.android.player.options.PlayerOptionsState()
    private var optionsV2Inflated = false
    private var optionsV2View: androidx.compose.ui.platform.ComposeView? = null

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

    // VOD chrome overlay (MK.16.player.vod.chrome). Hosts the Compose
    // BUFFERING / ERROR states — see VodPlayerChrome.kt. Inflated lazily
    // from the `vod_chrome_stub` ViewStub on first show; most sessions
    // never trigger either state so we keep the Compose owner off the
    // launch path. `chromeState` drives rendering; `chromeBuffering` and
    // `chromeError` carry pre-formatted display data.
    private var chromeOverlay: ComposeView? = null
    private var chromeState by mutableStateOf(VodChromeState.NONE)
    private var chromeBuffering by mutableStateOf(VodChromeBuffering())
    private var chromeError by mutableStateOf(VodChromeError())

    // 500 ms debounce so every micro-buffer doesn't flash the overlay.
    // Reset alongside `hasBeenReady` on each new MediaItem.
    private var bufferingShowJob: Job? = null

    // Gate for the buffering overlay: only surfaces once the current item
    // has reached STATE_READY at least once. Keeps the initial prepare's
    // BUFFERING (where the dimmed PlayerView is already the right signal)
    // from flashing the overlay on cold start / zap.
    private var hasBeenReady = false

    // VOD dock (MK.16.player.vod.dock). Replaces Media3's built-in
    // PlayerControlView for non-live items. Lazy-inflated via the
    // `vod_dock_stub` ViewStub on first show.
    private var vodDockOverlay: ComposeView? = null
    private var dockVisible by mutableStateOf(false)
    private var dockData by mutableStateOf(VodDockData())
    private var dockProgress by mutableStateOf(VodDockProgress())
    private var dockAutoHideJob: Job? = null
    private var dockProgressTickJob: Job? = null

    // Channel-surf overlay (ComposeView), inflated on first showSurf() via
    // ViewStub so Compose owner setup is off the activity launch path.
    // surfOverlay stays null until first use; everything downstream guards
    // on surfVisible / null-checks the field.
    private var surfOverlay: ComposeView? = null
    private var surfVisible by mutableStateOf(false)

    // 2026-04-27 — legacy player-options sheet retired. Both LIVE and
    // VOD now route through `optionsV2State` / `showOptionsV2`.
    // PlayerOptionsSheet.kt was deleted in the same commit. The slim
    // SheetMode enum (defined in VodPlayerDock.kt now) survives only
    // as a chip-route hint from the dock to the activity.

    // MK.12a.3 — SAF picker for external subtitle files. Registered in
    // onCreate (must happen before STARTED) and dispatched via
    // launchSubtitlePicker(). Returns null when the user dismisses without
    // picking, in which case the sheet stays closed and nothing else fires.
    private val subtitlePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onSubtitleUriPicked(uri)
        }

    // MK.9.4 — track which ExoPlayer instance our listener is attached to.
    // After a watchdog rebuild, controller.player is a new instance; this
    // ref lets attachShared() detect the swap and reattach without leaking
    // the listener on the released instance.
    private var attachedPlayer: ExoPlayer? = null
    private var controllerVisible = false
    // Long-press CENTER → options overlay (Google TV remotes lack MENU).
    private var longPressJob: Job? = null
    private var longPressFired = false
    private var longPressTracking = false
    private var currentProgramme: EpgProgramme? = null
    private var progressTickerJob: Job? = null
    private var quickInfoHideJob: Job? = null
    private var liveOffsetTickerJob: Job? = null
    private var inPip = false

    private val playerListener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "onPlayerError ${error.errorCodeName}", error)
                showStreamError(error)
            }

            override fun onRenderedFirstFrame() {
                ZapLatencyTracer.onFirstFrame()
            }

            override fun onPlaybackStateChanged(state: Int) {
                val name =
                    when (state) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN"
                    }
                Log.i(TAG, "onPlaybackStateChanged=$name")
                if (quickInfo.visibility == View.VISIBLE) refreshQuickInfo()
                when (state) {
                    Player.STATE_READY -> {
                        hasBeenReady = true
                        bufferingShowJob?.cancel()
                        bufferingShowJob = null
                        // Any non-error overlay clears on READY; the error
                        // state has its own retry flow and shouldn't auto-
                        // clear until the retry fires and we actually hit
                        // READY on the new prepare — which is this branch.
                        if (chromeState != VodChromeState.NONE) hideChrome()
                    }
                    Player.STATE_BUFFERING -> {
                        // Don't stack a buffering overlay on top of an
                        // error the user hasn't dismissed, and skip the
                        // initial prepare's BUFFERING (hasBeenReady=false).
                        if (hasBeenReady && chromeState != VodChromeState.ERROR) {
                            bufferingShowJob?.cancel()
                            bufferingShowJob =
                                lifecycleScope.launch {
                                    delay(500L)
                                    showBuffering()
                                }
                        }
                    }
                    Player.STATE_IDLE, Player.STATE_ENDED -> {
                        bufferingShowJob?.cancel()
                        bufferingShowJob = null
                    }
                }
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

        // MK.14.2-fix — in-app recording indicator. Fire TV doesn't show
        // foreground-service notifications over fullscreen video, so this
        // pinned pill is the user's primary "is it recording?" signal.
        // The composable observes the recordings flow internally; eager
        // setup here is essentially free (it renders nothing until a
        // matching row appears).
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.recording_indicator)
            .apply {
                setViewCompositionStrategy(
                    androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
                )
                setContent {
                    RecordingIndicator(
                        controller = controller,
                        recordings = recordings,
                    )
                }
            }

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

        // surfOverlay / chromeOverlay stay null — inflated lazily on first
        // show via their ViewStubs. Keeps Compose owner setup off the
        // launch path (MK.16.player.vod.chrome).

        playerView.useController = true
        // Controls must stay hidden on fullscreen entry. Media3's default
        // `controllerAutoShow = true` pops them on every STATE_READY /
        // paused / ended transition — which fired on every channel change
        // in fullscreen and on the initial bind when we hand off from the
        // mini preview. The user's mental model is: the picture fills the
        // screen, OK toggles the controls. See 2026-04-22 fix.
        playerView.controllerAutoShow = false
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
        // Settings → Playback → Aspect + MK.12a.5 sheet picker. Re-applies
        // whenever the user flips the chip row or picks a new ratio so a
        // fullscreen session can change between Fit / Fill / Zoom / 16:9 /
        // 4:3 without restarting. PlayerView reads the mode on the next
        // frame layout pass.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                prefs.playbackFlow.collect { applyResizeMode(it.resizeMode) }
            }
        }
        // MK.9.4 — re-bind to the new ExoPlayer after the watchdog rebuilds.
        // attachShared is idempotent (no-ops if attachedPlayer is already
        // the current instance) so backgrounded rebuilds also resync via
        // the next onStart() call.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.playerRebuilt.collect {
                    attachShared()
                }
            }
        }
    }

    /**
     * Translate a [ResizeMode] to the pair of knobs Media3 exposes:
     * [PlayerView.setResizeMode] for the fit/fill/zoom choice, and
     * [AspectRatioFrameLayout.setAspectRatio] (on the inner content frame)
     * for forcing a specific ratio. Passing 0 to setAspectRatio reverts to
     * automatic detection from the video's own size.
     */
    private fun applyResizeMode(mode: ResizeMode) {
        val frame = playerView.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
        when (mode) {
            ResizeMode.FIT -> {
                frame?.setAspectRatio(0f)
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            ResizeMode.FILL -> {
                frame?.setAspectRatio(0f)
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            ResizeMode.ZOOM -> {
                frame?.setAspectRatio(0f)
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            ResizeMode.RATIO_16_9 -> {
                frame?.setAspectRatio(16f / 9f)
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            ResizeMode.RATIO_4_3 -> {
                frame?.setAspectRatio(4f / 3f)
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }
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
        dockProgressTickJob?.cancel()
        dockProgressTickJob = null
        dockAutoHideJob?.cancel()
        dockAutoHideJob = null
        // MK.10.4 — cancel any pending numeric-zap commit so a backgrounded
        // activity doesn't fire it on resume.
        channelZapHandler.removeCallbacksAndMessages(null)
        channelZapState.clear()

        // MB-119 follow-up — explicit ordered surface detach symmetric to
        // PlayerLauncher's pre-launch clearVideoSurface(). Without this, the
        // implicit detach via PlayerView.player=null below races against
        // ThreadedRenderer's finalize() during activity teardown. At 4K
        // resolution the race deadlocked GPU resource cleanup for 10+
        // seconds and FinalizerWatchdog killed the app:
        //   FATAL: TimeoutException: ThreadedRenderer.finalize() timed out
        //   at android.view.ThreadedRenderer.nDeleteProxy(Native Method)
        // clearVideoSurface() is synchronous — it tells MediaCodec to stop
        // using this SurfaceView and waits for ack BEFORE we let the view
        // hierarchy teardown begin. Cost: a few ms of main-thread block on
        // the back-press; cheap relative to the alternative.
        controller.player.clearVideoSurface()
        playerView.player = null

        // VOD audio bleed fix: nothing reclaims the surface for movies /
        // episodes after fullscreen exit (the LIVE-only MiniPlayer in the
        // hero won't bind), so the shared ExoPlayer happily keeps decoding
        // audio in the background. Pause on the way out — resume position
        // was just persisted in onPause(), so a re-entry restores it.
        // LIVE keeps playing because the BrowseShell hero MiniPlayer takes
        // the surface back and the channel needs to stay current.
        val current = controller.currentItem.value
        if (current != null && current.type != com.yancotv.shared.types.ContentType.LIVE) {
            controller.player.playWhenReady = false
        }
    }

    override fun onDestroy() {
        attachedPlayer?.removeListener(playerListener)
        attachedPlayer = null
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

    // @RequiresApi documents the contract that lint can't infer across the
    // function boundary: every caller (enterPictureInPictureMode in
    // tryEnterPip + the onUserLeaveHint path) is already gated on
    // SDK_INT >= O via shouldEnterPip(). Without the annotation lint
    // sees PictureInPictureParams.Builder() (API 26) being called from a
    // function legal on minSdk=24 and reports it as an error.
    @RequiresApi(Build.VERSION_CODES.O)
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
        // MK.9.4 — listener is per-instance. If the watchdog rebuilt the
        // player, the attached instance no longer matches; remove from the
        // released ref (no-op since the player handled it on release()) and
        // attach fresh to the new one.
        if (attachedPlayer !== target) {
            attachedPlayer?.removeListener(playerListener)
            target.addListener(playerListener)
            attachedPlayer = target
        }
        // Drop any prior surface (the MiniPlayer's TextureView when the user
        // launched fullscreen from the shell) before binding to the local
        // PlayerView. PlayerView.setPlayer eventually calls
        // setVideoSurfaceView, which blocks the main thread waiting for the
        // previous output to ack-detach — without this pre-clear we hit
        // ExoTimeoutException "Detaching surface timed out" and the player
        // drops to IDLE. Mirrored on the way out by PlayerLauncher.
        target.clearVideoSurface()
        playerView.player = target
        playerView.requestFocus()
    }

    // ───── Zap bar / EPG ─────

    private fun onItemChanged(item: ContentItem?) {
        currentProgramme = null
        // Fresh MediaItem: drop any stale chrome and reset the buffering
        // gate so the *new* item's initial prepare doesn't flash the
        // "Tuning the stream" overlay — the dimmed PlayerView is already
        // the right signal for the cold-start / zap case.
        hideChrome()
        hideVodDock()
        hasBeenReady = false
        bufferingShowJob?.cancel()
        bufferingShowJob = null
        // MK.16.player.vod.dock — LIVE keeps Media3's built-in controller
        // (needed for the zap bar / program-progress overlays to ride
        // alongside it). VOD (movie / episode) disables the built-in one
        // because the Compose dock replaces it. Re-apply on every item
        // change so a zap from VOD → LIVE or back flips the control surface.
        val isLive = item?.type == ContentType.LIVE
        playerView.useController = (item == null) || isLive
        if (item == null) {
            zapBar.visibility = View.GONE
            progressRow.visibility = View.GONE
            return
        }
        val displayTitle = item.cleanTitle?.ifBlank { null } ?: item.title
        zapChannelName.text = displayTitle
        zapLiveDot.visibility = if (isLive) View.VISIBLE else View.GONE
        zapLiveLabel.visibility = if (isLive) View.VISIBLE else View.GONE
        zapNow.visibility = View.GONE
        zapNext.visibility = View.GONE
        // zap bar is chromed-in by the controller-visibility listener; we
        // no longer force the controller open on channel change (see the
        // `controllerAutoShow = false` decision above).
        zapBar.visibility = if (controllerVisible) View.VISIBLE else View.GONE

        val tvgId = item.tvgId?.takeIf { it.isNotBlank() }
        if (!isLive || tvgId == null) {
            applyOverlayVisibility()
            return
        }
        lifecycleScope.launch {
            val nn =
                try {
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
        val liveWithEpg =
            currentProgramme != null &&
                controller.currentItem.value?.type == ContentType.LIVE
        progressRow.visibility = if (controllerVisible && liveWithEpg) View.VISIBLE else View.GONE
    }

    // ───── Program progress ─────

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob =
            lifecycleScope.launch {
                while (isActive) {
                    renderProgramProgress()
                    delay(PROGRESS_TICK_MS)
                }
            }
    }

    private fun renderProgramProgress() {
        val prog =
            currentProgramme ?: run {
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
        ppTime.text = "$remainingMin min left • $totalMin min"
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
            quickInfoHideJob =
                lifecycleScope.launch {
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
        val fps = f.frameRate.takeIf { it > 0f }?.let { String.format(Locale.ROOT, "%.0f", it) }
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
        return if (total >= 1_000_000) {
            String.format(Locale.ROOT, "%.1f Mbps", total / 1_000_000.0)
        } else {
            String.format(Locale.ROOT, "%d kbps", total / 1000)
        }
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
        liveOffsetTickerJob =
            lifecycleScope.launch {
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
            h > 0 -> String.format(Locale.ROOT, "-%d:%02d:%02d", h, m, s)
            else -> String.format(Locale.ROOT, "-%d:%02d", m, s)
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

    // ───── MK.10.4 — numeric channel jump ─────

    /**
     * Push a digit into the entry buffer. First digit also lazy-inflates
     * the overlay ViewStub. Re-arms the auto-commit timer so a fast
     * typist gets multi-digit numbers without prematurely committing.
     */
    private fun onChannelDigit(digit: Char) {
        ensureChannelZapOverlay()
        channelZapState.pushDigit(digit)
        channelZapHandler.removeCallbacks(channelZapCommitRunnable)
        channelZapHandler.postDelayed(channelZapCommitRunnable, CHANNEL_ZAP_COMMIT_MS)
    }

    /**
     * Commit the typed channel number. Looks up the live queue for an
     * item with `sortOrder == typed`; if found, jumps via the existing
     * `controller.play(...)` queue surface (which will reuse already-
     * loaded MediaItems). On miss, just dismisses — silent fail beats
     * a confusing toast.
     */
    private fun commitChannelDigits() {
        channelZapHandler.removeCallbacks(channelZapCommitRunnable)
        val target = channelZapState.consume() ?: return
        val items = controller.queue.value
        if (items.isEmpty()) return
        val index = items.indexOfFirst { it.sortOrder == target }
        if (index < 0) return
        ZapLatencyTracer.markZapStart("NUM:$target")
        controller.play(items, index)
    }

    private fun cancelChannelDigits() {
        channelZapHandler.removeCallbacks(channelZapCommitRunnable)
        channelZapState.clear()
    }

    // ───── MK.options.redesign — new options popup + per-category panels ─────

    private fun showOptionsV2(initialCategory: com.yancotv.android.player.options.PlayerOptionCategory? = null) {
        ensureOptionsV2()
        playerView.hideController()
        // Audit: same defense the legacy sheet uses. Block PlayerView's
        // focusable descendants while options are up so a stray Compose
        // focus search can't escape into the Media3 controller buttons
        // underneath. Restored in hideOptionsV2.
        playerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        playerView.isFocusable = false
        optionsV2State.showMenu()
        // 2026-04-27 — VOD migration: chip on the dock can pre-open a
        // specific category panel (CC → SUBTITLES, AUDIO → AUDIO,
        // etc.) so the user lands directly on the relevant controls
        // instead of the popup root.
        initialCategory?.let { optionsV2State.openPanel(it) }
        optionsV2View?.visibility = View.VISIBLE
        optionsV2View?.post { optionsV2View?.requestFocus() }
    }

    private fun hideOptionsV2() {
        optionsV2State.hideMenu()
        optionsV2View?.visibility = View.GONE
        // Restore PlayerView focus so the user's next D-pad press flows
        // back into the Media3 controller / dock as expected.
        playerView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        playerView.isFocusable = true
    }

    private fun ensureOptionsV2() {
        if (optionsV2Inflated) return
        optionsV2Inflated = true
        val stub = findViewById<android.view.ViewStub>(R.id.player_options_v2_stub) ?: return
        val view = stub.inflate() as? androidx.compose.ui.platform.ComposeView ?: return
        view.setViewCompositionStrategy(
            androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        view.setContent {
            com.yancotv.android.ui.theme.YancoTheme(isTv = true) {
                val rows = buildOptionsV2Rows()
                com.yancotv.android.player.options.PlayerOptionsMenu(
                    state = optionsV2State,
                    rows = rows,
                    onDismiss = { hideOptionsV2() },
                )
                com.yancotv.android.player.options.PlayerOptionsPanelHost(
                    state = optionsV2State,
                    controller = controller,
                    prefs = prefs,
                    onPickSubtitleFile = {
                        // 2026-04-27 — direct SAF launch. No more
                        // legacy-sheet fallback. launchSubtitlePicker
                        // closes the popup itself before the picker
                        // pops.
                        launchSubtitlePicker()
                    },
                    onDismiss = { hideOptionsV2() },
                )
            }
        }
        optionsV2View = view
    }

    /**
     * Build the popup row list. Slice 1 implements Audio / Subtitles /
     * Aspect natively; the remaining categories fall back to the legacy
     * sheet at the right tab. LEFT/RIGHT cycling is wired for Aspect
     * (small enum); the rest open the panel for full controls.
     */
    @androidx.compose.runtime.Composable
    private fun buildOptionsV2Rows(): List<com.yancotv.android.player.options.PlayerOptionsRow> {
        val playback by prefs.playbackFlow.collectAsState()
        val scope = rememberCoroutineScope()
        val rows =
            mutableListOf<com.yancotv.android.player.options.PlayerOptionsRow>()
        rows +=
            com.yancotv.android.player.options.PlayerOptionsRow(
                category = com.yancotv.android.player.options.PlayerOptionCategory.AUDIO,
                label = "Audio",
                currentValue =
                playback.audioLanguage.takeIf { it.isNotBlank() }
                    ?.uppercase(java.util.Locale.ROOT) ?: "Auto",
                onPick = { optionsV2State.openPanel(com.yancotv.android.player.options.PlayerOptionCategory.AUDIO) },
                onCyclePrev = {
                    com.yancotv.android.player.options.cycleAudioTrack(
                        controller,
                        forward = false,
                        scope = scope,
                        prefs = prefs,
                    )
                },
                onCycleNext = {
                    com.yancotv.android.player.options.cycleAudioTrack(
                        controller,
                        forward = true,
                        scope = scope,
                        prefs = prefs,
                    )
                },
            )
        rows +=
            com.yancotv.android.player.options.PlayerOptionsRow(
                category = com.yancotv.android.player.options.PlayerOptionCategory.SUBTITLES,
                label = "Subtitles",
                currentValue =
                playback.subtitleLanguage.takeIf { it.isNotBlank() }
                    ?.uppercase(java.util.Locale.ROOT) ?: "Off",
                onPick = { optionsV2State.openPanel(com.yancotv.android.player.options.PlayerOptionCategory.SUBTITLES) },
                onCyclePrev = {
                    com.yancotv.android.player.options.cycleTextTrack(
                        controller,
                        forward = false,
                        scope = scope,
                        prefs = prefs,
                    )
                },
                onCycleNext = {
                    com.yancotv.android.player.options.cycleTextTrack(
                        controller,
                        forward = true,
                        scope = scope,
                        prefs = prefs,
                    )
                },
            )
        rows +=
            com.yancotv.android.player.options.PlayerOptionsRow(
                category = com.yancotv.android.player.options.PlayerOptionCategory.ASPECT,
                label = "Aspect",
                currentValue = playback.resizeMode.displayName,
                onPick = { optionsV2State.openPanel(com.yancotv.android.player.options.PlayerOptionCategory.ASPECT) },
                onCyclePrev = {
                    scope.launch {
                        com.yancotv.android.player.options.cycleAspect(prefs, forward = false)
                    }
                },
                onCycleNext = {
                    scope.launch {
                        com.yancotv.android.player.options.cycleAspect(prefs, forward = true)
                    }
                },
            )
        // Slice-1 fallthroughs — open the legacy sheet at the matching tab.
        rows +=
            com.yancotv.android.player.options.PlayerOptionsRow(
                category = com.yancotv.android.player.options.PlayerOptionCategory.SPEED,
                label = "Speed",
                currentValue = "${playback.speed}×",
                onPick = { optionsV2State.openPanel(com.yancotv.android.player.options.PlayerOptionCategory.SPEED) },
                onCyclePrev = {
                    scope.launch {
                        com.yancotv.android.player.options.cycleSpeed(controller, prefs, forward = false)
                    }
                },
                onCycleNext = {
                    scope.launch {
                        com.yancotv.android.player.options.cycleSpeed(controller, prefs, forward = true)
                    }
                },
            )
        val sleepState by controller.sleepTimer.collectAsState()
        rows +=
            com.yancotv.android.player.options.PlayerOptionsRow(
                category = com.yancotv.android.player.options.PlayerOptionCategory.SLEEP,
                label = "Sleep",
                currentValue =
                when (val s = sleepState) {
                    is com.yancotv.android.player.SleepTimerState.Off -> "Off"
                    is com.yancotv.android.player.SleepTimerState.Active ->
                        sleepRowLabel(s.option)
                },
                onPick = { optionsV2State.openPanel(com.yancotv.android.player.options.PlayerOptionCategory.SLEEP) },
            )
        // Slice 2b — these rows open native floating panels matching the
        // popup's visual language; no more legacy-sheet fallthrough.
        val currentItem by controller.currentItem.collectAsState()
        val recordingsInflight by remember { recordings.allFlow() }
            .collectAsState(initial = emptyList())
        val isRecordingNow =
            recordingsInflight.any {
                it.contentId == currentItem?.id &&
                    it.status == com.yancotv.shared.recording.RecordingStatus.RECORDING
            }
        // 2026-04-27 — Record row is live-only. The live-tee path
        // depends on a continuously-refilling broadcast; VOD streams
        // are finite and don't fit the "tap the watching channel"
        // model. For VOD users wanting to save the file, the
        // recording is already on the source server (catch-up) — they
        // can use External player to download via a third-party app
        // if needed.
        val isLiveItem = currentItem?.type == com.yancotv.shared.types.ContentType.LIVE
        if (isLiveItem) {
            rows +=
                com.yancotv.android.player.options.PlayerOptionsRow(
                    category = com.yancotv.android.player.options.PlayerOptionCategory.RECORD,
                    label = "Record",
                    currentValue = if (isRecordingNow) "Recording" else "—",
                    onPick = { optionsV2State.openPanel(com.yancotv.android.player.options.PlayerOptionCategory.RECORD) },
                )
        }
        // Favorites row reads `isFavoriteFlow` for the current id (or
        // series id for episode play). Empty current item → "—".
        val favoriteId =
            controller.currentEpisode.collectAsState().value?.seriesId
                ?: currentItem?.id
        val isFav by remember(favoriteId) {
            if (favoriteId != null) {
                favoritesRepo.isFavoriteFlow(favoriteId)
            } else {
                kotlinx.coroutines.flow.flowOf(false)
            }
        }.collectAsState(initial = false)
        rows +=
            com.yancotv.android.player.options.PlayerOptionsRow(
                category = com.yancotv.android.player.options.PlayerOptionCategory.FAVORITES,
                label = "Favorites",
                currentValue = if (isFav) "Saved" else "—",
                onPick = { optionsV2State.openPanel(com.yancotv.android.player.options.PlayerOptionCategory.FAVORITES) },
            )
        rows +=
            com.yancotv.android.player.options.PlayerOptionsRow(
                category = com.yancotv.android.player.options.PlayerOptionCategory.EXTERNAL,
                label = "External player",
                currentValue = "—",
                onPick = { optionsV2State.openPanel(com.yancotv.android.player.options.PlayerOptionCategory.EXTERNAL) },
            )
        return rows
    }

    /**
     * Returns true when a live stream is paused or already past the
     * "behind live edge" threshold. Used by LEFT/RIGHT to flip from
     * "open surf overlay" to "scrub the back buffer." Threshold matches
     * [LIVE_BEHIND_THRESHOLD_MS] so this and the JUMP TO LIVE pill
     * surface together — if the user can see the timeshift pill, they
     * can also seek with arrow keys.
     */
    private fun isTimeshifting(p: androidx.media3.exoplayer.ExoPlayer): Boolean {
        if (!p.playWhenReady) return true
        val raw = p.currentLiveOffset
        if (raw == androidx.media3.common.C.TIME_UNSET) return false
        return raw >= LIVE_BEHIND_THRESHOLD_MS
    }

    private fun sleepRowLabel(opt: SleepTimerOption): String = when (opt) {
        SleepTimerOption.MIN_15 -> "15 min"
        SleepTimerOption.MIN_30 -> "30 min"
        SleepTimerOption.MIN_45 -> "45 min"
        SleepTimerOption.MIN_60 -> "60 min"
        SleepTimerOption.END_OF_PROGRAM -> "End of programme"
    }

    /**
     * Lazy-inflate the channel-zap ViewStub on the first digit. Avoids
     * paying the Compose host setup cost during PlayerActivity.onCreate
     * — most playback sessions never type a channel number.
     */
    private fun ensureChannelZapOverlay() {
        if (channelZapOverlayInflated) return
        channelZapOverlayInflated = true
        val stub = findViewById<android.view.ViewStub>(R.id.channel_zap_overlay_stub) ?: return
        val view = stub.inflate() as? androidx.compose.ui.platform.ComposeView ?: return
        view.setViewCompositionStrategy(
            androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        view.setContent {
            com.yancotv.android.ui.theme.YancoTheme(isTv = true) {
                com.yancotv.android.player.zap.ChannelZapOverlay(state = channelZapState)
            }
        }
    }

    // ───── VOD chrome (buffering + error) ─────

    /**
     * Inflate the `vod_chrome_stub` ViewStub on first use and wire the
     * ComposeView to [VodPlayerChrome]. Mirrors the surf / sheet lazy
     * inflation pattern.
     *
     * Action callbacks map to the same primitives the removed XML overlay
     * used (`retryCurrent()`, `finish()`) plus the new hooks needed by the
     * Concept A error copy: `onPlaybackOptions` cross-opens the sheet,
     * and `onSwitchQuality` / `onTrySource` / `onReport` are stubbed for
     * the follow-up metadata + controls slices — for now they just
     * dismiss the overlay so the user isn't stuck.
     */
    private fun ensureChromeOverlay(): ComposeView {
        chromeOverlay?.let { return it }
        val stub = findViewById<android.view.ViewStub>(R.id.vod_chrome_stub)
        val inflated = stub.inflate() as ComposeView
        inflated.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        inflated.setContent {
            VodPlayerChrome(
                state = chromeState,
                buffering = chromeBuffering,
                error = chromeError,
                onRetry = { retryCurrent() },
                onBack = {
                    hideChrome()
                    finish()
                },
                onPlaybackOptions = {
                    hideChrome()
                    showOptionsV2()
                },
                onSwitchQuality = { hideChrome() },
                onTrySource = { hideChrome() },
                onReport = { hideChrome() },
            )
        }
        chromeOverlay = inflated
        return inflated
    }

    /**
     * Present the BUFFERING overlay. Called after the 500 ms debounce
     * in the player listener so a micro-buffer doesn't flash. Refreshes
     * the diagnostic tile data once — live updates while the overlay is
     * visible land with the controls slice.
     */
    private fun showBuffering() {
        if (chromeState == VodChromeState.ERROR) return
        chromeBuffering = buildBufferingData()
        chromeState = VodChromeState.BUFFERING
        val v = ensureChromeOverlay()
        v.visibility = View.VISIBLE
        v.post { v.requestFocus() }
    }

    /**
     * Present the ERROR overlay with a user-readable title pulled from the
     * PlaybackException code. Hides the built-in Media3 controller so the
     * two UIs don't stack.
     */
    private fun showStreamError(error: PlaybackException) {
        bufferingShowJob?.cancel()
        bufferingShowJob = null
        chromeError = buildErrorData(error)
        chromeState = VodChromeState.ERROR
        val v = ensureChromeOverlay()
        v.visibility = View.VISIBLE
        playerView.hideController()
        v.post { v.requestFocus() }
    }

    /**
     * Dismiss whatever chrome is up. Returns focus to the PlayerView so
     * D-pad drives the Media3 controller again.
     */
    private fun hideChrome() {
        if (chromeState == VodChromeState.NONE) return
        chromeState = VodChromeState.NONE
        chromeOverlay?.visibility = View.GONE
        playerView.requestFocus()
    }

    /**
     * Build the diagnostic tile payload from whatever the ExoPlayer can
     * tell us *right now*. All reads (`videoFormat`, `audioFormat`,
     * `bufferedPosition`, `currentPosition`) are main-thread-safe because
     * the shared ExoPlayer is main-thread-confined per the PlaybackController
     * contract.
     */
    private fun buildBufferingData(): VodChromeBuffering {
        val p = controller.player
        val videoFmt = p.videoFormat
        val audioFmt = p.audioFormat
        val aheadMs = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
        val bufferLabel = String.format(Locale.ROOT, "%.1f s", aheadMs / 1000.0)
        val resLabel =
            if (videoFmt != null && videoFmt.width > 0 && videoFmt.height > 0) {
                "${videoFmt.width}×${videoFmt.height}"
            } else {
                "—"
            }
        val bitrateSum =
            listOfNotNull(videoFmt?.bitrate, audioFmt?.bitrate).filter { it > 0 }.sum()
        val bitrateLabel =
            when {
                bitrateSum <= 0 -> "—"
                bitrateSum >= 1_000_000 ->
                    String.format(Locale.ROOT, "%.1f Mbps", bitrateSum / 1_000_000.0)
                else -> String.format(Locale.ROOT, "%d kbps", bitrateSum / 1000)
            }
        return VodChromeBuffering(
            bitrate = bitrateLabel,
            bufferFill = bufferLabel,
            latency = "—",
            resolution = resLabel,
            progressLabel = "BUFFERING",
        )
    }

    /**
     * Translate a PlaybackException into the display payload the error
     * overlay renders. Diagnostic block fields (source / stream / remote)
     * are left blank for this slice to avoid leaking raw IDs or stream
     * URLs to the UI — MK.16.player.vod.metadata resolves the source
     * display name via SourceRepository.nameFor and sanitizes the URL.
     */
    private fun buildErrorData(error: PlaybackException): VodChromeError {
        val title =
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                -> "Can't reach the stream"
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                    "Server refused the request"
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                -> "Stream not found"
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                -> "This device can't decode the stream"
                else -> "Couldn't open this stream"
            }
        val description =
            error.localizedMessage ?: "Check your connection or try another source."
        return VodChromeError(
            codeName = error.errorCodeName,
            codeNumeric = error.errorCode.toString(),
            title = title,
            description = description,
            sourceName = "",
            streamPath = "",
            remote = "",
            attempt = "1",
        )
    }

    private fun retryCurrent() {
        hideChrome()
        val p = controller.player
        p.prepare()
        p.playWhenReady = true
        playerView.requestFocus()
    }

    // ───── VOD dock (MK.16.player.vod.dock) ─────

    /**
     * Inflate the `vod_dock_stub` on first show and wire the ComposeView to
     * [VodPlayerDock]. Mirrors the sheet / chrome / surf lazy inflation
     * pattern.
     *
     * Callback wiring:
     *  - toggle play-pause drives [Player.playWhenReady]
     *  - skip ±10 s seeks the shared player directly
     *  - previous / next delegate to the controller queue traversal
     *  - open-sheet cross-opens the options sheet
     *  - favorite is a stub for the follow-up slice
     *  - onSeekTo is used by the progress-bar scrub-focus path (DPAD LEFT /
     *    RIGHT on the bar), also ±10 s for v1
     *  - onUserInteraction resets the 4 s auto-hide job so focus-wander
     *    doesn't clip the dock before the user decides what to press
     */
    private fun ensureVodDockOverlay(): ComposeView {
        vodDockOverlay?.let { return it }
        val stub = findViewById<android.view.ViewStub>(R.id.vod_dock_stub)
        val inflated = stub.inflate() as ComposeView
        inflated.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        inflated.setContent {
            // Reactively hide the prev/next buttons when the active queue is
            // single-item (VOD movies, per-episode play). collectAsState here
            // (not baked into dockData) keeps the visibility tied to the live
            // queue StateFlow so Live TV → channel zap or future queue swaps
            // recompute without a manual refresh.
            val queueSnapshot by controller.queue.collectAsState()
            VodPlayerDock(
                visibility = if (dockVisible) VodDockVisibility.VISIBLE else VodDockVisibility.HIDDEN,
                data = dockData,
                progress = dockProgress,
                hasSiblings = queueSnapshot.size > 1,
                onTogglePlayPause = {
                    val p = controller.player
                    p.playWhenReady = !p.playWhenReady
                    dockData = dockData.copy(isPlaying = p.playWhenReady)
                    resetDockAutoHide()
                },
                onSkipBack = {
                    val p = controller.player
                    p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L))
                    resetDockAutoHide()
                },
                onSkipForward = {
                    val p = controller.player
                    val dur = p.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                    p.seekTo((p.currentPosition + 10_000L).coerceAtMost(dur))
                    resetDockAutoHide()
                },
                onPrevious = {
                    ZapLatencyTracer.markZapStart("DOCK_PREV")
                    controller.previous()
                    resetDockAutoHide()
                },
                onNext = {
                    ZapLatencyTracer.markZapStart("DOCK_NEXT")
                    controller.next()
                    resetDockAutoHide()
                },
                onOpenSheet = { mode ->
                    // 2026-04-27 — VOD migration: dock chips now drop
                    // into the new options popup pre-focused on the
                    // matching category panel. Legacy SheetMode is
                    // preserved as the chip-side enum (the dock UI
                    // didn't change), but the consumer side maps to
                    // PlayerOptionCategory and uses the V2 surface.
                    hideVodDock()
                    val category =
                        when (mode) {
                            SheetMode.AUDIO -> com.yancotv.android.player.options.PlayerOptionCategory.AUDIO
                            SheetMode.SUBS -> com.yancotv.android.player.options.PlayerOptionCategory.SUBTITLES
                            SheetMode.SPEED -> com.yancotv.android.player.options.PlayerOptionCategory.SPEED
                            SheetMode.ASPECT -> com.yancotv.android.player.options.PlayerOptionCategory.ASPECT
                            SheetMode.SLEEP -> com.yancotv.android.player.options.PlayerOptionCategory.SLEEP
                            SheetMode.RECORD -> com.yancotv.android.player.options.PlayerOptionCategory.RECORD
                            SheetMode.FAV -> com.yancotv.android.player.options.PlayerOptionCategory.FAVORITES
                            SheetMode.EXT -> com.yancotv.android.player.options.PlayerOptionCategory.EXTERNAL
                            // CAST / LOOK had no V2 panel — drop to
                            // popup root so the user can still navigate
                            // to whatever they wanted (or pick another
                            // category).
                            else -> null
                        }
                    showOptionsV2(category)
                },
                onSeekTo = { offsetMs ->
                    val p = controller.player
                    val dur = p.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                    p.seekTo(offsetMs.coerceIn(0L, dur))
                    resetDockAutoHide()
                },
                onUserInteraction = { resetDockAutoHide() },
            )
        }
        vodDockOverlay = inflated
        return inflated
    }

    /**
     * Surface the VOD dock. Hides Media3's built-in controller first (both
     * can't be up at once). Starts the 500 ms progress ticker and arms the
     * 4 s auto-hide timer — matches Media3's [CONTROLLER_TIMEOUT_MS].
     */
    private fun showVodDock() {
        if (dockVisible) return
        playerView.hideController()
        dockData = buildDockData(controller.currentItem.value)
        dockProgress = readDockProgress()
        dockVisible = true
        val v = ensureVodDockOverlay()
        v.visibility = View.VISIBLE
        v.post { v.requestFocus() }
        startDockProgressTicker()
        resetDockAutoHide()
    }

    private fun hideVodDock() {
        if (!dockVisible) return
        dockVisible = false
        vodDockOverlay?.visibility = View.GONE
        stopDockProgressTicker()
        dockAutoHideJob?.cancel()
        dockAutoHideJob = null
        playerView.requestFocus()
    }

    private fun resetDockAutoHide() {
        dockAutoHideJob?.cancel()
        dockAutoHideJob =
            lifecycleScope.launch {
                delay(CONTROLLER_TIMEOUT_MS.toLong())
                hideVodDock()
            }
    }

    private fun startDockProgressTicker() {
        dockProgressTickJob?.cancel()
        dockProgressTickJob =
            lifecycleScope.launch {
                while (isActive) {
                    dockProgress = readDockProgress()
                    val playing = controller.player.playWhenReady
                    if (playing != dockData.isPlaying) {
                        dockData = dockData.copy(isPlaying = playing)
                    }
                    delay(500L)
                }
            }
    }

    private fun stopDockProgressTicker() {
        dockProgressTickJob?.cancel()
        dockProgressTickJob = null
    }

    private fun readDockProgress(): VodDockProgress {
        val p = controller.player
        return VodDockProgress(
            playedMs = p.currentPosition.coerceAtLeast(0L),
            bufferedMs = p.bufferedPosition.coerceAtLeast(0L),
            durationMs = p.duration.coerceAtLeast(0L),
        )
    }

    /**
     * Build the VOD dock metadata payload from the active [ContentItem].
     * Chip list keeps v1 minimal — type badge + group — so the dock lands
     * visibly without a dependency on the metadata / source-lookup work
     * deferred to MK.16.player.vod.metadata.
     */
    private fun buildDockData(item: ContentItem?): VodDockData {
        if (item == null) return VodDockData(isPlaying = controller.player.playWhenReady)
        val title = item.cleanTitle?.ifBlank { null } ?: item.title
        val chips = mutableListOf<VodDockChip>()
        val typeLabel =
            when (item.type) {
                ContentType.MOVIE -> "MOVIE"
                ContentType.SERIES -> "EPISODE"
                ContentType.LIVE -> "LIVE"
            }
        chips += VodDockChip(label = typeLabel, tone = VodDockChipTone.PREMIUM)
        item.groupName?.takeIf { it.isNotBlank() }?.let {
            chips += VodDockChip(label = it.uppercase(Locale.ROOT))
        }
        return VodDockData(
            title = title,
            chips = chips,
            isPlaying = controller.player.playWhenReady,
        )
    }

    // ───── Channel surf ─────

    private fun ensureSurfOverlay(): ComposeView {
        surfOverlay?.let { return it }
        // Inflate the ViewStub on first use. AppCompatActivity guarantees
        // setContentView ran first so the stub is in the view tree.
        val stub = findViewById<android.view.ViewStub>(R.id.surf_overlay_stub)
        val inflated = stub.inflate() as ComposeView
        inflated.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        inflated.setContent {
            if (surfVisible) {
                ChannelSurfOverlay(
                    currentContentId = controller.currentId,
                    onPick = { list, idx ->
                        hideSurf()
                        controller.play(list, idx)
                        playerView.requestFocus()
                    },
                    onIdleDismiss = { hideSurf() },
                )
            }
        }
        surfOverlay = inflated
        return inflated
    }

    private fun showSurf() {
        if (surfVisible) return
        // MK.10.4 fix — hide the Media3 controller first if it's up so
        // surf and the controller bar don't co-render. Block PlayerView's
        // descendant focus (transport buttons, etc.) too — without this
        // fence, Compose's 2D focus search inside surf can escape via
        // LEFT/RIGHT into a hidden controller button, which Media3 then
        // reacts to by re-showing the controller. Same lesson the sheet
        // and optionsV2 paths already encode (search "FOCUS_BLOCK_DESCENDANTS"
        // for the canonical pattern).
        if (controllerVisible) playerView.hideController()
        playerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        playerView.isFocusable = false
        surfVisible = true
        val v = ensureSurfOverlay()
        v.visibility = View.VISIBLE
        // Let Compose populate the list, then steal focus to the overlay so
        // D-pad input drives it and not the (possibly stale) PlayerView.
        v.post { v.requestFocus() }
    }

    private fun hideSurf() {
        if (!surfVisible) return
        surfVisible = false
        surfOverlay?.visibility = View.GONE
        // Restore PlayerView focus / descendants. Use FOCUS_AFTER_DESCENDANTS
        // (Media3 default) so the controller's transport buttons can take
        // focus when the controller is visible, falling back to the View
        // itself otherwise.
        playerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        playerView.isFocusable = true
        playerView.requestFocus()
    }

    /**
     * Launch the SAF "Open document" picker filtered to common subtitle
     * MIME types. Closes the V2 popup first — the picker opens as a
     * new activity and seeing the popup still up when we return reads
     * as broken. The callback [onSubtitleUriPicked] applies the pick.
     */
    private fun launchSubtitlePicker() {
        if (optionsV2Inflated && optionsV2State.menuVisible.value) hideOptionsV2()
        // Wildcard because most file explorers don't claim a MIME for .srt/
        // .vtt/.ass — filtering on explicit MIMEs hides most user files.
        // Sniffing happens downstream off the file extension.
        subtitlePicker.launch(arrayOf("*/*"))
    }

    /**
     * Receive the picked subtitle URI, sniff a MIME from the file name,
     * and hand off to the controller to rebuild the current MediaItem.
     * Controller rejects if the current item is live.
     */
    private fun onSubtitleUriPicked(uri: Uri) {
        val mime = sniffSubtitleMime(uri)
        controller.applyExternalSubtitle(uri, mime)
    }

    private fun sniffSubtitleMime(uri: Uri): String? {
        val name = uri.lastPathSegment?.lowercase(Locale.ROOT) ?: return null
        return when {
            name.endsWith(".srt") -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
            name.endsWith(".vtt") -> androidx.media3.common.MimeTypes.TEXT_VTT
            name.endsWith(".ass") || name.endsWith(".ssa") -> androidx.media3.common.MimeTypes.TEXT_SSA
            else -> null
        }
    }

    // ───── Keys ─────

    /**
     * Intercept key events before Media3's PlayerView sees them. With
     * [controllerAutoShow] = false, PlayerView.dispatchKeyEvent consumes
     * every D-pad press by calling `maybeShowController(true)` — which is a
     * no-op when auto-show is off — and returns `true`, so the event never
     * reaches [Activity.onKeyDown]. That's why MENU/CENTER/INFO appeared
     * "dead" when the controller was hidden (2026-04-22 follow-up).
     *
     * Handling the handful of keys we actually care about here short-circuits
     * PlayerView entirely; anything we don't consume falls through to the
     * normal dispatch chain so the controller's own focus traversal keeps
     * working once the controller is visible.
     */
    private fun isCenterKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // ── Long-press CENTER → options overlay (Google TV lacks MENU) ──
        // UP while we own the gesture: either perform the short-press
        // action (show controller / toggle dock) or swallow if the
        // long-press already fired showOptionsV2().
        if (event.action == KeyEvent.ACTION_UP && isCenterKey(event.keyCode) && longPressTracking) {
            longPressJob?.cancel()
            longPressJob = null
            longPressTracking = false
            if (longPressFired) {
                longPressFired = false
                return true
            }
            val isLive = controller.currentItem.value?.type == ContentType.LIVE
            if (isLive) {
                playerView.showController()
            } else {
                if (!dockVisible) showVodDock() else hideVodDock()
            }
            return true
        }
        // Repeats while held — consume so PlayerView doesn't act on them.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 &&
            isCenterKey(event.keyCode) && longPressTracking
        ) {
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            val optionsV2Visible = optionsV2Inflated && optionsV2State.menuVisible.value
            val noOverlay = !surfVisible && !dockVisible && !optionsV2Visible
            val isLiveNow = controller.currentItem.value?.type == ContentType.LIVE

            // Long-press timer: start on first CENTER DOWN when no overlay
            // is up and the controller is hidden. Consumes the DOWN so the
            // short-press action (show controller / toggle dock) is deferred
            // to ACTION_UP. If the user holds ≥500 ms the timer fires
            // showOptionsV2() instead.
            if (event.repeatCount == 0 && isCenterKey(event.keyCode) &&
                !controllerVisible && noOverlay && !channelZapState.visible.value
            ) {
                longPressJob?.cancel()
                longPressFired = false
                longPressTracking = true
                longPressJob = lifecycleScope.launch {
                    delay(LONG_PRESS_TIMEOUT_MS)
                    showOptionsV2()
                    longPressFired = true
                }
                return true
            }

            // MK.10.4 fix — LIVE LEFT / RIGHT / TV_CONTENTS_MENU / CHANNEL_UP /
            // CHANNEL_DOWN always go through our handler, even when the
            // Media3 controller is visible.
            if (isLiveNow && noOverlay) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_TV_CONTENTS_MENU,
                    KeyEvent.KEYCODE_CHANNEL_UP,
                    KeyEvent.KEYCODE_CHANNEL_DOWN,
                    -> {
                        if (onKeyDown(event.keyCode, event)) return true
                    }
                }
            }
            if (!controllerVisible && noOverlay) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_INFO,
                    KeyEvent.KEYCODE_GUIDE,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_TV_CONTENTS_MENU,
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_CHANNEL_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_CHANNEL_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_SPACE,
                    KeyEvent.KEYCODE_MEDIA_STOP,
                    -> {
                        if (onKeyDown(event.keyCode, event)) return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // MK.options.redesign — popup/panel takes priority over every
        // other handler. Without this, the `if (!controllerVisible)`
        // branch below was matching DPAD_LEFT first and opening the
        // surf overlay underneath the popup ("bleeds to the player").
        // BACK closes one level; arrows / CENTER / channel keys are
        // handled by the Compose layer when it has a target — boundary
        // presses become no-ops here so they don't leak.
        if (optionsV2Inflated && optionsV2State.menuVisible.value) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    if (optionsV2State.activePanel.value != null) {
                        optionsV2State.closePanel()
                    } else {
                        hideOptionsV2()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
                -> return true
            }
        }
        // Chrome (buffering / error) overlay takes precedence over
        // everything. BACK dismisses; on ERROR it also exits the player
        // because the overlay is blocking — the user is saying "I'm done
        // with this stream". Other keys fall through to the Compose
        // focus traversal inside VodPlayerChrome.
        if (chromeState != VodChromeState.NONE) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                val wasError = chromeState == VodChromeState.ERROR
                hideChrome()
                if (wasError) finish()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        // VOD dock takes precedence over the options sheet and surf overlay
        // (it's the surface the user just opened). BACK dismisses and
        // returns focus to the PlayerView; everything else falls through so
        // the dock's own Compose focus traversal gets the event (progress
        // bar ±10 s, transport buttons, secondary chips).
        if (dockVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                hideVodDock()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        // Surf overlay swallows keys while visible — it has its own focus
        // traversal; only BACK dismisses it. Do this early so built-in
        // PlayerView handlers don't also act on the key press.
        if (surfVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                hideSurf()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        // Dedicated CHANNEL_LIST key + LEFT (when the Media3 controller is
        // hidden) trigger the surf overlay on LIVE. LEFT is the canonical
        // TiviMate gesture; CHANNEL_LIST is what TV remotes with a guide
        // button emit.
        //
        // VOD behaviour diverges (MK.16.player.vod.dock follow-up): surf is
        // channel-zapping and doesn't apply to movies / episodes, so LEFT/
        // RIGHT with no overlay up become ±10 s seeks — matching the dock's
        // progress-row seek but saving the user a hop through the dock
        // transport row. This is what "seek from the main controller" means
        // in the Concept A port: LEFT/RIGHT always seek, the dock is just
        // the visual confirmation.
        if (!controllerVisible) {
            val isLive = controller.currentItem.value?.type == ContentType.LIVE
            when (keyCode) {
                KeyEvent.KEYCODE_TV_CONTENTS_MENU -> {
                    if (isLive) {
                        showSurf()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (isLive) {
                        // MK.options.redesign — once the user has paused
                        // or rewound a live stream, LEFT/RIGHT need to
                        // do timeshift seek instead of opening the surf
                        // overlay. Detection: paused OR currentLiveOffset
                        // already past the LIVE_BEHIND threshold (the
                        // same signal that surfaces the JUMP TO LIVE
                        // pill). Otherwise LEFT keeps its zap-to-surf
                        // role — playing at the edge, the user wants to
                        // browse channels.
                        val p = controller.player
                        if (isTimeshifting(p)) {
                            p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L))
                        } else {
                            showSurf()
                        }
                        return true
                    } else {
                        // VOD: silent seek (stays on plain video surface so
                        // the user can press LEFT/RIGHT repeatedly without
                        // the dock auto-hide timer gating each press).
                        val p = controller.player
                        p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L))
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isLive) {
                        // Mirror LEFT: when timeshifted, RIGHT scrubs
                        // forward toward the live edge. At-edge it has
                        // no meaning on live, so leave it alone (existing
                        // behaviour — fall through).
                        val p = controller.player
                        if (isTimeshifting(p)) {
                            // Cap at live edge: liveOffset 0 == at edge.
                            val target = p.currentPosition + 10_000L
                            p.seekTo(target)
                            return true
                        }
                    } else {
                        val p = controller.player
                        val dur = p.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                        p.seekTo((p.currentPosition + 10_000L).coerceAtMost(dur))
                        return true
                    }
                }
            }
        }
        // MK.10.4 — numeric entry takes priority. OK commits, BACK cancels.
        // Other keys fall through to the regular handler below.
        if (channelZapState.visible.value) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    commitChannelDigits()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    cancelChannelDigits()
                    return true
                }
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                finish()
                return true
            }
            // MK.options.redesign — MENU (Fire TV's 3-dot / Android TV
            // options key) now opens the lightweight popup. Live channels
            // 2026-04-27 — both LIVE and VOD use the new popup +
            // panels (MK.options.redesign). VOD-specific gating
            // (hide the live-only Record row) is done inside
            // buildOptionsV2Rows. Legacy sheet is still wired but no
            // longer reachable from MENU; will be deleted in a follow-up
            // once any remaining callers (e.g. SAF subtitle picker
            // fallback) are unwound.
            KeyEvent.KEYCODE_MENU -> {
                showOptionsV2()
                return true
            }
            // OK / ENTER toggles the control surface. LIVE keeps Media3's
            // built-in PlayerControlView (zap bar + program-progress ride
            // alongside it); VOD surfaces the Compose dock
            // (MK.16.player.vod.dock). `useController` is set per item in
            // onItemChanged so a single `showController()` call on LIVE is
            // sufficient — for VOD PlayerView ignores it anyway.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val isLive = controller.currentItem.value?.type == ContentType.LIVE
                if (isLive) {
                    if (!controllerVisible) {
                        playerView.showController()
                        return true
                    }
                } else {
                    if (!dockVisible) {
                        showVodDock()
                    } else {
                        hideVodDock()
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                toggleQuickInfo()
                return true
            }
            // MK.10.4 — numeric channel jump. Only on live; VOD typing
            // a digit would do nothing useful and would steal the digit
            // from any future text-input surface.
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5,
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
            KeyEvent.KEYCODE_9,
            KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_NUMPAD_1,
            KeyEvent.KEYCODE_NUMPAD_2, KeyEvent.KEYCODE_NUMPAD_3,
            KeyEvent.KEYCODE_NUMPAD_4, KeyEvent.KEYCODE_NUMPAD_5,
            KeyEvent.KEYCODE_NUMPAD_6, KeyEvent.KEYCODE_NUMPAD_7,
            KeyEvent.KEYCODE_NUMPAD_8, KeyEvent.KEYCODE_NUMPAD_9,
            -> {
                if (controller.currentItem.value?.type == ContentType.LIVE) {
                    val digit =
                        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                            '0' + (keyCode - KeyEvent.KEYCODE_0)
                        } else {
                            '0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
                        }
                    onChannelDigit(digit)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                // If a numeric entry is in progress, UP commits it instead
                // of cancelling — a few real remotes auto-fire UP after a
                // digit press, but our commit semantics are explicit OK
                // anyway; treat UP as the "next channel" it always was.
                if (channelZapState.visible.value) channelZapState.clear()
                ZapLatencyTracer.markZapStart("UP")
                if (controller.previous()) return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (channelZapState.visible.value) channelZapState.clear()
                ZapLatencyTracer.markZapStart("DOWN")
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
