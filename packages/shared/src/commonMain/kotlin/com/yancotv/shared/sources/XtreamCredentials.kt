package com.yancotv.shared.sources

/**
 * Decrypted Xtream credentials for a single source. Short-lived — callers
 * should hold this no longer than they need to build a URL. The password
 * field is deliberately a raw [String] rather than a char array because
 * [String] is what the URL builder + HTTP stack both need.
 */
data class XtreamCredentials(
    val baseUrl: String,
    val username: String,
    val password: String,
)
