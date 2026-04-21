package com.yancotv.android.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.yancotv.shared.types.ContentItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * App-scoped owner of the single [ExoPlayer] instance. One player is shared
 * between the mini view in the shell and the fullscreen [PlayerActivity];
 * the switch uses `PlayerView.switchTargetView()` so the underlying surface
 * hand-off happens without a rebuffer.
 *
 * A lightweight "queue" (current list + index) lets the fullscreen activity
 * zap channels with D-pad Up/Down by walking whichever list the shell was
 * showing when the user pressed Enter.
 */
@UnstableApi
class PlaybackController(context: Context) {

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val dataSourceFactory = OkHttpDataSource.Factory(okHttp)
        .setUserAgent(DEFAULT_USER_AGENT)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
        .build()

    private val _queue = MutableStateFlow<List<ContentItem>>(emptyList())
    private val _index = MutableStateFlow(-1)
    private val _currentItem = MutableStateFlow<ContentItem?>(null)
    val currentItem: StateFlow<ContentItem?> = _currentItem.asStateFlow()

    val queue: StateFlow<List<ContentItem>> = _queue.asStateFlow()
    val index: StateFlow<Int> = _index.asStateFlow()

    /** Plays [item] from [list]; the list is used for Up/Down zap in fullscreen. */
    fun play(list: List<ContentItem>, startIndex: Int) {
        if (startIndex !in list.indices) return
        _queue.value = list
        _index.value = startIndex
        loadCurrent()
    }

    fun next(): Boolean = step(+1)
    fun previous(): Boolean = step(-1)

    private fun step(delta: Int): Boolean {
        val list = _queue.value
        if (list.isEmpty()) return false
        val next = (_index.value + delta).coerceIn(0, list.size - 1)
        if (next == _index.value) return false
        _index.value = next
        loadCurrent()
        return true
    }

    private fun loadCurrent() {
        val item = safeItemAt(_index.value) ?: return
        _currentItem.value = item
        player.setMediaItem(MediaItem.fromUri(item.streamUrl))
        player.playWhenReady = true
        player.prepare()
    }

    private fun safeItemAt(i: Int): ContentItem? = _queue.value.getOrNull(i)

    fun stop() {
        player.stop()
        player.clearMediaItems()
        _queue.value = emptyList()
        _index.value = -1
        _currentItem.value = null
    }

    fun release() {
        player.release()
    }

    companion object {
        private const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
    }
}
