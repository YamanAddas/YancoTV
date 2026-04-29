package com.yancotv.shared.recording

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.http.HttpResponseError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray

/**
 * Tests for [HlsRecorder]. Covers:
 *
 *   - VOD playlist with N segments → all N segments are concatenated
 *     into the sink in order; state ends at Completed.
 *   - 5xx on a segment with retry success → recording completes
 *     normally.
 *   - 5xx on a segment that exhausts retries → segment is logged-and-
 *     skipped; recording still completes for the rest.
 *   - 4xx on the manifest → fast fail with `manifest_<status>` reason.
 *   - Master playlist → recorder follows the first variant URL.
 *   - Heartbeat watchdog → live playlist that stops adding segments
 *     fails after the configured window.
 */
class HlsRecorderTest {
    private val mediaUrl = "https://cdn.example.com/live/stream.m3u8"

    @Test fun vodPlaylistConcatenatesAllSegments() = runTest {
        val http = QueueHttpClient()
        // VOD manifest with 3 segments.
        http.queueText(
            """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-TARGETDURATION:6
                #EXT-X-MEDIA-SEQUENCE:0
                #EXTINF:6.000,
                segment-0.ts
                #EXTINF:6.000,
                segment-1.ts
                #EXTINF:6.000,
                segment-2.ts
                #EXT-X-ENDLIST
            """.trimIndent(),
        )
        // 3 segment fetches.
        http.queueBytes(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        http.queueBytes(byteArrayOf(0xCC.toByte()))
        http.queueBytes(byteArrayOf(0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()))

        val recorder = HlsRecorder(http, fixedClock())
        val sink = Buffer()
        val result =
            recorder.record(
                RecordInput(
                    recordId = "r1",
                    sourceUrl = mediaUrl,
                    title = "Test",
                    format = RecordingFormat.HLS,
                ),
                sink,
            )

        assertIs<RecordResult.Success>(result)
        assertEquals(6L, result.bytesWritten)
        // Sink contains the concatenation of all three segments.
        val written = sink.readByteArray()
        assertEquals(
            listOf(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF),
            written.map { it.toInt() and 0xFF },
        )
        // State ended at Completed.
        val terminal = recorder.state.value
        assertIs<RecorderState.Completed>(terminal)
        assertEquals("r1", terminal.recordId)
    }

    @Test fun retryRecoversFromTransient500OnSegment() = runTest {
        val http = QueueHttpClient()
        // Single-segment VOD manifest.
        http.queueText(
            """
                #EXTM3U
                #EXT-X-TARGETDURATION:5
                #EXTINF:5.000,
                segment-0.ts
                #EXT-X-ENDLIST
            """.trimIndent(),
        )
        // First fetch fails 503, retry succeeds.
        http.queueBytesError(HttpResponseError(503, "Service Unavailable"))
        http.queueBytes(byteArrayOf(0x01, 0x02, 0x03))

        val recorder = HlsRecorder(http, fixedClock(), strategy = RecorderStrategy(initialBackoffMs = 1L))
        val sink = Buffer()
        val result =
            recorder.record(
                RecordInput("r-retry", mediaUrl, "Test", RecordingFormat.HLS),
                sink,
            )

        assertIs<RecordResult.Success>(result)
        assertEquals(3L, result.bytesWritten)
    }

    @Test fun segmentThatExhaustsRetriesIsSkippedNotFatal() = runTest {
        val http = QueueHttpClient()
        http.queueText(
            """
                #EXTM3U
                #EXT-X-TARGETDURATION:5
                #EXTINF:5.000,
                segment-0.ts
                #EXTINF:5.000,
                segment-1.ts
                #EXT-X-ENDLIST
            """.trimIndent(),
        )
        // Segment 0 fails on every attempt (1 + 3 retries = 4 errors).
        repeat(4) { http.queueBytesError(HttpResponseError(503, "down")) }
        // Segment 1 succeeds.
        http.queueBytes(byteArrayOf(0x42))

        val recorder =
            HlsRecorder(
                http,
                fixedClock(),
                strategy = RecorderStrategy(maxRetriesPerRequest = 3, initialBackoffMs = 1L),
            )
        val sink = Buffer()
        val result =
            recorder.record(
                RecordInput("r-skip", mediaUrl, "Test", RecordingFormat.HLS),
                sink,
            )

        // Recording completes (1 segment landed); not failed.
        assertIs<RecordResult.Success>(result)
        assertEquals(1L, result.bytesWritten)
        assertTrue(sink.readByteArray().contentEquals(byteArrayOf(0x42)))
    }

    /**
     * Regression: cancellation during the initial manifest fetch must
     * propagate as `CancellationException`. The pre-fix `catch (t: Throwable)`
     * around `resolveStartingPlaylist` would convert it into a
     * `failManifest("manifest_error: ...")`, racing the service's
     * `markCancelled`. See [HlsRecorder.record] outer catches.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationDuringInitialManifestPropagatesAsCancellationException() = runTest {
        val http = NeverEndingHttpClient()
        val recorder = HlsRecorder(http, fixedClock())
        val sink = Buffer()

        var caught: Throwable? = null
        val job =
            launch {
                try {
                    recorder.record(
                        RecordInput("r-cancel-manifest", mediaUrl, "Test", RecordingFormat.HLS),
                        sink,
                    )
                } catch (t: Throwable) {
                    caught = t
                    throw t
                }
            }
        runCurrent()

        job.cancelAndJoin()

        assertNotNull(caught, "expected the launch body to throw, got nothing")
        assertIs<CancellationException>(
            caught,
            "expected CancellationException; got ${caught!!::class.simpleName}",
        )
        val terminal = recorder.state.value
        assertTrue(
            terminal !is RecorderState.Failed,
            "state must not be Failed after manifest-fetch cancel; got $terminal",
        )
    }

    /**
     * Regression: cancellation during a segment fetch retry loop must
     * propagate. Pre-fix `fetchSegmentWithRetry`'s `catch (t: Throwable)`
     * would catch CancellationException, treat it as a transient error,
     * `delay(backoff)` (which would itself throw CancellationException
     * due to coroutine cancellation), and ultimately re-throw — but
     * defensive in case `delay` semantics change, the explicit
     * `catch (c: CancellationException) { throw c }` arm guarantees it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationDuringSegmentFetchPropagatesAsCancellationException() = runTest {
        val http = QueueHttpClient()
        // Manifest fetches normally...
        http.queueText(
            """
                #EXTM3U
                #EXT-X-TARGETDURATION:5
                #EXTINF:5.000,
                segment-0.ts
                #EXT-X-ENDLIST
            """.trimIndent(),
        )
        // ...but the segment fetch hangs.
        http.queueBytesNeverEnding()

        val recorder = HlsRecorder(http, fixedClock())
        val sink = Buffer()

        var caught: Throwable? = null
        val job =
            launch {
                try {
                    recorder.record(
                        RecordInput("r-cancel-segment", mediaUrl, "Test", RecordingFormat.HLS),
                        sink,
                    )
                } catch (t: Throwable) {
                    caught = t
                    throw t
                }
            }
        runCurrent()

        job.cancelAndJoin()

        assertNotNull(caught, "expected the launch body to throw, got nothing")
        assertIs<CancellationException>(
            caught,
            "expected CancellationException; got ${caught!!::class.simpleName}",
        )
    }

    @Test fun manifest4xxFailsFastWithStatusReason() = runTest {
        val http = QueueHttpClient()
        http.queueTextError(HttpResponseError(403, "Forbidden"))

        val recorder = HlsRecorder(http, fixedClock())
        val sink = Buffer()
        val result =
            recorder.record(
                RecordInput("r-403", mediaUrl, "Test", RecordingFormat.HLS),
                sink,
            )

        assertIs<RecordResult.Failure>(result)
        assertEquals("manifest_403", result.reason)
        assertEquals(0L, result.bytesWritten)
        assertFalse(sink.readByteArray().isNotEmpty(), "no bytes should land on a fast-fail")
    }

    @Test fun masterPlaylistFollowsFirstVariant() = runTest {
        val variantUrl = "https://cdn.example.com/live/720p.m3u8"
        val http = QueueHttpClient()
        // Master playlist with two variants.
        http.queueText(
            """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
                720p.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
                1080p.m3u8
            """.trimIndent(),
        )
        // Then the variant playlist (single segment, VOD).
        http.queueText(
            """
                #EXTM3U
                #EXT-X-TARGETDURATION:5
                #EXTINF:5.000,
                seg-0.ts
                #EXT-X-ENDLIST
            """.trimIndent(),
        )
        http.queueBytes(byteArrayOf(0x99.toByte()))

        val recorder = HlsRecorder(http, fixedClock())
        val sink = Buffer()
        val result =
            recorder.record(
                RecordInput("r-master", mediaUrl, "Test", RecordingFormat.HLS),
                sink,
            )

        assertIs<RecordResult.Success>(result)
        assertEquals(1L, result.bytesWritten)
        // Recorder should have polled the variant URL after the master.
        assertTrue(http.urlsRequested.any { it == variantUrl }, "expected variant URL fetched: ${http.urlsRequested}")
    }

    @Test fun heartbeatTimeoutFiresOnStaleLivePlaylist() = runTest {
        val clock = AdvanceableClock(0L)
        val http = QueueHttpClient()
        // Live playlist (no ENDLIST) with one segment, then the same
        // manifest forever (no new segments).
        val live =
            """
                #EXTM3U
                #EXT-X-TARGETDURATION:1
                #EXT-X-MEDIA-SEQUENCE:0
                #EXTINF:1.000,
                seg-0.ts
            """.trimIndent()
        // Initial manifest, then re-fetches return the same thing.
        // Queue an arbitrary number of refreshes.
        repeat(40) { http.queueText(live) }
        http.queueBytes(byteArrayOf(0x10))

        val recorder =
            HlsRecorder(
                http,
                clock,
                strategy =
                RecorderStrategy(
                    heartbeatTimeoutMs = 5_000L,
                    manifestPollIntervalMs = 500L,
                ),
            )
        // Drive the clock forward as the recorder's `delay` advances
        // virtual time. The runTest scheduler auto-advances on delay,
        // so we just need our injected clock to read that virtual
        // time. Easy: hook into TestScope's currentTime.
        clock.bind(this)

        val sink = Buffer()
        val result =
            recorder.record(
                RecordInput("r-heartbeat", mediaUrl, "Test", RecordingFormat.HLS),
                sink,
            )

        assertIs<RecordResult.Failure>(result)
        assertEquals("heartbeat_timeout", result.reason)
        // First segment did land before the watchdog fired.
        assertEquals(1L, result.bytesWritten)
    }

    // ── helpers ────────────────────────────────────────────────────

    private fun fixedClock(start: Long = 1_700_000_000_000L): RecorderClock = RecorderClock { start }

    /**
     * Clock that reads `TestScope.currentTime`. Used in the heartbeat
     * test where virtual time matters; the helper avoids leaking
     * TestScope into the production type signature.
     */
    private class AdvanceableClock(private val initial: Long) : RecorderClock {
        private var scope: TestScope? = null

        fun bind(scope: TestScope) {
            this.scope = scope
        }

        override fun nowMs(): Long = (scope?.testScheduler?.currentTime ?: 0L) + initial
    }

    /**
     * Minimal queue-driven `HttpClient` fake. Tests `queueText` /
     * `queueBytes` / `queueTextError` / `queueBytesError` in the order
     * they expect requests to fire. Distinguishes text vs bytes
     * channels because the recorder uses `getText` for the manifest
     * and `getBytes` for segments — this catches "fetched the wrong
     * thing" bugs.
     */
    private class QueueHttpClient : HttpClient {
        private val textQueue = ArrayDeque<Any>()
        private val bytesQueue = ArrayDeque<Any>()
        val urlsRequested = mutableListOf<String>()

        fun queueText(value: String) {
            textQueue.addLast(value)
        }

        fun queueTextError(error: Throwable) {
            textQueue.addLast(error)
        }

        fun queueBytes(value: ByteArray) {
            bytesQueue.addLast(value)
        }

        fun queueBytesError(error: Throwable) {
            bytesQueue.addLast(error)
        }

        /** Sentinel marker — when consumed by `getBytes`, it suspends
         *  indefinitely instead of returning. Used by the cancellation
         *  test to simulate a segment fetch that the user cancels mid-
         *  flight. */
        private object NeverEnds

        fun queueBytesNeverEnding() {
            bytesQueue.addLast(NeverEnds)
        }

        override suspend fun getJson(url: String, options: HttpRequestOptions): Any? = throw UnsupportedOperationException("recorder doesn't use getJson")

        override suspend fun getText(url: String, options: HttpRequestOptions): String {
            urlsRequested += url
            val next = if (textQueue.isNotEmpty()) textQueue.removeFirst() else error("no queued text response for $url")
            if (next is Throwable) throw next
            return next as String
        }

        override suspend fun getBytes(url: String, options: HttpRequestOptions): ByteArray {
            urlsRequested += url
            val next = if (bytesQueue.isNotEmpty()) bytesQueue.removeFirst() else error("no queued bytes response for $url")
            if (next === NeverEnds) {
                delay(Long.MAX_VALUE)
                error("unreachable")
            }
            if (next is Throwable) throw next
            return next as ByteArray
        }
    }

    /**
     * HttpClient whose every endpoint suspends indefinitely. Models a
     * server that never sends response headers — exactly the failure
     * mode that motivated the cancellation re-throw in
     * [HlsRecorder.resolveStartingPlaylist].
     */
    private class NeverEndingHttpClient : HttpClient {
        override suspend fun getJson(url: String, options: HttpRequestOptions): Any? = suspendForever()

        override suspend fun getText(url: String, options: HttpRequestOptions): String = suspendForever()

        override suspend fun getBytes(url: String, options: HttpRequestOptions): ByteArray = suspendForever()

        override suspend fun <T> getSource(url: String, options: HttpRequestOptions, block: suspend (Source) -> T): T = suspendForever()

        private suspend fun <T> suspendForever(): T {
            delay(Long.MAX_VALUE)
            error("unreachable")
        }
    }
}
