package com.yancotv.android.player

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.yancotv.android.BuildConfig
import com.yancotv.android.R
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.recording.RecordingDataSink
import com.yancotv.android.recording.TeeingDataSourceFactory
import com.yancotv.shared.history.RecentChannelsRepository
import com.yancotv.shared.history.WatchHistoryRepository
import com.yancotv.shared.history.shouldRecordChannel
import com.yancotv.shared.http.CleartextAllowlistInterceptor
import com.yancotv.shared.playback.Playable
import com.yancotv.shared.playback.toPlayable
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Sleep-timer presets (MK.12b.1). Fixed durations are stored in
 * milliseconds; [END_OF_PROGRAM] has no fixed duration — the duration is
 * computed at pick time from the channel's current EPG programme end time
 * and passed to [PlaybackController.setSleepTimer] explicitly.
 */
/**
 * MK.17.5 — per-source HTTP override staged by
 * [PlaybackController.applySourceOverride] and read by the OkHttp
 * interceptor on every request. Either field can be null/blank
 * independently — a source might want a custom UA without a Referer
 * (Xtream typical) or a Referer with the global UA (Akamai-style hosts
 * that gate on Referer only).
 */
internal data class SourceNetworkOverride(val userAgent: String?, val referer: String?)

/**
 * MB-306 — a side-loaded subtitle currently attached to the media item.
 * [label] is what the user picked it by ("English", "Arabic [CC]"), carried
 * through so the player's SUBTITLES panel can name the track before
 * ExoPlayer has parsed the sidecar and produced a real `Format.label`.
 */
data class ExternalSubtitle(val uri: Uri, val mime: String?, val label: String)

enum class SleepTimerOption(val durationMs: Long?) {
    MIN_15(15L * 60_000L),
    MIN_30(30L * 60_000L),
    MIN_45(45L * 60_000L),
    MIN_60(60L * 60_000L),

    /** End of currently-airing EPG programme. Duration computed by caller. */
    END_OF_PROGRAM(null),
}

/**
 * Snapshot of the active sleep timer. UI observes [PlaybackController.sleepTimer]
 * to render the current selection (and its remaining time) in the options
 * sheet's SLEEP tab.
 */
sealed interface SleepTimerState {
    data object Off : SleepTimerState

    data class Active(
        /** Wall-clock instant when the timer fires, ms since epoch. */
        val deadlineMs: Long,
        val option: SleepTimerOption,
    ) : SleepTimerState
}

/**
 * MK.24.E.3 — pure decision for [PlaybackController.play] to gate the
 * two-tap no-op contract from the production guards. Extracted so the
 * decision table can be unit-tested without standing up an ExoPlayer
 * (the controller's constructor pulls in Android Context, OkHttp,
 * DefaultDataSource — none testable from `androidUnitTest`).
 *
 * Same shape as MK.23.C.1's `resumePointDecision`: callers pass in the
 * inputs, this function returns one of three actions, the production
 * code's when-branch maps each action to its side effects.
 *
 * The two-tap-on-same-tile case (Reject / SameTarget) is the
 * load-bearing one. A regression that lets `NewTarget` fire when ids
 * match would re-prepare the MediaItem on every tap, dropping the
 * buffer and forcing a 1–3 s rebuffer (= the bug MB-225 was filed
 * against). The `Reject` case (out-of-range / non-playable target)
 * keeps the controller silent for series containers and blank URLs
 * the way `ContentItem.toPlayable()` already gates them.
 */
internal sealed interface PlayLaunchDecision {
    /** Out-of-range index, or target is a series container / blank URL. No-op. */
    data object Reject : PlayLaunchDecision

    /** Target id matches current id. Caller updates queue/index but does NOT re-prepare the player. */
    data object SameTarget : PlayLaunchDecision

    /** Different target. Caller persists outgoing resume point, swaps queue, calls loadCurrent. */
    data object NewTarget : PlayLaunchDecision
}

internal fun playLaunchDecision(list: List<ContentItem>, startIndex: Int, currentId: String?): PlayLaunchDecision {
    if (startIndex !in list.indices) return PlayLaunchDecision.Reject
    val target = list[startIndex]
    if (target.toPlayable() == null) return PlayLaunchDecision.Reject
    return if (currentId == target.id) PlayLaunchDecision.SameTarget else PlayLaunchDecision.NewTarget
}

internal fun episodeLaunchDecision(episode: Playable.Episode, currentId: String?): PlayLaunchDecision {
    if (episode.streamUrl.isBlank()) return PlayLaunchDecision.Reject
    return if (currentId == episode.id) PlayLaunchDecision.SameTarget else PlayLaunchDecision.NewTarget
}

/**
 * App-scoped playback holder. Owns the single [ExoPlayer] shared between
 * [com.yancotv.android.ui.shell.MiniPlayer] and [PlayerActivity] via
 * direct assignment: `PlayerView.player = controller.player`.
 *
 * No MediaSessionService, no MediaController proxy, no async bind. The
 * ExoPlayer is constructed synchronously and lives for the life of the
 * process. Surface handoff between mini and fullscreen is the Media3
 * "swap two Views within one app" pattern: the leaving View clears its
 * `player` ref, the arriving View assigns it.
 *
 * Background playback (lock screen / BT media buttons / Android Auto) is
 * NOT provided — this is an IPTV app, video-first, TV-primary. If that
 * changes, wrap the player in a thin MediaSessionCompat without touching
 * the public API here.
 */
@UnstableApi
class PlaybackController(
    private val context: Context,
    private val prefs: AppPreferences,
    private val history: WatchHistoryRepository? = null,
    /**
     * MK.35.3 — live channels the user actually watched.
     *
     * Separate from [history] because a live stream has no resume offset:
     * `resumePointDecision` returns null for LIVE by design, which is correct
     * and is also why live content could never reach Home unless starred.
     * Nullable for the same reason [history] is — tests that do not exercise
     * persistence pass neither.
     */
    private val recentChannels: RecentChannelsRepository? = null,
    /**
     * MK.14.8 — singleton tee sink wired between [androidx.media3.datasource.okhttp.OkHttpDataSource]
     * and [androidx.media3.datasource.DefaultDataSource]. ExoPlayer's HTTP
     * reads pass through it; when the user starts a live recording,
     * `RecordingService` calls `recordingSink.begin(stream)` and bytes
     * stream to disk in parallel with playback. `null` in tests that
     * don't need recording wiring — the data-source chain skips the
     * tee in that case.
     */
    private val recordingSink: RecordingDataSink? = null,
    /**
     * MK.17.5 — per-source HTTP override. When non-null, [loadCurrent]
     * looks up the playing item's source and stages its `userAgent` /
     * `referer` for the OkHttp interceptor. The global UA from
     * [AppPreferences.networkFlow] is the fallback when a source has
     * no override or this dependency is absent (test paths).
     */
    private val sources: SourceRepository? = null,
    /**
     * MK.SEC.C — application-layer cleartext allow-list interceptor.
     * When non-null, the per-controller OkHttp instance refuses HTTP
     * requests to hosts that aren't in the user's source list. HTTPS
     * traffic is unaffected. `null` in tests / older tooling that
     * doesn't wire the allow-list — the player falls back to the
     * pre-MK.SEC.C "manifest says cleartext OK globally" behaviour
     * in that case.
     */
    private val cleartextInterceptor: CleartextAllowlistInterceptor? = null,
) {
    /**
     * MK.17.5 — staged per-source HTTP override read by the OkHttp
     * interceptor on every request. Updated by [loadCurrent] on item
     * change. `null` = use the global UA / no Referer. Volatile because
     * the interceptor reads from OkHttp's IO threads while writes
     * originate on the controller's coroutine scope.
     */
    @Volatile
    private var currentSourceNet: SourceNetworkOverride? = null

    // MK.26.A.4 — explicit per-source headers forwarded by a handoff SENDER.
    // When set, the OkHttp interceptor prefers it over [currentSourceNet]:
    // a handed-off stream's source isn't in THIS device's source list (source
    // ids are random per-install), so the local DB lookup can't resolve its
    // UA/Referer. Staged by [playHandoff] into [pendingHandoffOverride] and
    // consumed at the next play(); a normal local play() resets it (pending is
    // null), so handoff headers never leak onto a locally-started stream.
    @Volatile
    private var handoffNetOverride: SourceNetworkOverride? = null

    @Volatile
    private var pendingHandoffOverride: SourceNetworkOverride? = null

    /**
     * The shared ExoPlayer. Mutable to support MK.9.4's FFmpeg crash
     * watchdog: on a confirmed FFmpeg-renderer crash the controller
     * releases this instance and rebuilds with platform-only renderers.
     * Surfaces that hold the reference (`PlayerActivity`, `MiniPlayer`,
     * `MainActivity` keep-awake listener) observe [playerRebuilt] and
     * re-bind. Architecture rule 4 ("one ExoPlayer at a time") holds —
     * the old instance is released before the new one is exposed.
     */
    var player: ExoPlayer = buildPlayer(useFfmpeg = true)
        private set

    // MK.9.4 — watchdog state. One rebuild per session: if the platform-
    // only fallback also crashes, surface it as a stream error to the user
    // (the existing PlayerActivity error overlay path handles that) instead
    // of looping rebuilds.
    private var hasRebuiltOnce = false

    // MK.9.4 — fired after a successful rebuild. Surfaces re-bind PlayerView
    // / TextureView and re-attach their Player.Listener instances.
    // SharedFlow with replay = 0; backgrounded surfaces re-sync on next
    // attach via the attached-player identity check, not via missed signals.
    private val _playerRebuilt = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val playerRebuilt: SharedFlow<Unit> = _playerRebuilt.asSharedFlow()

    // MK.9.4 — internal listener owned by the controller (separate from
    // PlayerActivity's playerListener which handles user-facing errors).
    // Re-attaches itself to whichever player instance is current.
    private val internalErrorListener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (hasRebuiltOnce) return
                if (!isFfmpegRelated(error)) return
                rebuildWithoutFfmpeg()
            }
        }

    init {
        player.addListener(internalErrorListener)
    }

    /**
     * Construct the shared ExoPlayer. Called once at controller-init with
     * `useFfmpeg = true`, and again by [rebuildWithoutFfmpeg] with
     * `useFfmpeg = false` after a confirmed FFmpeg-renderer crash.
     */
    private fun buildPlayer(useFfmpeg: Boolean): ExoPlayer {
        // Dynamic network prefs — UA + per-call timeouts are read from
        // AppPreferences.networkFlow on every request via an interceptor.
        // The ExoPlayer itself is constructed once; the next MediaItem
        // picks up changes without a player rebuild.
        val okHttpBuilder =
            OkHttpClient
                .Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor { chain ->
                    val net = prefs.networkFlow.value
                    // MK.17.5 — per-source override wins over the global
                    // UA. Same fallback chain as before for sources that
                    // don't carry an override: source UA → global UA →
                    // hard-coded default. Referer is only sent when the
                    // active source explicitly opts in.
                    // A handoff-forwarded override (MK.26.A.4) wins over the
                    // local source lookup; null for all normal local playback.
                    val perSource = handoffNetOverride ?: currentSourceNet
                    val ua =
                        perSource?.userAgent?.takeIf { it.isNotBlank() }
                            ?: net.userAgentOverride?.takeIf { it.isNotBlank() }
                            ?: DEFAULT_USER_AGENT
                    val connect = net.connectTimeoutSec.takeIf { it > 0 } ?: DEFAULT_CONNECT_TIMEOUT_SEC
                    val read = net.readTimeoutSec.takeIf { it > 0 } ?: DEFAULT_READ_TIMEOUT_SEC
                    val builder =
                        chain.request().newBuilder()
                            .header("User-Agent", ua)
                    perSource?.referer?.takeIf { it.isNotBlank() }?.let {
                        builder.header("Referer", it)
                    }
                    chain
                        .withConnectTimeout(connect, TimeUnit.SECONDS)
                        .withReadTimeout(read, TimeUnit.SECONDS)
                        .proceed(builder.build())
                }
        // MK.SEC.C — application-layer cleartext-traffic allow-list for
        // the player's HTTP path. Added LAST in the chain so the UA /
        // Referer / timeout interceptor still runs for allowed HTTPS
        // requests; for HTTP-to-non-allowlisted hosts the cleartext
        // interceptor short-circuits with a synthetic 469 before the
        // network actually fires. Optional — tests / standalone uses
        // of this class without Koin pass null and inherit the
        // pre-MK.SEC.C "manifest globally permissive" behaviour.
        cleartextInterceptor?.let { okHttpBuilder.addInterceptor(it) }
        val okHttp = okHttpBuilder.build()
        // OkHttpDataSource.Factory.setUserAgent is intentionally NOT called —
        // the interceptor above is the sole source of the UA so user
        // overrides from Settings actually take effect per request.
        //
        // **MK.14.5 fix (2026-04-26):** wrap the OkHttp HTTP factory in a
        // `DefaultDataSource.Factory`. OkHttp only handles `http(s)://`
        // URIs; without this wrapper, every other scheme (file:// for
        // recordings, content:// for SAF-saved recordings, asset://,
        // raw://) falls through to nothing → ExoPlayer surfaces it as
        // ERROR_CODE_FAILED_RUNTIME_CHECK (1004). DefaultDataSource.Factory
        // routes by scheme: file/asset/content/raw → built-in handlers,
        // http(s) → our OkHttp factory. Live IPTV streams are unaffected
        // (they go through OkHttp the same way they always did).
        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttp)
        // **MK.14.8 (2026-04-26 pivot):** wrap the HTTP factory in a
        // [TeeingDataSourceFactory] so user-initiated live recordings can
        // tap ExoPlayer's existing HTTP stream instead of opening a fresh
        // GET. Single-stream IPTV providers refused the second connection;
        // tee'ing sidesteps the cap entirely. The sink is a no-op when
        // no recording is active, so non-recording playback is unaffected.
        // Wrapped *inside* DefaultDataSource.Factory so file:// /
        // content:// (used for finished-recording playback) bypass the
        // tee — playing back a recording can't accidentally tee itself.
        val sourceForExo: androidx.media3.datasource.DataSource.Factory =
            recordingSink?.let { TeeingDataSourceFactory(httpDataSourceFactory, it) }
                ?: httpDataSourceFactory
        val dataSourceFactory = DefaultDataSource.Factory(context, sourceForExo)
        // Tuned for channel-zap UX — start playing at 1s buffered instead
        // of the stock 2.5s. Rebuffer threshold stays at stock 5s so we
        // don't oscillate between BUFFERING and READY on flaky sources.
        //
        // **MB-119 / 2026-04-25 4K ANR retune** — earlier values were tuned
        // for ~5 Mbps streams: maxBufferMs=20s, backBufferMs=120s. At
        // ~10 Mbps 4K HEVC (e.g. Bien Sport 4K) the same window doubles to
        // ~25 MB max-buffer + ~150 MB back-buffer in native chunk cache.
        // Combined with TextureView frame copies on the GPU side, the
        // process hit ~94 MB heap usage with concurrent GC pauses up to
        // 583ms — main thread input dispatch lost 5+ seconds, ANR fired,
        // OS killed the app. Now sized for 4K headroom:
        //
        //   - maxBufferMs 15s (was 20s) — ~19 MB at 10 Mbps; HLS segment
        //     fetch latency is well-covered.
        //   - backBufferMs 30s (was 120s) — ~37 MB at 10 Mbps. Trades the
        //     "rewind 2 minutes" timeshift window for "rewind 30s to catch
        //     a line you missed", which is the actually-used pattern.
        //   - bufferForPlayback 1s, bufferForPlaybackAfterRebuffer 2.5s
        //     — unchanged; channel-zap UX stays snappy.
        // MK.17.4 — buffer profile is user-selectable via Settings.
        // Profile values land in `BufferProfile` (LOW_LATENCY / BALANCED /
        // STABLE). Default BALANCED matches the pre-prefs hardcode.
        val profile = prefs.playbackFlow.value.bufferProfile
        val loadControl =
            DefaultLoadControl
                .Builder()
                .setBufferDurationsMs(
                    profile.minBufferMs,
                    profile.maxBufferMs,
                    profile.playbackMs,
                    profile.rebufferMs,
                ).setBackBuffer(
                    profile.backBufferMs,
                    /* retainBackBufferFromKeyframe = */
                    true,
                ).build()
        // MK.9 — vendor the FFmpeg AUDIO extension to fix MB-14
        // (Fire TV ships without licensed AC3/EAC3/DTS/TrueHD decoders, so
        // ~30% of IPTV streams played audio-only or no-audio without it).
        //
        // Mode = EXTENSION_RENDERER_MODE_ON (NOT _PREFER): platform
        // (MediaCodec hardware) renderers are tried FIRST for every format;
        // the FFmpeg extension is only used when the platform decoder
        // rejects the format. This is the right behavior because:
        //
        //   - For HEVC / H.264 video: Fire TV's hardware decoder is vastly
        //     faster than software FFmpeg. Preferring FFmpeg ANR'd the app
        //     on common HEVC streams (2026-04-25 incident — main thread
        //     blocked waiting on slow software decode).
        //   - For AC3 / EAC3 / DTS / TrueHD audio: Fire TV has no platform
        //     decoder at all, so MediaCodec rejects the format and FFmpeg
        //     picks it up automatically. Same MB-14 fix, no preference
        //     change needed.
        //
        // setEnableDecoderFallback(true) gives a second chance: if the
        // chosen renderer fails initialisation, ExoPlayer tries the next.
        //
        // MK.9.4 — after a confirmed FFmpeg crash the watchdog rebuilds
        // with useFfmpeg = false (EXTENSION_RENDERER_MODE_OFF). Platform
        // decoders are then the only path; the crash class can't recur.
        //
        // ExperimentalFfmpegVideoRenderer was deliberately removed from the
        // vendored sources after the same 2026-04-25 ANR incident — software
        // HEVC decode at 1080p is borderline on Fire TV Stick class hardware
        // and impossible at 4K. The audio renderer is what fixes MB-14;
        // forcing software video decode buys us one rare edge case (HEVC
        // main10 the hw decoder partially handles) at the cost of routine
        // ANRs on common streams. Wrong trade.
        val extensionMode =
            if (useFfmpeg) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            } else {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            }
        // MK.17.3 — fallback toggle is user-controlled. Default ON.
        val renderersFactory =
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(extensionMode)
                .setEnableDecoderFallback(prefs.playbackFlow.value.enableDecoderFallback)
        val exo =
            ExoPlayer
                .Builder(context)
                .setRenderersFactory(renderersFactory)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory),
                ).setLoadControl(loadControl)
                .setHandleAudioBecomingNoisy(true)
                // MK.28.2 (MB-246) — request/abandon audio focus like every
                // other media app. Media3's builder default is
                // handleAudioFocus=false, so pre-fix YancoTV mixed its audio
                // over Spotify/YT Music and ignored calls/assistant taking
                // focus. With this, ExoPlayer pauses on loss and ducks on
                // transient loss automatically; both the initial build and
                // the MK.9.4 watchdog rebuild pass through here.
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */
                    true,
                ).build()
        // MK.12a.2 — apply the user's persisted preferred audio language
        // at construction so channels with multi-language audio default
        // to their pick. ExoPlayer carries TrackSelectionParameters across
        // MediaItem swaps, so one write here covers every channel zap.
        //
        // Audit catch — same wiring for subtitleLanguage. Pre-fix the
        // pref was saved by Settings → Playback but never read on
        // PlayerActivity launch; users had to re-pick subtitles on every
        // new title. Mirror the audio block, plus setTrackTypeDisabled
        // (text) = false so a non-blank pref also un-hides the text track
        // when ExoPlayer's default would otherwise leave it off.
        val playbackPrefs = prefs.playbackFlow.value
        val preferredAudio = playbackPrefs.audioLanguage
        val preferredSubtitle = playbackPrefs.subtitleLanguage
        if (preferredAudio.isNotBlank() || preferredSubtitle.isNotBlank()) {
            exo.trackSelectionParameters =
                exo.trackSelectionParameters
                    .buildUpon()
                    .apply {
                        if (preferredAudio.isNotBlank()) {
                            setPreferredAudioLanguage(preferredAudio)
                        }
                        if (preferredSubtitle.isNotBlank()) {
                            setPreferredTextLanguage(preferredSubtitle)
                            setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                        }
                    }
                    .build()
        }
        return exo
    }

    /**
     * MK.9.4 — release the current FFmpeg-enabled player and stand a new
     * one up with EXTENSION_RENDERER_MODE_OFF. Captures the live state
     * (current item, position, playWhenReady, speed, track-selection
     * params) before release; reloads the same item at the same offset on
     * the new instance so the user sees a brief buffer instead of a
     * stopped player. Emits [playerRebuilt] last so consumer surfaces have
     * a current instance to bind to when they observe.
     *
     * Architecture rule 4: the old player is fully released before the new
     * one is exposed, so there is never a moment with two live ExoPlayers.
     */
    private fun rebuildWithoutFfmpeg() {
        hasRebuiltOnce = true
        // Belt-and-suspenders: persist the watch-history offset before
        // tearing the player down. setMediaItem(item, pos) on the new
        // instance covers the resume too, but if the rebuild itself
        // throws, watch_history still has the right number for next launch.
        persistResumePoint()
        val old = player
        // Snapshot before release; ExoPlayer methods are main-thread-safe
        // and the listener that called us runs on the application thread
        // (which Media3 dispatches main events on by default).
        val pos = old.currentPosition.coerceAtLeast(0L)
        val playWhenReady = old.playWhenReady
        val speed = old.playbackParameters.speed
        val trackParams = old.trackSelectionParameters
        old.removeListener(internalErrorListener)
        old.release()

        val replacement = buildPlayer(useFfmpeg = false)
        replacement.addListener(internalErrorListener)
        replacement.trackSelectionParameters = trackParams
        if (speed != 1.0f) replacement.setPlaybackSpeed(speed)
        player = replacement

        // Restore the active item — read fresh from the queue/index flows;
        // they hold the user's last navigation choice independent of the
        // released player's MediaItem.
        val item = _queue.value.getOrNull(_index.value)
        if (item != null) {
            val mediaItem = buildMediaItem(item)
            if (pos > 0L) replacement.setMediaItem(mediaItem, pos) else replacement.setMediaItem(mediaItem)
            replacement.prepare()
            replacement.playWhenReady = playWhenReady
        }

        _playerRebuilt.tryEmit(Unit)
    }

    private fun isFfmpegRelated(error: PlaybackException): Boolean = isFfmpegRelatedError(error)

    // Main-immediate so state mutations stay on the main thread; IO work
    // (SQLDelight reads/writes for resume points) dispatches to IO via
    // withContext. All DB calls into `history` must go through this scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _queue = MutableStateFlow<List<ContentItem>>(emptyList())
    private val _index = MutableStateFlow(-1)
    private val _currentItem = MutableStateFlow<ContentItem?>(null)

    // Tracks the originating Playable.Episode for an episode play so
    // persistResumePoint can write watch_history with the *series* id as
    // content_id (FK target) and the episode id in the nullable episode_id
    // column. Cleared on every non-episode load and on stop/release.
    //
    // Exposed as a StateFlow because MK.13.1 (favorites toggle in the
    // sheet) needs to favourite the *series* id, not the episode-as-view
    // id — `favorites.content_id` is FK'd to `content(id)` and episodes
    // don't have content rows.
    /**
     * MK.35.3 — when the current item began playing, for the recent-channels
     * dwell rule.
     *
     * `elapsedRealtime`, NOT wall clock: a device that resyncs its clock or
     * crosses a DST boundary mid-programme would otherwise compute a nonsense
     * duration and either record a channel nobody watched or skip one they did.
     */
    private var currentItemStartedAtMs: Long = 0L

    private val _currentEpisode = MutableStateFlow<Playable.Episode?>(null)

    // MK.12a.3 — external subtitle side-loaded into the current MediaItem.
    // Cleared whenever the item changes (zap, next/prev, stop) because the
    // URI is per-title; holding it across zaps would layer the previous
    // title's captions onto a new stream.
    /**
     * MB-306 — the side-loaded subtitle, as observable state.
     *
     * This used to be a private `Pair<Uri, String?>?`, and the player's
     * SUBTITLES panel had no way to see it. The panel derived its selection
     * purely from `player.currentTracks`, so it showed "Off" whenever no
     * text track was selected — and an external subtitle is NOT in
     * `currentTracks` until ExoPlayer has fetched and parsed the sidecar
     * file. Between `prepare()` and that load completing, the panel
     * confidently reported Off for a subtitle that was on its way and
     * about to appear. Exposing it lets the panel distinguish "no subtitle"
     * from "subtitle still loading".
     */
    private val _externalSubtitle = MutableStateFlow<ExternalSubtitle?>(null)
    val externalSubtitle: StateFlow<ExternalSubtitle?> = _externalSubtitle.asStateFlow()

    /**
     * MK.29.3 — a subtitle chosen *before* playback starts, from the browse
     * preview pane's Subtitles picker.
     *
     * [applyExternalSubtitle] can't serve that flow: it operates on
     * `_currentItem`, which is still the previous title (or null) at the
     * moment the user picks. And it can't simply be called after `play()`
     * either — that would prepare the stream once without the subtitle and
     * immediately re-prepare it with, costing the user a visible double
     * buffer on every subtitled start.
     *
     * So the pick is *staged* here and promoted to [_externalSubtitle]
     * inside [loadCurrent], after every transition path has run its
     * `_externalSubtitle.value = null` reset. The staging carries the content id
     * it was made for and is consumed one-shot, so a pick the user
     * abandons (backs out of the pane, focuses a different poster, zaps
     * elsewhere) can never attach itself to an unrelated later stream.
     */
    private var _stagedSubtitle: StagedSubtitle? = null

    private data class StagedSubtitle(val contentId: String, val uri: Uri, val mime: String?, val label: String)

    /**
     * Fallback display name when a caller side-loads a subtitle without one
     * (the "Load external file…" picker, which only has a document URI).
     * Strips the OpenSubtitles cache prefix and the extension so a raw
     * filename at least reads as a title rather than an id.
     */
    private fun defaultSubtitleLabel(uri: Uri): String = uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.substringBeforeLast('.')
        ?.replace(Regex("^\\d+-"), "")
        ?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.pc_external_subtitle)

    // MK.12b.1 — sleep timer. Coroutine job lives on `scope`; cancellation
    // happens via [cancelSleepTimer], lifecycle [release], or — for the
    // END_OF_PROGRAM option only — when [loadCurrent] runs for a new item
    // (a new channel has different EPG, so the program-end deadline no
    // longer makes sense). Fixed-duration timers persist across zap.
    private val _sleepTimer = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    private var sleepJob: Job? = null

    val queue: StateFlow<List<ContentItem>> = _queue.asStateFlow()
    val index: StateFlow<Int> = _index.asStateFlow()
    val currentItem: StateFlow<ContentItem?> = _currentItem.asStateFlow()
    val currentEpisode: StateFlow<Playable.Episode?> = _currentEpisode.asStateFlow()
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    /** Stable id of whatever is loaded in the player right now, for fast identity checks. */
    val currentId: String? get() = _currentItem.value?.id

    /**
     * Plays [list]`[startIndex]` and stores the list for [next]/[previous] zap.
     *
     * Contract:
     *   - Target is validated via [ContentItem.toPlayable]: series containers
     *     and blank stream URLs are rejected at the type level, callers that
     *     pass one get a silent no-op instead of a dead player.
     *   - If the requested item is already the current one, the queue/index
     *     are updated in place but the underlying ExoPlayer is NOT re-prepared.
     *     This makes second-tap-to-fullscreen free of rebuffers and protects
     *     the mini-to-fullscreen handoff from racing with a fresh setMediaItem.
     *   - Switching to a different item first persists the outgoing resume
     *     point so VOD mid-seeks aren't lost when the user zaps between titles
     *     without hitting a lifecycle hook.
     */
    fun play(list: List<ContentItem>, startIndex: Int, fromStart: Boolean = false) {
        // MK.28.8 (MB-282) — consume the staged handoff headers, but COMMIT
        // them only on the NewTarget branch below. Pre-fix they were
        // promoted before the launch decision, so a rejected or same-id
        // handoff rewired the STILL-PLAYING local stream's UA/Referer (and a
        // plain local re-tap of a playing handoff stream stripped its
        // headers) — breaking segment fetches on header-gated providers
        // with no visible cause. Reject/SameTarget leave the player's
        // media untouched, so its headers must stay untouched too.
        val pendingHandoff = pendingHandoffOverride
        pendingHandoffOverride = null
        when (playLaunchDecision(list, startIndex, _currentItem.value?.id)) {
            PlayLaunchDecision.Reject -> return
            PlayLaunchDecision.SameTarget -> {
                // Same-id: update the navigation queue for next/prev zap.
                // The player keeps its loaded MediaItem (no rebuffer), but
                // we DO unpause — tapping Play / Continue Watching always
                // means "I want playback now," even if the player happened
                // to be paused from the prior session (user-paused, or
                // ended-at-credits then PlayerActivity flipped
                // playWhenReady=false). If fromStart=true we ALSO seek to
                // 0 so the same-target restart works as a true reset.
                _queue.value = list
                _index.value = startIndex
                if (fromStart) {
                    player.seekTo(0L)
                }
                player.playWhenReady = true
            }
            PlayLaunchDecision.NewTarget -> {
                // Different item: capture the outgoing offset before the queue swap.
                if (_currentItem.value != null) persistResumePoint()
                // MB-282 — a null pendingHandoff here is a normal local play
                // and correctly clears any stale handoff override.
                handoffNetOverride = pendingHandoff
                _currentEpisode.value = null
                _externalSubtitle.value = null
                _queue.value = list
                _index.value = startIndex
                loadCurrent(fromStart = fromStart)
            }
        }
    }

    /**
     * Play a series episode directly. Bypasses the [ContentItem] queue
     * (episode picks come from the detail overlay, not from a browsable
     * rail), so next/prev zap is disabled for the life of this play.
     *
     * Constructs a minimal single-item queue of a synthesized "episode-as-
     * MOVIE" [ContentItem] so the existing _currentItem/_queue StateFlows
     * that UI consumers observe continue to report sane values (id =
     * [Playable.Episode.id], type = MOVIE so `isLive` checks return false).
     *
     * Also stashes the [Playable.Episode] in [_currentEpisode] so
     * [persistResumePoint] can write the *series* id as the FK-clean
     * content_id and the episode id as the nullable episode_id. The
     * synthesized view's `id` is the episode id and would FK-violate
     * if used as content_id (episodes live in their own table).
     */
    fun play(episode: Playable.Episode, fromStart: Boolean = false) {
        // MB-282 — same commit-on-NewTarget-only contract as the list
        // overload above.
        val pendingHandoff = pendingHandoffOverride
        pendingHandoffOverride = null
        when (episodeLaunchDecision(episode, _currentItem.value?.id)) {
            PlayLaunchDecision.Reject -> return
            PlayLaunchDecision.SameTarget -> {
                _currentEpisode.value = episode
                _queue.value = listOf(episode.toContentItemView())
                _index.value = 0
                // Always unpause — same-target play means user wants
                // playback. fromStart=true also resets the seek head.
                // See the list-overload's SameTarget branch for the full
                // rationale on why the player needs to be re-armed even
                // when the MediaItem hasn't changed.
                if (fromStart) {
                    player.seekTo(0L)
                }
                player.playWhenReady = true
            }
            PlayLaunchDecision.NewTarget -> {
                if (_currentItem.value != null) persistResumePoint()
                handoffNetOverride = pendingHandoff
                _currentEpisode.value = episode
                _externalSubtitle.value = null
                _queue.value = listOf(episode.toContentItemView())
                _index.value = 0
                loadCurrent(fromStart = fromStart)
            }
        }
    }

    /**
     * MK.26.A.4 — play a handed-off channel/movie applying the SENDER's
     * resolved provider headers ([userAgent] / [referer]) via
     * [handoffNetOverride], because the source isn't in this device's source
     * list. Goes through the normal [play] path otherwise (queue, resume,
     * two-tap no-op).
     */
    fun playHandoff(list: List<ContentItem>, userAgent: String?, referer: String?) {
        pendingHandoffOverride = networkOverrideOf(userAgent, referer)
        play(list, 0)
    }

    /** [playHandoff] for a handed-off series episode. */
    fun playHandoff(episode: Playable.Episode, userAgent: String?, referer: String?) {
        pendingHandoffOverride = networkOverrideOf(userAgent, referer)
        play(episode)
    }

    private fun networkOverrideOf(userAgent: String?, referer: String?): SourceNetworkOverride? {
        val ua = userAgent?.takeIf { it.isNotBlank() }
        val ref = referer?.takeIf { it.isNotBlank() }
        return if (ua == null && ref == null) null else SourceNetworkOverride(ua, ref)
    }

    /**
     * Synthesize a [ContentItem] "view" of a [Playable.Episode] so UI
     * consumers that read [currentItem] (e.g. MiniPlayer, hero chrome,
     * PlayerActivity's title bar) keep working without knowing about the
     * Playable hierarchy. The synthesized item is typed MOVIE — it's a
     * VOD file, resume-point logic behaves correctly, and
     * `type == ContentType.LIVE` checks around the app correctly read false.
     */
    private fun Playable.Episode.toContentItemView(): ContentItem = ContentItem(
        id = id,
        sourceId = sourceId,
        type = ContentType.MOVIE,
        title = title,
        cleanTitle = title,
        groupName = null,
        streamUrl = streamUrl,
        logoUrl = artworkUrl,
        tvgId = null,
        metadataJson = null,
        sortOrder = 0,
        createdAt = 0L,
    )

    fun next(): Boolean = step(+1)

    fun previous(): Boolean = step(-1)

    fun stop() {
        persistResumePoint()
        _currentEpisode.value = null
        _externalSubtitle.value = null
        _queue.value = emptyList()
        _index.value = -1
        _currentItem.value = null
        // User explicitly stopped — any sleep timer that was about to fire
        // would now pause an already-stopped player. Clear it.
        cancelSleepTimer()
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        persistResumePoint()
        _currentEpisode.value = null
        _externalSubtitle.value = null
        cancelSleepTimer()
        player.release()
        scope.cancel()
    }

    /**
     * MK.12b.1 — arm a sleep timer. For fixed presets ([SleepTimerOption.MIN_15],
     * [SleepTimerOption.MIN_30], [SleepTimerOption.MIN_45], [SleepTimerOption.MIN_60])
     * the duration is read from [SleepTimerOption.durationMs]. For
     * [SleepTimerOption.END_OF_PROGRAM] the caller computes the duration
     * from the channel's EPG (programme end_time minus now) and passes it
     * via [endOfProgramMs]; if that value is null or non-positive the call
     * is a no-op.
     *
     * Replacing an existing timer is supported — the previous job is
     * cancelled before a fresh deadline is computed.
     *
     * On expiry the player is paused (not stopped — leaving the queue and
     * resume point intact lets the user hit play again without re-loading
     * the title). Sleep state resets to [SleepTimerState.Off].
     */
    fun setSleepTimer(option: SleepTimerOption, endOfProgramMs: Long? = null) {
        val durationMs = option.durationMs ?: endOfProgramMs ?: return
        if (durationMs <= 0L) return
        sleepJob?.cancel()
        val deadline = System.currentTimeMillis() + durationMs
        _sleepTimer.value = SleepTimerState.Active(deadline, option)
        sleepJob =
            scope.launch {
                delay(durationMs)
                player.pause()
                _sleepTimer.value = SleepTimerState.Off
                sleepJob = null
            }
    }

    /** MK.12b.1 — cancel any active sleep timer. Idempotent. */
    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepTimer.value = SleepTimerState.Off
    }

    /**
     * MK.9.4 — debug-only entry point that forces the watchdog rebuild
     * path on demand, for hands-on verification of the recovery flow on
     * real hardware. Production builds short-circuit and return.
     *
     * Triggered via `adb shell am broadcast -a
     * com.yancotv.android.debug.WATCHDOG_SMOKE_TEST` (the receiver lives
     * in `src/debug/`, only compiled into debug variants). Resets
     * [hasRebuiltOnce] so the rebuild can be re-exercised across multiple
     * triggers in one session — production-path single-rebuild guard
     * stays enforced for real FFmpeg crashes.
     *
     * Architecturally identical to a real rebuild: same release →
     * snapshot → rebuild → restore → emit-signal flow. Surfaces
     * (PlayerActivity, MiniPlayer, MainActivity keepAwake) re-bind via
     * the rebuilt-signal path, exercising the same code that fires on
     * a real FFmpeg-package crash.
     */
    @VisibleForTesting
    fun debugForceRebuildForVerification() {
        if (!BuildConfig.DEBUG) return
        hasRebuiltOnce = false
        rebuildWithoutFfmpeg()
    }

    private fun step(delta: Int): Boolean {
        val list = _queue.value
        if (list.isEmpty()) return false
        val target = (_index.value + delta).coerceIn(0, list.size - 1)
        if (target == _index.value) return false
        // Persist before mutating _index so the snapshot still reads the
        // outgoing item.
        persistResumePoint()
        _externalSubtitle.value = null
        _index.value = target
        loadCurrent()
        return true
    }

    /**
     * MK.12a.3 — side-load an external subtitle file into the current
     * item and resume at the exact same offset. Rebuilding the [MediaItem]
     * is the only way to register a subtitle URI with the media source, so
     * we pay one buffer to do it. Live streams are rejected (subtitles for
     * live IPTV aren't a real workflow and rebuilding them mid-zap is
     * noisy).
     *
     * Per the native-android-mk rule, every transition that replaces a
     * MediaItem must first persist the resume point — we do that before
     * swapping, and seek back to [Player.getCurrentPosition] after so the
     * user sees the same frame they were on.
     */
    /**
     * MK.29.3 — stage [uri] as the subtitle for the next load of
     * [contentId]. See [_stagedSubtitle]. Pass a null [uri] to drop a
     * previous staging (user set the picker back to Off).
     *
     * Main-thread only, like the rest of this class; it touches no repo
     * and no player state, so it is safe to call directly from a Compose
     * click handler.
     */
    fun stageExternalSubtitle(contentId: String, uri: Uri?, mime: String?, label: String? = null) {
        _stagedSubtitle = uri?.let { StagedSubtitle(contentId, it, mime, label ?: defaultSubtitleLabel(it)) }
    }

    /**
     * MB-306 — detach the side-loaded subtitle from the media item.
     *
     * Turning text tracks off in the selector is not enough on its own: the
     * subtitle stays in the [MediaItem]'s configuration, so the next
     * [loadCurrent] (next episode, resume, zap away and back) re-attaches it
     * with `SELECTION_FLAG_DEFAULT` and it comes back on. Called by the
     * SUBTITLES panel's "Off" row.
     *
     * Deliberately does NOT rebuild the media item — the renderer is
     * already disabled by the caller, so the user sees subtitles stop
     * immediately, and paying a rebuffer to remove an inert track from the
     * item would be a worse trade than letting the next natural load drop it.
     */
    fun clearExternalSubtitle() {
        _externalSubtitle.value = null
        _stagedSubtitle = null
    }

    fun applyExternalSubtitle(uri: Uri, mime: String?, label: String? = null) {
        val item = _currentItem.value ?: return
        if (item.type == ContentType.LIVE) return
        val pos = player.currentPosition.coerceAtLeast(0L)
        persistResumePoint()
        _externalSubtitle.value = ExternalSubtitle(uri, mime, label ?: defaultSubtitleLabel(uri))
        // Enable text tracks BEFORE setMediaItem — the order matters because
        // ExoPlayer's track selector evaluates the new tracks on prepare();
        // doing the enable after prepare leaves a window where the external
        // sub is loaded but the selector still treats text as disabled, so
        // the track is present in `currentTracks` with isTrackSelected==false
        // and the SUBTITLES panel mis-renders as "Off". Also clear any prior
        // text-track override (e.g. user previously picked an embedded
        // track) so the new external sub's SELECTION_FLAG_DEFAULT wins.
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()
        player.setMediaItem(buildMediaItem(item), pos)
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * MK.17.5 — stage [currentSourceNet] for the OkHttp interceptor by
     * resolving [sourceId] against [SourceRepository] off the main thread.
     * No-op when no [sources] dependency is wired (test paths) or the
     * source carries no override; the interceptor falls back to the
     * global UA either way.
     *
     * Drops the result if the playing item has changed by the time the
     * IO read completes — a fast cross-source zap shouldn't apply the
     * older source's headers to the new stream.
     */
    private fun applySourceOverride(sourceId: String) {
        val repo = sources ?: return
        val itemAtCall = _currentItem.value ?: _queue.value.getOrNull(_index.value)
        scope.launch(Dispatchers.IO) {
            val src = runCatching { repo.getById(sourceId) }.getOrNull()
            if (_currentItem.value?.id != itemAtCall?.id) return@launch
            currentSourceNet =
                if (src?.userAgent.isNullOrBlank() && src?.referer.isNullOrBlank()) {
                    null
                } else {
                    SourceNetworkOverride(
                        userAgent = src?.userAgent?.takeIf { it.isNotBlank() },
                        referer = src?.referer?.takeIf { it.isNotBlank() },
                    )
                }
        }
    }

    private fun loadCurrent(fromStart: Boolean = false) {
        val item = _queue.value.getOrNull(_index.value) ?: return
        // MK.17.5 — stage the playing source's UA / Referer so the OkHttp
        // interceptor applies them on the imminent prepare(). Look up runs
        // off-main; the interceptor reads whatever's staged at request
        // time. First HTTP open after a same-source play is already
        // covered (override carries over from the prior load); the only
        // race is the first HTTP open after a cross-source switch with
        // no prior override staged for the new source — falls back to
        // the global UA, which is the same behaviour as pre-MK.17.5.
        applySourceOverride(item.sourceId)
        // MK.12b.1 — end-of-program timers tie to the *current* channel's
        // EPG. Zapping to a different channel invalidates the deadline; cancel.
        // Fixed-duration timers stay armed because their deadline is
        // wall-clock and not channel-bound.
        val sleep = _sleepTimer.value
        if (sleep is SleepTimerState.Active && sleep.option == SleepTimerOption.END_OF_PROGRAM) {
            cancelSleepTimer()
        }
        _currentItem.value = item
        // MK.35.3 — start of the dwell window for recent-channel recording.
        // Set for every item, read only for LIVE; keeping it unconditional means
        // there is no branch to forget when a new load path is added.
        currentItemStartedAtMs = android.os.SystemClock.elapsedRealtime()
        // MK.29.3 — promote a pre-play subtitle pick. Runs here, after every
        // caller's `_externalSubtitle.value = null` reset and before
        // buildMediaItem, so the very first prepare() of this stream already
        // carries the subtitle configuration — no second buffer.
        //
        // Consumed one-shot whether or not it matched, so an abandoned pick
        // can't latch onto a later stream. LIVE is skipped for the same
        // reason applyExternalSubtitle rejects it (no fixed-length file to
        // sync against, and a rebuild mid-zap is noisy).
        _stagedSubtitle?.let { staged ->
            _stagedSubtitle = null
            if (staged.contentId == item.id && item.type != ContentType.LIVE) {
                _externalSubtitle.value = ExternalSubtitle(staged.uri, staged.mime, staged.label)
                // Same ordering rule as applyExternalSubtitle: text must be
                // un-disabled BEFORE the media item is set, or the selector
                // evaluates the new tracks with text off and the subtitle
                // loads but never renders.
                player.trackSelectionParameters =
                    player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
            }
        }
        val mediaItem = buildMediaItem(item)
        // MK.12a.4 — apply playback speed gated by content type. Live always
        // resets to 1.0× on a new MediaItem (a temporary speed-shift on live
        // is OK but it shouldn't survive channel zap); VOD / Episodes restore
        // the persisted pref so a user who watched at 1.25× yesterday picks
        // up where they left off.
        val targetSpeed = if (item.type == ContentType.LIVE) 1.0f else prefs.playbackFlow.value.speed
        if (player.playbackParameters.speed != targetSpeed) {
            player.setPlaybackSpeed(targetSpeed)
        }
        val repo = history
        if (item.type == ContentType.LIVE || repo == null) {
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
            return
        }
        // VOD: fetch the resume offset off the main thread, then finish
        // wiring the media item on main. If the user zapped again while
        // we were awaiting the IO read, drop this result — a newer
        // loadCurrent will have kicked off a fresh lookup for the new item.
        //
        // Branch on episode-vs-content: an episode's own resume row is
        // keyed by `episode_id` (not `content_id`), and `positionFor` is
        // intentionally guarded to return only content-level rows. Without
        // this branch, episodes always started from 0 even when the user
        // had stopped mid-episode the prior session.
        //
        // [fromStart] short-circuits the DB read entirely — "Play from
        // beginning" tapped on the detail screen must always seek to 0
        // even if the row carries a resume offset, and writing that
        // intent into the row would lose the mid-stream position the
        // user might want back later.
        if (fromStart) {
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
            return
        }
        val episode = _currentEpisode.value
        scope.launch {
            val resumeMs =
                withContext(Dispatchers.IO) {
                    val seconds =
                        if (episode != null) {
                            repo.positionForEpisode(episode.id)
                        } else {
                            repo.positionFor(item.id)
                        }
                    (seconds ?: 0L) * 1000L
                }
            if (_currentItem.value?.id != item.id) return@launch
            if (resumeMs > 0) player.setMediaItem(mediaItem, resumeMs) else player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        }
    }

    /**
     * Build the [MediaItem] for [item], layering in an external subtitle
     * configuration when [_externalSubtitle] is set. Used by both
     * [loadCurrent] (normal queue load) and [applyExternalSubtitle] (subtitle
     * rebuild at current position), so side-loaded subs survive lifecycle
     * re-creates that don't clear the external-sub state.
     */
    private fun buildMediaItem(item: ContentItem): MediaItem {
        val builder =
            MediaItem
                .Builder()
                .setUri(item.streamUrl)
                .setMediaId(item.id)
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(item.cleanTitle?.ifBlank { null } ?: item.title)
                        .setArtist(item.groupName)
                        .setArtworkUri(item.logoUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse))
                        .build(),
                )
        // Local recordings are MPEG-TS files. ExoPlayer's
        // ProgressiveMediaSource auto-sniffs but TsExtractor's sniff() can
        // false-negative on streams that lead with a DVB SDT packet (PID
        // 0x0011) before the PAT — observed on the user's recordings
        // (UnrecognizedInputFormatException despite valid TS bytes). Setting
        // the MIME explicitly forces TsExtractor selection without sniff.
        if (item.id.startsWith(LOCAL_RECORDING_ID_PREFIX)) {
            builder.setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP2T)
        }
        _externalSubtitle.value?.let { sub ->
            val cfg =
                MediaItem.SubtitleConfiguration
                    .Builder(sub.uri)
                    .apply { if (!sub.mime.isNullOrBlank()) setMimeType(sub.mime) }
                    .setLanguage("und")
                    // MB-306 — the picker's own label ("English", "Arabic
                    // [CC]"), not the URI's last path segment. That segment
                    // is the OpenSubtitles cache filename, so the player's
                    // SUBTITLES panel used to list the track as
                    // "1234-Some.Movie.2019.1080p.srt".
                    .setLabel(sub.label)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            builder.setSubtitleConfigurations(listOf(cfg))
        }
        return builder.build()
    }

    /**
     * Snapshot current position into the watch-history table. Called from
     * [PlayerActivity]/[com.yancotv.android.MainActivity] lifecycle hooks.
     *
     * Capture runs synchronously on the caller thread (main) because
     * [ExoPlayer.getCurrentPosition] is main-thread-only; the DB upsert
     * dispatches to IO to keep main unblocked.
     */
    /**
     * Record the current item as a watched channel, if it earned it.
     *
     * The dwell rule lives in `shouldRecordChannel` and is the difference
     * between a useful rail and a replay of the user's scrolling: the browse
     * coverflow auto-previews LIVE channels on focus after a 400 ms debounce, so
     * recording on play would log every channel the cursor rested on.
     */
    private fun recordRecentChannel(item: ContentItem) {
        val repo = recentChannels ?: return
        if (currentItemStartedAtMs == 0L) return
        val watchedMs = android.os.SystemClock.elapsedRealtime() - currentItemStartedAtMs
        if (!shouldRecordChannel(item.type, watchedMs)) return
        scope.launch(Dispatchers.IO) {
            // Swallowed on purpose: a Home rail that misses one entry is a
            // cosmetic loss, and this runs on the stop / pause path where
            // throwing would take down a teardown the user has already left.
            runCatching { repo.record(item, watchedMs) }
        }
    }

    fun persistResumePoint() {
        val item = _currentItem.value ?: return
        // MK.35.3 — recorded here rather than on a timer because this method is
        // already called at every moment that ends a viewing: stop, next,
        // previous, a queue change, and the activity's onPause / onStop. A live
        // channel never gets past resumePointDecision below, so without this
        // call live content simply never reaches Home.
        recordRecentChannel(item)
        val pos = player.currentPosition.coerceAtLeast(0L) / 1000L
        val dur = player.duration.takeIf { it > 0L }?.let { it / 1000L }
        // Snapshot the episode on the main thread before launching IO;
        // the field can be cleared by the next loadCurrent() before the
        // coroutine runs.
        val episode = _currentEpisode.value
        // Decision logic extracted (MK.23.C.1) so it can be tested
        // without standing up the full controller / ExoPlayer / DB.
        val write = resumePointDecision(item, episode, pos, dur) ?: return
        val repo = history ?: return
        scope.launch(Dispatchers.IO) {
            repo.upsert(
                contentId = write.contentId,
                episodeId = write.episodeId,
                positionSeconds = write.positionSeconds,
                durationSeconds = write.durationSeconds,
            )
        }
    }

    /**
     * MB-VOD-LOOP: mark the current item / episode as fully watched —
     * writes `position = duration` so the row trips
     * [EpisodeResumeInfo.isFinished] / the repo's 95% rule. Series
     * detail and Home tap routing read that signal to advance to the
     * next episode on the next play; the player's `positionFor` /
     * `positionForEpisode` lookups return null on finished rows so a
     * direct re-play starts the title fresh instead of seeking to
     * the credits.
     *
     * Called from [PlayerActivity]'s `STATE_ENDED` handler before
     * autoplay fires. Defense in depth: in normal flow
     * [persistResumePoint] would write the same near-duration offset
     * via the autoplay transition's `controller.play(next)` →
     * persist chain, but if ExoPlayer's currentPosition mis-reports
     * at STATE_ENDED (we've seen brief 0-reads in the wild) the row
     * would miss the threshold; this method forces the canonical
     * "finished" shape.
     *
     * No-op when duration is unknown — without a denominator the 95%
     * rule can't fire, and writing `position = 0` would look like a
     * fresh-tap row instead of a completed one. Streams with no
     * duration metadata fall back to whatever [persistResumePoint]
     * has already written; rare in practice (most VOD has duration).
     *
     * LIVE / `_rec_` skip rules mirror [resumePointDecision].
     */
    fun markCurrentCompleted() {
        val item = _currentItem.value ?: return
        if (item.type == ContentType.LIVE) return
        if (item.id.startsWith(LOCAL_RECORDING_ID_PREFIX)) return
        val dur = player.duration.takeIf { it > 0L }?.let { it / 1000L } ?: return
        val episode = _currentEpisode.value
        val contentId = episode?.seriesId ?: item.id
        val episodeId = episode?.id
        val repo = history ?: return
        scope.launch(Dispatchers.IO) {
            repo.upsert(
                contentId = contentId,
                episodeId = episodeId,
                positionSeconds = dur,
                durationSeconds = dur,
            )
        }
    }

    companion object {
        // VLC UA is the de-facto default IPTV providers whitelist. Matches
        // what the shell's OkHttp source clients send so provider-side UA
        // checks don't flip streams to audio-only.
        private const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

        /** Prefix on synthetic ContentItem ids built by RecordingsScreen
         *  so we can identify local-file recordings inside the controller
         *  and skip resume-point persistence (they have no `content` row). */
        const val LOCAL_RECORDING_ID_PREFIX = "_rec_"

        // Fallbacks for when AppPreferences returns 0/blank — kept here so
        // the controller has a safe floor independent of the prefs defaults.
        private const val DEFAULT_CONNECT_TIMEOUT_SEC = 15
        private const val DEFAULT_READ_TIMEOUT_SEC = 30

        // MK.9.4 — error codes the watchdog inspects. Anything outside this
        // set bypasses the FFmpeg classifier entirely and surfaces normally.
        // ERROR_CODE_DECODER_QUERY_FAILED isn't included: it fires when
        // MediaCodecList lookup fails on the platform side, never from the
        // extension renderer.
        internal val FFMPEG_RELEVANT_ERROR_CODES =
            setOf(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            )
    }
}

/**
 * MK.9.4 — error-classifier. The watchdog only triggers on errors that
 * trace back to the vendored FFmpeg extension package. Anything else
 * (network failure, bad HTTP status, decoder-init failures from
 * MediaCodec itself, source format errors, or unrelated classes that
 * happen to have "Ffmpeg" in their name) surfaces normally to
 * PlayerActivity's error overlay so the user can retry or pick a
 * different stream.
 *
 * Native crashes inside libffmpegJNI segfault the process and never
 * reach the listener — that's a hard limit; nothing to do here.
 *
 * The match is package-specific (`startsWith` on the FQN prefix) rather
 * than a substring on "Ffmpeg" — the substring shape false-positives
 * on user-defined exceptions or test fixtures with FFmpeg-mentioning
 * names. Only classes from the vendored extension package count.
 *
 * Extracted top-level (internal visibility) so unit tests can exercise
 * the cause-chain walk without standing up a full ExoPlayer.
 */
internal fun isFfmpegRelatedError(error: PlaybackException): Boolean {
    if (error.errorCode !in PlaybackController.FFMPEG_RELEVANT_ERROR_CODES) return false
    var cause: Throwable? = error
    while (cause != null) {
        if (cause::class.java.name.startsWith(FFMPEG_PACKAGE_PREFIX)) return true
        cause = cause.cause
    }
    return false
}

private const val FFMPEG_PACKAGE_PREFIX = "androidx.media3.decoder.ffmpeg."

/**
 * MK.23.C.1 — pure decision shape extracted from
 * [PlaybackController.persistResumePoint]. The controller is
 * main-thread-only and holds an ExoPlayer instance, so the contract
 * was never test-coverable in JVM unit tests. This function captures
 * the rules:
 *
 *   - LIVE channels never persist (no resume concept).
 *   - Synthetic local-recording items (id prefix `_rec_`) never
 *     persist — they have no `content` row, so a watch_history insert
 *     would FK-violate.
 *   - Positions under 5 seconds never persist — bailing out of a
 *     title shouldn't leave a "resume" card on the home shelf.
 *   - Episode sessions write the *series* id as `content_id` (FK
 *     target — series rows live in `content`; episode rows live in
 *     `episodes`) plus the episode id in the nullable `episode_id`
 *     column.
 *   - Movie sessions write `item.id` as `content_id`, `episodeId =
 *     null`.
 *
 * Returns null when the contract says skip; returns a [ResumePointWrite]
 * the controller threads into `WatchHistoryRepository.upsert(...)`
 * verbatim.
 *
 * Public-ish (internal) so the app-side test harness in
 * `app/src/test/.../ResumePointDecisionTest.kt` can exercise the full
 * matrix without instantiating the controller.
 */
internal fun resumePointDecision(item: ContentItem?, episode: Playable.Episode?, positionSeconds: Long, durationSeconds: Long?): ResumePointWrite? {
    if (item == null) return null
    if (item.type == ContentType.LIVE) return null
    if (item.id.startsWith(PlaybackController.LOCAL_RECORDING_ID_PREFIX)) return null
    if (positionSeconds < 5L) return null
    // Write the raw position. The 95% "finished" rule lives on the
    // READ side (`positionFor` / `positionForEpisode` return null,
    // `EpisodeResumeInfo.isFinished` returns true) — keeping it on
    // the write side would erase the "this was watched" signal that
    // the series detail page and Home tap routing need to advance
    // to the next episode after a binge.
    return if (episode != null) {
        ResumePointWrite(
            contentId = episode.seriesId,
            episodeId = episode.id,
            positionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
        )
    } else {
        ResumePointWrite(
            contentId = item.id,
            episodeId = null,
            positionSeconds = positionSeconds,
            durationSeconds = durationSeconds,
        )
    }
}

internal data class ResumePointWrite(val contentId: String, val episodeId: String?, val positionSeconds: Long, val durationSeconds: Long?)
