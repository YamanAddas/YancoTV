package com.yancotv.shared.http

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.writeString

data class HttpRequestOptions(
    /** Per-request timeout in milliseconds. */
    val timeoutMs: Long? = null,
    /** Extra request headers. */
    val headers: Map<String, String> = emptyMap(),
    /** Hard cap on response body size in bytes. Implementations should reject when exceeded. */
    val maxResponseBytes: Long? = null,
    /** Whether to follow HTTP 3xx redirects. Defaults to true. */
    val followRedirects: Boolean = true,
    /**
     * If true, [HttpClient.getSource] hands the caller a Source that reads
     * directly from the network channel as bytes arrive — no whole-body
     * buffering. Required for continuous bodies (MPEG-TS recordings) where
     * the response never ends until cancellation; the default temp-file
     * buffering path would loop forever and never deliver any bytes to
     * the caller's block. Catalog fetches keep the default (false) so the
     * memory-bounded temp-file path stays in effect.
     */
    val streamLive: Boolean = false,
)

/** Thrown for non-2xx HTTP responses. Preserves status so retry logic can match on it. */
class HttpResponseError(
    val status: Int,
    val statusText: String,
    message: String? = null,
) : RuntimeException(message ?: "HTTP $status: $statusText")

/**
 * Minimal HTTP client interface. Platform implementations handle transport
 * (Ktor engines, OkHttp, Darwin, etc.) while core code stays agnostic.
 */
interface HttpClient {
    /** Fetch JSON from a URL. Throws on network error, non-2xx status, or invalid JSON. */
    suspend fun getJson(
        url: String,
        options: HttpRequestOptions = HttpRequestOptions(),
    ): Any?

    /** Fetch plain text (UTF-8) from a URL. Used for M3U playlist downloads. */
    suspend fun getText(
        url: String,
        options: HttpRequestOptions = HttpRequestOptions(),
    ): String

    /**
     * Fetch raw bytes. Needed for binary payloads like `.xml.gz` EPG dumps
     * where going through UTF-8 decoding would corrupt the stream.
     *
     * Default falls back to UTF-8 encoding of [getText] so test fakes that
     * never serve binary content don't need to implement it.
     */
    suspend fun getBytes(
        url: String,
        options: HttpRequestOptions = HttpRequestOptions(),
    ): ByteArray = getText(url, options).encodeToByteArray()

    /**
     * Stream the response body as a [Source]. The [block] is called with a
     * lazy source that reads incrementally from the network; memory stays
     * bounded regardless of payload size. Used for the Xtream catalog fetches
     * where a single response can exceed 100MB and materializing it to a
     * String would OOM Fire TV.
     *
     * Default implementation buffers via [getText] — correct but not memory-
     * efficient. Production transports ([KtorHttpClient]) must override with a
     * real stream-through-the-channel path. Test fakes can ignore.
     */
    suspend fun <T> getSource(
        url: String,
        options: HttpRequestOptions = HttpRequestOptions(),
        block: suspend (Source) -> T,
    ): T {
        val text = getText(url, options)
        val buffer = Buffer().apply { writeString(text) }
        return buffer.use { block(it) }
    }
}
