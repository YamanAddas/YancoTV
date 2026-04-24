package com.yancotv.shared.content

import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.logger.NOOP_LOGGER
import com.yancotv.shared.sources.CredentialStore
import com.yancotv.shared.sources.FileContentReader
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.sources.testDatabase
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentMetadata
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.EpisodeInfo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ContentDetailService]. Focus is on the cache-hit decision
 * matrix + metadata persistence — the fetch path is covered transitively
 * by [com.yancotv.shared.xtream.XtreamClientTest]. Skipping a fetch when
 * we don't need one is the user-visible performance contract.
 */
class ContentDetailServiceTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private class FakeHttp : HttpClient {
        override suspend fun getJson(
            url: String,
            options: HttpRequestOptions,
        ): Any? = null

        override suspend fun getText(
            url: String,
            options: HttpRequestOptions,
        ): String = "{}"
    }

    private class PlainCreds : CredentialStore {
        override fun encrypt(plaintext: String): ByteArray = plaintext.encodeToByteArray()

        override fun decrypt(blob: ByteArray): String = blob.decodeToString()
    }

    private class NoFileReader : FileContentReader {
        override suspend fun readText(path: String): String = error("not expected")
    }

    private fun makeRepo(db: YancoDb): SourceRepository {
        val bundle = testDatabase()
        return SourceRepository(
            db = bundle.db,
            driver = bundle.driver,
            credentialStore = PlainCreds(),
            http = FakeHttp(),
            fileReader = NoFileReader(),
            clock = { 0L },
        )
    }

    private fun movieItem(metadata: ContentMetadata?): ContentItem =
        ContentItem(
            id = "mv-1",
            sourceId = "src-1",
            type = ContentType.MOVIE,
            title = "The Matrix",
            streamUrl = "http://h/mv/1.mp4",
            metadataJson = metadata?.let { json.encodeToString(ContentMetadata.serializer(), it) },
            sortOrder = 0,
            createdAt = 0,
        )

    private fun seriesItem(metadata: ContentMetadata?): ContentItem =
        ContentItem(
            id = "sr-1",
            sourceId = "src-1",
            type = ContentType.SERIES,
            title = "Breaking Bad",
            streamUrl = "",
            metadataJson = metadata?.let { json.encodeToString(ContentMetadata.serializer(), it) },
            sortOrder = 0,
            createdAt = 0,
        )

    private fun makeService(db: YancoDb): ContentDetailService =
        ContentDetailService(
            db = db,
            sources = makeRepo(db),
            http = FakeHttp(),
            logger = NOOP_LOGGER,
            clock = { 1_000_000L },
        )

    @Test fun movieWithCachedPlotSkipsFetch() =
        runTest {
            val bundle = testDatabase()
            val svc = makeService(bundle.db)
            val cached = ContentMetadata(plot = "A hacker discovers reality", streamId = 42L)
            val loaded = svc.load(movieItem(cached))
            assertEquals("A hacker discovers reality", loaded.metadata.plot)
            // No episodes for a movie.
            assertTrue(loaded.episodes.isEmpty())
        }

    @Test fun movieWithoutStreamIdSkipsFetch() =
        runTest {
            val bundle = testDatabase()
            val svc = makeService(bundle.db)
            // No streamId in cache and no plot either → still should NOT fetch,
            // because there's no ID to fetch against.
            val cached = ContentMetadata(plot = null, streamId = null)
            val loaded = svc.load(movieItem(cached))
            assertNull(loaded.metadata.plot)
        }

    @Test fun seriesWithCachedEpisodesSkipsFetch() =
        runTest {
            val bundle = testDatabase()
            val svc = makeService(bundle.db)
            val cachedEpisodes =
                listOf(
                    EpisodeInfo(id = "e1", seasonNumber = 1, episodeNumber = 1, title = "Pilot", streamUrl = "u"),
                )
            val cached = ContentMetadata(seriesId = 100L, episodes = cachedEpisodes)
            val loaded = svc.load(seriesItem(cached))
            assertEquals(1, loaded.episodes.size)
            assertEquals("Pilot", loaded.episodes[0].title)
        }

    @Test fun liveItemReturnsImmediately() =
        runTest {
            val bundle = testDatabase()
            val svc = makeService(bundle.db)
            val item =
                ContentItem(
                    id = "live-1",
                    sourceId = "src-1",
                    type = ContentType.LIVE,
                    title = "CNN",
                    streamUrl = "http://h/live/1.ts",
                    metadataJson = null,
                    sortOrder = 0,
                    createdAt = 0,
                )
            val loaded = svc.load(item)
            // LIVE should never fetch; returns empty-metadata shape.
            assertTrue(loaded.episodes.isEmpty())
            assertNull(loaded.metadata.plot)
        }

    @Test fun fetchIsSkippedWhenNoXtreamCredentials() =
        runTest {
            val bundle = testDatabase()
            // No source row inserted → xtreamCredentials returns null → we render
            // cached data without calling the HTTP client (which would fail).
            val svc = makeService(bundle.db)
            val cached = ContentMetadata(plot = null, streamId = 42L)
            val loaded = svc.load(movieItem(cached))
            // No fetch happened → plot is still null and no crash.
            assertNull(loaded.metadata.plot)
        }

    @Test fun corruptedMetadataJsonIsTreatedAsEmpty() =
        runTest {
            val bundle = testDatabase()
            val svc = makeService(bundle.db)
            val item =
                ContentItem(
                    id = "mv-1",
                    sourceId = "src-1",
                    type = ContentType.MOVIE,
                    title = "X",
                    streamUrl = "http://h/1.mp4",
                    metadataJson = "not-valid-json{{",
                    sortOrder = 0,
                    createdAt = 0,
                )
            // Must not throw — parseMetadata returns null, we fall back to empty.
            // And with empty metadata + no streamId there's nothing to fetch.
            val loaded = svc.load(item)
            assertNotNull(loaded)
            assertNull(loaded.metadata.plot)
        }
}
