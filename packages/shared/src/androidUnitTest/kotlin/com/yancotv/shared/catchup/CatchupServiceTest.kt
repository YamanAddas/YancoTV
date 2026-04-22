package com.yancotv.shared.catchup

import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.sources.PlaintextCredentialStore
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.sources.testDatabase
import com.yancotv.shared.types.AddSourceInput
import com.yancotv.shared.types.EpgProgramme
import com.yancotv.shared.types.SourceType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CatchupServiceTest {

    // 2026-04-15T14:30:00Z, matches UrlBuilderTest reference time.
    private val apr15_1430 = 1776263400L
    private val oneHour = 3600L

    @Test fun resolvesXtreamCatchupFromLiveChannel() = runTest {
        val (db, contentRepo, sourceRepo) = bootstrap()
        val source = sourceRepo.addSource(
            AddSourceInput(
                name = "Provider",
                type = SourceType.XTREAM,
                url = "http://provider.com",
                username = "user1",
                password = "pass1",
            ),
        )
        seedLiveChannel(
            db,
            id = "ch1",
            sourceId = source.id,
            tvgId = "cnn.us",
            streamUrl = "http://provider.com/live/user1/pass1/12345.ts",
        )

        val svc = CatchupService(contentRepo, sourceRepo, clock = { (apr15_1430 + oneHour) * 1000L })
        val result = svc.resolve(
            programme(channel = "cnn.us", start = apr15_1430, end = apr15_1430 + oneHour),
        )

        val playable = assertIs<CatchupService.Resolution.Playable>(result)
        assertEquals(
            "http://provider.com/timeshift/user1/pass1/60/2026-04-15:14-30/12345.ts",
            playable.item.streamUrl,
        )
        assertTrue(playable.item.id.startsWith("catchup:ch1:"))
    }

    @Test fun rejectsFutureProgramme() = runTest {
        val (_, contentRepo, sourceRepo) = bootstrap()
        val svc = CatchupService(contentRepo, sourceRepo, clock = { apr15_1430 * 1000L })
        val result = svc.resolve(
            programme(channel = "cnn.us", start = apr15_1430 + oneHour, end = apr15_1430 + 2 * oneHour),
        )
        val reason = assertIs<CatchupService.Resolution.Unavailable>(result).reason
        assertEquals(CatchupService.UnavailableReason.FUTURE_PROGRAMME, reason)
    }

    @Test fun rejectsWhenNoMatchingChannel() = runTest {
        val (_, contentRepo, sourceRepo) = bootstrap()
        val svc = CatchupService(contentRepo, sourceRepo, clock = { (apr15_1430 + oneHour) * 1000L })
        val result = svc.resolve(
            programme(channel = "unknown.tv", start = apr15_1430, end = apr15_1430 + oneHour),
        )
        val reason = assertIs<CatchupService.Resolution.Unavailable>(result).reason
        assertEquals(CatchupService.UnavailableReason.NO_MATCHING_CHANNEL, reason)
    }

    @Test fun resolvesM3uCatchupViaShiftMetadata() = runTest {
        val (db, contentRepo, sourceRepo) = bootstrap()
        val source = sourceRepo.addSource(
            AddSourceInput(
                name = "M3U Provider",
                type = SourceType.M3U_URL,
                url = "http://m3u.example/playlist.m3u",
            ),
        )
        seedLiveChannel(
            db,
            id = "ch-m",
            sourceId = source.id,
            tvgId = "espn.us",
            streamUrl = "http://m3u.example/live/espn",
            metadataJson = """{"catchupType":"shift"}""",
        )
        // now = start + 2h — we're two hours past the programme start
        val now = apr15_1430 + 2 * oneHour
        val svc = CatchupService(contentRepo, sourceRepo, clock = { now * 1000L })
        val result = svc.resolve(
            programme(channel = "espn.us", start = apr15_1430, end = apr15_1430 + oneHour),
        )
        val playable = assertIs<CatchupService.Resolution.Playable>(result)
        assertTrue(playable.item.streamUrl.contains("shift="), "got: ${playable.item.streamUrl}")
    }

    @Test fun rejectsOutsideArchiveWindow() = runTest {
        val (db, contentRepo, sourceRepo) = bootstrap()
        val source = sourceRepo.addSource(
            AddSourceInput(
                name = "Provider",
                type = SourceType.XTREAM,
                url = "http://provider.com",
                username = "user1",
                password = "pass1",
            ),
        )
        // Archive window = 3 days, programme started 10 days ago.
        seedLiveChannel(
            db,
            id = "ch-old",
            sourceId = source.id,
            tvgId = "cnn.us",
            streamUrl = "http://provider.com/live/user1/pass1/12345.ts",
            metadataJson = """{"tvArchive":1,"tvArchiveDuration":3}""",
        )
        val tenDaysAgo = apr15_1430 - 10 * 86_400L
        val svc = CatchupService(contentRepo, sourceRepo, clock = { apr15_1430 * 1000L })
        val result = svc.resolve(
            programme(channel = "cnn.us", start = tenDaysAgo, end = tenDaysAgo + oneHour),
        )
        val reason = assertIs<CatchupService.Resolution.Unavailable>(result).reason
        assertEquals(CatchupService.UnavailableReason.OUTSIDE_ARCHIVE_WINDOW, reason)
    }

    // ───── fixtures ─────

    private data class Bootstrap(
        val db: YancoDb,
        val contentRepo: ContentRepository,
        val sourceRepo: SourceRepository,
    )

    private fun bootstrap(): Bootstrap {
        val database = testDatabase()
        val now = 1_700_000_000_000L
        val sourceRepo = SourceRepository(
            db = database.db,
            driver = database.driver,
            credentialStore = PlaintextCredentialStore(),
            http = object : com.yancotv.shared.http.HttpClient {
                override suspend fun getJson(url: String, options: com.yancotv.shared.http.HttpRequestOptions): Any? = null
                override suspend fun getText(url: String, options: com.yancotv.shared.http.HttpRequestOptions): String = ""
                override suspend fun getBytes(url: String, options: com.yancotv.shared.http.HttpRequestOptions): ByteArray = ByteArray(0)
            },
            fileReader = object : com.yancotv.shared.sources.FileContentReader {
                override suspend fun readText(path: String): String = ""
            },
            clock = { now },
        )
        return Bootstrap(database.db, ContentRepository(database.db), sourceRepo)
    }

    private fun seedLiveChannel(
        db: YancoDb,
        id: String,
        sourceId: String,
        tvgId: String,
        streamUrl: String,
        metadataJson: String? = null,
    ) {
        db.contentQueries.insert(
            id = id,
            source_id = sourceId,
            type = "live",
            title = "Channel $id",
            clean_title = "Channel $id",
            group_name = null,
            stream_url = streamUrl,
            logo_url = null,
            tvg_id = tvgId,
            metadata_json = metadataJson,
            sort_order = 0L,
            created_at = 0L,
        )
    }

    private fun programme(channel: String, start: Long, end: Long) = EpgProgramme(
        id = "$channel|$start|p",
        channelTvgId = channel,
        title = "A Show",
        startTime = start,
        endTime = end,
    )
}
