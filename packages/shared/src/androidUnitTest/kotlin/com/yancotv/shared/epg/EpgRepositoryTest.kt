package com.yancotv.shared.epg

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class EpgRepositoryTest {
    private class FakeHttpClient(private val textResponses: Map<String, String> = emptyMap(), private val errors: Set<String> = emptySet()) : HttpClient {
        val calls = mutableListOf<String>()

        override suspend fun getJson(url: String, options: HttpRequestOptions): Any? = error("getJson not used")

        override suspend fun getText(url: String, options: HttpRequestOptions): String {
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

    private data class Quadruple(val channelId: String, val title: String, val startUnix: Long, val endUnix: Long)

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

    private fun insertSource(db: com.yancotv.shared.db.YancoDb, id: String, epgUrl: String?, epgPriority: Long = 0L) {
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
            epg_priority = epgPriority,
            auto_sync_on_start = false,
            created_at = 0,
            updated_at = 0,
        )
    }

    @Test
    fun `refresh with no sources returns ok with zero counts`() = runTest {
        val (db, driver) = newDbPair()
        val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { 10_000L })
        val result = repo.refresh()
        assertTrue(result.ok)
        assertEquals(0, result.programmeCount)
    }

    @Test
    fun `refresh ingests programmes into the DB`() = runTest {
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
    fun `refresh keeps existing rows when all sources fail`() = runTest {
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
    fun `getNowNext returns next-only when nothing is airing right now`() = runTest {
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
    fun `global EPG URL is included as the 'global' source key`() = runTest {
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

    @Test fun `getGuideData paginates cleanly over N channels`() = runTest {
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

    @Test fun `countGuideChannels matches number of distinct tvg_ids in window`() = runTest {
        val (db, driver) = newDbPair()
        val now = 1_700_000_000L
        seedGuideChannelsAndEpg(db, count = 37, windowCenter = now)
        val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { now * 1000L })

        assertEquals(
            37L,
            repo.countGuideChannels(startTime = now - 3600L, endTime = now + 3600L),
        )
    }

    @Test fun `getGuideData offset past end returns empty`() = runTest {
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

    @Test fun `getGuideData dedupes channels with multiple content variants sharing tvg_id`() = runTest {
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

    @Test fun `refresh error surfaces per-source failure messages`() = runTest {
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

    @Test fun `refresh success clears prior last-error`() = runTest {
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

    private fun seedGuideChannelsAndEpg(db: com.yancotv.shared.db.YancoDb, count: Int, windowCenter: Long) {
        insertSource(db, "src-A", null)
        for (i in 0 until count) {
            val tvg = "c$i"
            insertContent(db, "ch-$i", "src-A", tvg, "Channel $i")
            insertProgramme(db, tvg, windowCenter - 100L, windowCenter + 100L, "src-A")
        }
    }

    /**
     * MK.15.7 — multi-EPG priority. When two sources cover the same
     * `tvg_id` at the same time, queries that have a `LEFT JOIN
     * sources … ORDER BY epg_priority DESC` (nowForChannel,
     * nowNextForChannel, forChannelRange, futureByChannelAndTitle,
     * guideWindow) must return the higher-priority source's row first.
     * These tests pin that contract so a future refactor that drops
     * the JOIN can't silently regress.
     */
    @Test fun `getNowProgramme picks higher epg_priority source`() = runTest {
        val (db, driver) = newDbPair()
        insertSource(db, "src-low", null, epgPriority = 0L)
        insertSource(db, "src-high", null, epgPriority = 5L)

        // Both sources publish a programme covering "now".
        val nowSec = 1_000L
        db.epgProgrammesQueries.upsert(
            id = "low-prog",
            source_id = "src-low",
            channel_tvg_id = "cnn.us",
            title = "Low source show",
            description = null,
            start_time = nowSec - 500L,
            end_time = nowSec + 500L,
            category = null,
            icon_url = null,
        )
        db.epgProgrammesQueries.upsert(
            id = "high-prog",
            source_id = "src-high",
            channel_tvg_id = "cnn.us",
            title = "High source show",
            description = null,
            start_time = nowSec - 500L,
            end_time = nowSec + 500L,
            category = null,
            icon_url = null,
        )

        val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { nowSec * 1_000L })
        val now = repo.getNowProgramme("cnn.us")
        assertNotNull(now)
        assertEquals("High source show", now.title)
    }

    @Test fun `single-source case unchanged by priority JOIN`() = runTest {
        val (db, driver) = newDbPair()
        insertSource(db, "src-A", null, epgPriority = 0L)
        val nowSec = 1_000L
        db.epgProgrammesQueries.upsert(
            id = "p1",
            source_id = "src-A",
            channel_tvg_id = "cnn.us",
            title = "Solo show",
            description = null,
            start_time = nowSec - 500L,
            end_time = nowSec + 500L,
            category = null,
            icon_url = null,
        )

        val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { nowSec * 1_000L })
        assertEquals("Solo show", repo.getNowProgramme("cnn.us")?.title)
    }

    /**
     * MK.EPG.F — batched programme fetch. The pre-fix `getGuideData`
     * issued one `forChannelRange` per channel inside a 100-channel
     * page → 101 queries per page; the user's 4022-channel install
     * paid that on every guide load + every horizontal page step.
     *
     * These tests pin the new contract without locking the
     * implementation to a specific number of queries (a future
     * planner-driven refactor that goes back to per-channel for some
     * reason should still pass):
     *
     *   - Each channel still gets its own programmes (no cross-channel
     *     bleed from the IN-list).
     *   - Programmes within a channel are ordered priority DESC then
     *     start_time ASC, matching the per-channel `forChannelRange`
     *     contract that `getProgrammesForChannel` pins above.
     *   - Channels with no in-window programmes still appear in the
     *     guide with an empty `programmes` list (the channel-list
     *     query's EXISTS clause already filters those out — this test
     *     pins it as a load-bearing assumption).
     */
    @Test fun `getGuideData groups programmes per channel without cross-bleed`() = runTest {
        val (db, driver) = newDbPair()
        insertSource(db, "src-A", null)

        // Three channels, each with two programmes — the batched IN-list
        // would silently cross-bleed if the SQL or grouping mishandled
        // channel_tvg_id.
        val now = 1_700_000_000L
        insertContent(db, "ch-1", "src-A", "alpha.tv", "Alpha")
        insertContent(db, "ch-2", "src-A", "beta.tv", "Beta")
        insertContent(db, "ch-3", "src-A", "gamma.tv", "Gamma")
        insertProgramme(db, "alpha.tv", now - 1800, now - 600, "src-A")
        insertProgramme(db, "alpha.tv", now - 600, now + 1200, "src-A")
        insertProgramme(db, "beta.tv", now - 900, now + 100, "src-A")
        insertProgramme(db, "beta.tv", now + 100, now + 1500, "src-A")
        insertProgramme(db, "gamma.tv", now - 200, now + 800, "src-A")
        insertProgramme(db, "gamma.tv", now + 800, now + 2400, "src-A")

        val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { now * 1000L })
        val page = repo.getGuideData(startTime = now - 3600L, endTime = now + 3600L)

        assertEquals(3, page.channels.size)
        val byTvg = page.channels.associateBy { it.tvgId }
        assertEquals(2, byTvg["alpha.tv"]?.programmes?.size)
        assertEquals(2, byTvg["beta.tv"]?.programmes?.size)
        assertEquals(2, byTvg["gamma.tv"]?.programmes?.size)
        // Cross-bleed guard — alpha's programme channelTvgIds must all
        // be alpha.tv. A regression that grouped wrong (e.g. lost the
        // grouping key, or returned every row to every channel) would
        // tip this over.
        byTvg["alpha.tv"]?.programmes?.forEach {
            assertEquals("alpha.tv", it.channelTvgId, "alpha programmes must not contain beta/gamma rows")
        }
        byTvg["beta.tv"]?.programmes?.forEach {
            assertEquals("beta.tv", it.channelTvgId)
        }
        byTvg["gamma.tv"]?.programmes?.forEach {
            assertEquals("gamma.tv", it.channelTvgId)
        }
        // Within a channel, programmes ordered by start_time. Same
        // contract as the single-channel `getProgrammesForChannel`
        // when there's no priority tier conflict.
        val alphaStarts = byTvg["alpha.tv"]?.programmes?.map { it.startTime }
        assertEquals(listOf(now - 1800, now - 600), alphaStarts)
    }

    @Test fun `getGuideData preserves per-channel priority ordering across the batch`() = runTest {
        // MK.EPG.F regression guard — the batched query has to keep the
        // same priority-DESC tie-break as `forChannelRange` per channel,
        // otherwise multi-EPG installs would see a low-priority source's
        // duplicate programme leak in front of the high-priority one
        // when they share start_time. Mirrors the single-channel
        // `getProgrammesForChannel orders priority then start_time` test
        // but exercises the batched path.
        //
        // MK.EPG.G follow-up — same-(channel, start_time) duplicates
        // are now dedup'd to the highest-priority row, so this test
        // also pins that the WINNING row of a multi-source overlap is
        // the high-priority one (not the low-priority one).
        val (db, driver) = newDbPair()
        insertSource(db, "src-low", null, epgPriority = 0L)
        insertSource(db, "src-high", null, epgPriority = 10L)

        val now = 1_700_000_000L
        insertContent(db, "ch-cnn", "src-high", "cnn.us", "CNN")
        insertContent(db, "ch-cnn-low", "src-low", "cnn.us", "CNN backup")

        // Two sources, same channel, overlapping programmes.
        db.epgProgrammesQueries.upsert(
            id = "low|${now - 100}|src-low",
            source_id = "src-low", channel_tvg_id = "cnn.us",
            title = "Low source", description = null,
            start_time = now - 100, end_time = now + 100,
            category = null, icon_url = null,
        )
        db.epgProgrammesQueries.upsert(
            id = "high|${now - 100}|src-high",
            source_id = "src-high", channel_tvg_id = "cnn.us",
            title = "High source", description = null,
            start_time = now - 100, end_time = now + 100,
            category = null, icon_url = null,
        )

        val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { now * 1000L })
        val page = repo.getGuideData(startTime = now - 3600L, endTime = now + 3600L)
        // GROUP BY c.tvg_id collapses the two content variants to one row.
        assertEquals(1, page.channels.size)
        val cnn = page.channels.single()
        assertEquals("cnn.us", cnn.tvgId)
        // Post-MK.EPG.G: dedup collapses (channel, start_time)
        // duplicates to the highest-priority row.
        assertEquals(1, cnn.programmes.size, "duplicate (channel, start_time) across sources must collapse")
        assertEquals("High source", cnn.programmes.single().title, "high-priority source wins the dedup")
    }

    /**
     * MK.EPG.G — multi-source dedup. When the user wires up two EPG
     * sources covering the same channel (common: provider's xmltv.php
     * + a global epg.xz), both publish a programme for the same
     * `(channel_tvg_id, start_time)` slot. Pre-fix, every read query
     * returned BOTH rows and the Guide cell rendered duplicates
     * (same title overlapping, sometimes with different descriptions
     * stacked on top). The fix adds a `NOT EXISTS` priority filter
     * that keeps the highest-priority row per (channel, start_time)
     * with deterministic id-based tie-break on equal priorities.
     *
     * Tests below pin all three deduped queries:
     * `forChannelRange` (single channel), `forChannelsRange`
     * (batched, used by getGuideData), and `nowNextForChannel`
     * (which previously could return "now-A + now-B" instead of
     * "now + next" when priorities tied).
     */
    @Test fun `getProgrammesForChannel dedupes same-time programmes from multiple sources`() = runTest {
        val (db, driver) = newDbPair()
        insertSource(db, "src-low", null, epgPriority = 0L)
        insertSource(db, "src-high", null, epgPriority = 10L)

        // Two sources, same channel, same start_time — duplicates.
        val start = 1_500L
        db.epgProgrammesQueries.upsert(
            id = "low-prog", source_id = "src-low", channel_tvg_id = "cnn.us",
            title = "Low version", description = null,
            start_time = start, end_time = start + 100,
            category = null, icon_url = null,
        )
        db.epgProgrammesQueries.upsert(
            id = "high-prog", source_id = "src-high", channel_tvg_id = "cnn.us",
            title = "High version", description = null,
            start_time = start, end_time = start + 100,
            category = null, icon_url = null,
        )

        val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { 0L })
        val rows = repo.getProgrammesForChannel("cnn.us", startTime = 0L, endTime = 10_000L)
        assertEquals(1, rows.size, "duplicate (channel, start_time) across sources must collapse to one row")
        assertEquals("High version", rows.single().title, "highest epg_priority must win")
    }

    @Test fun `getProgrammesForChannel dedup tiebreak is deterministic on equal priorities`() = runTest {
        // Equal-priority tie-break is by `id` ascending — among
        // duplicates with the same epg_priority, the row with the
        // lexicographically-smallest id wins. The id format is
        // `channelId|startTime|sourceKey`, so for ties this resolves
        // to "smallest sourceKey wins" which is stable across queries.
        // A regression that flipped the comparison would still dedup
        // but pick the OTHER row, breaking caller assumptions.
        val (db, driver) = newDbPair()
        insertSource(db, "src-aaa", null, epgPriority = 5L)
        insertSource(db, "src-zzz", null, epgPriority = 5L)

        val start = 1_500L
        db.epgProgrammesQueries.upsert(
            id = "cnn.us|$start|src-aaa", source_id = "src-aaa", channel_tvg_id = "cnn.us",
            title = "AAA version", description = null,
            start_time = start, end_time = start + 100,
            category = null, icon_url = null,
        )
        db.epgProgrammesQueries.upsert(
            id = "cnn.us|$start|src-zzz", source_id = "src-zzz", channel_tvg_id = "cnn.us",
            title = "ZZZ version", description = null,
            start_time = start, end_time = start + 100,
            category = null, icon_url = null,
        )

        val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { 0L })
        val rows = repo.getProgrammesForChannel("cnn.us", startTime = 0L, endTime = 10_000L)
        assertEquals(1, rows.size)
        assertEquals("AAA version", rows.single().title, "ties resolve to smallest id (= AAA's source key)")
    }

    @Test fun `getProgrammesForChannel does not dedup distinct start_times`() = runTest {
        // Sanity guard — the dedup applies only to (channel, start_time)
        // collisions, not to a single source's adjacent programmes.
        // A regression that over-applied the dedup (e.g. comparing on
        // channel only, ignoring start_time) would silently collapse
        // every programme on a channel to one row.
        val (db, driver) = newDbPair()
        insertSource(db, "src-A", null)
        for (i in 0 until 5) {
            db.epgProgrammesQueries.upsert(
                id = "p$i", source_id = "src-A", channel_tvg_id = "cnn.us",
                title = "Show $i", description = null,
                start_time = (i * 1000L), end_time = (i * 1000L + 500),
                category = null, icon_url = null,
            )
        }

        val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { 0L })
        val rows = repo.getProgrammesForChannel("cnn.us", startTime = 0L, endTime = 10_000L)
        assertEquals(5, rows.size, "distinct start_times must NOT be collapsed")
    }

    @Test fun `getGuideData dedupes batched query across multi-source channels`() = runTest {
        // Same scenario as `getProgrammesForChannel dedupes ...` but
        // exercises the batched `forChannelsRange` path used by the
        // Guide grid. Verifies the dedup carries across the IN-list
        // form (the per-channel test above doesn't — they're
        // separate SQL queries).
        val (db, driver) = newDbPair()
        insertSource(db, "src-low", null, epgPriority = 0L)
        insertSource(db, "src-high", null, epgPriority = 10L)
        insertContent(db, "ch-cnn", "src-high", "cnn.us", "CNN")
        insertContent(db, "ch-bbc", "src-high", "bbc.uk", "BBC")

        val now = 1_700_000_000L
        // CNN: duplicate from both sources.
        db.epgProgrammesQueries.upsert(
            id = "cnn-low", source_id = "src-low", channel_tvg_id = "cnn.us",
            title = "Low CNN", description = null,
            start_time = now - 100, end_time = now + 100,
            category = null, icon_url = null,
        )
        db.epgProgrammesQueries.upsert(
            id = "cnn-high", source_id = "src-high", channel_tvg_id = "cnn.us",
            title = "High CNN", description = null,
            start_time = now - 100, end_time = now + 100,
            category = null, icon_url = null,
        )
        // BBC: single source, no duplicates — verifies dedup is
        // scoped per-channel and doesn't cross-talk.
        db.epgProgrammesQueries.upsert(
            id = "bbc-only", source_id = "src-high", channel_tvg_id = "bbc.uk",
            title = "BBC News", description = null,
            start_time = now - 100, end_time = now + 100,
            category = null, icon_url = null,
        )

        val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { now * 1000L })
        val page = repo.getGuideData(startTime = now - 3600L, endTime = now + 3600L)
        val byTvg = page.channels.associateBy { it.tvgId }

        assertEquals(1, byTvg["cnn.us"]?.programmes?.size, "CNN duplicate must collapse to one row")
        assertEquals("High CNN", byTvg["cnn.us"]?.programmes?.first()?.title)
        assertEquals(1, byTvg["bbc.uk"]?.programmes?.size, "BBC's single row must survive")
        assertEquals("BBC News", byTvg["bbc.uk"]?.programmes?.first()?.title)
    }

    @Test fun `getNowNext returns deduped now + next when two equal-priority sources cover the same now slot`() = runTest {
        // Pre-fix bug shape: `nowNextForChannel` LIMIT 2 returned
        // [now-from-source-A, now-from-source-B] — two copies of
        // "now" and no "next" — when both sources had the same
        // epg_priority. With dedup, LIMIT 2 sees the deduped row set
        // [now, next] and returns the correct pair.
        val (db, driver) = newDbPair()
        insertSource(db, "src-aaa", null, epgPriority = 5L)
        insertSource(db, "src-zzz", null, epgPriority = 5L)

        val now = 1_700_000_000L
        // Both sources publish the SAME "now" programme (same start_time).
        db.epgProgrammesQueries.upsert(
            id = "cnn.us|${now - 100}|src-aaa", source_id = "src-aaa", channel_tvg_id = "cnn.us",
            title = "Now AAA", description = null,
            start_time = now - 100, end_time = now + 100,
            category = null, icon_url = null,
        )
        db.epgProgrammesQueries.upsert(
            id = "cnn.us|${now - 100}|src-zzz", source_id = "src-zzz", channel_tvg_id = "cnn.us",
            title = "Now ZZZ", description = null,
            start_time = now - 100, end_time = now + 100,
            category = null, icon_url = null,
        )
        // Source AAA has the upcoming "next" programme.
        db.epgProgrammesQueries.upsert(
            id = "cnn.us|${now + 100}|src-aaa", source_id = "src-aaa", channel_tvg_id = "cnn.us",
            title = "Next AAA", description = null,
            start_time = now + 100, end_time = now + 1100,
            category = null, icon_url = null,
        )

        val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { now * 1000L })
        val nn = repo.getNowNext("cnn.us")
        assertNotNull(nn.now, "now must be populated — pre-fix dedup-less query lost it to the duplicate")
        assertEquals("Now AAA", nn.now!!.title, "smaller-id source wins on equal priority")
        assertNotNull(nn.next, "next must survive — duplicate now-rows previously consumed both LIMIT slots")
        assertEquals("Next AAA", nn.next!!.title)
    }

    @Test fun `getProgrammesForChannel orders priority then start_time`() = runTest {
        val (db, driver) = newDbPair()
        insertSource(db, "src-low", null, epgPriority = 0L)
        insertSource(db, "src-high", null, epgPriority = 10L)

        // Same channel, two sources, programmes interleaved in time.
        // forChannelRange's ORDER BY priority DESC, start_time means
        // ALL high-priority rows come before any low-priority rows
        // even when their start_time is later.
        val low1 = 1000L
        val low2 = 2000L
        val high1 = 1500L
        db.epgProgrammesQueries.upsert(
            id = "low-1", source_id = "src-low", channel_tvg_id = "cnn.us",
            title = "Low 1", description = null, start_time = low1, end_time = low1 + 100,
            category = null, icon_url = null,
        )
        db.epgProgrammesQueries.upsert(
            id = "low-2", source_id = "src-low", channel_tvg_id = "cnn.us",
            title = "Low 2", description = null, start_time = low2, end_time = low2 + 100,
            category = null, icon_url = null,
        )
        db.epgProgrammesQueries.upsert(
            id = "high-1", source_id = "src-high", channel_tvg_id = "cnn.us",
            title = "High 1", description = null, start_time = high1, end_time = high1 + 100,
            category = null, icon_url = null,
        )

        val repo = EpgRepository(db, driver, FakeHttpClient(), clock = { 0L })
        val rows = repo.getProgrammesForChannel("cnn.us", startTime = 0L, endTime = 10_000L)
        assertEquals(3, rows.size)
        // High-priority row first, then low-priority by start_time.
        assertEquals("High 1", rows[0].title)
        assertEquals("Low 1", rows[1].title)
        assertEquals("Low 2", rows[2].title)
    }

    private fun insertContent(db: com.yancotv.shared.db.YancoDb, id: String, sourceId: String, tvgId: String, title: String) {
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

    private fun insertProgramme(db: com.yancotv.shared.db.YancoDb, tvgId: String, start: Long, end: Long, sourceKey: String) {
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
