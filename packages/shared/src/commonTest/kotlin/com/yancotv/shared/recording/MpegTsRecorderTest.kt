package com.yancotv.shared.recording

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.http.HttpResponseError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.write

/**
 * Tests for [MpegTsRecorder]. Covers:
 *
 *   - Server-closed body → bytes round-trip into the sink, status
 *     ends at Completed.
 *   - 4xx on the connection → fast fail with `stream_<status>` reason.
 *   - maxDurationMs cutoff → a long body is truncated when the
 *     deadline lands; status ends Completed (deadline is a normal
 *     completion path, not a failure).
 *
 * Heartbeat watchdog (`withTimeout` on each read) is exercised by
 * [kotlinx.coroutines.withTimeout]'s own test suite — making a
 * kotlinx.io [Source] suspend without data-or-EOF requires either a
 * true streaming HTTP transport or a custom dispatcher hack; neither
 * adds confidence beyond what `withTimeout` already guarantees.
 * Hands-on / instrumentation tests catch real network hangs.
 */
class MpegTsRecorderTest {
    private val streamUrl = "https://catchup.example.com/stream.ts"

    @Test fun completedBodyRoundTripsBytesIntoSink() = runTest {
        val payload =
            ByteArray(40_000) { i -> (i and 0xFF).toByte() }
        val http = OneShotSourceClient(payload)

        val recorder = MpegTsRecorder(http, fixedClock())
        val sink = Buffer()
        val result =
            recorder.record(
                RecordInput("r1", streamUrl, "Catch-up", RecordingFormat.MPEG_TS),
                sink,
            )

        assertIs<RecordResult.Success>(result)
        assertEquals(payload.size.toLong(), result.bytesWritten)
        val written = sink.readByteArray()
        assertTrue(written.contentEquals(payload), "sink should hold the full server body")
        val terminal = recorder.state.value
        assertIs<RecorderState.Completed>(terminal)
        assertEquals(payload.size.toLong(), terminal.bytesWritten)
    }

    @Test fun upstream4xxFailsWithStreamStatusReason() = runTest {
        val http = ErroringSourceClient(HttpResponseError(403, "Forbidden"))
        val recorder = MpegTsRecorder(http, fixedClock())
        val sink = Buffer()
        val result =
            recorder.record(
                RecordInput("r-403", streamUrl, "Catch-up", RecordingFormat.MPEG_TS),
                sink,
            )

        assertIs<RecordResult.Failure>(result)
        assertEquals("stream_403", result.reason)
        assertEquals(0L, result.bytesWritten)
    }

    /**
     * Regression: when the launching coroutine is cancelled (Android
     * `RecordingService.handleStop` → `job.cancelAndJoin()`), the recorder
     * must let `CancellationException` propagate. The pre-fix outer
     * `catch (t: Throwable)` swallowed cancellation and converted it into
     * `RecordResult.Failure(reason = "stream_error")`, which then race-wrote
     * `markFailed` on top of the service's `markCancelled`/`markCompleted`.
     * See [MpegTsRecorder.record] catch chain.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationDuringGetSourcePropagatesAsCancellationException() = runTest {
        val http = NeverEndingSourceClient()
        val recorder = MpegTsRecorder(http, fixedClock())
        val sink = Buffer()

        var caught: Throwable? = null
        val job =
            launch {
                try {
                    recorder.record(
                        RecordInput("r-cancel", streamUrl, "Catch-up", RecordingFormat.MPEG_TS),
                        sink,
                    )
                } catch (t: Throwable) {
                    caught = t
                    throw t
                }
            }
        // Let the launch dispatch into record() so we're suspended
        // inside the never-ending getSource.
        runCurrent()

        job.cancelAndJoin()

        assertNotNull(caught, "expected the launch body to throw, got nothing")
        assertIs<CancellationException>(
            caught,
            "expected CancellationException; got ${caught!!::class.simpleName}",
        )
        // The recorder must not transition to Failed on cancellation —
        // that's the service's row-state job. Idle (never set anything)
        // or Recording (mid-flight) are both acceptable.
        val terminal = recorder.state.value
        assertTrue(
            terminal !is RecorderState.Failed,
            "state must not be Failed after cancel; got $terminal",
        )
    }

    @Test fun maxDurationCutoffEndsAsCompleted() = runTest {
        // 100 KB body; the recorder's max duration is configured to
        // land in mid-stream. StepClock advances 1 s per nowMs()
        // read so a few read-loop iterations happen before the
        // deadline check trips. With CHUNK_SIZE = 16 KiB we expect
        // 2–3 chunks (~32–48 KiB) before the loop exits.
        val payload = ByteArray(100_000) { 0xC0.toByte() }
        val http = OneShotSourceClient(payload)
        val clock = StepClock(start = 1_000_000L, stepMs = 1_000L)

        val recorder = MpegTsRecorder(http, clock)
        val sink = Buffer()
        val result =
            recorder.record(
                RecordInput(
                    recordId = "r-deadline",
                    sourceUrl = streamUrl,
                    title = "Catch-up",
                    format = RecordingFormat.MPEG_TS,
                    maxDurationMs = 5_000L,
                ),
                sink,
            )

        assertIs<RecordResult.Success>(result)
        // Some bytes landed (at least one chunk), but not all.
        assertTrue(result.bytesWritten > 0L, "deadline should not pre-empt the first read")
        assertTrue(result.bytesWritten < payload.size.toLong(), "deadline should pre-empt completion")
    }

    // ── helpers ────────────────────────────────────────────────────

    private fun fixedClock(start: Long = 1_700_000_000_000L): RecorderClock = RecorderClock { start }

    /** Clock that advances by `stepMs` on each `nowMs()` read. Lets us
     *  cross a deadline deterministically without wiring TestScope. */
    private class StepClock(start: Long, private val stepMs: Long) : RecorderClock {
        private var t = start

        override fun nowMs(): Long {
            val v = t
            t += stepMs
            return v
        }
    }

    /**
     * Single-shot streaming HTTP client: serves [body] as a complete
     * Source, then exhaustion. Tests the normal "server closes after
     * sending everything" path.
     */
    private class OneShotSourceClient(private val body: ByteArray) : HttpClient {
        override suspend fun getJson(url: String, options: HttpRequestOptions): Any? = throw UnsupportedOperationException()

        override suspend fun getText(url: String, options: HttpRequestOptions): String = throw UnsupportedOperationException()

        override suspend fun <T> getSource(url: String, options: HttpRequestOptions, block: suspend (Source) -> T): T {
            val buf = Buffer().apply { write(body) }
            return buf.use { block(it) }
        }
    }

    /**
     * Client that throws on getSource — used to exercise the
     * 4xx / 5xx fast-fail path.
     */
    private class ErroringSourceClient(private val error: Throwable) : HttpClient {
        override suspend fun getJson(url: String, options: HttpRequestOptions): Any? = throw UnsupportedOperationException()

        override suspend fun getText(url: String, options: HttpRequestOptions): String = throw UnsupportedOperationException()

        override suspend fun <T> getSource(url: String, options: HttpRequestOptions, block: suspend (Source) -> T): T = throw error
    }

    /**
     * Client whose getSource suspends indefinitely — used to exercise the
     * cancellation path. Real-world equivalent: a slow IPTV server that
     * never delivers response headers, exactly the case the user reported
     * where stopping a recording produced 0-byte files unless the
     * recorder properly propagated cancellation.
     */
    private class NeverEndingSourceClient : HttpClient {
        override suspend fun getJson(url: String, options: HttpRequestOptions): Any? = throw UnsupportedOperationException()

        override suspend fun getText(url: String, options: HttpRequestOptions): String = throw UnsupportedOperationException()

        override suspend fun <T> getSource(url: String, options: HttpRequestOptions, block: suspend (Source) -> T): T {
            // Suspends forever; only completes when the parent coroutine
            // is cancelled, throwing CancellationException through.
            delay(Long.MAX_VALUE)
            throw IllegalStateException("unreachable")
        }
    }
}
