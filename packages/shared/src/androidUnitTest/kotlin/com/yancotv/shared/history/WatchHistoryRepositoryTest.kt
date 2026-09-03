package com.yancotv.shared.history

import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.sources.testDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

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
    @Test fun upsertWritesResumePointAtContentLevel() = runTest {
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "movie-1", "src-A", type = "movie")
        val repo = WatchHistoryRepository(db, clock = { 1_000L })

        repo.upsert(contentId = "movie-1", positionSeconds = 120L, durationSeconds = 7200L)

        assertEquals(120L, repo.positionFor("movie-1"))
    }

    @Test fun upsertWithEpisodeIdUsesDistinctRowKey() = runTest {
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

    @Test fun positionFor_ignoresEpisodeRowsWhenNoContainerRow() = runTest {
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

    @Test fun positionFor_returnsContainerRowWhenBothExist() = runTest {
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "series-1", "src-A", type = "series")
        insertEpisode(db, id = "ep-1", contentId = "series-1")
        val repo = WatchHistoryRepository(db, clock = { 0L })

        repo.upsert("series-1", episodeId = null, positionSeconds = 42L)
        repo.upsert("series-1", episodeId = "ep-1", positionSeconds = 900L)

        assertEquals(42L, repo.positionFor("series-1"))
    }

    // ───── Finished-row null return (MB-VOD-LOOP) ─────

    @Test fun positionFor_returnsNull_whenContentRowIsFinished() = runTest {
        // The bug: after a movie was watched to credits, the row sat at
        // position=duration. Re-tap → loadCurrent seeks to that offset
        // → STATE_ENDED fires immediately → user is stuck. The repo now
        // returns null for rows that hit the 95% threshold so the player
        // starts the title from the beginning instead of seeking to the
        // credits.
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "movie-1", "src-A", type = "movie")
        val repo = WatchHistoryRepository(db, clock = { 0L })

        repo.upsert("movie-1", positionSeconds = 6900L, durationSeconds = 7200L)
        assertNull(
            repo.positionFor("movie-1"),
            "≥95% must be treated as finished — caller starts the title fresh",
        )
    }

    @Test fun positionFor_returnsValue_justBelow95Percent() = runTest {
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "movie-1", "src-A", type = "movie")
        val repo = WatchHistoryRepository(db, clock = { 0L })

        // 94.9% — under the threshold.
        repo.upsert("movie-1", positionSeconds = 6831L, durationSeconds = 7200L)
        assertEquals(6831L, repo.positionFor("movie-1"))
    }

    @Test fun positionFor_returnsValue_whenDurationUnknown() = runTest {
        // Null duration disables the ratio check — the explicit
        // markCurrentCompleted() path covers these on STATE_ENDED.
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "movie-1", "src-A", type = "movie")
        val repo = WatchHistoryRepository(db, clock = { 0L })

        repo.upsert("movie-1", positionSeconds = 9_999L, durationSeconds = null)
        assertEquals(9_999L, repo.positionFor("movie-1"))
    }

    @Test fun positionForEpisode_returnsNull_whenRowIsFinished() = runTest {
        // Same rule on the episode-keyed lookup. This is the path that
        // chained the autoplay loop for binge-watched series.
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "series-1", "src-A", type = "series")
        insertEpisode(db, id = "ep-1", contentId = "series-1")
        val repo = WatchHistoryRepository(db, clock = { 0L })

        repo.upsert("series-1", episodeId = "ep-1", positionSeconds = 1750L, durationSeconds = 1800L)
        assertNull(
            repo.positionForEpisode("ep-1"),
            "finished episode row must return null — autoplay loop guard",
        )
    }

    @Test fun positionForEpisode_returnsValue_whenMidEpisode() = runTest {
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "series-1", "src-A", type = "series")
        insertEpisode(db, id = "ep-1", contentId = "series-1")
        val repo = WatchHistoryRepository(db, clock = { 0L })

        repo.upsert("series-1", episodeId = "ep-1", positionSeconds = 600L, durationSeconds = 1800L)
        assertEquals(600L, repo.positionForEpisode("ep-1"))
    }

    @Test fun positionForEpisode_returnsValue_whenDurationUnknown() = runTest {
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "series-1", "src-A", type = "series")
        insertEpisode(db, id = "ep-1", contentId = "series-1")
        val repo = WatchHistoryRepository(db, clock = { 0L })

        repo.upsert("series-1", episodeId = "ep-1", positionSeconds = 1_800L, durationSeconds = null)
        assertEquals(1_800L, repo.positionForEpisode("ep-1"))
    }

    // ───── hasAnyForContent ─────

    @Test fun hasAnyForContent_falseWhenNoRows() = runTest {
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "movie-1", "src-A", type = "movie")
        val repo = WatchHistoryRepository(db, clock = { 0L })

        assertEquals(false, repo.hasAnyForContent("movie-1"))
    }

    @Test fun hasAnyForContent_trueAfterContentRowUpsert() = runTest {
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "movie-1", "src-A", type = "movie")
        val repo = WatchHistoryRepository(db, clock = { 0L })

        repo.upsert("movie-1", positionSeconds = 60L, durationSeconds = 7200L)
        assertEquals(true, repo.hasAnyForContent("movie-1"))
    }

    @Test fun hasAnyForContent_trueAfterEpisodeRowUpsert() = runTest {
        // Episode rows write seriesId as content_id (FK target), so a
        // series with any episode history also reports true here. Used by
        // the detail screen to decide whether to show "Reset progress".
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "series-1", "src-A", type = "series")
        insertEpisode(db, id = "ep-1", contentId = "series-1")
        val repo = WatchHistoryRepository(db, clock = { 0L })

        repo.upsert("series-1", episodeId = "ep-1", positionSeconds = 300L, durationSeconds = 1800L)
        assertEquals(true, repo.hasAnyForContent("series-1"))
    }

    @Test fun hasAnyForContent_falseAfterRemoveForContent() = runTest {
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "series-1", "src-A", type = "series")
        insertEpisode(db, id = "ep-1", contentId = "series-1")
        val repo = WatchHistoryRepository(db, clock = { 0L })

        repo.upsert("series-1", episodeId = "ep-1", positionSeconds = 300L)
        repo.removeForContent("series-1")
        assertEquals(false, repo.hasAnyForContent("series-1"), "reset wipe must clear hasAny flag")
    }

    @Test fun upsertOnSameKeyReplaces() = runTest {
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

    @Test fun recent_ordersByWatchedAtDesc() = runTest {
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

    @Test fun recent_respectsLimit() = runTest {
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

    @Test fun recent_dropsOrphanRowsWhenContentDeleted() = runTest {
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

    @Test fun removeForContentDropsAllRelatedRows() = runTest {
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

    @Test fun clearAllWipesEverything() = runTest {
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

    /**
     * MK.23.D.4 — `recent()` must not crash on orphan history rows.
     *
     * Schema declares `watch_history.content_id` as `REFERENCES
     * content(id) ON DELETE CASCADE`, so under normal FK enforcement
     * an orphan row can't exist. But:
     *   - During a sync, `BulkContentWriter.prepareSource()` toggles
     *     PRAGMA foreign_keys = OFF (MB-220 fix). If the process
     *     crashes mid-sync, the next launch sees content rows missing
     *     for one source while watch_history rows still reference
     *     them — until `finishSource`'s orphan sweep runs.
     *   - Future code that disables FK for any reason (corruption
     *     recovery, batch import) creates the same window.
     *
     * The repo's `recent()` does a client-side LEFT-JOIN-like via
     * `mapNotNull` — it skips watch_history rows whose `content_id`
     * doesn't resolve. Pin that contract so a future refactor that
     * changes the join shape (e.g., flatMap or a SQL JOIN that NPEs
     * on missing rows) doesn't crash the home screen.
     */
    @Test fun recent_silentlyDropsOrphanRowsWhoseContentIsMissing() = runTest {
        val database = com.yancotv.shared.sources.testDatabase()
        val db = database.db
        insertSource(db, "src-A")
        insertContent(db, "m-1", "src-A", type = "movie")
        val repo = WatchHistoryRepository(db, clock = { 1_000L })

        // Real watched movie with a real content row.
        repo.upsert("m-1", positionSeconds = 60L)
        // Manually-injected orphan: an episode-like history row
        // pointing at a content_id that doesn't exist. Achieved by
        // toggling FK off for the insert, mirroring the production
        // window described in the docstring above.
        database.driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
        database.driver.execute(
            null,
            "INSERT INTO watch_history (id, content_id, episode_id, " +
                "position_seconds, duration_seconds, watched_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            6,
        ) {
            bindString(0, "wh:orphan-series:ep-1")
            bindString(1, "orphan-series") // content_id pointing at nothing
            bindString(2, "ep-1")
            bindLong(3, 90L)
            bindLong(4, 1800L)
            bindLong(5, 2_000L)
        }
        database.driver.execute(null, "PRAGMA foreign_keys = ON", 0)

        // Sanity — both rows exist in watch_history.
        assertEquals(2, db.watchHistoryQueries.selectRecent(50L).executeAsList().size)

        // recent() must not throw, must skip the orphan, must return
        // only the real entry.
        val result = repo.recent()
        assertEquals(1, result.size, "recent() must drop the orphan row")
        assertEquals("m-1", result[0].contentId)
    }

    @Test fun cascade_removingContentRemovesHistoryRow() = runTest {
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

    // ───── MK.28.1 — batched reactive tile-progress flows ─────

    @Test fun entriesByContentFlow_emptyInputEmitsEmptyMap() = runTest {
        val db = testDb()
        val repo = WatchHistoryRepository(db, clock = { 0L })

        val snapshot = repo.entriesByContentFlow(emptySet()).first()
        assertTrue(snapshot.isEmpty(), "empty input must short-circuit and not hit SQL")
    }

    @Test fun entriesByContentFlow_returnsContainerRowPreferredOverEpisode() = runTest {
        // A series with both a container-level row (legacy / manual) AND an
        // episode-level row must report the CONTAINER row's progress on its
        // tile. Otherwise tile progress would jitter between container and
        // episode depending on insertion order. The same rule the play-side
        // positionFor() enforces.
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "series-1", "src-A", type = "series")
        insertEpisode(db, id = "ep-1", contentId = "series-1")
        var t = 1_000L
        val repo = WatchHistoryRepository(db, clock = { t })

        // Episode row inserted FIRST (so it's the "newest" by watched_at).
        t = 2_000L
        repo.upsert("series-1", episodeId = "ep-1", positionSeconds = 600L, durationSeconds = 1800L)
        // Container row inserted later — still wins despite identical watched_at.
        t = 1_500L
        repo.upsert("series-1", positionSeconds = 42L, durationSeconds = 9000L)

        val snapshot = repo.entriesByContentFlow(setOf("series-1")).first()
        val entry = assertNotNull(snapshot["series-1"], "series-1 must be present")
        assertEquals(42L, entry.positionSeconds, "container row must win over episode row")
        assertNull(entry.episodeId, "container row has no episode_id")
    }

    @Test fun entriesByContentFlow_fallsBackToMostRecentEpisode() = runTest {
        // The series-tile Netflix experience: no container row → show the
        // progress of the most-recent watched episode. This is what makes
        // a Movies/Series wheel tile show "halfway through E5" rather than
        // "no progress" when the user has been bingeing.
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "series-1", "src-A", type = "series")
        insertEpisode(db, id = "ep-1", contentId = "series-1")
        insertEpisode(db, id = "ep-2", contentId = "series-1")
        var t = 1_000L
        val repo = WatchHistoryRepository(db, clock = { t })

        repo.upsert("series-1", episodeId = "ep-1", positionSeconds = 100L, durationSeconds = 1800L)
        t = 2_000L
        repo.upsert("series-1", episodeId = "ep-2", positionSeconds = 700L, durationSeconds = 1800L)

        val snapshot = repo.entriesByContentFlow(setOf("series-1")).first()
        val entry = assertNotNull(snapshot["series-1"])
        assertEquals("ep-2", entry.episodeId, "most-recent episode row should drive series tile progress")
        assertEquals(700L, entry.positionSeconds)
    }

    @Test fun entriesByContentFlow_returnsMovieRow() = runTest {
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "movie-1", "src-A", type = "movie")
        val repo = WatchHistoryRepository(db, clock = { 1_000L })

        repo.upsert("movie-1", positionSeconds = 90L, durationSeconds = 5400L)

        val snapshot = repo.entriesByContentFlow(setOf("movie-1", "movie-unknown")).first()
        assertEquals(1, snapshot.size, "unknown ids must drop, not 404 the lookup")
        val entry = assertNotNull(snapshot["movie-1"])
        assertEquals(90L, entry.positionSeconds)
        assertNull(entry.episodeId)
    }

    @Test fun entriesByContentFlow_includesFinishedRowsSoTileShowsWatchedCheck() = runTest {
        // The play-time positionFor() returns null on ≥95% rows to avoid
        // seeking to credits. But the tile UI still needs to KNOW the row
        // is finished so it can paint a ✓ instead of a progress bar. The
        // batched flow must surface finished rows, NOT filter them away.
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "movie-1", "src-A", type = "movie")
        val repo = WatchHistoryRepository(db, clock = { 1_000L })

        repo.upsert("movie-1", positionSeconds = 6900L, durationSeconds = 7200L)

        val snapshot = repo.entriesByContentFlow(setOf("movie-1")).first()
        val entry = assertNotNull(snapshot["movie-1"], "finished rows must still appear in tile-progress lookup")
        assertTrue(entry.isFinished(), "tile UI uses isFinished() to swap stripe → ✓")
    }

    @Test fun entriesByContentFlow_reemitsOnUpsert() = runTest {
        // Reactive contract: a write must propagate to subscribed tiles
        // without any caller-side refresh. This is the same SQLDelight
        // notification graph that powers Home Continue Watching.
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "movie-1", "src-A", type = "movie")
        var t = 1_000L
        val repo = WatchHistoryRepository(db, clock = { t })

        repo.upsert("movie-1", positionSeconds = 60L, durationSeconds = 3600L)
        val first = repo.entriesByContentFlow(setOf("movie-1")).first()
        assertEquals(60L, first["movie-1"]?.positionSeconds)

        t = 2_000L
        repo.upsert("movie-1", positionSeconds = 1200L, durationSeconds = 3600L)
        val second = repo.entriesByContentFlow(setOf("movie-1")).first()
        assertEquals(1200L, second["movie-1"]?.positionSeconds, "flow must re-emit on upsert")
    }

    @Test fun allProgressFlow_returnsEveryWatchedTitleKeyedByContentId() = runTest {
        // MB-374 — the browse coverflow uses this INSTEAD of an IN-list of
        // every loaded tile, so a large category can page past 1000 items
        // without overflowing SQLite's bind-variable limit. The whole table
        // comes back keyed by content_id; unwatched titles are simply absent.
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "movie-1", "src-A", type = "movie")
        insertContent(db, "series-1", "src-A", type = "series")
        insertContent(db, "movie-unwatched", "src-A", type = "movie")
        insertEpisode(db, id = "ep-1", contentId = "series-1")
        var t = 1_000L
        val repo = WatchHistoryRepository(db, clock = { t })

        repo.upsert("movie-1", positionSeconds = 90L, durationSeconds = 5400L)
        t = 2_000L
        repo.upsert("series-1", episodeId = "ep-1", positionSeconds = 700L, durationSeconds = 1800L)

        val snapshot = repo.allProgressFlow().first()
        assertEquals(2, snapshot.size, "only watched titles appear; the unwatched movie must be absent")
        assertEquals(90L, snapshot["movie-1"]?.positionSeconds)
        assertEquals(700L, snapshot["series-1"]?.positionSeconds)
        assertNull(snapshot["movie-unwatched"], "a title with no watch_history row must not appear")
    }

    @Test fun allProgressFlow_containerRowPreferredOverEpisode() = runTest {
        // Shares resolveContentProgress with entriesByContentFlow, so the
        // container-preferred rule must hold identically — this pins that the
        // full-table feed goes through the same resolver (AGENTS rule 8).
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "series-1", "src-A", type = "series")
        insertEpisode(db, id = "ep-1", contentId = "series-1")
        var t = 2_000L
        val repo = WatchHistoryRepository(db, clock = { t })
        repo.upsert("series-1", episodeId = "ep-1", positionSeconds = 600L, durationSeconds = 1800L)
        t = 1_500L
        repo.upsert("series-1", positionSeconds = 42L, durationSeconds = 9000L)

        val entry = assertNotNull(repo.allProgressFlow().first()["series-1"])
        assertEquals(42L, entry.positionSeconds, "container row must win over episode row")
        assertNull(entry.episodeId)
    }

    @Test fun allProgressFlow_reemitsOnUpsert() = runTest {
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "movie-1", "src-A", type = "movie")
        var t = 1_000L
        val repo = WatchHistoryRepository(db, clock = { t })

        repo.upsert("movie-1", positionSeconds = 60L, durationSeconds = 3600L)
        assertEquals(60L, repo.allProgressFlow().first()["movie-1"]?.positionSeconds)

        t = 2_000L
        repo.upsert("movie-1", positionSeconds = 1200L, durationSeconds = 3600L)
        assertEquals(1200L, repo.allProgressFlow().first()["movie-1"]?.positionSeconds, "flow must re-emit on upsert")
    }

    @Test fun entriesByEpisodeFlow_keysByEpisodeId() = runTest {
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "series-1", "src-A", type = "series")
        insertEpisode(db, id = "ep-S01E01", contentId = "series-1")
        insertEpisode(db, id = "ep-S01E02", contentId = "series-1")
        val repo = WatchHistoryRepository(db, clock = { 1_000L })

        repo.upsert("series-1", episodeId = "ep-S01E01", positionSeconds = 300L, durationSeconds = 1800L)
        repo.upsert("series-1", episodeId = "ep-S01E02", positionSeconds = 600L, durationSeconds = 1800L)

        val snapshot = repo.entriesByEpisodeFlow(setOf("ep-S01E01", "ep-S01E02")).first()
        assertEquals(2, snapshot.size)
        assertEquals(300L, snapshot["ep-S01E01"]?.positionSeconds)
        assertEquals(600L, snapshot["ep-S01E02"]?.positionSeconds)
    }

    /**
     * The read-once form SwiftUI uses, which has no Flow. Same rows, same
     * keys — it shares [entriesByEpisodeFlow]'s mapper so the two cannot
     * drift apart.
     */
    @Test fun entriesByEpisode_matchesTheFlow() = runTest {
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "series-1", "src-A", type = "series")
        insertEpisode(db, id = "ep-S01E01", contentId = "series-1")
        insertEpisode(db, id = "ep-S01E02", contentId = "series-1")
        val repo = WatchHistoryRepository(db, clock = { 1_000L })

        repo.upsert("series-1", episodeId = "ep-S01E01", positionSeconds = 300L, durationSeconds = 1800L)
        repo.upsert("series-1", episodeId = "ep-S01E02", positionSeconds = 600L, durationSeconds = 1800L)

        val once = repo.entriesByEpisode(setOf("ep-S01E01", "ep-S01E02"))
        assertEquals(repo.entriesByEpisodeFlow(setOf("ep-S01E01", "ep-S01E02")).first(), once)
        assertEquals(300L, once["ep-S01E01"]?.positionSeconds)
        // The series is the content row; the episode is the episode row.
        // Writing the episode's id into content_id — which iOS did — leaves
        // a row that `selectRecentWithContent`'s inner join throws away.
        assertEquals("series-1", once["ep-S01E01"]?.contentId)
        assertTrue(repo.entriesByEpisode(emptySet()).isEmpty())
    }

    @Test fun entriesByEpisodeFlow_emptyInputEmitsEmptyMap() = runTest {
        val db = testDb()
        val repo = WatchHistoryRepository(db, clock = { 0L })
        assertTrue(repo.entriesByEpisodeFlow(emptySet()).first().isEmpty())
    }

    @Test fun entriesByEpisodeFlow_doesNotReturnContentLevelRows() = runTest {
        // SQLite's `WHERE col IN (...)` skips NULLs in `col`, so content-
        // level rows (episode_id IS NULL) must not leak into the episode
        // lookup. Pin that contract — a future "selectByEpisodeIds" that
        // forgets the IS NOT NULL would otherwise overshoot.
        val db = testDb()
        insertSource(db, "src-A")
        insertContent(db, "movie-1", "src-A", type = "movie")
        val repo = WatchHistoryRepository(db, clock = { 1_000L })

        repo.upsert("movie-1", positionSeconds = 60L, durationSeconds = 3600L)

        val snapshot = repo.entriesByEpisodeFlow(setOf("ep-bogus")).first()
        assertTrue(snapshot.isEmpty(), "content-level rows must not appear in episode lookup")
    }

    // ───── MK.28.1 — WatchProgress pure-data behaviour ─────

    @Test fun watchProgress_ratioIsPositionOverDuration() = runTest {
        val p = WatchProgress(
            contentId = "x",
            episodeId = null,
            positionSeconds = 300L,
            durationSeconds = 600L,
            watchedAt = 0L,
        )
        assertEquals(0.5f, p.ratio)
    }

    @Test fun watchProgress_ratioZeroWhenDurationUnknown() = runTest {
        val p = WatchProgress("x", null, positionSeconds = 9_999L, durationSeconds = null, watchedAt = 0L)
        assertEquals(0f, p.ratio, "no denominator → 0 (let UI decide whether to render stripe)")
    }

    @Test fun watchProgress_ratioCoercedToZeroOneRange() = runTest {
        val over = WatchProgress("x", null, positionSeconds = 1_000L, durationSeconds = 100L, watchedAt = 0L)
        assertEquals(1f, over.ratio, "ratio must coerce in 0..1 even if position > duration")
        val negative = WatchProgress("x", null, positionSeconds = -50L, durationSeconds = 100L, watchedAt = 0L)
        assertEquals(0f, negative.ratio)
    }

    @Test fun watchProgress_isFinishedAt95Percent() = runTest {
        val finished = WatchProgress("x", null, positionSeconds = 950L, durationSeconds = 1000L, watchedAt = 0L)
        assertTrue(finished.isFinished())
        val midway = WatchProgress("x", null, positionSeconds = 949L, durationSeconds = 1000L, watchedAt = 0L)
        assertFalse(midway.isFinished())
    }

    @Test fun watchProgress_remainingSecondsClampsAtZero() = runTest {
        val past = WatchProgress("x", null, positionSeconds = 2000L, durationSeconds = 1000L, watchedAt = 0L)
        assertEquals(0L, past.remainingSeconds())
        val mid = WatchProgress("x", null, positionSeconds = 300L, durationSeconds = 1000L, watchedAt = 0L)
        assertEquals(700L, mid.remainingSeconds())
        val unknown = WatchProgress("x", null, positionSeconds = 300L, durationSeconds = null, watchedAt = 0L)
        assertNull(unknown.remainingSeconds(), "unknown duration → null, let UI fall through to 'Resume'")
    }

    // ───── fixtures ─────

    private fun insertSource(db: YancoDb, id: String) {
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

    private fun insertContent(db: YancoDb, id: String, sourceId: String, type: String) {
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

    private fun insertEpisode(db: YancoDb, id: String, contentId: String) {
        db.episodesQueries.insert(
            id = id,
            content_id = contentId,
            season_number = null,
            episode_number = null,
            title = null,
            stream_url = "http://stream/$id",
            duration = null,
            still_url = null,
            air_date = null,
        )
    }
}
