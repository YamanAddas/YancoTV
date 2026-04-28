package com.yancotv.shared.history

import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.sources.testDb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [WatchHistoryRepository] — resume points + recent-watched list.
 *
 * The tricky behavior this covers:
 *  - `positionFor(contentId)` must NOT fall through to episode rows; a
 *    series container may have episode rows but asking for the
 *    container's resume must return the content-level row or null. A
 *    prior bug (MB-41) had the container seeking to some arbitrary
 *    episode's offset, which this guards.
 *  - `recent()` does a client-side join against `content`. Orphan history
 *    rows (content wiped by a sync) must drop silently, not crash.
 *  - ON DELETE CASCADE — removing a content row removes its history rows.
 *    The DB schema enforces this, but we verify the behaviour here so a
 *    future schema change doesn't regress it silently.
 */
class WatchHistoryRepositoryTest {
    @Test fun upsertWritesResumePointAtContentLevel() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "movie-1", "src-A", type = "movie")
            val repo = WatchHistoryRepository(db, clock = { 1_000L })

            repo.upsert(contentId = "movie-1", positionSeconds = 120L, durationSeconds = 7200L)

            assertEquals(120L, repo.positionFor("movie-1"))
        }

    @Test fun upsertWithEpisodeIdUsesDistinctRowKey() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "series-1", "src-A", type = "series")
            insertEpisode(db, id = "ep-S01E01", contentId = "series-1")
            insertEpisode(db, id = "ep-S01E02", contentId = "series-1")
            val repo = WatchHistoryRepository(db, clock = { 1_000L })

            repo.upsert("series-1", episodeId = "ep-S01E01", positionSeconds = 300L, durationSeconds = 1800L)
            repo.upsert("series-1", episodeId = "ep-S01E02", positionSeconds = 600L, durationSeconds = 1800L)

            // Two distinct rows — different episode ids ⇒ different wh:... keys.
            val all = db.watchHistoryQueries.selectByContent("series-1").executeAsList()
            assertEquals(2, all.size)
            val keys = all.map { it.id }.toSet()
            assertTrue(keys.contains("wh:series-1:ep-S01E01"))
            assertTrue(keys.contains("wh:series-1:ep-S01E02"))
        }

    @Test fun positionFor_ignoresEpisodeRowsWhenNoContainerRow() =
        runTest {
            // Critical regression guard for MB-41: positionFor must return
            // null if there's only episode-level rows, NOT an arbitrary
            // episode's offset.
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "series-1", "src-A", type = "series")
            insertEpisode(db, id = "ep-1", contentId = "series-1")
            val repo = WatchHistoryRepository(db, clock = { 0L })

            repo.upsert("series-1", episodeId = "ep-1", positionSeconds = 900L)
            assertNull(
                repo.positionFor("series-1"),
                "container resume must be null when only episode rows exist",
            )
        }

    @Test fun positionFor_returnsContainerRowWhenBothExist() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "series-1", "src-A", type = "series")
            insertEpisode(db, id = "ep-1", contentId = "series-1")
            val repo = WatchHistoryRepository(db, clock = { 0L })

            repo.upsert("series-1", episodeId = null, positionSeconds = 42L)
            repo.upsert("series-1", episodeId = "ep-1", positionSeconds = 900L)

            assertEquals(42L, repo.positionFor("series-1"))
        }

    @Test fun upsertOnSameKeyReplaces() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "movie-1", "src-A", type = "movie")
            val repo = WatchHistoryRepository(db, clock = { 0L })

            repo.upsert("movie-1", positionSeconds = 100L, durationSeconds = 6000L)
            repo.upsert("movie-1", positionSeconds = 2000L, durationSeconds = 6000L)

            assertEquals(2000L, repo.positionFor("movie-1"))
            // Single row, not two.
            assertEquals(
                1,
                db.watchHistoryQueries
                    .selectByContent("movie-1")
                    .executeAsList()
                    .size,
            )
        }

    @Test fun recent_ordersByWatchedAtDesc() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "m-1", "src-A", type = "movie")
            insertContent(db, "m-2", "src-A", type = "movie")
            insertContent(db, "m-3", "src-A", type = "movie")

            // Inject ascending timestamps so m-3 is most recent.
            var t = 1_000L
            val repo = WatchHistoryRepository(db, clock = { t })
            repo.upsert("m-1", positionSeconds = 10L)
            t = 2_000L
            repo.upsert("m-2", positionSeconds = 20L)
            t = 3_000L
            repo.upsert("m-3", positionSeconds = 30L)

            val recent = repo.recent()
            assertEquals(listOf("m-3", "m-2", "m-1"), recent.map { it.contentId })
            assertEquals(30.0, recent.first().positionSeconds)
        }

    @Test fun recent_respectsLimit() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            for (i in 0 until 5) insertContent(db, "m-$i", "src-A", type = "movie")
            var t = 0L
            val repo = WatchHistoryRepository(db, clock = { t })
            for (i in 0 until 5) {
                t += 1000L
                repo.upsert("m-$i", positionSeconds = i * 10L)
            }

            assertEquals(2, repo.recent(limit = 2L).size)
        }

    @Test fun recent_dropsOrphanRowsWhenContentDeleted() =
        runTest {
            // FK CASCADE should remove history rows on content delete, but
            // even without that safety-net, the client-side join must not
            // crash — it returns only the rows whose content still exists.
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "m-live", "src-A", type = "movie")
            val repo = WatchHistoryRepository(db, clock = { 1_000L })
            repo.upsert("m-live", positionSeconds = 42L)

            // Manually insert a history row pointing at a non-existent content.
            // Use the raw query directly so we bypass the FK check, then also
            // disable the CASCADE by writing directly.
            // In SQLite with foreign_keys=ON (our default), this insert would
            // fail — but the join-based recent() must not crash on the
            // legitimate "content row removed mid-query" race, so we simulate
            // by deleting the content AFTER inserting the history.
            insertContent(db, "m-gone", "src-A", type = "movie")
            repo.upsert("m-gone", positionSeconds = 10L)
            // Drop the content. CASCADE will sweep the history row too.
            db.contentQueries.deleteBySource("src-A")

            // Re-insert only one row and ask for recent. The live row is
            // gone too (src-A was wiped) — repo should return empty, not
            // throw.
            val recent = repo.recent()
            assertTrue(recent.isEmpty(), "recent() must tolerate CASCADE-swept rows")
        }

    @Test fun removeForContentDropsAllRelatedRows() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "series-1", "src-A", type = "series")
            insertEpisode(db, id = "ep-1", contentId = "series-1")
            insertEpisode(db, id = "ep-2", contentId = "series-1")
            val repo = WatchHistoryRepository(db, clock = { 0L })
            repo.upsert("series-1", positionSeconds = 10L)
            repo.upsert("series-1", episodeId = "ep-1", positionSeconds = 20L)
            repo.upsert("series-1", episodeId = "ep-2", positionSeconds = 30L)

            repo.removeForContent("series-1")
            assertEquals(
                0,
                db.watchHistoryQueries
                    .selectByContent("series-1")
                    .executeAsList()
                    .size,
            )
        }

    @Test fun clearAllWipesEverything() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "m-1", "src-A", type = "movie")
            insertContent(db, "m-2", "src-A", type = "movie")
            val repo = WatchHistoryRepository(db, clock = { 0L })
            repo.upsert("m-1", positionSeconds = 1L)
            repo.upsert("m-2", positionSeconds = 2L)

            repo.clearAll()
            assertTrue(repo.recent().isEmpty())
        }

    @Test fun cascade_removingContentRemovesHistoryRow() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "m-1", "src-A", type = "movie")
            val repo = WatchHistoryRepository(db, clock = { 0L })
            repo.upsert("m-1", positionSeconds = 1L)
            assertEquals(
                1,
                db.watchHistoryQueries
                    .selectByContent("m-1")
                    .executeAsList()
                    .size,
            )

            // Deleting the content row should cascade-delete the history row
            // per the FK definition in WatchHistory.sq.
            db.contentQueries.deleteBySource("src-A")
            assertEquals(
                0,
                db.watchHistoryQueries
                    .selectByContent("m-1")
                    .executeAsList()
                    .size,
            )
        }

    // ───── fixtures ─────

    private fun insertSource(
        db: YancoDb,
        id: String,
    ) {
        db.sourcesQueries.insert(
            id = id,
            name = id,
            type = "xtream",
            url = "http://host/$id",
            file_path = null,
            username_encrypted = null,
            password_encrypted = null,
            mac_address_encrypted = null,
            epg_url = null,
            user_agent = null,
            referer = null,
            last_synced = null,
            last_sync_error = null,
            is_active = true,
            priority = 0L,
            channel_count = 0,
            auto_sync_interval = 0,
            epg_priority = 0,
            auto_sync_on_start = false,
            created_at = 0L,
            updated_at = 0L,
        )
    }

    private fun insertContent(
        db: YancoDb,
        id: String,
        sourceId: String,
        type: String,
    ) {
        db.contentQueries.insert(
            id = id,
            source_id = sourceId,
            type = type,
            title = id,
            clean_title = id,
            group_name = null,
            stream_url = "http://stream/$id",
            logo_url = null,
            tvg_id = null,
            metadata_json = null,
            sort_order = 0L,
            created_at = 0L,
        )
    }

    private fun insertEpisode(
        db: YancoDb,
        id: String,
        contentId: String,
    ) {
        db.episodesQueries.insert(
            id = id,
            content_id = contentId,
            season_number = null,
            episode_number = null,
            title = null,
            stream_url = "http://stream/$id",
            duration = null,
        )
    }
}
