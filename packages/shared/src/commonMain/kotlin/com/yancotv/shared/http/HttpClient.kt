package com.yancotv.shared.http

data class HttpRequestOptions(
    /** Per-request timeout in milliseconds. */
    val timeoutMs: Long? = null,
    /** Extra request headers. */
    val headers: Map<String, String> = emptyMap(),
    /** Hard cap on response body size in bytes. Implementations should reject when exceeded. */
    val maxResponseBytes: Long? = null,
    /** Whether to follow HTTP 3xx redirects. Defaults to true. */
    val followRedirects: Boolean = true,
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
    suspend fun getJson(url: String, options: HttpRequestOptions = HttpRequestOptions()): Any?

    /** Fetch plain text (UTF-8) from a URL. Used for M3U playlist downloads. */
    suspend fun getText(url: String, options: HttpRequestOptions = HttpRequestOptions()): String
}
