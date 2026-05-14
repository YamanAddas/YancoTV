package com.yancotv.shared.http

import com.yancotv.shared.types.Source
import com.yancotv.shared.types.SourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the application-layer cleartext allow-list. Two surfaces:
 *
 *   1. [cleartextAllowlistFromSources] — derives the host set from a
 *      `List<Source>` snapshot. Pure function; pinned by table-driven
 *      tests over real-world IPTV provider URL shapes.
 *   2. [StaticCleartextAllowlist] / [PermitAllCleartextAllowlist] —
 *      the two concrete implementations of the contract.
 *
 * MK.SEC.B will add an Android-only `ReactiveCleartextAllowlist` that
 * tracks `SourceRepository`'s StateFlow; its tests live in
 * `androidUnitTest`. The contract tested here doesn't change.
 */
class CleartextAllowlistTest {
    // ─── Derivation: cleartextAllowlistFromSources ──────────────────────

    @Test
    fun httpUrlAddsItsHostToTheAllowlist() {
        val hosts = cleartextAllowlistFromSources(listOf(makeSource(url = "http://provider.example.com/playlist.m3u")))
        assertEquals(setOf("provider.example.com"), hosts)
    }

    @Test
    fun httpsUrlIsNotAddedBecauseItDoesNotNeedCleartextPermission() {
        val hosts = cleartextAllowlistFromSources(listOf(makeSource(url = "https://provider.example.com/playlist.m3u")))
        assertTrue(hosts.isEmpty(), "HTTPS hosts shouldn't be in the cleartext allow-list — they don't need it")
    }

    @Test
    fun multipleHttpSourcesEachContributeAHost() {
        val hosts =
            cleartextAllowlistFromSources(
                listOf(
                    makeSource(id = "1", url = "http://provider-a.example.com/playlist"),
                    makeSource(id = "2", url = "http://provider-b.example.com/player_api.php"),
                ),
            )
        assertEquals(setOf("provider-a.example.com", "provider-b.example.com"), hosts)
    }

    @Test
    fun httpEpgUrlOnSameSourceAddsAdditionalHost() {
        val hosts =
            cleartextAllowlistFromSources(
                listOf(
                    makeSource(
                        url = "http://provider.example.com/playlist",
                        epgUrl = "http://epg.example.com/guide.xml.gz",
                    ),
                ),
            )
        assertEquals(setOf("provider.example.com", "epg.example.com"), hosts)
    }

    @Test
    fun mixedHttpHttpsOnSameSourceOnlyAddsTheHttpHost() {
        // Provider URL is HTTP (needs allow-list); EPG is HTTPS (doesn't).
        val hosts =
            cleartextAllowlistFromSources(
                listOf(
                    makeSource(
                        url = "http://provider.example.com/playlist",
                        epgUrl = "https://epg.example.com/guide.xml.gz",
                    ),
                ),
            )
        assertEquals(setOf("provider.example.com"), hosts)
    }

    @Test
    fun xtreamStyleUrlWithCredentialsInQueryParamsExtractsHostWithoutCredentials() {
        // Real Xtream auth shape — credentials in query string, not basic-auth.
        val hosts =
            cleartextAllowlistFromSources(
                listOf(
                    makeSource(
                        url = "http://provider.example.com/player_api.php?username=foo&password=bar",
                    ),
                ),
            )
        assertEquals(setOf("provider.example.com"), hosts)
    }

    @Test
    fun basicAuthUserinfoIsDroppedFromTheHost() {
        // user:pass@host shape — userinfo before the @ should not leak
        // into the allow-list.
        val hosts =
            cleartextAllowlistFromSources(
                listOf(makeSource(url = "http://user:pass@provider.example.com/playlist")),
            )
        assertEquals(setOf("provider.example.com"), hosts)
    }

    @Test
    fun explicitPortIsStrippedFromTheHost() {
        val hosts =
            cleartextAllowlistFromSources(
                listOf(makeSource(url = "http://provider.example.com:8080/playlist")),
            )
        assertEquals(setOf("provider.example.com"), hosts)
    }

    @Test
    fun ipv6BracketsAreStrippedFromTheHost() {
        val hosts =
            cleartextAllowlistFromSources(
                listOf(makeSource(url = "http://[2001:db8::1]:8080/playlist")),
            )
        assertEquals(setOf("2001:db8::1"), hosts)
    }

    @Test
    fun hostsAreLowercased() {
        val hosts =
            cleartextAllowlistFromSources(
                listOf(makeSource(url = "http://Provider.Example.COM/playlist")),
            )
        assertEquals(setOf("provider.example.com"), hosts)
    }

    @Test
    fun nullUrlIsSkippedSilently() {
        val hosts = cleartextAllowlistFromSources(listOf(makeSource(url = null)))
        assertTrue(hosts.isEmpty(), "null url should contribute nothing")
    }

    @Test
    fun emptyUrlIsSkippedSilently() {
        val hosts = cleartextAllowlistFromSources(listOf(makeSource(url = "")))
        assertTrue(hosts.isEmpty(), "empty url should contribute nothing")
    }

    @Test
    fun malformedUrlIsSkippedSilently() {
        val hosts =
            cleartextAllowlistFromSources(
                listOf(
                    makeSource(id = "1", url = "not even a url"),
                    makeSource(id = "2", url = "http://"),
                    makeSource(id = "3", url = "://no-scheme"),
                ),
            )
        assertTrue(hosts.isEmpty(), "malformed urls should be skipped, not throw")
    }

    @Test
    fun emptySourceListProducesEmptyAllowlist() {
        val hosts = cleartextAllowlistFromSources(emptyList())
        assertTrue(hosts.isEmpty())
    }

    @Test
    fun duplicateHttpHostsAcrossSourcesAreDeduped() {
        // Two sources pointing at the same provider host should
        // produce one allow-list entry, not two.
        val hosts =
            cleartextAllowlistFromSources(
                listOf(
                    makeSource(id = "1", url = "http://provider.example.com/playlist-a"),
                    makeSource(id = "2", url = "http://provider.example.com/playlist-b"),
                ),
            )
        assertEquals(setOf("provider.example.com"), hosts)
    }

    // ─── StaticCleartextAllowlist ───────────────────────────────────────

    @Test
    fun staticAllowlistPermitsHostsInTheSet() {
        val list = StaticCleartextAllowlist(setOf("provider.example.com"))
        assertTrue(list.isHostAllowed("provider.example.com"))
    }

    @Test
    fun staticAllowlistRefusesHostsNotInTheSet() {
        val list = StaticCleartextAllowlist(setOf("allowed.example.com"))
        assertFalse(list.isHostAllowed("other.example.com"))
    }

    @Test
    fun staticAllowlistComparesCaseInsensitively() {
        // Input host can come from anywhere (OkHttp Request.url.host
        // normalises to lowercase, but Media3 doesn't always); the
        // contract is "case-insensitive match against the stored set".
        val list = StaticCleartextAllowlist(setOf("provider.example.com"))
        assertTrue(list.isHostAllowed("PROVIDER.example.com"))
        assertTrue(list.isHostAllowed("Provider.Example.Com"))
    }

    @Test
    fun staticAllowlistWithBlankHostInputAlwaysReturnsFalse() {
        val list = StaticCleartextAllowlist(setOf("provider.example.com"))
        assertFalse(list.isHostAllowed(""))
        assertFalse(list.isHostAllowed("   "))
    }

    @Test
    fun staticAllowlistWithEmptySetAlwaysReturnsFalse() {
        val list = StaticCleartextAllowlist(emptySet())
        assertFalse(list.isHostAllowed("anything.example.com"))
    }

    // ─── PermitAllCleartextAllowlist ────────────────────────────────────

    @Test
    fun permitAllAllowsAnyNonBlankHost() {
        assertTrue(PermitAllCleartextAllowlist.isHostAllowed("anything.example.com"))
        assertTrue(PermitAllCleartextAllowlist.isHostAllowed("10.0.0.1"))
    }

    @Test
    fun permitAllStillRefusesBlankInput() {
        // Even the permissive fallback rejects empty input — a blank
        // host means the caller's URL parser failed; that shouldn't be
        // silently accepted just because we're in the fallback mode.
        assertFalse(PermitAllCleartextAllowlist.isHostAllowed(""))
        assertFalse(PermitAllCleartextAllowlist.isHostAllowed(" "))
    }

    // ─── Fixture ────────────────────────────────────────────────────────

    /**
     * Minimal `Source` constructor for tests. All non-relevant fields
     * default to neutral values so individual tests only have to pass
     * the URL(s) under test.
     */
    private fun makeSource(
        id: String = "src-1",
        url: String? = null,
        epgUrl: String? = null,
    ): Source =
        Source(
            id = id,
            name = "Test Source $id",
            type = SourceType.M3U_URL,
            url = url,
            epgUrl = epgUrl,
            isActive = true,
            priority = 0,
            channelCount = 0,
            autoSyncInterval = 0,
            createdAt = 0L,
            updatedAt = 0L,
        )
}
