package com.yancotv.shared.http

/**
 * Platform-specific factory for the default [HttpClient]. Android picks the
 * OkHttp engine (already on the classpath via Media3), iOS picks Darwin.
 * The shared module therefore doesn't hardcode an engine in commonMain.
 */
expect fun createHttpClient(defaultUserAgent: String = "YancoTV/0.1.0"): HttpClient
