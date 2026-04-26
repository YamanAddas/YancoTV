package com.yancotv.android.recording

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER
import java.io.IOException
import java.io.OutputStream

/**
 * MK.14.8 — Live-recording sink that taps ExoPlayer's existing data flow
 * via Media3's [androidx.media3.datasource.TeeDataSource]. Wired into the
 * shared [com.yancotv.android.player.PlaybackController]'s HTTP factory
 * by [TeeingDataSourceFactory], so every byte ExoPlayer reads from an
 * `http(s)://` source flows here on its way to the decoder.
 *
 * **Why not a fresh HTTP GET** (the previous Stage 3.1 architecture):
 * Most Xtream-style IPTV providers cap concurrent streams per account.
 * The recorder's parallel GET to the same channel either hangs in
 * `performGet` (server holds the slot for the player) or kicks the
 * player off. Tapping the bytes the player is *already* pulling sidesteps
 * the cap entirely — record-while-watching works on 1-stream plans.
 *
 * **Lifecycle (user-driven, not Tee-driven):**
 *   - [begin] — opens a [java.io.FileOutputStream]-or-SAF stream and
 *     starts capturing writes. Idempotent: a duplicate begin closes the
 *     prior stream first (defensive — UI gates this, but better safe).
 *   - [end] — flushes + closes the stream and returns the byte count.
 *     Safe to call when no recording is active (idempotent no-op).
 *   - [open] / [close] — **deliberate no-ops.** Tee invokes them once per
 *     [DataSpec] (= one per HTTP GET for continuous MPEG-TS, one per
 *     segment for HLS). Routing those through to our stream would
 *     fragment the recording across segment boundaries; the user's
 *     "Record this channel" intent spans the entire viewing session,
 *     not per-segment.
 *
 * **Concurrency model.** Singleton across the app process (one instance
 * managed by Koin). Three threads touch it:
 *   - ExoPlayer's load thread → [write]
 *   - RecordingService's IO scope → [begin] / [end]
 *   - UI / random callers → [isActive] / [bytesSinceBegin] reads
 *
 * All access is gated by [lock]. Writes after [end] (or before [begin])
 * are silently dropped — the user's recording is finalized; ExoPlayer
 * keeps reading bytes for ongoing playback, and we don't want to write
 * those into a closed file.
 *
 * **Single recording at a time.** The current UI path (RecordPanel →
 * `activeForChannel` check) ensures only one user-initiated live
 * recording can be active. Catch-up / scheduled recordings on different
 * URLs go through `MpegTsRecorder`/`HlsRecorder`'s fresh-GET path and
 * don't touch this sink.
 */
@UnstableApi
class RecordingDataSink(
    @Suppress("unused") private val logger: Logger = NOOP_LOGGER,
) : DataSink {
    private val lock = Any()
    private var output: OutputStream? = null
    private var bytesWritten: Long = 0L

    /** True while a recording is in progress (between [begin] and [end]). */
    val isActive: Boolean
        get() = synchronized(lock) { output != null }

    /** Bytes written to the current recording. Resets on [begin]. */
    val bytesSinceBegin: Long
        get() = synchronized(lock) { bytesWritten }

    /**
     * Start capturing into [stream]. The sink takes ownership — [end]
     * closes it. Caller is responsible for buffering (we don't wrap;
     * call sites that want a buffer pass a `BufferedOutputStream`).
     *
     * If a prior recording was active without [end] being called, the
     * old stream is force-closed first. Defensive: production gates this
     * via UI / RecordingService bookkeeping, so this branch shouldn't
     * fire. Tests exercise it.
     */
    fun begin(stream: OutputStream) {
        synchronized(lock) {
            output?.let { prior ->
                runCatching { prior.flush() }
                runCatching { prior.close() }
            }
            output = stream
            bytesWritten = 0L
        }
    }

    /**
     * Stop capturing and close the current stream. Returns the byte
     * count written between [begin] and now. Idempotent: returns 0 when
     * called without an active recording.
     */
    fun end(): Long {
        synchronized(lock) {
            val total = bytesWritten
            output?.let { stream ->
                runCatching { stream.flush() }
                runCatching { stream.close() }
            }
            output = null
            bytesWritten = 0L
            return total
        }
    }

    /**
     * No-op. [androidx.media3.datasource.TeeDataSource.open] calls this
     * once per upstream `open(DataSpec)` (= one per HTTP GET for
     * continuous MPEG-TS, one per segment for HLS). The user-driven
     * recording lifecycle owned by [begin] / [end] must survive across
     * those — opening/closing the file per DataSpec would fragment the
     * recording.
     */
    override fun open(dataSpec: DataSpec) {
        // Intentionally empty — see KDoc.
    }

    /**
     * Capture bytes into the active stream. Called from ExoPlayer's load
     * thread. When no recording is active (UI hasn't called [begin] yet,
     * or [end] already ran) writes are silently dropped — they belong to
     * regular playback, not a recording.
     *
     * IO failures during a write force-end the stream (we drop the rest
     * of the recording rather than crash the player). The UI's "Stop
     * recording" still works to finalize the row with whatever made it
     * to disk.
     */
    @Throws(IOException::class)
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            val stream = output ?: return
            try {
                stream.write(buffer, offset, length)
                bytesWritten += length.toLong()
            } catch (t: IOException) {
                runCatching { stream.close() }
                output = null
                // Do not rethrow — Tee.read() would then propagate the
                // IOException up to ExoPlayer's loader and abort playback.
                // Recording is best-effort; the player keeps going.
            }
        }
    }

    /**
     * No-op. See [open]. The TeeDataSource's per-DataSpec close cycle
     * doesn't end the user's recording; only [end] does.
     */
    override fun close() {
        // Intentionally empty — see KDoc.
    }
}
