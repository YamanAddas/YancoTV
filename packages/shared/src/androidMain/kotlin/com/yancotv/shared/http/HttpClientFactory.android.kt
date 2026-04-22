package com.yancotv.shared.http

import io.ktor.client.HttpClient as KtorClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File

private fun buildKtor(): KtorClient = KtorClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 90_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 90_000
    }
    engine {
        config {
            followRedirects(true)
            followSslRedirects(true)
        }
    }
}

actual fun createHttpClient(defaultUserAgent: String): HttpClient =
    KtorHttpClient(buildKtor(), defaultUserAgent)

/**
 * Android-specific factory that returns an [AndroidKtorHttpClient] — drops
 * the default [KtorHttpClient.getSource] (which buffers the whole HTTP body)
 * for a temp-file streaming variant. See that class's KDoc for why.
 *
 * Providers let Settings → Network override User-Agent + request timeout at
 * runtime without rebuilding the singleton. The engine-level timeouts in
 * [buildKtor] remain the floor; per-request values only apply when the
 * caller didn't pass its own [HttpRequestOptions.timeoutMs].
 */
fun createAndroidHttpClient(
    userAgentProvider: () -> String,
    perRequestReadTimeoutMs: () -> Long?,
    cacheDir: File,
): HttpClient = AndroidKtorHttpClient(buildKtor(), userAgentProvider, perRequestReadTimeoutMs, cacheDir)
