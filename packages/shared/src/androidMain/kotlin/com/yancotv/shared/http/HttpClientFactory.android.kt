package com.yancotv.shared.http

import io.ktor.client.HttpClient as KtorClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

actual fun createHttpClient(defaultUserAgent: String): HttpClient {
    val ktor = KtorClient(OkHttp) {
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
    return KtorHttpClient(ktor, defaultUserAgent)
}
