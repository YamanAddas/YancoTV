package com.yancotv.shared.http

import com.yancotv.shared.types.Source

/**
 * Application-layer cleartext-traffic allow-list — contract.
 *
 * Why this exists: the Android manifest sets
 * `usesCleartextTraffic="true"` because the provider host-set is
 * user-configured at runtime and Android's `network_security_config.xml`
 * is static (see AGENTS.md "Cleartext traffic (Android)" and bugs.md
 * MB-203 for the full threat-model rationale). This interface plus the
 * companion derivation [cleartextAllowlistFromSources] are the
 * application-layer defense-in-depth: the OkHttp interceptor landing in
 * MK.SEC.B and the Media3 `HttpDataSource.Factory` wrapper landing in
 * MK.SEC.C consult this allow-list to refuse HTTP requests whose host
 * is not on it, while HTTPS traffic flows untouched.
 *
 * The contract is intentionally minimal — one method that takes a host
 * string and returns whether plain HTTP is permitted to it. Production
 * implementations back this with the runtime `sources` list; tests pass
 * a fixed `Set<String>` via [StaticCleartextAllowlist].
 *
 * @see cleartextAllowlistFromSources for the derivation function that
 * produces the set of allowed hosts from a snapshot of user sources.
 */
interface CleartextAllowlist {
    /**
     * @param host the host portion of an `http://` URL. Callers should
     *             pre-strip port + userinfo + brackets (for IPv6); the
     *             allow-list normalises to lowercase internally.
     * @return `true` if cleartext (HTTP) is permitted to this host.
     *         Empty or blank input returns `false`.
     */
    fun isHostAllowed(host: String): Boolean
}

/**
 * Immutable in-memory allow-list backed by a pre-computed set of
 * lowercased hosts. Use when the source list is known up-front; for the
 * runtime-reactive Android wiring that updates as the user adds /
 * removes sources, see the StateFlow-backed impl in MK.SEC.B.
 */
class StaticCleartextAllowlist(allowedHosts: Set<String>) : CleartextAllowlist {
    private val allowedHosts: Set<String> = allowedHosts.map { it.lowercase() }.toSet()

    override fun isHostAllowed(host: String): Boolean {
        if (host.isBlank()) return false
        return host.lowercase() in allowedHosts
    }
}

/**
 * Permit-everything allow-list — equivalent to the current "manifest
 * says cleartext is OK globally" behaviour at the application layer.
 * Use only as the very-early-startup fallback before the source list
 * has loaded; the production wiring should always replace this with a
 * concrete allow-list once `SourceRepository` is queryable.
 */
object PermitAllCleartextAllowlist : CleartextAllowlist {
    override fun isHostAllowed(host: String): Boolean = host.isNotBlank()
}

/**
 * Derive the cleartext allow-list from a snapshot of user sources.
 * Every `http://` URL field (`url` and `epgUrl`) on every source
 * contributes its host to the result set.
 *
 *   - `https://` URLs are ignored — they don't need allow-listing, the
 *     OS-layer cleartext check doesn't gate them.
 *   - Malformed, empty, or null URLs are skipped silently. A source
 *     without a URL (just-created Stalker portal mid-edit) contributes
 *     nothing.
 *   - Hosts are lowercased. IPv6 hosts in `[::1]` form have their
 *     brackets stripped before insertion. Ports and userinfo are
 *     dropped.
 *
 * Pure function — no I/O. Lives in `commonMain` so it's testable on
 * the JVM target and ports straight to iOS post-1.0.
 */
fun cleartextAllowlistFromSources(sources: List<Source>): Set<String> {
    val hosts = mutableSetOf<String>()
    for (s in sources) {
        addHttpHost(s.url, hosts)
        addHttpHost(s.epgUrl, hosts)
    }
    return hosts
}

/**
 * Internal: pull the host out of a candidate URL string and add it to
 * [into] if the scheme is `http`. Tolerant of empty / null / malformed
 * input — anything that isn't a recognisable `http://` URL is dropped
 * silently.
 */
private fun addHttpHost(rawUrl: String?, into: MutableSet<String>) {
    val url = rawUrl?.trim() ?: return
    if (url.isEmpty()) return
    val match = HTTP_HOST_REGEX.find(url) ?: return
    val rawHost = match.groupValues[1]
    val host =
        if (rawHost.startsWith('[') && rawHost.endsWith(']')) {
            rawHost.substring(1, rawHost.length - 1)
        } else {
            rawHost
        }
    if (host.isNotBlank()) into.add(host.lowercase())
}

/**
 * Matches an `http://` URL and captures the host.
 *
 *   - `http://` scheme prefix (case-insensitive).
 *   - Optional `user@` or `user:pass@` userinfo, non-greedy — anything
 *     up to (but not including) the `@`, no `/` / whitespace allowed.
 *   - Host: either `[IPv6]` bracket form OR a non-bracketed hostname
 *     (anything but `/`, `:`, `?`, `#`, whitespace). Port, path,
 *     query, and fragment all sit outside the captured group.
 *
 * `https://` URLs are deliberately not matched — they don't need
 * allow-listing.
 */
private val HTTP_HOST_REGEX = Regex(
    """^http://(?:[^/@\s]+@)?(\[[0-9a-fA-F:]+]|[^/:?#\s]+)""",
    RegexOption.IGNORE_CASE,
)
