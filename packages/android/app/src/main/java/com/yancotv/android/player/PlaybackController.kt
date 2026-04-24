package com.yancotv.android.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.shared.history.WatchHistoryRepository
import com.yancotv.shared.playback.Playable
import com.yancotv.shared.playback.toPlayable
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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
    context: Context,
    private val prefs: AppPreferences,
    private val history: WatchHistoryRepository? = null,
) {
    val player: ExoPlayer =
        run {
            // Dynamic network prefs — UA + per-call timeouts are read from
            // AppPreferences.networkFlow on every request via an interceptor.
            // The ExoPlayer itself is constructed once; the next MediaItem
            // picks up changes without a player rebuild.
            val okHttp =
                OkHttpClient
                    .Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .addInterceptor { chain ->
                        val net = prefs.networkFlow.value
                        val ua =
                            net.userAgentOverride
                                ?.takeIf { it.isNotBlank() }
                                ?: DEFAULT_USER_AGENT
                        val connect = net.connectTimeoutSec.takeIf { it > 0 } ?: DEFAULT_CONNECT_TIMEOUT_SEC
                        val read = net.readTimeoutSec.takeIf { it > 0 } ?: DEFAULT_READ_TIMEOUT_SEC
                        val req =
                            chain.request().newBuilder()
                                .header("User-Agent", ua)
                                .build()
                        chain
                            .withConnectTimeout(connect, TimeUnit.SECONDS)
                            .withReadTimeout(read, TimeUnit.SECONDS)
                            .proceed(req)
                    }.build()
            // OkHttpDataSource.Factory.setUserAgent is intentionally NOT called —
            // the interceptor above is the sole source of the UA so user
            // overrides from Settings actually take effect per request.
            val dataSourceFactory = OkHttpDataSource.Factory(okHttp)
            // Tuned for channel-zap UX — start playing at 1s buffered instead
            // of the stock 2.5s. Rebuffer threshold stays at stock 5s so we
            // don't oscillate between BUFFERING and READY on flaky sources.
            //
            // MK.8.2 timeshift retains a back-buffer so users can pause/rewind
            // a non-DVR live stream. Sized at 2 minutes — covers the realistic
            // "missed that line, rewind it" use case without piling up off-heap
            // chunk cache during long viewing sessions. At ~5 Mbps that's ~75 MB
            // of cache vs ~375 MB at the original 10-minute window; the bigger
            // window made the app noticeably heavier the longer it ran on Fire
            // TV Stick (320 MB heap, modest GPU memory pool).
            //
            // maxBufferMs trimmed from 30s → 20s for the same reason — saves
            // ~6 MB per active stream at 5 Mbps, still gives plenty of headroom
            // for HLS segment fetch latency.
            val loadControl =
                DefaultLoadControl
                    .Builder()
                    .setBufferDurationsMs(
                        // minBufferMs =
                        15_000,
                        // maxBufferMs =
                        20_000,
                        // bufferForPlaybackMs =
                        1_000,
                        // bufferForPlaybackAfterRebufferMs =
                        2_500,
                    ).setBackBuffer(
                        // backBufferDurationMs =
                        2 * 60 * 1_000,
                        // retainBackBufferFromKeyframe =
                        true,
                    ).build()
            val exo =
                ExoPlayer
                    .Builder(context)
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory),
                    ).setLoadControl(loadControl)
                    .setHandleAudioBecomingNoisy(true)
                    .build()
            // MK.12a.2 — apply the user's persisted preferred audio language
            // at construction so channels with multi-language audio default
            // to their pick. ExoPlayer carries TrackSelectionParameters across
            // MediaItem swaps, so one write here covers every channel zap.
            val preferredAudio = prefs.playbackFlow.value.audioLanguage
            if (preferredAudio.isNotBlank()) {
                exo.trackSelectionParameters =
                    exo.trackSelectionParameters
                        .buildUpon()
                        .setPreferredAudioLanguage(preferredAudio)
                        .build()
            }
            exo
        }

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
    private var _currentEpisode: Playable.Episode? = null

    val queue: StateFlow<List<ContentItem>> = _queue.asStateFlow()
    val index: StateFlow<Int> = _index.asStateFlow()
    val currentItem: StateFlow<ContentItem?> = _currentItem.asStateFlow()

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
    fun play(
        list: List<ContentItem>,
        startIndex: Int,
    ) {
        if (startIndex !in list.indices) return
        val target = list[startIndex]
        // Sealed-type gate — rejects Series containers and blank URLs.
        target.toPlayable() ?: return
        // Same-id: update the navigation queue for next/prev zap but don't
        // touch the player — second OK on the current item must be a no-op.
        if (_currentItem.value?.id == target.id) {
            _queue.value = list
            _index.value = startIndex
            return
        }
        // Different item: capture the outgoing offset before the queue swap.
        if (_currentItem.value != null) persistResumePoint()
        _currentEpisode = null
        _queue.value = list
        _index.value = startIndex
        loadCurrent()
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
    fun play(episode: Playable.Episode) {
        if (episode.streamUrl.isBlank()) return
        val view = episode.toContentItemView()
        if (_currentItem.value?.id == view.id) {
            _currentEpisode = episode
            _queue.value = listOf(view)
            _index.value = 0
            return
        }
        if (_currentItem.value != null) persistResumePoint()
        _currentEpisode = episode
        _queue.value = listOf(view)
        _index.value = 0
        loadCurrent()
    }

    /**
     * Synthesize a [ContentItem] "view" of a [Playable.Episode] so UI
     * consumers that read [currentItem] (e.g. MiniPlayer, hero chrome,
     * PlayerActivity's title bar) keep working without knowing about the
     * Playable hierarchy. The synthesized item is typed MOVIE — it's a
     * VOD file, resume-point logic behaves correctly, and
     * `type == ContentType.LIVE` checks around the app correctly read false.
     */
    private fun Playable.Episode.toContentItemView(): ContentItem =
        ContentItem(
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
        _currentEpisode = null
        _queue.value = emptyList()
        _index.value = -1
        _currentItem.value = null
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        persistResumePoint()
        _currentEpisode = null
        player.release()
        scope.cancel()
    }

    private fun step(delta: Int): Boolean {
        val list = _queue.value
        if (list.isEmpty()) return false
        val target = (_index.value + delta).coerceIn(0, list.size - 1)
        if (target == _index.value) return false
        // Persist before mutating _index so the snapshot still reads the
        // outgoing item.
        persistResumePoint()
        _index.value = target
        loadCurrent()
        return true
    }

    private fun loadCurrent() {
        val item = _queue.value.getOrNull(_index.value) ?: return
        _currentItem.value = item
        val mediaItem =
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
                ).build()
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
        scope.launch {
            val resumeMs =
                withContext(Dispatchers.IO) {
                    (repo.positionFor(item.id) ?: 0L) * 1000L
                }
            if (_currentItem.value?.id != item.id) return@launch
            if (resumeMs > 0) player.setMediaItem(mediaItem, resumeMs) else player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        }
    }

    /**
     * Snapshot current position into the watch-history table. Called from
     * [PlayerActivity]/[com.yancotv.android.MainActivity] lifecycle hooks.
     *
     * Capture runs synchronously on the caller thread (main) because
     * [ExoPlayer.getCurrentPosition] is main-thread-only; the DB upsert
     * dispatches to IO to keep main unblocked.
     */
    fun persistResumePoint() {
        val item = _currentItem.value ?: return
        if (item.type == ContentType.LIVE) return
        val pos = player.currentPosition.coerceAtLeast(0L) / 1000L
        val dur = player.duration.takeIf { it > 0L }?.let { it / 1000L }
        // Don't record positions near the very start — if the user opened a
        // title and immediately bailed they probably don't want a resume card.
        if (pos < 5L) return
        val repo = history ?: return
        // Episode sessions: write the *series* id into content_id (the FK
        // target — series rows live in `content`, episode rows do not) and
        // the episode id into the nullable episode_id column. Movies fall
        // through to the simple item.id path. Snapshot the episode on the
        // main thread before launching IO; the field can be cleared by the
        // next loadCurrent() before the coroutine runs.
        val episode = _currentEpisode
        scope.launch(Dispatchers.IO) {
            if (episode != null) {
                repo.upsert(
                    contentId = episode.seriesId,
                    episodeId = episode.id,
                    positionSeconds = pos,
                    durationSeconds = dur,
                )
            } else {
                repo.upsert(
                    contentId = item.id,
                    positionSeconds = pos,
                    durationSeconds = dur,
                )
            }
        }
    }

    companion object {
        // VLC UA is the de-facto default IPTV providers whitelist. Matches
        // what the shell's OkHttp source clients send so provider-side UA
        // checks don't flip streams to audio-only.
        private const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
        // Fallbacks for when AppPreferences returns 0/blank — kept here so
        // the controller has a safe floor independent of the prefs defaults.
        private const val DEFAULT_CONNECT_TIMEOUT_SEC = 15
        private const val DEFAULT_READ_TIMEOUT_SEC = 30
    }
}
