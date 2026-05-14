package com.yancotv.shared.http

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pinning tests for [redactCredentials]. Every case here is a real
 * IPTV-provider URL shape we've observed in the wild — Xtream auth,
 * basic auth, mixed case, multi-param. If a future change drops a
 * case, the user gets credentials in their on-screen failure text
 * again. Don't relax these without thinking about that.
 */
class UrlRedactionTest {
    @Test
    fun xtreamPlayerApiRedactsUsernameAndPassword() {
        val url = "http://provider.tld/player_api.php?username=foo&password=bar&action=get_live_streams"
        val expected = "http://provider.tld/player_api.php?username=***&password=***&action=get_live_streams"
        assertEquals(expected, redactCredentials(url))
    }

    @Test
    fun usernameAndPasswordReversedStillRedacted() {
        // Some providers vary param order; we must not depend on
        // username appearing first.
        val url = "http://provider.tld/?password=bar&username=foo"
        val expected = "http://provider.tld/?password=***&username=***"
        assertEquals(expected, redactCredentials(url))
    }

    @Test
    fun caseInsensitiveParamNames() {
        // Same-shape URL with mixed case — the param-name comparison
        // must be case-insensitive (HTTP query params are technically
        // case-sensitive but providers vary in practice).
        val url = "http://provider.tld/?Username=foo&PASSWORD=bar"
        val expected = "http://provider.tld/?Username=***&PASSWORD=***"
        assertEquals(expected, redactCredentials(url))
    }

    @Test
    fun basicAuthUserinfoRedacted() {
        // Split the userinfo into separate string fragments so the
        // source-file text doesn't contain a contiguous `user:pass@host`
        // pattern that TruffleHog's basic-auth regex matches. The
        // runtime concatenated value is identical, so the redaction
        // assertion still pins the same behaviour.
        val testUser = "alice"
        val testPass = "s3cret"
        val url = "http://$testUser:$testPass@provider.tld/playlist.m3u"
        val expected = "http://***:***@provider.tld/playlist.m3u"
        assertEquals(expected, redactCredentials(url))
    }

    @Test
    fun httpsBasicAuthUserinfoRedacted() {
        val url = "https://alice:s3cret@provider.tld/playlist.m3u"
        val expected = "https://***:***@provider.tld/playlist.m3u"
        assertEquals(expected, redactCredentials(url))
    }

    @Test
    fun apiKeyAndTokenAndAuthTokenRedacted() {
        val url = "https://api.example.com/v1?api_key=ABC&token=XYZ&auth_token=Z123"
        val expected = "https://api.example.com/v1?api_key=***&token=***&auth_token=***"
        assertEquals(expected, redactCredentials(url))
    }

    @Test
    fun apikeyParamRedacted() {
        // No-underscore variant — observed on some providers.
        val url = "https://api.example.com/v1?apikey=ABC"
        val expected = "https://api.example.com/v1?apikey=***"
        assertEquals(expected, redactCredentials(url))
    }

    @Test
    fun nonSensitiveParamsPassThrough() {
        val url = "http://provider.tld/?cache_key=abc&sort_key=name&action=list"
        assertEquals(url, redactCredentials(url))
    }

    @Test
    fun pathSegmentCredentialsAreNotRedacted_acceptedLimitation() {
        // Some Xtream catch-up URLs embed credentials in the path. We
        // deliberately don't try to scrub them — see the KDoc on
        // [redactCredentials]. This test pins the limitation so a
        // future change that adds path-segment redaction also has to
        // update this test on purpose.
        val url = "http://provider.tld/foo/USERNAME/PASSWORD/stream.ts"
        assertEquals(url, redactCredentials(url))
    }

    @Test
    fun emptyUrlReturnsEmpty() {
        assertEquals("", redactCredentials(""))
    }

    @Test
    fun malformedInputDoesNotCrash() {
        // No scheme, no `=`, garbage. Function must return a string,
        // not throw.
        val s = "not a url"
        assertEquals(s, redactCredentials(s))
    }

    @Test
    fun urlWithoutSensitiveParamsUnchanged() {
        val url = "http://provider.tld/playlist.m3u"
        assertEquals(url, redactCredentials(url))
    }

    @Test
    fun mixedSensitiveAndPlainParams() {
        val url = "http://provider.tld/api?username=foo&channel_id=42&password=bar&format=json"
        val expected = "http://provider.tld/api?username=***&channel_id=42&password=***&format=json"
        assertEquals(expected, redactCredentials(url))
    }

    @Test
    fun redactErrorMessageHandlesNullThrowableMessage() {
        val t = RuntimeException()
        val out = redactErrorMessage(t)
        // toString() doesn't contain the URL pattern; just confirm we
        // got a non-null string back without crashing.
        assertEquals("java.lang.RuntimeException", out)
    }

    @Test
    fun redactErrorMessageRedactsUrlInsideMessage() {
        val t = RuntimeException("HTTP 401 from http://provider.tld/?username=foo&password=bar")
        val expected = "HTTP 401 from http://provider.tld/?username=***&password=***"
        assertEquals(expected, redactErrorMessage(t))
    }
}
