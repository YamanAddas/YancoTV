package com.yancotv.android.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
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

/**
 * App-scoped client façade in front of [PlaybackService]'s [MediaController].
 *
 * Ownership split:
 *
 *   * [PlaybackService] owns the real [androidx.media3.exoplayer.ExoPlayer]
 *     and a [androidx.media3.session.MediaSession].
 *   * This class holds a [MediaController] — a Player-compatible proxy the
 *     rest of the app talks to. Binding is async; until it connects,
 *     [player] returns null and new playback calls are deferred into a
 *     small queue that flushes on connect.
 *
 * The queue state (`queue`, `index`, `currentItem`) lives here, not in the
 * player, because the shell wants fast client-side identity checks (e.g.
 * "is the focused item already playing?") that don't require a round trip
 * through the session IPC.
 *
 * Zap: [next]/[previous] walk the same list the shell passed to [play]; we
 * do not push the whole list as a Media3 playlist (binder transactions cap
 * at ~1MB and a 50k-channel source wouldn't fit). Tradeoff: BT headset
 * next/previous buttons drive transport on the current stream only, not
 * channel zap. [PlayerActivity] wires D-pad up/down to [next]/[previous]
 * to cover the TV path.
 */
@UnstableApi
class PlaybackController(
    private val context: Context,
    private val history: WatchHistoryRepository? = null,
) {

    private var controller: MediaController? = null
    private val pendingOps = ArrayDeque<() -> Unit>()

    // Main-immediate so state mutations stay on the main thread; IO work
    // (SQLDelight reads/writes) dispatches into Dispatchers.IO via withContext.
    // All DB calls into `history` must go through this scope — the repo is
    // synchronous and blocks the caller thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The [MediaController] once connected, or null during app boot before
     * [connect] resolves. Callers that bind a `PlayerView` must observe
     * [connected] and reassign the view's player when it flips.
     */
    val player: MediaController? get() = controller

    private val _queue = MutableStateFlow<List<ContentItem>>(emptyList())
    private val _index = MutableStateFlow(-1)
    private val _currentItem = MutableStateFlow<ContentItem?>(null)
    private val _connected = MutableStateFlow(false)

    val queue: StateFlow<List<ContentItem>> = _queue.asStateFlow()
    val index: StateFlow<Int> = _index.asStateFlow()
    val currentItem: StateFlow<ContentItem?> = _currentItem.asStateFlow()
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Stable id of whatever is loaded in the player right now, for fast identity checks. */
    val currentId: String? get() = _currentItem.value?.id

    /**
     * Bind to [PlaybackService]. Must be called on the main thread
     * (typically from `Application.onCreate`). Safe to call repeatedly;
     * subsequent invocations after the first successful connect are no-ops.
     */
    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                _connected.value = true
                while (pendingOps.isNotEmpty()) pendingOps.removeFirst().invoke()
            } catch (t: Throwable) {
                // Failed binds leave us in the disconnected state. The shell
                // handles a null player gracefully (empty mini surface) and
                // any queued ops remain buffered; a later connect() call
                // will flush them.
            }
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        controller?.release()
        controller = null
        _connected.value = false
        pendingOps.clear()
        scope.cancel()
    }

    /** Plays [list]`[startIndex]` and stores the list for [next]/[previous] zap. */
    fun play(list: List<ContentItem>, startIndex: Int) {
        if (startIndex !in list.indices) return
        _queue.value = list
        _index.value = startIndex
        runOrDefer { loadCurrent() }
    }

    fun next(): Boolean = step(+1)
    fun previous(): Boolean = step(-1)

    fun stop() {
        // Snapshot the outgoing VOD's resume point before we drop the queue —
        // BACK out of the fullscreen player must not lose progress.
        persistResumePoint()
        _queue.value = emptyList()
        _index.value = -1
        _currentItem.value = null
        runOrDefer {
            controller?.stop()
            controller?.clearMediaItems()
        }
    }

    private fun step(delta: Int): Boolean {
        val list = _queue.value
        if (list.isEmpty()) return false
        val target = (_index.value + delta).coerceIn(0, list.size - 1)
        if (target == _index.value) return false
        // Persist before mutating _index so the snapshot still reads the
        // outgoing item. The upsert dispatches to IO; the capture is main.
        persistResumePoint()
        _index.value = target
        runOrDefer { loadCurrent() }
        return true
    }

    private fun loadCurrent() {
        val item = _queue.value.getOrNull(_index.value) ?: return
        _currentItem.value = item
        val c = controller ?: return
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
            c.setMediaItem(mediaItem)
            c.prepare()
            c.playWhenReady = true
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
            val liveCtl = controller ?: return@launch
            if (resumeMs > 0) liveCtl.setMediaItem(mediaItem, resumeMs) else liveCtl.setMediaItem(mediaItem)
            liveCtl.prepare()
            liveCtl.playWhenReady = true
        }
    }

    /**
     * Snapshot current position into the watch-history table. Called from
     * [PlayerActivity] on pause/stop — we don't persist on every tick because
     * (a) it's wasteful, (b) a crash loses at most a few seconds, and (c) the
     * app lifecycle guarantees an onStop before the process is killed in
     * nearly all cases.
     *
     * Capture runs synchronously on the caller thread (main) because
     * [MediaController.currentPosition] is main-thread-only; the DB upsert
     * is dispatched to IO to keep main unblocked.
     */
    fun persistResumePoint() {
        val item = _currentItem.value ?: return
        if (item.type == ContentType.LIVE) return
        val c = controller ?: return
        val pos = c.currentPosition.coerceAtLeast(0L) / 1000L
        val dur = c.duration.takeIf { it > 0L }?.let { it / 1000L }
        // Don't bother recording positions near the very start — if the user
        // opened a title and immediately bailed they probably didn't want a
        // resume card.
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

    private fun runOrDefer(op: () -> Unit) {
        if (controller != null) op() else pendingOps.addLast(op)
    }
}
