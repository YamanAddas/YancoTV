package com.yancotv.android.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.yancotv.shared.history.WatchHistoryRepository
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
    private val history: WatchHistoryRepository? = null,
) {

    val player: ExoPlayer = run {
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val dataSourceFactory = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(DEFAULT_USER_AGENT)
        // Tuned for channel-zap UX — start playing at 1s buffered instead
        // of the stock 2.5s. Rebuffer threshold stays at stock 5s so we
        // don't oscillate between BUFFERING and READY on flaky sources.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500,
            )
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory),
            )
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    // Main-immediate so state mutations stay on the main thread; IO work
    // (SQLDelight reads/writes for resume points) dispatches to IO via
    // withContext. All DB calls into `history` must go through this scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _queue = MutableStateFlow<List<ContentItem>>(emptyList())
    private val _index = MutableStateFlow(-1)
    private val _currentItem = MutableStateFlow<ContentItem?>(null)

    val queue: StateFlow<List<ContentItem>> = _queue.asStateFlow()
    val index: StateFlow<Int> = _index.asStateFlow()
    val currentItem: StateFlow<ContentItem?> = _currentItem.asStateFlow()

    /** Stable id of whatever is loaded in the player right now, for fast identity checks. */
    val currentId: String? get() = _currentItem.value?.id

    /** Plays [list]`[startIndex]` and stores the list for [next]/[previous] zap. */
    fun play(list: List<ContentItem>, startIndex: Int) {
        if (startIndex !in list.indices) return
        _queue.value = list
        _index.value = startIndex
        loadCurrent()
    }

    fun next(): Boolean = step(+1)
    fun previous(): Boolean = step(-1)

    fun stop() {
        persistResumePoint()
        _queue.value = emptyList()
        _index.value = -1
        _currentItem.value = null
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        persistResumePoint()
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
        val mediaItem = MediaItem.Builder()
            .setUri(item.streamUrl)
            .setMediaId(item.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.cleanTitle?.ifBlank { null } ?: item.title)
                    .setArtist(item.groupName)
                    .setArtworkUri(item.logoUrl?.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .build(),
            )
            .build()
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
            val resumeMs = withContext(Dispatchers.IO) {
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
        scope.launch(Dispatchers.IO) {
            repo.upsert(
                contentId = item.id,
                positionSeconds = pos,
                durationSeconds = dur,
            )
        }
    }

    companion object {
        // VLC UA is the de-facto default IPTV providers whitelist. Matches
        // what the shell's OkHttp source clients send so provider-side UA
        // checks don't flip streams to audio-only.
        private const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
        private const val CONNECT_TIMEOUT_SEC = 15L
        private const val READ_TIMEOUT_SEC = 30L
    }
}
