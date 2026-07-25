package com.yancotv.shared.http

import io.ktor.client.HttpClient as KtorClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.Source
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * Android variant of [KtorHttpClient] that streams large response bodies
 * to a temp file on disk instead of buffering them in heap.
 *
 * Background: [KtorHttpClient.getSource] default uses
 * `ByteReadChannel.readRemaining()` which materialises the whole body into
 * a `kotlinx.io.Buffer`. For small API responses (Xtream auth, category
 * lists, reminders) that's fine. For *catalog* responses — 50–100MB
 * `get_live_streams` / `get_vod_streams` on providers with 250k channels —
 * concurrent buffering across the three parallel fetches puts peak heap
 * at ~150MB, uncomfortably close to the 320MB `largeHeap` ceiling on Fire
 * TV. This class tips that back to ~1MB by streaming through a temp file.
 *
 * How: [streamToFile] drains [ByteReadChannel] into a JVM
 * [FileOutputStream] as bytes arrive through a fixed 64KB buffer, so heap
 * cost is constant regardless of body size. (This was originally Ktor's
 * `copyTo`, which does NOT bound its buffer when the limit is unbounded —
 * see [streamToFile] for the measurement that caught it.) Then the temp
 * file is re-opened as a
 * [kotlinx.io.Source] so [Json.decodeSourceToSequence] can lazy-parse
 * it the same way it used to lazy-parse the in-memory Buffer. Ktor 3.1.3's
 * `toInputStream` / kotlinx-io's `asSource` (both on the JVM target) do
 * the adapter work.
 *
 * Cost: one sequential disk write + disk read of the response body.
 * A 60MB response writes in ~150ms to internal storage and reads in ~50ms
 * — negligible vs the multi-second network download + SQLite insert cost.
 */
class AndroidKtorHttpClient(ktor: KtorClient, userAgentProvider: () -> String, perRequestReadTimeoutMs: () -> Long?, private val cacheDir: File) :
    KtorHttpClient(ktor, userAgentProvider, perRequestReadTimeoutMs) {
    override suspend fun <T> getSource(url: String, options: HttpRequestOptions, block: suspend (Source) -> T): T = withContext(Dispatchers.IO) {
        // Streaming-live path: hand the caller a Source backed directly
        // by the network channel. Required for [MpegTsRecorder] where the
        // body is a multi-hour MPEG-TS that never ends until the caller
        // cancels — the default temp-file path below would call
        // `streamToFile` and loop forever, never reaching `block(source)`,
        // so the recorder's sink would receive zero bytes (regression
        // observed when stopping a recording produced 0-byte files).
        //
        // Deliberately OUTSIDE [spoolSemaphore]: a recording holds its
        // channel open for hours and would starve every other request.
        if (options.streamLive) {
            val channel: ByteReadChannel = performGet(url, options).bodyAsChannel()
            return@withContext channel.toInputStream().use { input ->
                val source: Source = input.asSource().buffered()
                source.use { block(it) }
            }
        }

        val tempFile = File(cacheDir, "ktor-stream-${UUID.randomUUID()}.bin")
        try {
            // MB-230 — serialise REQUEST + DRAIN across all callers.
            //
            // The catalog sync fires get_live_streams / get_vod_streams /
            // get_series concurrently (~155MB of body between them). Ktor's
            // response channel buffers whatever the server has sent but the
            // consumer has not read, and eMMC on a Fire TV Stick is slower
            // than wifi — so bodies nobody is draining pile up in heap.
            //
            // The permit MUST cover `performGet`, not just the copy. An
            // earlier version acquired it around the copy only, and heap was
            // still 114MB/384MB at the moment the SMALLEST catalog finished
            // spooling: the other two requests had already been issued, their
            // servers were streaming, and nothing was reading them. Issuing
            // the request late is the whole point.
            //
            // This does not serialise the sync. Spool happens under the
            // permit, decode happens after it is released, and each catalog
            // runs on its own coroutine — so catalog N downloads while
            // catalog N-1 decodes and writes. Only the memory-heavy drains
            // take turns. Small API responses pass through in milliseconds.
            val bytesWritten =
                spoolSemaphore.withPermit {
                    val response = performGet(url, options)
                    options.maxResponseBytes?.let { cap ->
                        response.headers["Content-Length"]?.toLongOrNull()?.let { declared ->
                            if (declared > cap) {
                                throw HttpResponseError(
                                    status = HttpStatusCode.PayloadTooLarge.value,
                                    statusText = "Payload too large",
                                    message = "Response declared $declared bytes exceeds cap $cap bytes ($url)",
                                )
                            }
                        }
                    }
                    streamToFile(response.bodyAsChannel(), tempFile, options.maxResponseBytes)
                }
            // Extra post-download guard in case the server omitted Content-Length.
            options.maxResponseBytes?.let { cap ->
                if (bytesWritten > cap) {
                    throw HttpResponseError(
                        status = HttpStatusCode.PayloadTooLarge.value,
                        statusText = "Payload too large",
                        message = "Response $bytesWritten bytes exceeds cap $cap bytes ($url)",
                    )
                }
            }
            tempFile.inputStream().use { fis ->
                val source: Source = fis.asSource().buffered()
                source.use { block(it) }
            }
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    /**
     * MB-230 — copy the response body to [dest] through a fixed 64KB buffer.
     *
     * This used to be `channel.copyTo(fos, limit)` on the belief that Ktor's
     * `copyTo` streamed through a small internal buffer. It does not when
     * `limit` is unbounded: it resolves the whole body first, so a catalog
     * response arrived as ONE contiguous array before a single byte reached
     * disk. Measured on the Fire TV Stick (AFTMM, 384MB heap ceiling) against
     * a 279,577-item provider:
     *
     *     I/YancoHeap: spooled 20022KB to temp file; heap 86MB/384MB
     *                  url=get_live_streams
     *     W/art: Throwing OutOfMemoryError "Failed to allocate a 72213488
     *            byte allocation with 4194304 free bytes and 51MB until OOM"
     *
     * 72,213,488 bytes is the `get_vod_streams` body (177,357 items). The
     * temp-file spool was defeated by its own copy step — the class KDoc's
     * "tips that back to ~1MB" was true of the *decode*, never of the
     * download.
     *
     * This also retro-explains why making the three catalog fetches
     * sequential did nothing (peak 318 -> 313MB): the largest single array is
     * one catalog's whole body, which concurrency does not change.
     *
     * `toInputStream()` is the same blocking adapter the `streamLive` path
     * above already uses, and we are on [Dispatchers.IO], so blocking reads
     * are correct here. The byte cap is enforced as we go rather than after
     * the fact, so a misbehaving provider is cut off mid-stream instead of
     * after it has already been materialised.
     */
    private fun streamToFile(channel: ByteReadChannel, dest: File, maxBytes: Long?): Long {
        val limit = maxBytes ?: Long.MAX_VALUE
        var copied = 0L
        FileOutputStream(dest).use { fos ->
            channel.toInputStream().use { input ->
                val buf = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    copied += read
                    if (copied > limit) {
                        throw HttpResponseError(
                            status = HttpStatusCode.PayloadTooLarge.value,
                            statusText = "Payload too large",
                            message = "Response exceeded cap $limit bytes mid-stream",
                        )
                    }
                    fos.write(buf, 0, read)
                }
            }
            fos.fd.sync()
        }
        return copied
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024

        /**
         * Guards the response-body drain. One permit process-wide: this is a
         * memory ceiling, not a rate limit, so it is deliberately shared
         * across every [AndroidKtorHttpClient] instance.
         */
        val spoolSemaphore = Semaphore(1)
    }
}
