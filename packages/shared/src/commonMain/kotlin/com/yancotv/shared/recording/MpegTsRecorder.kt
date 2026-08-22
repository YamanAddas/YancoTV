package com.yancotv.shared.recording

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.http.HttpResponseError
import com.yancotv.shared.http.redactErrorMessage
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readByteArray

/**
 * MPEG-TS recorder for HTTP-served continuous TS bodies. The
 * dominant Xtream catch-up format — a single GET on the catch-up URL
 * returns a long-lived response body that streams TS packets until
 * the server closes the connection (programme end) or we hit
 * [RecordInput.maxDurationMs].
 *
 * Distinct from [HlsRecorder]:
 *   - No manifest, no segments, no per-fetch retry. There's exactly
 *     one HTTP request; if it 4xx's, the recording fails fast.
 *   - Streaming via [HttpClient.getSource] — the response body never
 *     materialises in memory; bytes flow directly from network to
 *     sink in [CHUNK_SIZE]-aligned chunks.
 *   - Heartbeat watchdog wraps each read in [withTimeout]; a hung
 *     server returns no bytes for [RecorderStrategy.heartbeatTimeoutMs]
 *     and we fail with `reason = "heartbeat_timeout"`.
 *
 * Concurrency: same contract as [HlsRecorder] — one instance per
 * recording, suspending function called from `Dispatchers.IO`,
 * caller cancellation flushes the sink and propagates. Caller owns
 * sink lifecycle.
 */
class MpegTsRecorder(
    private val http: HttpClient,
    private val clock: RecorderClock,
    private val strategy: RecorderStrategy = RecorderStrategy(),
    private val logger: Logger = NOOP_LOGGER,
) {
    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    suspend fun record(input: RecordInput, sink: Sink): RecordResult {
        val startedAt = clock.nowMs()
        val deadlineMs = input.maxDurationMs?.let { startedAt + it }
        var bytesWritten = 0L
        var nextProgressLogMs = startedAt + PROGRESS_LOG_INTERVAL_MS

        // MB-355 observability. Before this, the recorder was mute: it
        // took a `logger` annotated `@Suppress("unused")` and never
        // called it, so a recording that produced a 0-byte file emitted
        // no evidence of WHERE it stalled -- connect, first byte, or
        // mid-stream. Three field incidents (2026-05-15, 2026-08-22 x2)
        // were all diagnosed by guesswork for want of these lines.
        logger.info("mpegts[${input.recordId}]: opening stream")
        return try {
            http.getSource(input.sourceUrl, options(input.userAgent, input.referer)) { source ->
                // Reached only once response headers are in. A stall
                // BEFORE this line is connect/response; AFTER it is the
                // body. That distinction is the whole point of the log.
                logger.info(
                    "mpegts[${input.recordId}]: response open after " +
                        "${clock.nowMs() - startedAt}ms, reading body",
                )
                val staging = Buffer()
                while (true) {
                    if (deadlineMs != null && clock.nowMs() >= deadlineMs) {
                        return@getSource finishCompleted(input.recordId, startedAt, bytesWritten, "deadline")
                    }
                    val read: Long =
                        try {
                            withTimeout(strategy.heartbeatTimeoutMs) {
                                source.readAtMostTo(staging, CHUNK_SIZE.toLong())
                            }
                        } catch (e: TimeoutCancellationException) {
                            // withTimeout fires TimeoutCancellationException on
                            // expiry. Catching this specific subclass is
                            // permitted (Kotlin's structured-concurrency rule
                            // bans catching the general CancellationException —
                            // caller-initiated cancels still propagate above).
                            return@getSource failWithReason(
                                input.recordId,
                                "heartbeat_timeout",
                                cause = e,
                                bytesWritten = bytesWritten,
                            )
                        }
                    if (read == -1L) {
                        // Server closed the connection cleanly — the
                        // programme ended (catch-up) or the live stream
                        // was reaped server-side. Either way: completed.
                        return@getSource finishCompleted(input.recordId, startedAt, bytesWritten, "eof")
                    }
                    if (read > 0L) {
                        val bytes = staging.readByteArray()
                        sink.write(bytes)
                        sink.flush()
                        val wasEmpty = bytesWritten == 0L
                        bytesWritten += bytes.size
                        if (wasEmpty) {
                            logger.info(
                                "mpegts[${input.recordId}]: first bytes after " +
                                    "${clock.nowMs() - startedAt}ms",
                            )
                        }
                        val nowMs = clock.nowMs()
                        if (nowMs >= nextProgressLogMs) {
                            nextProgressLogMs = nowMs + PROGRESS_LOG_INTERVAL_MS
                            logger.info(
                                "mpegts[${input.recordId}]: ${bytesWritten / 1024}KB in " +
                                    "${(nowMs - startedAt) / 1000}s",
                            )
                        }
                        _state.value =
                            RecorderState.Recording(
                                recordId = input.recordId,
                                bytesWritten = bytesWritten,
                                secondsElapsed = (clock.nowMs() - startedAt) / 1000L,
                            )
                    }
                }
                // Unreachable; while(true) returns from each branch.
                @Suppress("UNREACHABLE_CODE")
                finishCompleted(input.recordId, startedAt, bytesWritten, "unreachable")
            }
        } catch (e: HttpResponseError) {
            // 4xx / 5xx on the connection itself — distinct from a
            // mid-stream EOF (handled inline). MPEG-TS doesn't retry
            // because there's no "request the next segment" — the body
            // either streams or it doesn't.
            failWithReason(input.recordId, "stream_${e.status}", e, bytesWritten)
        } catch (c: CancellationException) {
            // Caller cancelled the launching Job (Android `RecordingService.handleStop`
            // → `job.cancel()`). Re-throw so the calling coroutine ends with
            // CancellationException and the service's `markCancelled` is the
            // only writer to the row. Catching this as a generic Throwable
            // would convert a manual stop into a `RecordResult.Failure` and
            // race-write FAILED on top of CANCELLED.
            throw c
        } catch (t: Throwable) {
            // redactErrorMessage strips ?username=…&password=… that
            // Ktor / OkHttp echoes back in exception messages for failed
            // requests against Xtream `/live/<user>/<pass>/<id>.ts` URLs.
            // `reason` is persisted to the recordings row + StateFlow +
            // any Sentry breadcrumb, so the raw form would leak provider
            // creds three ways. Audit catch.
            failWithReason(input.recordId, "stream_error: ${redactErrorMessage(t)}", t, bytesWritten)
        }
    }

    private fun options(userAgent: String?, referer: String?): HttpRequestOptions {
        val headers = buildMap {
            if (!userAgent.isNullOrBlank()) put("User-Agent", userAgent)
            if (!referer.isNullOrBlank()) put("Referer", referer)
        }
        // Long-lived response — no client-side maxResponseBytes cap;
        // the recorder bounds via deadlineMs / heartbeat instead.
        //
        // **Critical** (Stage 3.1 / MK.14.2 bug fix): the request-level
        // timeout MUST be disabled here. AppPreferences-derived defaults
        // give every request a 90s ceiling, which means a continuous
        // MPEG-TS body (typical Xtream catch-up: a several-hour single
        // GET) gets killed at 90s in. The heartbeat watchdog inside
        // record() bounds idle time per chunk; that's the right shape
        // of bound for streaming, not a hard request timeout. Long.MAX_VALUE
        // is Ktor's documented "no timeout" sentinel.
        //
        // **Critical** (follow-up bug fix): `streamLive = true`. The default
        // [HttpClient.getSource] on Android buffers the entire response body
        // to a temp file before invoking `block` — fine for catalog fetches,
        // catastrophic for a continuous MPEG-TS stream because the body
        // never ends. Without this flag, the recorder's `sink.write` loop
        // never runs and stop produces a 0-byte file regardless of how long
        // it ran. With it, bytes flow channel → caller sink as they arrive.
        //
        // **Critical** (MK.REC.RESILIENCE 2026-05-15): `socketTimeoutMs`
        // bounds the time the OkHttp socket reader will wait BETWEEN reads
        // before throwing SocketTimeoutException. The engine default of 90s
        // produced the 17-minute zero-byte ghost recording observed on
        // 2026-05-15: provider accepted the TCP handshake, the recorder
        // entered its `readAtMostTo` loop, but no bytes ever arrived from
        // the server. The recorder's own `withTimeout(heartbeatTimeoutMs)`
        // CANNOT cancel the read because `channel.toInputStream()` (used by
        // AndroidKtorHttpClient.getSource when streamLive=true) bridges to
        // a blocking `InputStream.read()` via `runBlocking` — that
        // `runBlocking` is its own coroutine scope and doesn't honour the
        // outer cancellation. The OkHttp socket timeout kills the stuck
        // read at the socket layer, throws up to the recorder's catch, and
        // transitions the row to FAILED with a clear reason. 20s is well
        // above any reasonable cold-server first-byte latency and well
        // below the 90s "user thinks the app is frozen" threshold.
        return HttpRequestOptions(
            timeoutMs = Long.MAX_VALUE,
            socketTimeoutMs = 20_000L,
            headers = headers,
            streamLive = true,
        )
    }

    private fun finishCompleted(recordId: String, startedAtMs: Long, bytesWritten: Long, why: String): RecordResult {
        val secs = (clock.nowMs() - startedAtMs) / 1000L
        if (bytesWritten == 0L) {
            // MB-355. Arriving here with nothing written means the server
            // accepted the request and then ended the body -- or the
            // duration cap expired -- without ever serving a byte. That is
            // a FAILED recording, not a completed one. Reporting Success
            // put a `Play` button on an empty file and recorded "Saved
            // 0 KB", which is precisely what MB-355 looked like from the
            // outside; tapping Play then hit ExoPlayer's 3003
            // unrecognized-input error. `no_response_from_server` is the
            // vocabulary RecordingService.handleStop already uses for this
            // exact condition on its own path, so the two agree.
            return failWithReason(recordId, "no_response_from_server", cause = null, bytesWritten = 0L)
        }
        logger.info("mpegts[$recordId]: completed via $why — ${bytesWritten / 1024}KB in ${secs}s")
        _state.value =
            RecorderState.Completed(
                recordId = recordId,
                bytesWritten = bytesWritten,
                secondsElapsed = secs,
            )
        return RecordResult.Success(recordId, bytesWritten, secs)
    }

    private fun failWithReason(recordId: String, reason: String, cause: Throwable?, bytesWritten: Long): RecordResult {
        logger.warn("mpegts[$recordId]: FAILED reason=$reason after ${bytesWritten / 1024}KB")
        _state.value =
            RecorderState.Failed(
                recordId = recordId,
                reason = reason,
                partialBytesWritten = bytesWritten,
            )
        return RecordResult.Failure(recordId, bytesWritten, reason, cause)
    }

    private companion object {
        // 16 KiB = 87 × 188-byte TS packets, give or take. Big enough
        // that we don't thrash sink.flush(); small enough that the
        // heartbeat watchdog fires within the configured window even
        // on slow streams. Not 188-aligned by construction — the
        // sink is just bytes; alignment is the recipient's problem
        // (ExoPlayer handles unaligned TS fine).
        const val CHUNK_SIZE = 16 * 1024

        // Progress cadence. 30s is frequent enough to prove a recording
        // is alive across a multi-hour catch-up GET without turning
        // logcat into a firehose (a 3h recording emits ~360 lines).
        const val PROGRESS_LOG_INTERVAL_MS = 30_000L
    }
}
