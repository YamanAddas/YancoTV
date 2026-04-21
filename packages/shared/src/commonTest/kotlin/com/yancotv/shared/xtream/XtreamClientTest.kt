package com.yancotv.shared.xtream

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.types.Result
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Mirrors `tests/unit/xtream-client.test.ts`. TS suite mocks `http.get`/`https.get`
 * at the module level; here we inject a FakeHttpClient. Each `enqueue(...)` seeds
 * the next HTTP response (equivalent to the TS `mockHttpGet(...)` helper).
 *
 * XtreamClient fetches via [HttpClient.getText] and parses JSON itself (the old
 * getJson path materialized a redundant Map/List tree on top of the JsonElement
 * tree and OOM'd on large Fire TV catalogs). The fake therefore serializes the
 * enqueued native Kotlin value back to JSON text on-the-fly.
 */

private class FakeHttpClient : HttpClient {
    private val queue = ArrayDeque<Any?>()
    val calls = mutableListOf<Pair<String, HttpRequestOptions>>()

    fun enqueue(response: Any?) { queue.addLast(ResponseBox(response)) }
    fun enqueueError(error: Throwable) { queue.addLast(error) }

    override suspend fun getJson(url: String, options: HttpRequestOptions): Any? {
        calls.add(url to options)
        val next = if (queue.isNotEmpty()) queue.removeFirst() else null
        if (next is Throwable) throw next
        if (next is ResponseBox) return next.value
        return next
    }

    override suspend fun getText(url: String, options: HttpRequestOptions): String {
        calls.add(url to options)
        val next = if (queue.isNotEmpty()) queue.removeFirst() else null
        if (next is Throwable) throw next
        val value = if (next is ResponseBox) next.value else next
        return toJson(value).toString()
    }

    private fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, value) -> k.toString() to toJson(value) })
        is List<*> -> JsonArray(v.map { toJson(it) })
        else -> JsonPrimitive(v.toString())
    }

    private class ResponseBox(val value: Any?)
}

class XtreamClientTest {

    private fun makeClient(http: HttpClient = FakeHttpClient()): XtreamClient =
        XtreamClient("http://provider.com", "user1", "pass1", XtreamClientOptions(http = http))

    // --- constructor ---

    @Test
    fun constructorNormalizesBaseUrlTrailingSlash() {
        val c1 = XtreamClient(
            "http://provider.com/", "u", "p",
            XtreamClientOptions(http = FakeHttpClient()),
        )
        assertEquals(
            "http://provider.com/live/u/p/1.ts",
            c1.buildStreamUrl(1, XtreamStreamType.LIVE),
        )
    }

    @Test
    fun constructorNormalizesBaseUrlPlayerApiSuffix() {
        val c2 = XtreamClient(
            "http://provider.com/player_api.php", "u", "p",
            XtreamClientOptions(http = FakeHttpClient()),
        )
        assertEquals(
            "http://provider.com/live/u/p/1.ts",
            c2.buildStreamUrl(1, XtreamStreamType.LIVE),
        )
    }

    // --- buildStreamUrl ---

    @Test
    fun buildsLiveStreamUrl() {
        assertEquals(
            "http://provider.com/live/user1/pass1/123.ts",
            makeClient().buildStreamUrl(123, XtreamStreamType.LIVE),
        )
    }

    @Test
    fun buildsMovieStreamUrl() {
        assertEquals(
            "http://provider.com/movie/user1/pass1/456.mp4",
            makeClient().buildStreamUrl(456, XtreamStreamType.MOVIE),
        )
    }

    @Test
    fun buildsSeriesStreamUrlWithCustomExtension() {
        assertEquals(
            "http://provider.com/series/user1/pass1/789.mkv",
            makeClient().buildStreamUrl(789, XtreamStreamType.SERIES, "mkv"),
        )
    }

    @Test
    fun defaultsToTsForLive() {
        assertTrue(makeClient().buildStreamUrl(100, XtreamStreamType.LIVE).endsWith(".ts"))
    }

    @Test
    fun defaultsToMp4ForMovie() {
        assertTrue(makeClient().buildStreamUrl(100, XtreamStreamType.MOVIE).endsWith(".mp4"))
    }

    @Test
    fun defaultsToMp4ForSeries() {
        assertTrue(makeClient().buildStreamUrl(100, XtreamStreamType.SERIES).endsWith(".mp4"))
    }

    @Test
    fun fallsBackOnEmptyExtensionMovie() {
        assertEquals(
            "http://provider.com/movie/user1/pass1/100.mp4",
            makeClient().buildStreamUrl(100, XtreamStreamType.MOVIE, ""),
        )
    }

    @Test
    fun fallsBackOnWhitespaceExtension() {
        assertEquals(
            "http://provider.com/movie/user1/pass1/100.mp4",
            makeClient().buildStreamUrl(100, XtreamStreamType.MOVIE, "  "),
        )
    }

    @Test
    fun fallsBackOnEmptyExtensionLive() {
        assertEquals(
            "http://provider.com/live/user1/pass1/100.ts",
            makeClient().buildStreamUrl(100, XtreamStreamType.LIVE, ""),
        )
    }

    @Test
    fun trimsWhitespaceFromExtension() {
        assertEquals(
            "http://provider.com/movie/user1/pass1/100.mkv",
            makeClient().buildStreamUrl(100, XtreamStreamType.MOVIE, " mkv "),
        )
    }

    @Test
    fun handlesAllValidContainerExtensions() {
        val client = makeClient()
        for (ext in listOf("mp4", "mkv", "avi", "ts", "flv")) {
            val url = client.buildStreamUrl(100, XtreamStreamType.MOVIE, ext)
            assertTrue(url.endsWith(".$ext"), "expected url $url to end with .$ext")
        }
    }

    // --- authenticate ---

    @Test
    fun authenticateReturnsAuthInfoOnSuccess() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            mapOf(
                "user_info" to mapOf(
                    "username" to "user1",
                    "status" to "Active",
                    "exp_date" to "1700000000",
                    "is_trial" to "0",
                    "active_cons" to 1,
                    "max_connections" to 2,
                ),
                "server_info" to mapOf(
                    "url" to "provider.com",
                    "port" to "80",
                    "server_protocol" to "http",
                    "time_now" to "2024-01-01 00:00:00",
                    "timezone" to "UTC",
                ),
            ),
        )
        val client = makeClient(http)
        val result = client.authenticate()
        assertTrue(result is Result.Ok)
        assertEquals("user1", result.value.userInfo.username)
        assertEquals("Active", result.value.userInfo.status)
        assertFalse(result.value.userInfo.isTrial)
    }

    @Test
    fun authenticateReturnsErrorForDisabledAccount() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            mapOf(
                "user_info" to mapOf("auth" to 0, "status" to "Disabled", "username" to "user1"),
                "server_info" to emptyMap<String, Any?>(),
            ),
        )
        val result = makeClient(http).authenticate()
        assertFalse(result is Result.Ok)
    }

    @Test
    fun authenticateReturnsErrorForMissingUserInfo() = runTest {
        val http = FakeHttpClient()
        http.enqueue(emptyMap<String, Any?>())
        val result = makeClient(http).authenticate()
        assertFalse(result is Result.Ok)
    }

    // --- getLiveStreams ---

    @Test
    fun getLiveStreamsParsesResponse() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            listOf(
                mapOf(
                    "num" to 1,
                    "name" to "CNN",
                    "stream_type" to "live",
                    "stream_id" to 101,
                    "stream_icon" to "http://icon.com/cnn.png",
                    "epg_channel_id" to "cnn.us",
                    "category_id" to "5",
                ),
                mapOf(
                    "num" to 2,
                    "name" to "BBC",
                    "stream_type" to "live",
                    "stream_id" to 102,
                    "stream_icon" to "",
                    "epg_channel_id" to "bbc.uk",
                    "category_id" to "5",
                ),
            ),
        )
        val result = makeClient(http).getLiveStreams()
        assertTrue(result is Result.Ok)
        assertEquals(2, result.value.size)
        assertEquals("CNN", result.value[0].name)
        assertEquals(101, result.value[0].streamId)
        assertEquals("cnn.us", result.value[0].epgChannelId)
    }

    @Test
    fun getLiveStreamsHandlesNonArrayResponse() = runTest {
        val http = FakeHttpClient()
        http.enqueue(null)
        val result = makeClient(http).getLiveStreams()
        assertTrue(result is Result.Ok)
        assertEquals(0, result.value.size)
    }

    // --- getVodStreams ---

    @Test
    fun getVodStreamsParsesResponse() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            listOf(
                mapOf(
                    "num" to 1,
                    "name" to "The Matrix",
                    "stream_type" to "movie",
                    "stream_id" to 201,
                    "stream_icon" to "http://icon.com/matrix.jpg",
                    "rating" to "8.7",
                    "category_id" to "10",
                    "container_extension" to "mp4",
                ),
            ),
        )
        val result = makeClient(http).getVodStreams()
        assertTrue(result is Result.Ok)
        assertEquals(1, result.value.size)
        assertEquals("The Matrix", result.value[0].name)
        assertEquals("8.7", result.value[0].rating)
        assertEquals("mp4", result.value[0].containerExtension)
    }

    // --- getSeriesList ---

    @Test
    fun getSeriesListParsesResponse() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            listOf(
                mapOf(
                    "num" to 1,
                    "name" to "Breaking Bad",
                    "series_id" to 301,
                    "cover" to "http://cover.com/bb.jpg",
                    "plot" to "A chemistry teacher turns to crime",
                    "genre" to "Drama",
                    "rating" to "9.5",
                    "category_id" to "15",
                ),
            ),
        )
        val result = makeClient(http).getSeriesList()
        assertTrue(result is Result.Ok)
        assertEquals(1, result.value.size)
        assertEquals("Breaking Bad", result.value[0].name)
        assertEquals(301, result.value[0].seriesId)
        assertEquals("Drama", result.value[0].genre)
    }

    // --- getSeriesInfo ---

    @Test
    fun getSeriesInfoParsesResponse() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            mapOf(
                "info" to mapOf(
                    "name" to "Breaking Bad",
                    "cover" to "http://cover.com/bb.jpg",
                    "plot" to "Plot text",
                    "genre" to "Drama",
                    "rating" to "9.5",
                ),
                "seasons" to listOf(
                    mapOf("season_number" to 1, "name" to "Season 1"),
                    mapOf("season_number" to 2, "name" to "Season 2"),
                ),
                "episodes" to mapOf(
                    "1" to listOf(
                        mapOf(
                            "id" to "1001",
                            "episode_num" to 1,
                            "title" to "Pilot",
                            "container_extension" to "mp4",
                            "info" to mapOf("duration" to "00:58:00", "season" to 1),
                        ),
                        mapOf(
                            "id" to "1002",
                            "episode_num" to 2,
                            "title" to "Cat's in the Bag",
                            "container_extension" to "mp4",
                            "info" to mapOf("duration" to "00:48:00", "season" to 1),
                        ),
                    ),
                ),
            ),
        )
        val result = makeClient(http).getSeriesInfo(301)
        assertTrue(result is Result.Ok)
        assertEquals("Breaking Bad", result.value.info.name)
        assertEquals(2, result.value.seasons.size)
        val ep1 = result.value.episodes["1"]
        assertNotNull(ep1)
        assertEquals(2, ep1.size)
        assertEquals("Pilot", ep1[0].title)
    }

    // --- getLiveCategories ---

    @Test
    fun getLiveCategoriesParsesResponse() = runTest {
        val http = FakeHttpClient()
        http.enqueue(
            listOf(
                mapOf("category_id" to "1", "category_name" to "News", "parent_id" to 0),
                mapOf("category_id" to "2", "category_name" to "Sports", "parent_id" to 0),
            ),
        )
        val result = makeClient(http).getLiveCategories()
        assertTrue(result is Result.Ok)
        assertEquals(2, result.value.size)
        assertEquals("News", result.value[0].categoryName)
        assertEquals("Sports", result.value[1].categoryName)
    }

    // --- error handling ---
    // Note: TS test allows ~20s for retry backoff to complete. runTest uses a
    // virtual clock so `delay(...)` in the retry loop is skipped; this runs fast.

    @Test
    fun returnsErrorOnNetworkFailure() = runTest {
        val http = FakeHttpClient()
        // The retry loop tries MAX_RETRIES + 1 = 4 times — enqueue enough errors.
        repeat(4) { http.enqueueError(RuntimeException("ECONNREFUSED")) }
        val result = makeClient(http).authenticate()
        assertFalse(result is Result.Ok)
        val err = (result as Result.Err).error
        assertTrue(
            (err.message ?: "").contains("ECONNREFUSED"),
            "expected ECONNREFUSED in error message, was: ${err.message}",
        )
    }
}
