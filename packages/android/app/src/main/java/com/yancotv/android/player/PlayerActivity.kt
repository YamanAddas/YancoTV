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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
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
import com.yancotv.shared.http.redactCredentials
import com.yancotv.shared.playback.toPlayable
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
    // MK.31.1 — same wrap as MainActivity. AppCompatDelegate would cover this
    // Activity on its own, but going through LocaleController keeps one source
    // of truth: an Activity that skips the wrap renders in the device language
    // while the rest of the app is switched.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.yancotv.android.locale.LocaleController.wrap(newBase))
    }

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

        // MK.25.A.1 — tiered BUFFERING debounce.
        //
        // LIVE: 500 ms (unchanged). The "Tuning the stream" overlay is
        // semantically correct for live tuner re-lock and the user
        // expects feedback when zapping channels.
        //
        // VOD steady-state: 4 s. Most VOD stalls during normal playback
        // are buffer fluctuations that resolve in 1–2 s; only show the
        // overlay if we're stuck for longer than that.
        //
        // VOD post-seek: 8 s. ExoPlayer re-aligns to the nearest
        // keyframe after a seek — on slow IPTV servers this stretches
        // to 4–5 s and can flip-flop BUFFERING → briefly READY →
        // BUFFERING when the buffer depletes after a partial fill.
        // 8 s is generous enough to swallow the whole sequence; a
        // truly-stuck seek (URL gone, server down) still eventually
        // surfaces the overlay.
        private const val LIVE_BUFFER_DEBOUNCE_MS = 500L
        private const val VOD_BUFFER_DEBOUNCE_MS = 4000L
        private const val SEEK_REBUFFER_GRACE_MS = 8000L

        // MK.25.A.2 — seek-flash auto-hide window. Multi-press inside
        // this window stacks into one badge ("+30s" not three "+10s")
        // so a TV remote keypress flurry doesn't thrash the visual.
        private const val SEEK_FLASH_HIDE_MS = 600L

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
    private val contentRepo: com.yancotv.shared.content.ContentRepository by inject()
    private val castController: com.yancotv.android.cast.CastController by inject()

    // Guard against re-entering autoplay during the small STATE_ENDED → next
    // episode prepare window where ExoPlayer can fire ENDED a second time.
    // Reset whenever a new MediaItem starts (STATE_READY).
    private var autoplayInFlight: Boolean = false

    // MK.25.A.1 — wall-clock of the most recent user seek. Used by the
    // STATE_BUFFERING listener to compute a tiered debounce: long after a
    // recent seek (rebuffer is normal, suppress the overlay), shorter
    // during steady-state VOD playback (genuine stall surfaces sooner).
    //
    // Earlier attempts: (1) fixed 1500 ms gate — too short for slow IPTV
    // re-aligns; (2) state-flag cleared on STATE_READY — broke when
    // ExoPlayer flipped BUFFERING → READY → BUFFERING within seconds
    // (buffer depleting after a partial fill). Tiered debounce handles
    // both cases by giving the buffer time to fully recover before
    // committing to "show the overlay".
    private var lastSeekAtMs: Long = 0L

    // MK.25.A.2 — multi-press-coalescing seek flash. Each LEFT/RIGHT press
    // adds ±10 to the current accumulator; a 600 ms timer clears it. Three
    // presses inside the window show "+30s", not three separate "+10s"
    // flashes — TV remotes spam keys.
    private val seekFlashFlow = kotlinx.coroutines.flow.MutableStateFlow(0)
    private var seekFlashJob: Job? = null

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

    // Phone player chrome (MK.26.A.3): on-screen Back + More (⋮) + Cast
    // buttons. The player is D-pad-first; a phone needs touch affordances to
    // exit the player and to reach the options popup / "Play on TV". Shown
    // for a phone while controls are up (live controller OR VOD dock); hidden
    // on TV. Visibility driven by updatePlayerChrome(). moreButton + castButton
    // live inside the topActions row.
    private lateinit var backButton: android.widget.ImageButton
    private lateinit var channelsButton: android.widget.ImageButton
    private lateinit var moreButton: android.widget.ImageButton
    private lateinit var castButton: android.widget.ImageButton
    private lateinit var topActions: View

    // MK.26.B — Chromecast sender state overlay. Covers the locally-paused video
    // with a "Casting to <device>" card + Stop while a Cast session is active.
    private lateinit var castOverlay: View
    private lateinit var castOverlayDevice: android.widget.TextView
    private lateinit var castOverlayStop: View

    // Phone single-tap → surface controls. The player is D-pad-first; without
    // this a touchscreen tap does nothing for VOD (the Compose dock is
    // otherwise opened only by KEYCODE_DPAD_CENTER) and LIVE leans on Media3's
    // own touch toggle. Routes both through onPlayerSingleTap() so touch and
    // remote behave identically. Lazy — needs the Activity as context.
    private val playerTapDetector by lazy {
        android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean = true

                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                    onPlayerSingleTap()
                    return true
                }
            },
        )
    }

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

    // Audit catch — single-attempt auto-retry for transient network
    // errors. On the typical IPTV Fire TV use case (flaky Wi-Fi, 5GHz
    // roaming, brief router blip) a 2-3s drop currently kicks the user
    // out to the blocking error overlay. Now: first time we see an
    // IO_NETWORK_* error on the current MediaItem, fire one delayed
    // prepare() before surfacing UI. Resets to false on every new
    // MediaItem and on every successful STATE_READY. This is the
    // smallest-shape fix — a richer retry framework (NetworkCallback,
    // backoff over N attempts) is on the next-session list.
    private var networkRetryAttempted = false
    private var networkRetryJob: Job? = null

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
            // MK.28.2 (MB-249) — playback-gated keep-screen-on, mirroring
            // MainActivity.keepAwakeListener. Paused / errored / sleep-timer-
            // stopped playback lets the display sleep; active playback holds it.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "onPlayerError ${error.errorCodeName}", error)
                // Audit catch — HLS BEHIND_LIVE_WINDOW is the canonical
                // "user paused / timeshifted past the manifest tail"
                // error. Media3's recommended recovery is seek-to-default
                // + prepare; we previously surfaced the generic "Couldn't
                // open this stream" overlay, blaming the stream for a
                // recoverable state. Common on live IPTV channels —
                // every brief pause that exceeds the live window
                // triggered it.
                val player = controller.player
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
                    player.isCurrentMediaItemLive
                ) {
                    Log.i(TAG, "BEHIND_LIVE_WINDOW: re-seeking to live edge and re-preparing")
                    player.seekToDefaultPosition()
                    player.prepare()
                    android.widget.Toast.makeText(
                        this@PlayerActivity,
                        getString(R.string.pa_reconnecting_live),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    return
                }
                // Audit catch — single-attempt auto-retry on transient
                // network errors. Most "stream broken" reports on Fire
                // TV are 2-3s Wi-Fi blips that resolve on their own.
                // Fire one prepare() after 1.5s before surfacing UI; if
                // it lands READY the attempt counter resets and the
                // user sees nothing. If it fails again we fall through
                // to the existing overlay path. Catchup 404s and codec
                // errors bypass this path — they're not transient.
                val isTransientNetwork =
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                if (isTransientNetwork && !networkRetryAttempted) {
                    networkRetryAttempted = true
                    Log.i(TAG, "Transient network error — queuing auto-retry in 1500ms")
                    android.widget.Toast.makeText(
                        this@PlayerActivity,
                        getString(R.string.pa_reconnecting),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    networkRetryJob?.cancel()
                    networkRetryJob = lifecycleScope.launch {
                        delay(1500L)
                        runCatching { player.prepare() }
                            .onFailure { Log.w(TAG, "auto-retry prepare() failed", it) }
                    }
                    return
                }
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
                        // A new MediaItem reaching READY clears any prior
                        // autoplay-in-flight guard so the next end-of-episode
                        // can fire the autoplay flow again.
                        autoplayInFlight = false
                        // Audit catch — successful prepare resets the
                        // transient-network retry budget. Next blip on
                        // this MediaItem (or a future one) gets its own
                        // single quiet attempt.
                        networkRetryAttempted = false
                        networkRetryJob?.cancel()
                        networkRetryJob = null
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
                            // MK.25.A.1 — tiered debounce. Live keeps the
                            // original 500 ms because "Tuning the stream"
                            // is correct semantics for tuner re-lock. VOD
                            // uses a much longer window so seek-induced
                            // re-buffers (which include flip-flops like
                            // BUFFERING → briefly READY → BUFFERING when
                            // the buffer depletes after a partial fill)
                            // resolve silently — the overlay only shows
                            // for genuinely-stuck streams.
                            val isLive = controller.currentItem.value?.type ==
                                com.yancotv.shared.types.ContentType.LIVE
                            val sinceSeek =
                                android.os.SystemClock.elapsedRealtime() - lastSeekAtMs
                            val debounceMs =
                                when {
                                    // MB-338 — the seek grace has to be checked
                                    // BEFORE the isLive branch, not after it.
                                    // Ordered the other way (as it was), `isLive`
                                    // short-circuited first and live timeshift
                                    // seeks got the 500 ms tuner window, so
                                    // MK.25.A.1's protection never applied to
                                    // them at all. Harmless while a seek was one
                                    // 10 s step; with hold-to-seek reaching
                                    // 300 s/tick, an IPTV origin stalls past
                                    // 500 ms routinely and the full-screen
                                    // "Tuning the stream" scrim would come back
                                    // mid-gesture — the exact complaint MK.25.A
                                    // was filed for. A user-initiated seek is
                                    // never a tuner re-lock, live or not.
                                    sinceSeek < SEEK_REBUFFER_GRACE_MS ->
                                        SEEK_REBUFFER_GRACE_MS
                                    isLive -> LIVE_BUFFER_DEBOUNCE_MS
                                    else -> VOD_BUFFER_DEBOUNCE_MS
                                }
                            bufferingShowJob =
                                lifecycleScope.launch {
                                    delay(debounceMs)
                                    showBuffering()
                                }
                        }
                    }
                    Player.STATE_IDLE, Player.STATE_ENDED -> {
                        bufferingShowJob?.cancel()
                        bufferingShowJob = null
                        if (state == Player.STATE_ENDED) {
                            // MB-VOD-LOOP: clear the just-finished item's resume
                            // point BEFORE autoplay swaps the queue. Without this,
                            // re-tapping a binge-watched series from Home seeks
                            // straight to credits → STATE_ENDED → autoplay-next →
                            // also at credits → loop. Idempotent with the 95%-cap
                            // inside resumePointDecision, but covers streams with
                            // unknown duration where the % rule can't fire.
                            controller.markCurrentCompleted()
                            tryAutoplayNextEpisode()
                        }
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (quickInfo.visibility == View.VISIBLE) refreshQuickInfo()
            }
        }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        // MK.28.2 (MB-249) — keep-screen-on is playback-gated via
        // onIsPlayingChanged in playerListener (mirrors MainActivity's
        // keepAwakeListener), NOT held unconditionally. The old permanent
        // FLAG_KEEP_SCREEN_ON (plus android:keepScreenOn in the layout)
        // meant a paused VOD, an error overlay, or the sleep timer's pause
        // kept the display awake forever — defeating the MK.12b.1 sleep
        // timer's purpose and inviting OLED burn-in on TV.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // MK.28.1 — SHORT_EDGES lets fullscreen video render into the notch
        // on Android 9–14 instead of leaving a black band beside it (the OS
        // default keeps the window out of the cutout once the bars are
        // hidden). Android 15+ already coerces non-floating windows to
        // ALWAYS; this aligns the older releases with it. No cutouts on TV.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cutoutLp = window.attributes
            cutoutLp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = cutoutLp
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
                setThemedContent {
                    RecordingIndicator(
                        controller = controller,
                        recordings = recordings,
                    )
                }
            }

        // MK.25.A.2 — seek-flash overlay. Eager-inflated: zero render cost
        // when seekFlashFlow.value == 0 (composable returns an empty Box),
        // and the trigger path can call into a ready ComposeView without
        // a stub-inflation latency hit on the user's first seek.
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.seek_flash)
            .apply {
                setViewCompositionStrategy(
                    androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
                )
                setThemedContent {
                    SeekFlashOverlay(seekFlashFlow = seekFlashFlow)
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

        // Phone player chrome (Back / More / Cast). Gated in
        // updatePlayerChrome(): Back exits the player, More opens the options
        // popup, Cast jumps straight to the "Play on TV" panel.
        backButton = findViewById(R.id.player_back_button)
        channelsButton = findViewById(R.id.player_channels_button)
        moreButton = findViewById(R.id.player_more_button)
        castButton = findViewById(R.id.player_cast_button)
        topActions = findViewById(R.id.player_top_actions)
        backButton.setOnClickListener { finish() }
        // MK.28.7 (MB-272) — touch entry to the surf overlay; the rows
        // inside are already tappable, only the entry was key-code-only.
        channelsButton.setOnClickListener {
            playerView.hideController()
            showSurf()
        }
        moreButton.setOnClickListener { openPlayerOptions(null) }
        // MK.28.7 (MB-274) — quick-info (stream stats) had only KEYCODE_INFO
        // / GUIDE entries, keys phones don't have. Long-press More = the
        // touch path to the same diagnostics overlay (it auto-hides itself).
        moreButton.setOnLongClickListener {
            toggleQuickInfo()
            true
        }
        castButton.setOnClickListener {
            openPlayerOptions(com.yancotv.android.player.options.PlayerOptionCategory.PLAY_ON_TV)
        }
        castOverlay = findViewById(R.id.cast_overlay)
        castOverlayDevice = findViewById(R.id.cast_overlay_device)
        castOverlayStop = findViewById(R.id.cast_overlay_stop)
        castOverlayStop.setOnClickListener { castController.stopCasting() }
        // The Back button shares the top-start corner with the zap bar on
        // live; nudge the zap bar right (phone only) so they don't overlap.
        // Form factor is fixed at runtime, so do it once.
        if (!isTvDevice()) {
            (zapBar.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
                it.marginStart = (76 * resources.displayMetrics.density).toInt()
                zapBar.layoutParams = it
            }
        }

        // MK.28.1 — the window now extends into the display cutout (see the
        // SHORT_EDGES block above), which is right for the video but wrong
        // for the corner chrome: the fixed-landscape rotation can put the
        // camera hole exactly where the phone Back button renders. Offset the
        // corner overlays by the cutout insets on top of their layout-time
        // base margins (captured AFTER the phone zap nudge above so the 76dp
        // base survives). System-bar insets are 0 while the bars are hidden,
        // so cutout is the only component; TVs report none → no-op.
        run {
            val corners: List<View> =
                listOf(backButton, zapBar, findViewById(R.id.recording_indicator))
            val bases =
                corners.associateWith { v ->
                    val lp = v.layoutParams as ViewGroup.MarginLayoutParams
                    intArrayOf(lp.marginStart, lp.topMargin, lp.marginEnd)
                }
            ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(android.R.id.content),
            ) { _, insets ->
                val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                val rtl =
                    resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val startInset = if (rtl) cut.right else cut.left
                val endInset = if (rtl) cut.left else cut.right
                for ((v, base) in bases) {
                    val lp = v.layoutParams as ViewGroup.MarginLayoutParams
                    lp.marginStart = base[0] + startInset
                    lp.topMargin = base[1] + cut.top
                    lp.marginEnd = base[2] + endInset
                    v.layoutParams = lp
                }
                insets
            }
        }

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
        // Phone touch drives controller/dock via our own single-tap detector
        // (below), so disable Media3's built-in touch toggle to avoid
        // double-handling. TV uses D-pad keys and is unaffected.
        playerView.controllerHideOnTouch = false
        playerView.setControllerShowTimeoutMs(CONTROLLER_TIMEOUT_MS)

        // The player is D-pad-first. On a phone, a single tap on the video
        // must surface the controls — LIVE toggles Media3's built-in
        // controller, VOD toggles the Compose dock (otherwise reachable only
        // via KEYCODE_DPAD_CENTER, so phones saw nothing on tap). Controller
        // buttons / dock chips are child / overlay views that consume their
        // own taps first, so only video-area taps reach this and toggle.
        playerView.setOnTouchListener { _, event -> playerTapDetector.onTouchEvent(event) }

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
        // MK.26.B — show the casting overlay while a Cast session is active so the
        // phone isn't left on a frozen, locally-paused frame. Inert where Cast is
        // unavailable (sessionState never leaves Idle).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                castController.sessionState.collect { applyCastOverlay(it) }
            }
        }
    }

    /**
     * MK.26.B — reflect [com.yancotv.android.cast.CastController.sessionState] on
     * screen. Active covers the paused video with the full-screen "Casting to
     * <device>" overlay; Idle hides it. The overlay is GONE in XML, so this is the
     * only thing that surfaces it.
     */
    private fun applyCastOverlay(state: com.yancotv.android.cast.CastSessionState) {
        when (state) {
            is com.yancotv.android.cast.CastSessionState.Active -> {
                castOverlayDevice.text = state.deviceName
                castOverlay.visibility = View.VISIBLE
            }
            com.yancotv.android.cast.CastSessionState.Idle -> castOverlay.visibility = View.GONE
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
        // MB-340 — onStop cancels the dock ticker but nothing restarted it, and
        // onStop does not clear dockVisible. Returning from background with the
        // dock up therefore left it rendered but frozen. Harmless while it only
        // showed a stale position; with an ends-at wall clock it would state a
        // finish time wrong by however long the app was away.
        if (dockVisible) {
            startDockProgressTicker()
            resetDockAutoHide()
        }
    }

    override fun onPause() {
        super.onPause()
        // MB-338 — BEFORE persistResumePoint, deliberately. onPause is the only
        // resume-point write on the fullscreen path, and an in-flight hold is by
        // definition still moving the position being written. Ending the gesture
        // first means what gets persisted is where the user actually stopped.
        endSeekHold()
        controller.persistResumePoint()
    }

    override fun onStop() {
        super.onStop()
        // MB-338 — belt and braces. onPause already ends it, but onStop is where
        // the surface is surrendered (clearVideoSurfaceView below), and a hold
        // that somehow survived to here must not reach a player whose output this
        // activity no longer owns.
        endSeekHold()
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
        //
        // MK.28.2 (MB-248) — the detach must be TYPED, not the no-arg
        // clearVideoSurface(). On BACK from fullscreen live, the OS draws
        // MainActivity before this onStop runs, and MiniPlayer re-attaches
        // its own Surface to the shared player during that first draw. The
        // no-arg overload cleared WHATEVER output was current — stripping
        // the mini's freshly attached surface and leaving the hero preview
        // black (audio still running) until a recomposition healed it. The
        // typed clear detaches only OUR SurfaceView and no-ops when the
        // output has already moved on, preserving the MB-119 synchronous-
        // detach guarantee in exactly the case it was added for.
        (playerView.videoSurfaceView as? android.view.SurfaceView)?.let {
            controller.player.clearVideoSurfaceView(it)
        }
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
        // MB-338 — PiP is the worst stranding case: entering it moves window
        // focus, so the ACTION_UP that would have ended the hold is delivered
        // elsewhere and lost, and onStop is not guaranteed to run while a PiP
        // window is visible. Nothing else in this callback cancels anything.
        endSeekHold()
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
        // MK.28.2 (MB-249) — seed keep-screen-on from the current state so
        // re-entering fullscreen with playback already running holds the
        // screen immediately (onIsPlayingChanged only fires on transitions).
        if (target.isPlaying) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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
        // Audit catch — reset transient-network retry budget when the
        // player swaps to a different MediaItem so each title / channel
        // gets its own single quiet attempt.
        networkRetryAttempted = false
        networkRetryJob?.cancel()
        networkRetryJob = null
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
            updatePlayerChrome()
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
        updatePlayerChrome()
    }

    /**
     * Show/hide the phone chrome (Back / More / Cast) with the controls. Phone
     * only — TV uses the remote's BACK + MENU. LIVE keys off the Media3
     * controller visibility; VOD off the Compose dock.
     */
    private fun updatePlayerChrome() {
        if (isTvDevice()) {
            backButton.visibility = View.GONE
            topActions.visibility = View.GONE
            return
        }
        val isLive = controller.currentItem.value?.type == ContentType.LIVE
        val show =
            when (controller.currentItem.value?.type) {
                ContentType.LIVE -> controllerVisible
                ContentType.MOVIE, ContentType.SERIES -> dockVisible
                null -> false
            }
        val vis = if (show) View.VISIBLE else View.GONE
        backButton.visibility = vis
        topActions.visibility = vis
        // MB-272 — surf is a live-TV concept; hide the entry on VOD.
        channelsButton.visibility = if (show && isLive) View.VISIBLE else View.GONE
    }

    /**
     * Phone chrome → options popup. Hides the VOD dock first (the popup
     * replaces it) and lands on [category] when given (Cast → PLAY_ON_TV).
     */
    private fun openPlayerOptions(category: com.yancotv.android.player.options.PlayerOptionCategory?) {
        if (dockVisible) hideVodDock()
        showOptionsV2(category)
    }

    /**
     * Phone single-tap on the video surface — mirror the KEYCODE_DPAD_CENTER
     * handling so touch and remote behave identically. LIVE toggles Media3's
     * built-in controller; VOD toggles the Compose dock. Full-screen overlays
     * (options popup / surf) sit above the PlayerView and consume their own
     * taps, so this only fires on the bare video area.
     */
    private fun onPlayerSingleTap() {
        // MK.28.7 (MB-272) — with the surf panel open, a tap on the video
        // area dismisses it (it was BACK-only + 5s idle before; on touch
        // the tap otherwise toggled the controller UNDERNEATH the panel).
        if (surfVisible) {
            hideSurf()
            return
        }
        val isLive = controller.currentItem.value?.type == ContentType.LIVE
        if (isLive) {
            if (controllerVisible) playerView.hideController() else playerView.showController()
        } else {
            if (dockVisible) hideVodDock() else showVodDock()
        }
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
        ppTime.text = getString(R.string.pa_programme_time, remainingMin, totalMin)
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
        // Audit catch — guard against the user re-typing the same channel
        // number they're already watching (common D-pad fumble, or
        // deliberate to dismiss a typed-but-wrong prefix). The
        // PlaybackController SameTarget branch masks the rebuffer
        // internally, but ZapLatencyTracer.markZapStart was firing on
        // the no-op zap and contaminating telemetry with synthetic
        // events. Short-circuit before the tracer.
        val picked = items[index]
        if (picked.id == controller.currentId) return
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
            com.yancotv.android.ui.theme.YancoTheme(isTv = isTvDevice()) {
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
                label = getString(R.string.po_audio),
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
                label = getString(R.string.cf_subtitles),
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
                label = getString(R.string.po_aspect),
                currentValue = getString(playback.resizeMode.labelRes),
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
                label = getString(R.string.po_speed),
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
                label = getString(R.string.po_sleep),
                currentValue =
                when (val s = sleepState) {
                    is com.yancotv.android.player.SleepTimerState.Off -> getString(R.string.ps_off)
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
                    label = getString(R.string.po_record),
                    currentValue = if (isRecordingNow) getString(R.string.pa_recording_now) else "—",
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
                label = getString(R.string.cat_favorites),
                currentValue = if (isFav) "Saved" else "—",
                onPick = { optionsV2State.openPanel(com.yancotv.android.player.options.PlayerOptionCategory.FAVORITES) },
            )
        rows +=
            com.yancotv.android.player.options.PlayerOptionsRow(
                category = com.yancotv.android.player.options.PlayerOptionCategory.EXTERNAL,
                label = getString(R.string.po_external),
                currentValue = "—",
                onPick = { optionsV2State.openPanel(com.yancotv.android.player.options.PlayerOptionCategory.EXTERNAL) },
            )
        // MK.26.A.3 — "Play on TV" is a PHONE-ONLY sender: on a TV this app IS
        // the receiver (MainActivity starts HandoffReceiverService when it
        // detects a TV), so the row is hidden there. UiModeManager is the
        // canonical form-factor seam (not screen-size heuristics).
        if (!isTvDevice()) {
            rows +=
                com.yancotv.android.player.options.PlayerOptionsRow(
                    category = com.yancotv.android.player.options.PlayerOptionCategory.PLAY_ON_TV,
                    label = getString(R.string.po_play_on_tv),
                    currentValue = "—",
                    onPick = { optionsV2State.openPanel(com.yancotv.android.player.options.PlayerOptionCategory.PLAY_ON_TV) },
                )
        }
        return rows
    }

    /**
     * MB-298 — wrap a player [ComposeView] in [YancoTheme].
     *
     * Five of the player's Compose surfaces were calling `setContent` bare:
     * the recording indicator, the seek-flash badge, the VOD chrome, the VOD
     * dock and the channel-surf list. `YancoTheme` is what provides
     * `LocalYancoPalette`, the font-scale density override and the
     * reduce-motion flag, so outside it those surfaces silently fell back to
     * the DEFAULT palette — meaning a user on any theme other than Frosted
     * Emerald saw the wrong colours in the player — and the
     * Settings -> Appearance -> Font scale preference did nothing for them at
     * all. The channel-surf list is the most-used surface in a live session.
     */
    private fun ComposeView.setThemedContent(content: @Composable () -> Unit) {
        setContent {
            com.yancotv.android.ui.theme.YancoTheme(isTv = isTvDevice()) { content() }
        }
    }

    private fun isTvDevice(): Boolean {
        val uiModeManager =
            getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        return uiModeManager?.currentModeType ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
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
        SleepTimerOption.END_OF_PROGRAM -> getString(R.string.po_end_of_programme)
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
            com.yancotv.android.ui.theme.YancoTheme(isTv = isTvDevice()) {
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
        inflated.setThemedContent {
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
     * MK.25.A — record a user-initiated seek and surface the +N/-N flash.
     *
     * Two side effects:
     *   - bumps [lastSeekAtMs] so the STATE_BUFFERING listener can
     *     pick the long debounce (A.1). Slow IPTV re-aligns flip-flop
     *     between BUFFERING and READY; a state flag cleared on first
     *     READY misses the second BUFFERING bounce. A wider time
     *     window covers both bounces.
     *   - accumulates [deltaSec] into [seekFlashFlow] and (re)starts a
     *     600 ms timer that clears it (A.2). Multi-press inside the
     *     window stacks: three RIGHT presses → "+30s", not three
     *     "+10s" sequential flashes that thrash the visual.
     *
     * Sign convention: positive for forward seek, negative for back.
     * [SeekFlashOverlay] keys the badge edge off the sign.
     */
    /**
     * MB-338 — true while we own a LEFT/RIGHT hold, i.e. the first press
     * actually performed a seek rather than opening the surf overlay.
     *
     * Armed by [armSeekHold] at `repeatCount == 0` from inside the seek branches
     * themselves, so the arming decision reuses the existing live-edge / mode
     * logic instead of duplicating it. Cleared by [endSeekHold] on key release
     * and on every teardown path.
     */
    private var seekHoldActive = false

    /** MB-338 — arm on a first press that genuinely seeked. */
    private fun armSeekHold() {
        seekHoldActive = true
    }

    /**
     * MB-338 — end the gesture.
     *
     * Called from ACTION_UP and from every lifecycle exit. There is deliberately
     * no ticker to cancel: the OS already delivers auto-repeat ACTION_DOWN
     * events, so accelerating per delivered tick needs no coroutine and cannot
     * strand one. That matters here — `lifecycleScope` is cancelled only at
     * ON_DESTROY (it survives onStop and PiP), and `PlaybackController` is a
     * process-scoped singleton whose ExoPlayer outlives this activity, so a
     * self-driven ticker could have kept seeking a player the user had already
     * navigated away from.
     */
    private fun endSeekHold() {
        seekHoldActive = false
    }

    /**
     * MB-338 — the single commit point for every keyboard/remote seek.
     *
     * Replaces four near-duplicate bodies that had drifted apart in exactly the
     * places acceleration stresses:
     *  - live RIGHT had a `// Cap at live edge` comment and **no clamp at all**;
     *  - VOD RIGHT clamped to `Long.MAX_VALUE` when duration was unknown, i.e.
     *    did not clamp;
     *  - only the two LEFT paths clamped, and only the lower bound.
     *
     * At 10 s a tick that was survivable. At 300 s a single tick lands in
     * unpublished segments or past the end, which is what produces an indefinite
     * rebuffer or a jump to the next item mid-gesture.
     *
     * The live forward bound is the **live edge** (`currentPosition +
     * currentLiveOffset`), not `duration`: a progressive MPEG-TS live stream has
     * no live window, so `duration` is `TIME_UNSET` and would clamp to nothing.
     */
    private fun commitSeek(deltaSec: Int) {
        val p = controller.player
        val isLive = controller.currentItem.value?.type == ContentType.LIVE
        val target = p.currentPosition + deltaSec * 1_000L
        val clamped =
            if (isLive) {
                val offset = p.currentLiveOffset.takeIf { it != C.TIME_UNSET } ?: 0L
                target.coerceIn(0L, p.currentPosition + offset.coerceAtLeast(0L))
            } else {
                SeekAccelerator.clampTargetMs(target, p.duration)
            }
        p.seekTo(clamped)
        onUserSeek(deltaSec)
    }

    private fun onUserSeek(deltaSec: Int) {
        // A.1 — extend the BUFFERING debounce for the next 8 s.
        lastSeekAtMs = android.os.SystemClock.elapsedRealtime()
        // A.2 — accumulating multi-press flash.
        seekFlashFlow.value = seekFlashFlow.value + deltaSec
        seekFlashJob?.cancel()
        seekFlashJob =
            lifecycleScope.launch {
                delay(SEEK_FLASH_HIDE_MS)
                seekFlashFlow.value = 0
            }
    }

    /**
     * Autoplay the next episode in the current series when STATE_ENDED
     * fires, gated by the user's `autoPlayNext` pref. No-ops in the
     * common cases that aren't series playback (movies, live TV, no
     * current episode) so it's safe to call unconditionally from the
     * STATE_ENDED branch.
     *
     * The DB lookup runs on `Dispatchers.IO`; the `controller.play`
     * dispatch is back on main since [PlaybackController] is
     * main-thread-only. [autoplayInFlight] guards against ExoPlayer
     * occasionally double-firing STATE_ENDED while the next prepare
     * is in progress; resets on STATE_READY of the new MediaItem.
     */
    private fun tryAutoplayNextEpisode() {
        if (autoplayInFlight) return
        val episode = controller.currentEpisode.value ?: return
        if (!prefs.playbackFlow.value.autoPlayNext) return
        autoplayInFlight = true
        lifecycleScope.launch {
            val nextPlayable =
                withContext(Dispatchers.IO) {
                    val nextInfo =
                        contentRepo.nextEpisodeAfter(
                            seriesId = episode.seriesId,
                            currentEpisodeId = episode.id,
                        )
                    val series = contentRepo.findById(episode.seriesId)
                    if (nextInfo == null || series == null) return@withContext null
                    nextInfo.toPlayable(series)
                }
            if (nextPlayable == null) {
                // End of series, episode missing locally, or series row
                // gone. Drop the guard so a future ENDED can retry.
                autoplayInFlight = false
                Log.i(TAG, "autoplay: no next episode for series=${episode.seriesId}")
                return@launch
            }
            Log.i(TAG, "autoplay: advancing to next episode=${nextPlayable.id}")
            controller.play(nextPlayable)
        }
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
            progressLabel = getString(R.string.pa_buffering),
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
        // Audit catch — catchup items use the id prefix "catchup:" (see
        // CatchupService.withCatchupUrl). When the user tries to play a
        // 3-day-old EPG recording and the provider's archive window has
        // expired, ExoPlayer surfaces ERROR_CODE_IO_FILE_NOT_FOUND or
        // ERROR_CODE_IO_BAD_HTTP_STATUS (404/410). The generic "Stream
        // not found" copy made the user assume the app is broken; tell
        // them the truth instead.
        val isCatchup = controller.currentItem.value?.id?.startsWith("catchup:") == true
        val isCatchup404 = isCatchup &&
            (
                error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                )
        val title = when {
            isCatchup404 -> getString(R.string.pe_catchup_expired)
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                getString(R.string.pe_cant_reach)
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                getString(R.string.pe_server_refused)
            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
                getString(R.string.pe_not_found)
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                getString(R.string.pe_cant_decode)
            else -> getString(R.string.pe_cant_open)
        }
        // Audit catch — ExoPlayer's localizedMessage wraps OkHttp's
        // exception text, which routinely echoes the request URL. For
        // Xtream sources that URL contains `?username=&password=`, so
        // the user-visible error overlay was literally painting
        // credentials on the TV screen. Same redaction helper the
        // EpgRepository / SourceRepository / Recorder log paths use.
        // For catch-up 404 we replace the OkHttp text entirely with a
        // friendlier explanation pointing at the Guide.
        val description = if (isCatchup404) {
            getString(R.string.pe_catchup_expired_desc)
        } else {
            error.localizedMessage?.let(::redactCredentials)
                ?: getString(R.string.pa_check_connection)
        }
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
        inflated.setThemedContent {
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
                isTv = isTvDevice(),
                onTogglePlayPause = {
                    val p = controller.player
                    p.playWhenReady = !p.playWhenReady
                    dockData = dockData.copy(isPlaying = p.playWhenReady)
                    resetDockAutoHide()
                },
                onSkipBack = {
                    // MB-338 — via commitSeek so the dock button clamps, flashes
                    // and earns the rebuffer grace exactly like a LEFT press.
                    commitSeek(-SeekAccelerator.BASE_STEP_SEC)
                    resetDockAutoHide()
                },
                onSkipForward = {
                    commitSeek(+SeekAccelerator.BASE_STEP_SEC)
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
        updatePlayerChrome()
        val v = ensureVodDockOverlay()
        v.visibility = View.VISIBLE
        v.post { v.requestFocus() }
        startDockProgressTicker()
        resetDockAutoHide()
    }

    private fun hideVodDock() {
        if (!dockVisible) return
        dockVisible = false
        updatePlayerChrome()
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
        // MB-340 — episode context for series. Read once per dock show, which is
        // sufficient: onItemChanged hides the dock on every item swap, so it is
        // always rebuilt after a title change. Null for movies, and the dock is
        // never shown for LIVE at all, so both degrade to the brand kicker.
        val kicker = episodeKicker(controller.currentEpisode.value)?.let { k ->
            if (k.title != null) getString(R.string.vd_episode_kicker, k.code, k.title) else k.code
        }
        return VodDockData(
            kicker = kicker,
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
        inflated.setThemedContent {
            if (surfVisible) {
                ChannelSurfOverlay(
                    currentContentId = controller.currentId,
                    onPick = { list, idx ->
                        hideSurf()
                        // Audit catch — guard against picking the
                        // currently-playing channel (initialFocusIndex
                        // pre-focuses it). The PlaybackController
                        // SameTarget branch already short-circuits the
                        // rebuffer, but the guard keeps the call site
                        // explicit so a future controller change can't
                        // regress this.
                        val target = list.getOrNull(idx)
                        if (target != null && target.id != controller.currentId) {
                            controller.play(list, idx)
                        }
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
    private fun isCenterKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    /** MB-338 — the two keys that drive the timeline. */
    private fun isSeekKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

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
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount > 0 &&
            isCenterKey(event.keyCode) &&
            longPressTracking
        ) {
            return true
        }

        // ── MB-338 — accelerating hold-to-seek ──
        // Lives here, in dispatchKeyEvent, and NOT in onKeyDown, for two reasons
        // the collision map established:
        //   1. onKeyDown has TWO entry points for one event (this method calls it
        //      directly for the live and controller-hidden pre-empt blocks, and
        //      the platform calls it again if the view tree declines), so a
        //      handler there can run twice for a single tick.
        //   2. There is no usable onKeyUp for these keys: PlayerActivity has no
        //      override, and Media3's PlayerView consumes D-pad ACTION_UP, so on
        //      LIVE a release hook there would never fire. The CENTER long-press
        //      above handles ACTION_UP right here for the same reason.
        if (isSeekKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) {
                val owned = seekHoldActive
                endSeekHold()
                // Consume only a gesture we owned. Returning true unconditionally
                // would swallow releases belonging to the surf overlay, the dock
                // or the options menu.
                if (owned) return true
            } else if (event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount > 0 &&
                seekHoldActive
            ) {
                // Elapsed hold, not repeatCount: auto-repeat cadence differs
                // between a Fire TV remote, a Google TV remote and a USB
                // keyboard, so a count-derived curve would make the same gesture
                // feel different on each device.
                val heldMs = event.eventTime - event.downTime
                // Physical direction on purpose. Ordered lists in this app mirror
                // under RTL (MK.31.2) but a media timeline does not —
                // VodPlayerDock marks its own seek "DELIBERATELY PHYSICAL".
                // Mirroring here would rewind when an Arabic user meant forward.
                val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                commitSeek(
                    SeekAccelerator.deltaSecondsForTick(forward, event.repeatCount, heldMs),
                )
                return true
            }
        }

        if (event.action == KeyEvent.ACTION_DOWN) {
            val optionsV2Visible = optionsV2Inflated && optionsV2State.menuVisible.value
            val noOverlay = !surfVisible && !dockVisible && !optionsV2Visible
            // MK.28.2 (MB-247) — CENTER must reach the buffering/error chrome
            // overlay's focused RETRY button. Pre-fix, a stream error left
            // controllerVisible false and no overlay flag set, so the CENTER
            // long-press tracker consumed the press and toggled the dock /
            // controller UNDER the error card — recovery was impossible by
            // D-pad (stream errors are routine IPTV events). Only the
            // CENTER-key paths are gated on chrome: LEFT/RIGHT/CHANNEL zap
            // stays live so the user can still zap away from a dead channel
            // while the overlay is up (the pre-fix behaviour worth keeping).
            val chromeUp = chromeState != VodChromeState.NONE
            val isLiveNow = controller.currentItem.value?.type == ContentType.LIVE

            // Long-press timer: start on first CENTER DOWN when no overlay
            // is up and the controller is hidden. Consumes the DOWN so the
            // short-press action (show controller / toggle dock) is deferred
            // to ACTION_UP. If the user holds ≥500 ms the timer fires
            // showOptionsV2() instead.
            if (event.repeatCount == 0 &&
                isCenterKey(event.keyCode) &&
                !controllerVisible &&
                noOverlay &&
                !chromeUp &&
                !channelZapState.visible.value
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
                        // MB-247 — with the chrome overlay up, CENTER/ENTER
                        // must fall through to the overlay's focused
                        // RETRY/BACK button instead of our handler.
                        if (!(chromeUp && isCenterKey(event.keyCode))) {
                            if (onKeyDown(event.keyCode, event)) return true
                        }
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
                            // MB-338 — arm the hold so auto-repeat ticks
                            // accelerate. Armed only where a seek ACTUALLY
                            // happened: at the live edge this branch opens surf
                            // instead, and a hold there must not be treated as
                            // a scrub gesture.
                            armSeekHold()
                            commitSeek(-SeekAccelerator.BASE_STEP_SEC)
                        } else {
                            showSurf()
                        }
                        return true
                    } else {
                        // VOD: silent seek (stays on plain video surface so
                        // the user can press LEFT/RIGHT repeatedly without
                        // the dock auto-hide timer gating each press).
                        armSeekHold()
                        commitSeek(-SeekAccelerator.BASE_STEP_SEC)
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
                            // MB-338 — the live-edge cap this comment promised is
                            // now actually implemented, in commitSeek.
                            armSeekHold()
                            commitSeek(+SeekAccelerator.BASE_STEP_SEC)
                            return true
                        }
                    } else {
                        armSeekHold()
                        commitSeek(+SeekAccelerator.BASE_STEP_SEC)
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
