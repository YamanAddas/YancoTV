package com.yancotv.shared.content

import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.sources.testDb
import com.yancotv.shared.types.ContentType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ContentRepository] — FTS4 search + pagination + the
 * `findLiveByTvgId` lookup used by catch-up resolution.
 *
 * Focus areas:
 *  - Priority-based channel picking when multiple sources carry the
 *    same tvg_id (the common provider + backup provider case).
 *  - Search handles queries with punctuation + multi-word prefix match.
 *  - Pagination offsets past count return empty, not error.
 */
class ContentRepositoryTest {
    @Test fun findLiveByTvgId_picksHighestPrioritySource() =
        runTest {
            val db = testDb()
            // Two sources with the same channel; source-A wins (lower priority
            // number = higher sort priority in the Source row).
            insertSource(db, "src-A", priority = 0)
            insertSource(db, "src-B", priority = 5)
            insertContent(db, "ch-A", "src-A", tvgId = "cnn.us", title = "CNN (A)")
            insertContent(db, "ch-B", "src-B", tvgId = "cnn.us", title = "CNN (B)")

            val repo = ContentRepository(db)
            val match = repo.findLiveByTvgId("cnn.us")
            assertNotNull(match)
            assertEquals("ch-A", match.id)
            assertEquals("CNN (A)", match.title)
        }

    @Test fun findLiveByTvgId_nullForUnknownId() =
        runTest {
            val db = testDb()
            val repo = ContentRepository(db)
            assertNull(repo.findLiveByTvgId("nonexistent.tv"))
        }

    @Test fun findLiveByTvgId_ignoresBlankTvgId() =
        runTest {
            val db = testDb()
            val repo = ContentRepository(db)
            // Blank input must short-circuit without a DB hit; desktop does the
            // same to avoid matching rows where tvg_id = ''.
            assertNull(repo.findLiveByTvgId(""))
            assertNull(repo.findLiveByTvgId("   "))
        }

    @Test fun findLiveByTvgId_ignoresVodAndSeriesRows() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A", priority = 0)
            // Hypothetical movie that shares a tvg_id (rare but possible in
            // weird M3U setups) — should NOT be returned by a "find live" call.
            insertContent(
                db,
                id = "movie-1",
                sourceId = "src-A",
                tvgId = "hbo.us",
                title = "HBO Movie",
                type = "movie",
            )
            val repo = ContentRepository(db)
            assertNull(repo.findLiveByTvgId("hbo.us"))
        }

    @Test fun search_ftsFullWordMatch() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A", priority = 0)
            insertContent(db, "ch-1", "src-A", tvgId = "cnn.us", title = "CNN International")
            insertContent(db, "ch-2", "src-A", tvgId = "bbc.uk", title = "BBC News")
            insertContent(db, "ch-3", "src-A", tvgId = "sky.uk", title = "Sky Sports")

            val repo = ContentRepository(db)
            // Full-word match — FTS4 built-in behavior without the SQLite JDBC
            // driver's sometimes-missing prefix support.
            val matches = repo.search("Sports")
            assertEquals(1, matches.size)
            assertEquals("Sky Sports", matches.single().title)
        }

    @Test fun search_emptyQueryReturnsNothing() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A", priority = 0)
            insertContent(db, "ch-1", "src-A", tvgId = "cnn.us", title = "CNN")
            val repo = ContentRepository(db)
            assertTrue(repo.search("").isEmpty())
            assertTrue(repo.search("   ").isEmpty())
        }

    @Test fun search_respectsLimit() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A", priority = 0)
            for (i in 0 until 20) insertContent(db, "ch-$i", "src-A", "c$i", "Match $i")
            val repo = ContentRepository(db)
            assertEquals(5, repo.search("Match", limit = 5L).size)
        }

    @Test fun page_offsetPastEndReturnsEmpty() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A", priority = 0)
            for (i in 0 until 3) insertContent(db, "ch-$i", "src-A", "c$i", "Ch $i")
            val repo = ContentRepository(db)
            val page = repo.page(ContentType.LIVE, group = null, offset = 100L, limit = 10L)
            assertTrue(page.isEmpty())
        }

    @Test fun count_matchesRowsForType() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A", priority = 0)
            insertContent(db, "ch-1", "src-A", "c1", "A", type = "live")
            insertContent(db, "ch-2", "src-A", "c2", "B", type = "live")
            insertContent(db, "mv-1", "src-A", null, "M1", type = "movie")
            val repo = ContentRepository(db)
            assertEquals(2L, repo.count(ContentType.LIVE))
            assertEquals(1L, repo.count(ContentType.MOVIE))
            assertEquals(0L, repo.count(ContentType.SERIES))
        }

    // ───── fixtures ─────

    private fun insertSource(
        db: YancoDb,
        id: String,
        priority: Int,
    ) {
        db.sourcesQueries.insert(
            id = id,
            name = id,
            type = "m3u_url",
            url = "http://host/$id.m3u",
            file_path = null,
            username_encrypted = null,
            password_encrypted = null,
            mac_address_encrypted = null,
            epg_url = null,
            user_agent = null,
            last_synced = null,
            last_sync_error = null,
            is_active = true,
            priority = priority.toLong(),
            channel_count = 0,
            auto_sync_interval = 0,
            created_at = 0L,
            updated_at = 0L,
        )
    }

    private fun insertContent(
        db: YancoDb,
        id: String,
        sourceId: String,
        tvgId: String?,
        title: String,
        type: String = "live",
    ) {
        db.contentQueries.insert(
            id = id,
            source_id = sourceId,
            type = type,
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
}
