package com.yancotv.shared.stalker

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirrors `tests/unit/stalker-client.test.ts`. The TS suite mocks `http.get`;
 * here a dummy HttpClient is injected (construction-only tests never call it).
 */

private class DummyHttpClient : HttpClient {
    override suspend fun getJson(url: String, options: HttpRequestOptions): Any? = null
}

class StalkerClientTest {

    private val http: HttpClient = DummyHttpClient()

    private fun makeClient(
        portal: String = "http://portal.example.com/stalker_portal",
        mac: String = "00:1A:79:AA:BB:CC",
    ): StalkerClient = StalkerClient(portal, mac, StalkerClientOptions(http = http))

    // --- constructor ---

    @Test
    fun canBeInstantiatedWithRequiredParameters() {
        val c = StalkerClient(
            "http://portal.example.com", "00:1A:79:00:00:01",
            StalkerClientOptions(http = http),
        )
        assertTrue(c is StalkerClient)
    }

    @Test
    fun stripsTrailingSlashesFromPortalUrl() {
        val c = StalkerClient(
            "http://portal.example.com/stalker_portal///",
            "00:1A:79:00:00:01",
            StalkerClientOptions(http = http),
        )
        assertEquals(
            "http://stream.example.com/live/123",
            c.buildStreamUrl("http://stream.example.com/live/123"),
        )
    }

    @Test
    fun acceptsOptionalTimeoutParameter() {
        val c = StalkerClient(
            "http://portal.example.com", "00:1A:79:00:00:01",
            StalkerClientOptions(http = http, timeoutMs = 30_000),
        )
        assertTrue(c is StalkerClient)
    }

    // --- buildStreamUrl ---

    @Test
    fun stripsFfrtPrefix() {
        assertEquals(
            "http://stream.example.com/live/123",
            makeClient().buildStreamUrl("ffrt http://stream.example.com/live/123"),
        )
    }

    @Test
    fun stripsFfmpegPrefix() {
        assertEquals(
            "http://stream.example.com/live/456",
            makeClient().buildStreamUrl("ffmpeg http://stream.example.com/live/456"),
        )
    }

    @Test
    fun stripsAutoPrefix() {
        assertEquals(
            "http://stream.example.com/live/789",
            makeClient().buildStreamUrl("auto http://stream.example.com/live/789"),
        )
    }

    @Test
    fun handlesPlainUrlWithoutPrefix() {
        assertEquals(
            "http://stream.example.com/live/100",
            makeClient().buildStreamUrl("http://stream.example.com/live/100"),
        )
    }

    @Test
    fun trimsWhitespace() {
        assertEquals(
            "http://stream.example.com/live/200",
            makeClient().buildStreamUrl("  http://stream.example.com/live/200  "),
        )
    }

    @Test
    fun performsCaseInsensitivePrefixRemoval() {
        val c = makeClient()
        assertEquals(
            "http://stream.example.com/live/300",
            c.buildStreamUrl("FFRT http://stream.example.com/live/300"),
        )
        assertEquals(
            "http://stream.example.com/live/301",
            c.buildStreamUrl("Ffmpeg http://stream.example.com/live/301"),
        )
        assertEquals(
            "http://stream.example.com/live/302",
            c.buildStreamUrl("AUTO http://stream.example.com/live/302"),
        )
    }

    @Test
    fun stripsPrefixWithExtraWhitespace() {
        assertEquals(
            "http://stream.example.com/live/400",
            makeClient().buildStreamUrl("ffrt   http://stream.example.com/live/400"),
        )
    }

    @Test
    fun doesNotStripPrefixLikeSubstringsInMiddleOfUrl() {
        val url = "http://stream.example.com/ffrt/live/500"
        assertEquals(url, makeClient().buildStreamUrl(url))
    }

    @Test
    fun handlesEmptyString() {
        assertEquals("", makeClient().buildStreamUrl(""))
    }
}
