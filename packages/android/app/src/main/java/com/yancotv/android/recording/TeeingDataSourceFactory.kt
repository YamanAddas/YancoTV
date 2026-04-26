package com.yancotv.android.recording

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TeeDataSource

/**
 * MK.14.8 — Wraps an HTTP [DataSource.Factory] (typically
 * [androidx.media3.datasource.okhttp.OkHttpDataSource.Factory]) so every
 * created data source pipes its bytes through [RecordingDataSink] on the
 * way out. The sink decides per-frame whether bytes get persisted (it's
 * a no-op when no user recording is active, see [RecordingDataSink.write]).
 *
 * Used inside [com.yancotv.android.player.PlaybackController.buildPlayer]
 * before the result is wrapped in [androidx.media3.datasource.DefaultDataSource.Factory].
 * That outer factory routes by URI scheme, so only `http(s)://` traffic
 * goes through here — `file://`, `content://`, `asset://` (used to play
 * back finished local recordings) bypass the tee entirely. ExoPlayer
 * therefore can't accidentally record itself when playing a recording back.
 *
 * One [TeeDataSource] is created per upstream [DataSource] request. The
 * sink instance is shared across all of them — the singleton tee state
 * lives in [RecordingDataSink], not here.
 */
@UnstableApi
class TeeingDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val sink: RecordingDataSink,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = TeeDataSource(upstreamFactory.createDataSource(), sink)
}
