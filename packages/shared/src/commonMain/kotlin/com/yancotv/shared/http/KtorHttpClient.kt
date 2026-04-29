package com.yancotv.shared.http

import io.ktor.client.HttpClient as KtorClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Ktor-backed [HttpClient]. Engine is injected (OkHttp on Android, Darwin on iOS)
 * so this class stays in commonMain while transport is platform-specific.
 *
 * Non-2xx responses throw [HttpResponseError] so upstream retry logic can match
 * on the status code — same semantics as the desktop `HttpClient` wrapper.
 */
open class KtorHttpClient(
    protected val ktor: KtorClient,
    protected val userAgentProvider: () -> String = { "YancoTV/0.1.0" },
    /** When non-null, overrides the engine-level request timeout for calls that
     * don't pass their own [HttpRequestOptions.timeoutMs]. Settings → Network
     * feeds this from [AppPreferences.networkFlow]. */
    protected val perRequestReadTimeoutMs: () -> Long? = { null },
) : HttpClient {
    /** Backward-compat ctor used by tests + iOS factory. */
    constructor(ktor: KtorClient, defaultUserAgent: String) : this(ktor, { defaultUserAgent })

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

    override suspend fun getJson(url: String, options: HttpRequestOptions): Any? {
        val body = fetchText(url, options)
        // Decode to JsonElement then flatten into Map/List/primitives so
        // XtreamClient/StalkerClient (which expect plain Any trees) can traverse
        // the result without importing kotlinx.serialization types.
        return json.parseToJsonElement(body).toPlainAny()
    }

    override suspend fun getText(url: String, options: HttpRequestOptions): String = fetchText(url, options)

    override suspend fun getBytes(url: String, options: HttpRequestOptions): ByteArray {
        val response = performGet(url, options)
        val bytes: ByteArray = response.body()
        options.maxResponseBytes?.let { cap ->
            if (bytes.size.toLong() > cap) {
                throw HttpResponseError(
                    status = HttpStatusCode.PayloadTooLarge.value,
                    statusText = "Payload too large",
                    message = "Response ${bytes.size} bytes exceeds cap $cap bytes ($url)",
                )
            }
        }
        return bytes
    }

    /**
     * Stream the response body directly from Ktor's [ByteReadChannel] as a
     * [kotlinx.io.Source]. Never buffers the whole response. This is the memory-
     * safe path used by [com.yancotv.shared.xtream.XtreamClient] for catalog
     * fetches — a 100MB+ VOD list stays off-heap except for whatever the
     * consumer decodes + retains.
     */
    override suspend fun <T> getSource(url: String, options: HttpRequestOptions, block: suspend (Source) -> T): T = withContext(Dispatchers.Default) {
        val response = performGet(url, options)
        val channel: ByteReadChannel = response.bodyAsChannel()
        // Default implementation buffers via readRemaining() — correct but
        // not memory-efficient on catalogs >~30MB. Production transports
        // (AndroidKtorHttpClient) override to stream via a temp file so peak
        // memory stays bounded regardless of payload size. iOS + test fakes
        // can keep the eager fallback.
        val source: Source = channel.readRemaining()
        source.use { block(it) }
    }

    protected suspend fun performGet(url: String, options: HttpRequestOptions): HttpResponse {
        val response: HttpResponse =
            ktor.get(url) {
                header("User-Agent", options.headers["User-Agent"] ?: userAgentProvider())
                for ((k, v) in options.headers) {
                    if (!k.equals("User-Agent", ignoreCase = true)) header(k, v)
                }
                // Honor per-request timeout. The engine-level default (90s in
                // HttpClientFactory.android.kt) is too long for a fast Xtream auth
                // probe — without this the user sees "fetching…" for 90s × retries
                // before getting feedback. MK.6 sync debug: caller passes 30s for
                // auth, 60s for catalog fetches.
                val explicit = options.timeoutMs
                if (explicit != null) {
                    timeout { requestTimeoutMillis = explicit }
                } else {
                    perRequestReadTimeoutMs()?.let { ms ->
                        timeout { requestTimeoutMillis = ms }
                    }
                }
            }
        if (!response.status.isSuccess()) {
            // Redact provider credentials before this string ends up in
            // Android logcat / `sources.last_sync_error` / the on-screen
            // failure dialog. Xtream URLs carry username/password in the
            // query string; shipping them in an error message is a real
            // PII leak. See [redactCredentials].
            throw HttpResponseError(
                status = response.status.value,
                statusText = response.status.description,
                message = "HTTP ${response.status.value} from ${redactCredentials(url)}",
            )
        }
        return response
    }

    private suspend fun fetchText(url: String, options: HttpRequestOptions): String = withContext(Dispatchers.Default) {
        // Force this off the caller's dispatcher — Ktor dispatches network I/O
        // internally, but `bodyAsText()` + UTF-8 decoding of a 20MB Xtream
        // response on Main thread will ANR the app. Dispatchers.Default is
        // KMP-safe (unlike Dispatchers.IO which is JVM-only).
        val response = performGet(url, options)
        val text = response.bodyAsText()
        options.maxResponseBytes?.let { cap ->
            // bodyAsText has already buffered; this is a post-hoc sanity check.
            // Using char count (not encodeToByteArray) saves a full ByteArray
            // clone of the payload — critical on memory-tight TV devices where
            // a single 30MB Xtream response would otherwise burn an extra 30MB
            // just to run this check. Xtream/M3U are ASCII-dominant so chars
            // ≈ bytes; allow up to 2× to cover occasional non-ASCII without a
            // second pass.
            if (text.length.toLong() > cap) {
                throw HttpResponseError(
                    status = HttpStatusCode.PayloadTooLarge.value,
                    statusText = "Payload too large",
                    message = "Response ~${text.length} chars exceeds cap $cap bytes ($url)",
                )
            }
        }
        text
    }
}

/**
 * Used internally by [KtorHttpClient.getJson] so callers can keep receiving
 * structured JSON as `Any?` (Map/List/primitive trees) the way [XtreamClient]
 * / [StalkerClient] already expect.
 */
internal fun JsonElement.toPlainAny(): Any? = when (this) {
    is kotlinx.serialization.json.JsonNull -> null
    is kotlinx.serialization.json.JsonPrimitive ->
        if (isString) {
            content
        } else {
            content.toBooleanStrictOrNull() ?: content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
        }
    is kotlinx.serialization.json.JsonArray -> map { it.toPlainAny() }
    is kotlinx.serialization.json.JsonObject -> entries.associate { it.key to it.value.toPlainAny() }
}
