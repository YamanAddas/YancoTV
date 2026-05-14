package com.yancotv.shared.http

import io.ktor.client.HttpClient as KtorClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import okhttp3.Interceptor

private fun buildKtor(interceptors: List<Interceptor> = emptyList()): KtorClient = KtorClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 90_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 90_000
    }
    engine {
        config {
            followRedirects(true)
            followSslRedirects(true)
            // MK.SEC.B — application-layer cleartext allow-list. The Ktor
            // OkHttp engine builds an OkHttpClient under the hood and
            // exposes its Builder via this `config` block; the
            // interceptor refuses HTTP requests to hosts the user
            // hasn't added as a Source. HTTPS traffic flows untouched.
            // Caller supplies the interceptor so the dependency on
            // SourceRepository doesn't leak into HttpClientFactory.
            interceptors.forEach { addInterceptor(it) }
        }
    }
}

actual fun createHttpClient(defaultUserAgent: String): HttpClient = KtorHttpClient(buildKtor(), defaultUserAgent)

/**
 * Android-specific factory that returns an [AndroidKtorHttpClient] — drops
 * the default [KtorHttpClient.getSource] (which buffers the whole HTTP body)
 * for a temp-file streaming variant. See that class's KDoc for why.
 *
 * Providers let Settings → Network override User-Agent + request timeout at
 * runtime without rebuilding the singleton. The engine-level timeouts in
 * [buildKtor] remain the floor; per-request values only apply when the
 * caller didn't pass its own [HttpRequestOptions.timeoutMs].
 *
 * @param interceptors OkHttp interceptors to attach to the Ktor engine's
 *                     underlying OkHttpClient. Default is empty for the
 *                     KMP/test path; production wiring in `AppModules.kt`
 *                     passes `CleartextAllowlistInterceptor` here.
 */
fun createAndroidHttpClient(
    userAgentProvider: () -> String,
    perRequestReadTimeoutMs: () -> Long?,
    cacheDir: File,
    interceptors: List<Interceptor> = emptyList(),
): HttpClient = AndroidKtorHttpClient(buildKtor(interceptors), userAgentProvider, perRequestReadTimeoutMs, cacheDir)
