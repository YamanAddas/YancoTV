package com.yancotv.shared.http

import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.HttpClient as KtorClient

actual fun createHttpClient(defaultUserAgent: String): HttpClient {
    val ktor =
        KtorClient(Darwin) {
            install(HttpTimeout) {
                requestTimeoutMillis = 90_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 90_000
            }
        }
    return KtorHttpClient(ktor, defaultUserAgent)
}
