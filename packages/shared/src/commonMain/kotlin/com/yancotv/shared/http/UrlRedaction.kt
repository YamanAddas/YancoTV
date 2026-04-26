package com.yancotv.shared.http

/**
 * Replace IPTV-provider credentials in [url] with the literal placeholder
 * (three asterisks) so error messages, logs, the `sources.last_sync_error`
 * DB column, and on-screen failure dialogs can include a URL without
 * leaking the user's username/password.
 *
 * Why this matters: Xtream-style providers authenticate via query params
 * shaped like `?username=foo&password=bar&action=get`. Without redaction,
 * an HTTP 401 turns into an exception with the credentials baked into
 * the message; that message hits Android logcat (visible to anyone with
 * adb), the SQLite `last_sync_error` column, and ultimately the Sources
 * screen's "Failed: ..." error text rendered back to the user. The
 * user-facing path is the most acute leak — the one screenshot they
 * send for support reveals their credentials.
 *
 * What's redacted:
 *   - HTTP basic-auth userinfo at the start of the authority — the
 *     `user:pass@host` form gets replaced with placeholders.
 *   - Query params named `username` / `password` / `token` / `api_key` /
 *     `apikey` / `auth_token`. Comparison is case-insensitive on the
 *     param name. Value is replaced with the placeholder; the param
 *     name remains so the reader can tell what shape of URL failed.
 *
 * What's deliberately NOT redacted:
 *   - Bare `auth=` / `key=` / `secret=` — too many false positives
 *     (`cache_key=`, `sort_key=`, IPTV providers occasionally use
 *     `key=` for non-credential routing tokens). Add to [SENSITIVE_KEYS]
 *     when a real provider needs it.
 *   - Path segments. Some providers embed credentials in path segments
 *     (Xtream catch-up does this). Path-segment redaction is a follow-up;
 *     it needs a per-provider rule because we can't tell `/Movies/title`
 *     from credentials by shape alone.
 *
 * Pure: no exceptions, no parsing — string-level regex replacement.
 * A malformed URL passes through with whatever pattern it matched, no
 * crash. Lives in `commonMain` so it works on JVM/Android/iOS identically.
 *
 * @see UrlRedactionTest for the cases pinned by tests.
 */
fun redactCredentials(url: String): String {
    if (url.isEmpty()) return url
    var out = url
    out = BASIC_AUTH_REGEX.replace(out) { m -> "${m.groupValues[1]}***:***@" }
    out =
        QUERY_PARAM_REGEX.replace(out) { m ->
            val name = m.groupValues[2]
            if (name.lowercase() in SENSITIVE_KEYS) {
                "${m.groupValues[1]}$name=***"
            } else {
                m.value
            }
        }
    return out
}

/**
 * Apply [redactCredentials] to whatever value [t] returns from
 * `t.message ?: t.toString()`. Convenience for catch-blocks that build
 * a redacted log/DB message in one call.
 */
fun redactErrorMessage(t: Throwable): String = redactCredentials(t.message ?: t.toString())

private val SENSITIVE_KEYS =
    setOf(
        "username",
        "password",
        "token",
        "api_key",
        "apikey",
        "auth_token",
    )

// Matches `scheme://user:pass@` where scheme follows RFC 3986 (letter
// followed by letters/digits/+/-/.). Stops at the first `/`, `@`, or
// whitespace inside user/pass to avoid eating arbitrary later content.
private val BASIC_AUTH_REGEX = Regex("""^([a-z][a-z0-9+\-.]*://)([^/@\s]+):([^/@\s]+)@""", RegexOption.IGNORE_CASE)

// Matches `?name=value` and `&name=value`. Captures the leading `?`/`&`
// in group 1 (preserved on output), the param name in group 2, and the
// value (anything until the next `&` or whitespace) in group 3 — value
// is dropped on redaction.
private val QUERY_PARAM_REGEX = Regex("""([?&])([^=&]+)=([^&\s]*)""")
