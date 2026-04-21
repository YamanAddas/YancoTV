package com.yancotv.shared.epg

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.sources.testDb
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
        override suspend fun getJson(url: String, options: HttpRequestOptions): Any? =
            error("getJson not used")
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
        val progXml = programmes.joinToString("\n") { p ->
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

    private fun insertSource(db: com.yancotv.shared.db.YancoDb, id: String, epgUrl: String?) {
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
    fun `refresh with no sources returns ok with zero counts`() = runTest {
        val db = testDb()
        val repo = EpgRepository(db, FakeHttpClient(), clock = { 10_000L })
        val result = repo.refresh()
        assertTrue(result.ok)
        assertEquals(0, result.programmeCount)
    }

    @Test
    fun `refresh ingests programmes into the DB`() = runTest {
        val db = testDb()
        insertSource(db, "src-A", "http://host/epg-a.xml")

        val now = 1_700_000_000L
        val xml = xmltv(
            listOf(
                Quadruple("c1", "Morning Show", now - 1800, now + 1800),
                Quadruple("c1", "Lunch Hour", now + 1800, now + 5400),
            ),
        )
        val http = FakeHttpClient(mapOf("http://host/epg-a.xml" to xml))
        val repo = EpgRepository(db, http, clock = { now * 1000L })

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
        val db = testDb()
        insertSource(db, "src-A", "http://host/epg-a.xml")

        val now = 1_700_000_000L
        // First refresh: success.
        val xml = xmltv(listOf(Quadruple("c1", "Baseline", now - 100, now + 100)))
        var repo = EpgRepository(
            db,
            FakeHttpClient(mapOf("http://host/epg-a.xml" to xml)),
            clock = { now * 1000L },
        )
        assertTrue(repo.refresh().ok)
        assertEquals(1L, repo.getStats().programmeCount)

        // Second refresh: network failure — existing row must survive.
        repo = EpgRepository(
            db,
            FakeHttpClient(errors = setOf("http://host/epg-a.xml")),
            clock = { now * 1000L },
        )
        val failed = repo.refresh()
        assertFalse(failed.ok)
        assertEquals(1L, repo.getStats().programmeCount, "existing rows must not be dropped on failure")
    }

    @Test
    fun `getNowNext returns next-only when nothing is airing right now`() = runTest {
        val db = testDb()
        insertSource(db, "src-A", "http://host/epg-a.xml")
        val now = 1_700_000_000L
        val xml = xmltv(listOf(Quadruple("c1", "Upcoming", now + 600, now + 1200)))
        val repo = EpgRepository(
            db,
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
        val db = testDb()
        val now = 1_700_000_000L
        val xml = xmltv(listOf(Quadruple("c1", "Global Show", now - 100, now + 100)))
        val repo = EpgRepository(
            db,
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
}
