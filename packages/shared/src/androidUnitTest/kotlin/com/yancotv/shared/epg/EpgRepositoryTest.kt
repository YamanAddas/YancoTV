package com.yancotv.shared.epg

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpgRepositoryTest {
    private class FakeHttpClient(
        private val textResponses: Map<String, String> = emptyMap(),
        private val errors: Set<String> = emptySet(),
    ) : HttpClient {
        val calls = mutableListOf<String>()

        override suspend fun getJson(
            url: String,
            options: HttpRequestOptions,
        ): Any? = error("getJson not used")

        override suspend fun getText(
            url: String,
            options: HttpRequestOptions,
        ): String {
            calls.add(url)
            if (url in errors) throw RuntimeException("simulated network failure for $url")
            return textResponses[url] ?: error("unmocked URL: $url")
        }
    }

    /**
     * Builds a minimal XMLTV doc. `programmes` is a list of
     * (channelId, title, startUnix, endUnix) — timestamps get formatted into
     * the XMLTV "YYYYMMDDHHmmss +0000" UTC form the parser accepts.
     */
    private fun xmltv(programmes: List<Quadruple>): String {
        val progXml =
            programmes.joinToString("\n") { p ->
                """<programme start="${fmtUtc(p.startUnix)} +0000" stop="${fmtUtc(p.endUnix)} +0000" channel="${p.channelId}">
                 <title>${p.title}</title>
               </programme>"""
            }
        return """<?xml version="1.0"?>
<tv>
  <channel id="c1"><display-name>Channel One</display-name></channel>
  $progXml
</tv>"""
    }

    private data class Quadruple(
        val channelId: String,
        val title: String,
        val startUnix: Long,
        val endUnix: Long,
    )

    private fun fmtUtc(unix: Long): String {
        // Howard-Hinnant inverse: unix seconds -> civil date UTC.
        val daysSinceEpoch = unix / 86_400L
        val secOfDay = (unix % 86_400L).toInt().let { if (it < 0) it + 86_400 else it }
        val hour = secOfDay / 3600
        val minute = (secOfDay % 3600) / 60
        val second = secOfDay % 60
        val (y, m, d) = daysToCivil(daysSinceEpoch)
        return buildString {
            append(y.toString().padStart(4, '0'))
            append(m.toString().padStart(2, '0'))
            append(d.toString().padStart(2, '0'))
            append(hour.toString().padStart(2, '0'))
            append(minute.toString().padStart(2, '0'))
            append(second.toString().padStart(2, '0'))
        }
    }

    private fun daysToCivil(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val doe = (z - era * 146_097L)
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val yy = (if (m <= 2) y + 1 else y).toInt()
        return Triple(yy, m, d)
    }

    private fun insertSource(
        db: com.yancotv.shared.db.YancoDb,
        id: String,
        epgUrl: String?,
    ) {
        db.sourcesQueries.insert(
            id = id,
            name = id,
            type = "m3u_url",
            url = "http://example/$id.m3u",
            file_path = null,
            username_encrypted = null,
            password_encrypted = null,
            mac_address_encrypted = null,
            epg_url = epgUrl,
            user_agent = null,
            referer = null,
            last_synced = null,
            last_sync_error = null,
            is_active = true,
            priority = 0,
            channel_count = 0,
            auto_sync_interval = 0,
            created_at = 0,
            updated_at = 0,
        )
    }

    @Test
    fun `refresh with no sources returns ok with zero counts`() =
        runTest {
            val (db, driver) = newDbPair()
            val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { 10_000L })
            val result = repo.refresh()
            assertTrue(result.ok)
            assertEquals(0, result.programmeCount)
        }

    @Test
    fun `refresh ingests programmes into the DB`() =
        runTest {
            val (db, driver) = newDbPair()
            insertSource(db, "src-A", "http://host/epg-a.xml")

            val now = 1_700_000_000L
            val xml =
                xmltv(
                    listOf(
                        Quadruple("c1", "Morning Show", now - 1800, now + 1800),
                        Quadruple("c1", "Lunch Hour", now + 1800, now + 5400),
                    ),
                )
            val http = FakeHttpClient(mapOf("http://host/epg-a.xml" to xml))
            val repo = EpgRepository(db, driver, http, clock = { now * 1000L })

            val result = repo.refresh()
            assertTrue(result.ok, "error: ${result.error}")
            assertEquals(2, result.programmeCount)
            assertEquals(1, result.channelCount)

            val nn = repo.getNowNext("c1")
            assertNotNull(nn.now)
            assertEquals("Morning Show", nn.now!!.title)
            assertNotNull(nn.next)
            assertEquals("Lunch Hour", nn.next!!.title)
        }

    @Test
    fun `refresh keeps existing rows when all sources fail`() =
        runTest {
            val (db, driver) = newDbPair()
            insertSource(db, "src-A", "http://host/epg-a.xml")

            val now = 1_700_000_000L
            // First refresh: success.
            val xml = xmltv(listOf(Quadruple("c1", "Baseline", now - 100, now + 100)))
            var repo =
                EpgRepository(
                    db,
                    driver,
                    FakeHttpClient(mapOf("http://host/epg-a.xml" to xml)),
                    clock = { now * 1000L },
                )
            assertTrue(repo.refresh().ok)
            assertEquals(1L, repo.getStats().programmeCount)

            // Second refresh: network failure — existing row must survive.
            repo =
                EpgRepository(
                    db,
                    driver,
                    FakeHttpClient(errors = setOf("http://host/epg-a.xml")),
                    clock = { now * 1000L },
                )
            val failed = repo.refresh()
            assertFalse(failed.ok)
            assertEquals(1L, repo.getStats().programmeCount, "existing rows must not be dropped on failure")
        }

    @Test
    fun `getNowNext returns next-only when nothing is airing right now`() =
        runTest {
            val (db, driver) = newDbPair()
            insertSource(db, "src-A", "http://host/epg-a.xml")
            val now = 1_700_000_000L
            val xml = xmltv(listOf(Quadruple("c1", "Upcoming", now + 600, now + 1200)))
            val repo =
                EpgRepository(
                    db,
                    driver,
                    FakeHttpClient(mapOf("http://host/epg-a.xml" to xml)),
                    clock = { now * 1000L },
                )
            assertTrue(repo.refresh().ok)

            val nn = repo.getNowNext("c1")
            assertNull(nn.now)
            assertNotNull(nn.next)
            assertEquals("Upcoming", nn.next!!.title)
        }

    @Test
    fun `global EPG URL is included as the 'global' source key`() =
        runTest {
            val (db, driver) = newDbPair()
            val now = 1_700_000_000L
            val xml = xmltv(listOf(Quadruple("c1", "Global Show", now - 100, now + 100)))
            val repo =
                EpgRepository(
                    db,
                    driver,
                    FakeHttpClient(mapOf("http://host/global.xml" to xml)),
                    clock = { now * 1000L },
                )
            repo.setGlobalEpgUrl("http://host/global.xml")
            val result = repo.refresh()
            assertTrue(result.ok)
            assertEquals(1, result.programmeCount)
            val nn = repo.getNowNext("c1")
            assertEquals("Global Show", nn.now?.title)
        }

    // ───── Paginated guide + stats + last-error (MK.7.5 + MK.8.6) ─────

    @Test fun `getGuideData paginates cleanly over N channels`() =
        runTest {
            val (db, driver) = newDbPair()
            val now = 1_700_000_000L
            // 120 distinct tvg_ids, all with an in-window programme.
            seedGuideChannelsAndEpg(db, count = 120, windowCenter = now)

            val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { now * 1000L })
            val windowStart = now - 3600L
            val windowEnd = now + 3600L

            val page0 = repo.getGuideData(windowStart, windowEnd, limit = 50L, offset = 0L)
            val page1 = repo.getGuideData(windowStart, windowEnd, limit = 50L, offset = 50L)
            val page2 = repo.getGuideData(windowStart, windowEnd, limit = 50L, offset = 100L)

            assertEquals(50, page0.channels.size)
            assertEquals(50, page1.channels.size)
            assertEquals(20, page2.channels.size, "final page must carry the remainder")

            // Pages must not overlap. This would have caught the
            // DISTINCT-on-full-row bug that shipped LazyColumn duplicate-key
            // crashes for 250k-channel users.
            val allIds = (page0.channels + page1.channels + page2.channels).map { it.tvgId }
            assertEquals(allIds.size, allIds.toSet().size, "pagination must yield unique tvg_ids")
            assertEquals(120, allIds.toSet().size)
        }

    @Test fun `countGuideChannels matches number of distinct tvg_ids in window`() =
        runTest {
            val (db, driver) = newDbPair()
            val now = 1_700_000_000L
            seedGuideChannelsAndEpg(db, count = 37, windowCenter = now)
            val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { now * 1000L })

            assertEquals(
                37L,
                repo.countGuideChannels(startTime = now - 3600L, endTime = now + 3600L),
            )
        }

    @Test fun `getGuideData offset past end returns empty`() =
        runTest {
            val (db, driver) = newDbPair()
            val now = 1_700_000_000L
            seedGuideChannelsAndEpg(db, count = 5, windowCenter = now)
            val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { now * 1000L })

            val page =
                repo.getGuideData(
                    startTime = now - 3600L,
                    endTime = now + 3600L,
                    limit = 100L,
                    offset = 1000L,
                )
            assertTrue(page.channels.isEmpty())
        }

    @Test fun `getGuideData dedupes channels with multiple content variants sharing tvg_id`() =
        runTest {
            val (db, driver) = newDbPair()
            val now = 1_700_000_000L
            // Two content rows carry the same tvg_id — typical for providers that
            // ship 1080p + 720p variants. The paged query's GROUP BY c.tvg_id
            // must collapse them to one guide row.
            insertSource(db, "src-A", "http://a")
            insertContent(db, "ch-1080p", "src-A", "cnn.us", "CNN 1080p")
            insertContent(db, "ch-720p", "src-A", "cnn.us", "CNN 720p")
            insertProgramme(db, tvgId = "cnn.us", start = now - 100, end = now + 100, sourceKey = "src-A")

            val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { now * 1000L })
            val page =
                repo.getGuideData(
                    startTime = now - 3600L,
                    endTime = now + 3600L,
                    limit = 100L,
                    offset = 0L,
                )
            assertEquals(1, page.channels.size, "two variants of cnn.us must collapse to one guide row")
            assertEquals("cnn.us", page.channels.single().tvgId)
        }

    @Test fun `refresh error surfaces per-source failure messages`() =
        runTest {
            val (db, driver) = newDbPair()
            insertSource(db, "src-A", "http://a.xml")
            insertSource(db, "src-B", "http://b.xml")
            val now = 1_700_000_000L
            val repo =
                EpgRepository(
                    db,
                    driver,
                    // Both fail.
                    FakeHttpClient(errors = setOf("http://a.xml", "http://b.xml")),
                    clock = { now * 1000L },
                )

            val result = repo.refresh()
            assertFalse(result.ok)
            val err = result.error ?: ""
            // Joined per-source messages, not just "All EPG sources failed".
            assertTrue(err.contains("src-A"), "error must name src-A: $err")
            assertTrue(err.contains("src-B"), "error must name src-B: $err")
            // Persisted for diagnostics panel.
            assertEquals(err, repo.getLastError())
        }

    @Test fun `refresh success clears prior last-error`() =
        runTest {
            val (db, driver) = newDbPair()
            insertSource(db, "src-A", "http://a.xml")
            val now = 1_700_000_000L
            val xml = xmltv(listOf(Quadruple("c1", "Show", now - 100, now + 100)))

            // First refresh: fail.
            EpgRepository(db, driver, FakeHttpClient(errors = setOf("http://a.xml")), clock = { now * 1000L })
                .refresh()
            val repo = EpgRepository(db, driver, FakeHttpClient(mapOf("http://a.xml" to xml)), clock = { now * 1000L })
            assertTrue(repo.getLastError() != null, "sanity: first refresh should have stamped an error")

            // Second refresh: success.
            val ok = repo.refresh()
            assertTrue(ok.ok)
            assertNull(repo.getLastError(), "success must clear the stored last-error")
        }

    // ───── seeding helpers ─────

    private fun seedGuideChannelsAndEpg(
        db: com.yancotv.shared.db.YancoDb,
        count: Int,
        windowCenter: Long,
    ) {
        insertSource(db, "src-A", null)
        for (i in 0 until count) {
            val tvg = "c$i"
            insertContent(db, "ch-$i", "src-A", tvg, "Channel $i")
            insertProgramme(db, tvg, windowCenter - 100L, windowCenter + 100L, "src-A")
        }
    }

    private fun insertContent(
        db: com.yancotv.shared.db.YancoDb,
        id: String,
        sourceId: String,
        tvgId: String,
        title: String,
    ) {
        db.contentQueries.insert(
            id = id,
            source_id = sourceId,
            type = "live",
            title = title,
            clean_title = title,
            group_name = null,
            stream_url = "http://stream/$id",
            logo_url = null,
            tvg_id = tvgId,
            metadata_json = null,
            sort_order = 0L,
            created_at = 0L,
        )
    }

    private fun insertProgramme(
        db: com.yancotv.shared.db.YancoDb,
        tvgId: String,
        start: Long,
        end: Long,
        sourceKey: String,
    ) {
        db.epgProgrammesQueries.upsert(
            id = "$tvgId|$start|$sourceKey",
            source_id = sourceKey,
            channel_tvg_id = tvgId,
            title = "Show",
            description = null,
            start_time = start,
            end_time = end,
            category = null,
            icon_url = null,
        )
    }

    private fun newDbPair(): Pair<com.yancotv.shared.db.YancoDb, app.cash.sqldelight.db.SqlDriver> {
        val pair =
            com.yancotv.shared.sources
                .testDatabase()
        return pair.db to pair.driver
    }
}
