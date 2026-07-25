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
 *   - **Xtream path-segment credentials (MB-292).** Every *playback* URL
 *     the app builds carries the username and password as path segments,
 *     not query params:
 *
 *         {base}/live|movie|series/{username}/{password}/{id}.{ext}
 *         {base}/timeshift/{username}/{password}/{mins}/{start}/{id}.ts
 *
 *     (`XtreamClient.buildStreamUrl`, `catchup/UrlBuilder`.) Only
 *     `player_api.php` uses the `?username=&password=` form the query
 *     rule above covers — so before this, the canonical URL shape for
 *     every live channel, movie, episode and catch-up stream went
 *     unredacted through logs, `sources.last_sync_error`, Sentry
 *     breadcrumbs and on-screen error text.
 *
 * An earlier revision of this file deliberately skipped path segments,
 * reasoning that it "needs a per-provider rule because we can't tell
 * `/Movies/title` from credentials by shape alone". That objection does
 * not apply to URLs *we* generate: the two templates above are fixed, so
 * they are matched exactly — a known type segment followed by exactly two
 * segments. A third-party URL that happens to match this shape loses two
 * path segments to `***` in a log line; a leaked password is permanent.
 * That trade is deliberate.
 *
 * What's deliberately NOT redacted:
 *   - Bare `auth=` / `key=` / `secret=` — too many false positives
 *     (`cache_key=`, `sort_key=`, IPTV providers occasionally use
 *     `key=` for non-credential routing tokens). Add to [SENSITIVE_KEYS]
 *     when a real provider needs it.
 *   - Arbitrary path segments outside the templates above. Stalker
 *     portals authenticate with a MAC address in a Cookie header rather
 *     than in the URL, so there is no equivalent shape to match.
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
    // MB-292 — before the query rule, so a timeshift URL carrying both
    // shapes is fully scrubbed.
    out = PATH_CREDENTIALS_REGEX.replace(out) { m -> "/${m.groupValues[1]}/***/***/" }
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

// MB-292 — Xtream playback/catch-up credentials carried as path segments.
// Anchored on the fixed type segment our own builders emit (live / movie /
// series / timeshift) followed by exactly two segments, which are the
// username and password in every one of those templates. Segments are
// `[^/?\s]+` so the match cannot run past a `/`, a query string, or
// whitespace, and the trailing `/` keeps it from firing on a two-segment
// path that merely ends in something type-shaped.
private val PATH_CREDENTIALS_REGEX =
    Regex("""/(live|movie|series|timeshift)/[^/?\s]+/[^/?\s]+/""", RegexOption.IGNORE_CASE)

// Matches `?name=value` and `&name=value`. Captures the leading `?`/`&`
// in group 1 (preserved on output), the param name in group 2, and the
// value (anything until the next `&` or whitespace) in group 3 — value
// is dropped on redaction.
private val QUERY_PARAM_REGEX = Regex("""([?&])([^=&]+)=([^&\s]*)""")
