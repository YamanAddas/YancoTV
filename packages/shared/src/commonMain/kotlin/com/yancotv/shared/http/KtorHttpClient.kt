package com.yancotv.shared.http

import io.ktor.client.HttpClient as KtorClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Ktor-backed [HttpClient]. Engine is injected (OkHttp on Android, Darwin on iOS)
 * so this class stays in commonMain while transport is platform-specific.
 *
 * Non-2xx responses throw [HttpResponseError] so upstream retry logic can match
 * on the status code — same semantics as the desktop `HttpClient` wrapper.
 */
class KtorHttpClient(
    private val ktor: KtorClient,
    private val defaultUserAgent: String = "YancoTV/0.1.0",
) : HttpClient {

    private val json = Json {
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

    override suspend fun getText(url: String, options: HttpRequestOptions): String =
        fetchText(url, options)

    private suspend fun fetchText(url: String, options: HttpRequestOptions): String {
        // Per-request timeouts would require installing the HttpTimeout plugin
        // on the Ktor client. For now, the engine-level timeout configured in
        // HttpClientFactory.{android,ios}.kt (90s) is the effective bound.
        val response: HttpResponse = ktor.get(url) {
            header("User-Agent", options.headers["User-Agent"] ?: defaultUserAgent)
            for ((k, v) in options.headers) {
                if (!k.equals("User-Agent", ignoreCase = true)) header(k, v)
            }
        }
        if (!response.status.isSuccess()) {
            throw HttpResponseError(
                status = response.status.value,
                statusText = response.status.description,
                message = "HTTP ${response.status.value} from $url",
            )
        }
        val text = response.bodyAsText()
        options.maxResponseBytes?.let { cap ->
            // bodyAsText has already buffered; enforce size cap after the fact.
            // Strict pre-buffer cap requires a raw channel read — not worth the
            // complexity here since M3U downloads are bounded by MAX_M3U_SIZE
            // in the caller.
            val size = text.encodeToByteArray().size.toLong()
            if (size > cap) {
                throw HttpResponseError(
                    status = HttpStatusCode.PayloadTooLarge.value,
                    statusText = "Payload too large",
                    message = "Response $size bytes exceeds cap $cap bytes ($url)",
                )
            }
        }
        return text
    }
}

/**
 * Used internally by [KtorHttpClient.getJson] so callers can keep receiving
 * structured JSON as `Any?` (Map/List/primitive trees) the way [XtreamClient]
 * / [StalkerClient] already expect.
 */
internal fun JsonElement.toPlainAny(): Any? = when (this) {
    is kotlinx.serialization.json.JsonNull -> null
    is kotlinx.serialization.json.JsonPrimitive -> if (isString) content else
        content.toBooleanStrictOrNull() ?: content.toLongOrNull() ?: content.toDoubleOrNull() ?: content
    is kotlinx.serialization.json.JsonArray -> map { it.toPlainAny() }
    is kotlinx.serialization.json.JsonObject -> entries.associate { it.key to it.value.toPlainAny() }
}
