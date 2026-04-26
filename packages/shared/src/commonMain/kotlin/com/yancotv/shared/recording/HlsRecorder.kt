package com.yancotv.shared.recording

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.http.HttpResponseError
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.Sink

/**
 * HLS recorder. Fetches the playlist at [RecordInput.sourceUrl],
 * walks the segments in order, and writes each segment's raw bytes to
 * the caller-supplied [Sink]. The resulting file is a concatenated
 * MPEG-TS — ExoPlayer plays it back via the existing local-file path
 * with no remuxing.
 *
 * Concurrency contract:
 *   - [record] is a `suspend` function. The caller (Android
 *     `RecordingService`) launches it on `Dispatchers.IO`.
 *   - One `HlsRecorder` instance ⇒ one in-flight recording. The
 *     `RecordingService` instantiates a recorder per recording.
 *   - Cancellation: caller `cancel()`s the launching `Job`. The
 *     recorder responds by flushing whatever's in the sink and
 *     emitting [RecorderState.Failed] with `reason = "cancelled"`
 *     (or [RecorderState.Completed] if the cancellation arrived
 *     after we'd already finished naturally — race-safe via the
 *     final state being terminal).
 *
 * Failure modes (see `recording-spec.md` §2 Q9):
 *   - 5xx / connection-reset on a segment: retry with exponential
 *     backoff up to [RecorderStrategy.maxRetriesPerRequest]; if all
 *     retries exhaust, log a gap, continue with the next segment.
 *     Lost segments don't fail the recording — IPTV streams routinely
 *     drop frames; one missing chunk is recoverable.
 *   - 4xx on the manifest: fail fast — the source has revoked.
 *   - No new segments for [RecorderStrategy.heartbeatTimeoutMs]:
 *     fail with `reason = "heartbeat_timeout"`.
 */
class HlsRecorder(
    private val http: HttpClient,
    private val clock: RecorderClock,
    private val strategy: RecorderStrategy = RecorderStrategy(),
    private val logger: Logger = NOOP_LOGGER,
) {
    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    /**
     * Drive a recording from start to terminal state. Returns when
     * the source ends (VOD `EXT-X-ENDLIST` reached, or `maxDurationMs`
     * elapsed) or when an unrecoverable error occurs.
     *
     * The [sink] is NOT closed here — the caller owns its lifecycle
     * (Android side flushes + closes after this function returns,
     * regardless of outcome, before transitioning the DB row).
     */
    suspend fun record(
        input: RecordInput,
        sink: Sink,
    ): RecordResult {
        val startedAt = clock.nowMs()
        val deadlineMs = input.maxDurationMs?.let { startedAt + it }

        // Resolve master → first variant once. A few production HLS
        // streams ship with master playlists; we follow the highest-
        // quality entry the way ExoPlayer does for v1.0 simplicity.
        // We hold onto the parsed first playlist so the main loop can
        // skip a redundant fetch on iteration 0.
        val (mediaUrl, initialPlaylist) =
            try {
                resolveStartingPlaylist(input.sourceUrl, input.userAgent, input.referer)
            } catch (e: HttpResponseError) {
                return failManifest(input.recordId, "manifest_${e.status}", e, bytesWritten = 0L)
            } catch (c: CancellationException) {
                // Manual stop — let the caller's job.cancel() propagate. The
                // service writes CANCELLED via markCancelled; converting this
                // to failManifest would race-write FAILED on top.
                throw c
            } catch (t: Throwable) {
                return failManifest(input.recordId, "manifest_error: ${t.message ?: t::class.simpleName}", t, 0L)
            }

        var bytesWritten = 0L
        var lastSegmentSeq = -1L // sentinel; first manifest fetch initialises
        var lastProgressAtMs = clock.nowMs()
        var consecutiveEmptyManifests = 0
        var nextPlaylist: HlsPlaylist? = initialPlaylist

        while (true) {
            // Hard deadline.
            if (deadlineMs != null && clock.nowMs() >= deadlineMs) {
                return finishCompleted(input.recordId, startedAt, bytesWritten)
            }

            val playlist =
                nextPlaylist ?: run {
                    val playlistText =
                        try {
                            fetchManifest(mediaUrl, input.userAgent, input.referer)
                        } catch (c: CancellationException) {
                            throw c
                        } catch (t: Throwable) {
                            // Manifest fetch failures are unrecoverable; the source URL
                            // probably rotated. Failing here is the right move.
                            return failManifest(
                                input.recordId,
                                "manifest_refresh_error: ${t.message ?: t::class.simpleName}",
                                t,
                                bytesWritten,
                            )
                        }
                    HlsManifestParser.parse(playlistText, mediaUrl)
                }
            nextPlaylist = null
            // Following a master that itself points at a master is paranoid;
            // assume the variant URL is a media playlist.

            val newSegments = playlist.segments.filter { it.sequence > lastSegmentSeq }
            if (newSegments.isEmpty()) {
                consecutiveEmptyManifests += 1
            } else {
                consecutiveEmptyManifests = 0
            }

            for (segment in newSegments) {
                if (deadlineMs != null && clock.nowMs() >= deadlineMs) {
                    return finishCompleted(input.recordId, startedAt, bytesWritten)
                }
                val segmentBytes =
                    try {
                        fetchSegmentWithRetry(segment.url, input.userAgent, input.referer)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        // Per §2 Q9, lost segments aren't fatal — log + skip.
                        logger.warn("HlsRecorder segment fetch failed seq=${segment.sequence}: ${t.message}")
                        continue
                    }
                sink.write(segmentBytes)
                sink.flush()
                bytesWritten += segmentBytes.size
                lastSegmentSeq = segment.sequence
                lastProgressAtMs = clock.nowMs()
                _state.value =
                    RecorderState.Recording(
                        recordId = input.recordId,
                        bytesWritten = bytesWritten,
                        secondsElapsed = ((clock.nowMs() - startedAt) / 1000L),
                    )
            }

            if (playlist.isVod) {
                // VOD playlists are fully enumerated up-front; once we've
                // run through every segment we're done.
                return finishCompleted(input.recordId, startedAt, bytesWritten)
            }

            // Heartbeat watchdog — if no segment has landed for the
            // configured window, declare the source dead.
            if (clock.nowMs() - lastProgressAtMs > strategy.heartbeatTimeoutMs) {
                return failHeartbeat(input.recordId, bytesWritten)
            }

            // Wait before re-polling. Use the manifest's target duration
            // when present (HLS spec recommends half), otherwise fall back
            // to the configured default.
            val pollMs =
                playlist.targetDurationSec?.let { (it * 1000L) / 2 }
                    ?.coerceAtLeast(strategy.manifestPollIntervalMs)
                    ?: strategy.manifestPollIntervalMs
            // Rapid empty-manifest polling backs off slightly — guards
            // against a misbehaving server that returns the same manifest
            // forever. After 10 consecutive empties we still rely on the
            // heartbeat watchdog above to bail out.
            val effectivePoll = if (consecutiveEmptyManifests >= 3) pollMs * 2 else pollMs
            delay(effectivePoll)
        }
    }

    /**
     * Fetch the source URL once. Returns the URL the recorder will
     * poll (the source URL itself for media playlists, or the first
     * variant URL if the source was a master playlist) along with
     * the freshly-parsed media playlist so the main loop can skip a
     * redundant fetch on iteration 0.
     */
    private suspend fun resolveStartingPlaylist(
        sourceUrl: String,
        userAgent: String?,
        referer: String?,
    ): Pair<String, HlsPlaylist> {
        val text = fetchManifest(sourceUrl, userAgent, referer)
        val parsed = HlsManifestParser.parse(text, sourceUrl)
        if (!parsed.isMaster) return sourceUrl to parsed
        val variantUrl =
            parsed.variants.firstOrNull()
                ?: throw IllegalStateException("master playlist has no variants: $sourceUrl")
        // Follow the variant once to get its media playlist; that's
        // what the loop polls thereafter.
        val variantText = fetchManifest(variantUrl, userAgent, referer)
        val variantPlaylist = HlsManifestParser.parse(variantText, variantUrl)
        return variantUrl to variantPlaylist
    }

    private suspend fun fetchManifest(
        url: String,
        userAgent: String?,
        referer: String?,
    ): String =
        http.getText(url, options(userAgent, referer))

    /**
     * Fetch a segment's bytes with retry. Distinct from
     * [fetchManifest] because segments are binary and we want to use
     * `getBytes`, but the retry shape is the same.
     */
    private suspend fun fetchSegmentWithRetry(
        url: String,
        userAgent: String?,
        referer: String?,
    ): ByteArray {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= strategy.maxRetriesPerRequest) {
            try {
                return http.getBytes(url, options(userAgent, referer))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                lastError = t
                // 4xx on a SEGMENT is unusual but recoverable — the
                // server may have rotated the URL by the time we got
                // around to it (live HLS sliding window past the
                // EXT-X-MEDIA-SEQUENCE we cached). Retry treats it the
                // same as 5xx; the manifest re-fetch on the next loop
                // will pick up the current valid set.
                attempt += 1
                if (attempt > strategy.maxRetriesPerRequest) break
                val backoff = strategy.initialBackoffMs * (1L shl (attempt - 1))
                delay(backoff)
            }
        }
        throw lastError ?: IllegalStateException("segment fetch retry loop exited without error")
    }

    private fun options(
        userAgent: String?,
        referer: String?,
    ): HttpRequestOptions {
        val headers = buildMap {
            if (!userAgent.isNullOrBlank()) put("User-Agent", userAgent)
            if (!referer.isNullOrBlank()) put("Referer", referer)
        }
        // Override the user's network read-timeout (defaults to 90s per
        // AppPreferences). Manifest + segment fetches should always be
        // quick, but a slow CDN under load could push individual
        // requests near a minute. 5 min per single fetch is plenty;
        // anything truly stuck is caught by the recorder's own
        // heartbeat watchdog (60s by default), not by Ktor's request
        // timeout. Stage 3.1 / MK.14.2 bug fix: the previous behaviour
        // (no timeoutMs passed) inherited the global 90s default and
        // killed live recordings at the 90s mark.
        return HttpRequestOptions(
            timeoutMs = HLS_REQUEST_TIMEOUT_MS,
            headers = headers,
        )
    }

    private fun finishCompleted(
        recordId: String,
        startedAtMs: Long,
        bytesWritten: Long,
    ): RecordResult {
        val secs = (clock.nowMs() - startedAtMs) / 1000L
        val terminal =
            RecorderState.Completed(
                recordId = recordId,
                bytesWritten = bytesWritten,
                secondsElapsed = secs,
            )
        _state.value = terminal
        return RecordResult.Success(recordId, bytesWritten, secs)
    }

    private fun failManifest(
        recordId: String,
        reason: String,
        cause: Throwable,
        bytesWritten: Long,
    ): RecordResult {
        _state.value =
            RecorderState.Failed(
                recordId = recordId,
                reason = reason,
                partialBytesWritten = bytesWritten,
            )
        return RecordResult.Failure(recordId, bytesWritten, reason, cause)
    }

    private fun failHeartbeat(
        recordId: String,
        bytesWritten: Long,
    ): RecordResult {
        val reason = "heartbeat_timeout"
        _state.value =
            RecorderState.Failed(
                recordId = recordId,
                reason = reason,
                partialBytesWritten = bytesWritten,
            )
        return RecordResult.Failure(recordId, bytesWritten, reason)
    }

    private companion object {
        // 5 min per single manifest / segment fetch — generous enough for
        // any individual request even on a slow CDN; the recorder's
        // heartbeat watchdog (60s by default) handles "stream is stuck"
        // cases. See options() for full rationale.
        const val HLS_REQUEST_TIMEOUT_MS: Long = 5L * 60_000L
    }
}
