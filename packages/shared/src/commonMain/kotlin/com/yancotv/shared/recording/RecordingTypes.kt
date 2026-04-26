package com.yancotv.shared.recording

import kotlinx.io.Sink

/**
 * What kind of stream we're recording. Drives which Recorder
 * implementation runs:
 *
 *   - [HLS] — manifest at `sourceUrl`, fetch each .ts segment in
 *     order, write raw bytes to the output sink. Output file is a
 *     concatenated MPEG-TS playable by ExoPlayer's local-file path.
 *   - [MPEG_TS] — direct HTTP GET of an HTTP-served MPEG-TS body
 *     (Xtream catch-up's dominant format). Read in 188-byte aligned
 *     chunks, write as we go.
 *
 * DASH and encrypted-segment formats are explicitly out of scope for
 * v1.0 — see `docs/design/recording-spec.md` §5.
 */
enum class RecordingFormat {
    HLS,
    MPEG_TS,
}

/**
 * Caller-provided handle for a recording. The DB row at [recordId]
 * is expected to exist before [HlsRecorder.record] / [MpegTsRecorder.record]
 * is called — the recorder writes bytes + reports state, the calling
 * service / repo owns the row's lifecycle.
 */
data class RecordInput(
    val recordId: String,
    val sourceUrl: String,
    val title: String,
    val format: RecordingFormat,
    /**
     * Hard cap on the recording duration in milliseconds. Scheduled
     * recordings set this from `recording_schedules.scheduled_end -
     * scheduled_start + post_padding`. `null` means unbounded — the
     * recorder runs until the source dries up or the caller cancels.
     */
    val maxDurationMs: Long? = null,
    val userAgent: String? = null,
    val referer: String? = null,
)

/**
 * Knobs the recorder honours when fetching from an unreliable source.
 * Defaults match `recording-spec.md` §3 (3 retries with exponential
 * backoff, 60 s heartbeat). Tests override the heartbeat for
 * fast-fail scenarios.
 */
data class RecorderStrategy(
    val maxRetriesPerRequest: Int = 3,
    val initialBackoffMs: Long = 1_000L,
    /**
     * Time window during which at least one HTTP request must complete
     * with content; otherwise the recorder fails with
     * `error = "heartbeat_timeout"`. Avoids a hung connection wedging
     * the foreground service indefinitely.
     */
    val heartbeatTimeoutMs: Long = 60_000L,
    /**
     * For live HLS, how long to sleep between manifest re-fetches when
     * we've consumed all currently-known segments. Defaults to a small
     * fraction of the manifest's `EXT-X-TARGETDURATION` so we pick up
     * new segments soon after they're published. The HLS spec guidance
     * is half the target duration; we use 1500 ms when target isn't
     * available.
     */
    val manifestPollIntervalMs: Long = 1_500L,
)

/**
 * Live state of a single recording. Exposed to the UI via the
 * recorder's [kotlinx.coroutines.flow.StateFlow]; the
 * `RecordingsScreen` and ongoing notification both observe.
 */
sealed interface RecorderState {
    data object Idle : RecorderState

    data class Recording(
        val recordId: String,
        val bytesWritten: Long,
        val secondsElapsed: Long,
    ) : RecorderState

    data class Completed(
        val recordId: String,
        val bytesWritten: Long,
        val secondsElapsed: Long,
    ) : RecorderState

    data class Failed(
        val recordId: String,
        val reason: String,
        /** Bytes successfully written before the failure. May be 0. */
        val partialBytesWritten: Long,
    ) : RecorderState
}

/**
 * Final outcome of a [record] call. Either flavour is "the recorder
 * is done"; the caller uses this to update the DB row's status (success
 * → `completed`, failure → `failed` with reason).
 */
sealed interface RecordResult {
    val recordId: String
    val bytesWritten: Long

    data class Success(
        override val recordId: String,
        override val bytesWritten: Long,
        val secondsElapsed: Long,
    ) : RecordResult

    data class Failure(
        override val recordId: String,
        override val bytesWritten: Long,
        val reason: String,
        val cause: Throwable? = null,
    ) : RecordResult
}

/**
 * Marker output for tests: a [Sink] backed by an in-memory buffer.
 * Production callers pass a sink that wraps Android's MediaStore
 * `OutputStream` (see `RecordingService`).
 *
 * Kept here rather than commonTest because the recorders' `record`
 * signature accepts any `Sink` — the buffer-backed implementation
 * only matters at test time, but exposing it from main keeps the
 * test fakes from re-implementing it in five places.
 */
fun interface RecorderClock {
    fun nowMs(): Long
}
