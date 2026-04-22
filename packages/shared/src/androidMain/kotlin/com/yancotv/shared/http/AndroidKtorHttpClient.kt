package com.yancotv.shared.http

import io.ktor.client.HttpClient as KtorClient
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Source
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

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
 * How: [Ktor's copyTo][copyTo] drains [ByteReadChannel] into a JVM
 * [FileOutputStream] as bytes arrive — bounded 4KB buffers, no
 * accumulation. Then the temp file is re-opened as a
 * [kotlinx.io.Source] so [Json.decodeSourceToSequence] can lazy-parse
 * it the same way it used to lazy-parse the in-memory Buffer. Ktor 3.1.3's
 * `toInputStream` / kotlinx-io's `asSource` (both on the JVM target) do
 * the adapter work.
 *
 * Cost: one sequential disk write + disk read of the response body.
 * A 60MB response writes in ~150ms to internal storage and reads in ~50ms
 * — negligible vs the multi-second network download + SQLite insert cost.
 */
class AndroidKtorHttpClient(
    ktor: KtorClient,
    defaultUserAgent: String,
    private val cacheDir: File,
) : KtorHttpClient(ktor, defaultUserAgent) {

    override suspend fun <T> getSource(
        url: String,
        options: HttpRequestOptions,
        block: suspend (Source) -> T,
    ): T = withContext(Dispatchers.IO) {
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

        val tempFile = File(cacheDir, "ktor-stream-${UUID.randomUUID()}.bin")
        try {
            val bytesWritten = streamToFile(response.bodyAsChannel(), tempFile, options.maxResponseBytes)
            // Extra post-download guard in case the server omitted Content-Length.
            options.maxResponseBytes?.let { cap ->
                if (bytesWritten > cap) {
                    throw HttpResponseError(
                        status = HttpStatusCode.PayloadTooLarge.value,
                        statusText = "Payload too large",
                        message = "Response ${bytesWritten} bytes exceeds cap $cap bytes ($url)",
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

    private suspend fun streamToFile(
        channel: ByteReadChannel,
        dest: File,
        maxBytes: Long?,
    ): Long {
        FileOutputStream(dest).use { fos ->
            // Ktor's copyTo streams the channel into an OutputStream with a
            // bounded internal buffer (default 4KB). We cap it here so a
            // misbehaving provider can't push gigabytes before we notice.
            val limit = maxBytes ?: Long.MAX_VALUE
            val copied = channel.copyTo(fos, limit)
            fos.fd.sync()
            return copied
        }
    }
}
