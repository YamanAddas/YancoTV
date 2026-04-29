package com.yancotv.shared.sources

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.parsers.M3uEntry
import com.yancotv.shared.stalker.StalkerChannel
import com.yancotv.shared.stalker.StalkerSeriesItem
import com.yancotv.shared.stalker.StalkerVodItem
import com.yancotv.shared.xtream.XtreamClient
import com.yancotv.shared.xtream.XtreamClientOptions
import com.yancotv.shared.xtream.XtreamLiveStream
import com.yancotv.shared.xtream.XtreamSeriesInfo
import com.yancotv.shared.xtream.XtreamVodStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the dupe-tolerance contract added to `BulkContentWriter`. Providers
 * routinely send the same stream twice (M3u's parser already logs "duplicate
 * URLs collapsed"), and the FNV-1a 32-bit hash used for M3U IDs has
 * birthday-collision probability that matters on >10k-entry playlists. Before
 * `INSERT OR IGNORE` a single duplicate PK failed the 80-row INSERT, rolled
 * back the chunk, and `abortSource()` wiped every row written so far —
 * surfacing as a "sync mysteriously failed" error to the user.
 *
 * These tests feed each writer a batch containing a duplicate and assert the
 * sync completes with the expected deduped row count + FTS consistency.
 */
class BulkContentWriterTest {
    private val noopHttp =
        object : HttpClient {
            override suspend fun getJson(url: String, options: HttpRequestOptions): Any? = null

            override suspend fun getText(url: String, options: HttpRequestOptions): String = ""
        }

    private fun insertSource(db: com.yancotv.shared.db.YancoDb, id: String = "s1") {
        db.sourcesQueries.insert(
            id = id,
            name = "Test",
            type = "m3u_url",
            url = "http://x",
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
            priority = 0,
            channel_count = 0,
            auto_sync_interval = 0,
            epg_priority = 0,
            auto_sync_on_start = false,
            created_at = 1L,
            updated_at = 1L,
        )
    }

    private fun m3uEntry(title: String, url: String, group: String = "News") = M3uEntry(
        duration = -1.0,
        title = title,
        groupTitle = group,
        tvgId = "",
        tvgName = "",
        tvgLogo = "",
        streamUrl = url,
        rawAttributes = "",
    )

    private fun liveStream(id: Int, name: String) = XtreamLiveStream(
        num = id,
        name = name,
        streamType = "live",
        streamId = id,
        streamIcon = "",
        epgChannelId = "",
        added = "",
        categoryId = "1",
        categoryIds = emptyList(),
        customSid = "",
        tvArchive = 0,
        directSource = "",
        tvArchiveDuration = 0,
    )

    private fun vodStream(id: Int, name: String) = XtreamVodStream(
        num = id,
        name = name,
        streamType = "movie",
        streamId = id,
        streamIcon = "",
        rating = "",
        added = "",
        categoryId = "1",
        containerExtension = "mp4",
        directSource = "",
    )

    private fun seriesInfo(id: Int, name: String) = XtreamSeriesInfo(
        num = id,
        name = name,
        seriesId = id,
        cover = "",
        plot = "",
        cast = "",
        director = "",
        genre = "",
        releaseDate = "",
        rating = "",
        categoryId = "1",
        lastModified = "",
    )

    private fun stalkerChannel(id: Int, name: String) = StalkerChannel(
        id = id,
        name = name,
        cmd = "http://s/$id",
        tvGenreId = "1",
        logo = "",
        epgId = "",
        number = id,
        tvArchive = 0,
        tvArchiveDuration = 0,
    )

    private fun stalkerVod(id: Int, name: String) = StalkerVodItem(
        id = id,
        name = name,
        cmd = "http://v/$id",
        categoryId = "1",
        logo = "",
        description = "",
    )

    private fun stalkerSeries(id: Int, name: String) = StalkerSeriesItem(
        id = id,
        name = name,
        categoryId = "1",
        cover = "",
        plot = "",
        genre = "",
    )

    private fun xtreamClient(sourceId: String = "s1") = XtreamClient(
        url = "http://example.test",
        username = "u",
        password = "p",
        options = XtreamClientOptions(http = noopHttp),
    ).also { _ -> sourceId } // pin sourceId for symmetry with sut calls

    // ───── M3U ─────

    @Test
    fun `writeM3uChunk is dupe-tolerant — duplicate title+URL is ignored, sync completes`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver)

        writer.prepareSource("s1")
        val items =
            listOf(
                m3uEntry("BBC News", "http://a/1.ts"),
                m3uEntry("CNN", "http://a/2.ts"),
                // Exact dupe — same hash → same ID. Without OR IGNORE this fails the whole chunk.
                m3uEntry("BBC News", "http://a/1.ts"),
                m3uEntry("Sky", "http://a/3.ts"),
            )
        val written = writer.writeM3uChunk("s1", items, now = 100L, sortOrderStart = 0L)
        writer.finishSource("s1")

        // Writer returns the nominal count; actual DB state is what matters.
        assertEquals(4, written)
        assertEquals(3L, db.contentQueries.countBySource("s1").executeAsOne())
        // FTS must be consistent — the deduped row must still be searchable.
        val hits = db.contentQueries.searchFts("bbc", 50).executeAsList()
        assertEquals(1, hits.size)
    }

    // ───── Xtream ─────

    @Test
    fun `writeLiveChunk is dupe-tolerant on duplicate stream_id`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver)
        val client = xtreamClient()

        writer.prepareSource("s1")
        val items =
            listOf(
                liveStream(1, "Channel 1"),
                liveStream(2, "Channel 2"),
                liveStream(1, "Channel 1 Duplicate"), // same stream_id → same content id
                liveStream(3, "Channel 3"),
            )
        writer.writeLiveChunk(
            sourceId = "s1",
            client = client,
            items = items,
            categoryNames = mapOf("1" to "News"),
            now = 100L,
            sortOrderStart = 0L,
        )
        writer.finishSource("s1")

        assertEquals(3L, db.contentQueries.countBySource("s1").executeAsOne())
        // First write wins per SQLite `INSERT OR IGNORE` semantics.
        val row = db.contentQueries.selectById(ContentIds.xtreamLive("s1", "1")).executeAsOneOrNull()
        assertNotNull(row)
        assertEquals("Channel 1", row.title)
    }

    @Test
    fun `writeVodChunk is dupe-tolerant on duplicate stream_id`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver)
        val client = xtreamClient()

        writer.prepareSource("s1")
        val items =
            listOf(
                vodStream(10, "Movie A"),
                vodStream(10, "Movie A dupe"),
                vodStream(11, "Movie B"),
            )
        writer.writeVodChunk(
            sourceId = "s1",
            client = client,
            items = items,
            categoryNames = mapOf("1" to "Drama"),
            now = 100L,
            sortOrderStart = 0L,
        )
        writer.finishSource("s1")

        assertEquals(2L, db.contentQueries.countBySource("s1").executeAsOne())
    }

    @Test
    fun `writeSeriesChunk is dupe-tolerant on duplicate series_id`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver)

        writer.prepareSource("s1")
        val items =
            listOf(
                seriesInfo(100, "Show A"),
                seriesInfo(100, "Show A dupe"),
                seriesInfo(101, "Show B"),
                seriesInfo(102, "Show C"),
            )
        writer.writeSeriesChunk(
            sourceId = "s1",
            items = items,
            categoryNames = mapOf("1" to "Drama"),
            now = 100L,
            sortOrderStart = 0L,
        )
        writer.finishSource("s1")

        assertEquals(3L, db.contentQueries.countBySource("s1").executeAsOne())
    }

    // ───── Stalker ─────

    @Test
    fun `writeStalkerLiveChunk is dupe-tolerant on duplicate channel id`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver)

        writer.prepareSource("s1")
        val items =
            listOf(
                stalkerChannel(1, "Ch1"),
                stalkerChannel(2, "Ch2"),
                stalkerChannel(1, "Ch1 dupe"),
            )
        writer.writeStalkerLiveChunk(
            sourceId = "s1",
            items = items,
            categoryNames = mapOf("1" to "News"),
            now = 100L,
            sortOrderStart = 0L,
        )
        writer.finishSource("s1")

        assertEquals(2L, db.contentQueries.countBySource("s1").executeAsOne())
    }

    @Test
    fun `writeStalkerVodChunk is dupe-tolerant on duplicate vod id`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver)

        writer.prepareSource("s1")
        val items =
            listOf(
                stalkerVod(1, "V1"),
                stalkerVod(1, "V1 dupe"),
                stalkerVod(2, "V2"),
            )
        writer.writeStalkerVodChunk(
            sourceId = "s1",
            items = items,
            categoryNames = mapOf("1" to "Drama"),
            now = 100L,
            sortOrderStart = 0L,
        )
        writer.finishSource("s1")

        assertEquals(2L, db.contentQueries.countBySource("s1").executeAsOne())
    }

    @Test
    fun `writeStalkerSeriesChunk is dupe-tolerant on duplicate series id`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver)

        writer.prepareSource("s1")
        val items =
            listOf(
                stalkerSeries(1, "S1"),
                stalkerSeries(2, "S2"),
                stalkerSeries(1, "S1 dupe"),
            )
        writer.writeStalkerSeriesChunk(
            sourceId = "s1",
            items = items,
            categoryNames = mapOf("1" to "Drama"),
            now = 100L,
            sortOrderStart = 0L,
        )
        writer.finishSource("s1")

        assertEquals(2L, db.contentQueries.countBySource("s1").executeAsOne())
    }

    // ───── Large-batch correctness ─────

    @Test
    fun `writer handles a batch that spans the 80-row boundary with a dupe inside`() {
        // BulkContentWriter.BATCH_ROWS = 80; a 100-row batch exercises both
        // the full-batch `sqlBatch` path and the tail `buildInsertSql(20)`
        // path. Seed the dupe near the end of the first 80 so the failing
        // row is in the main batch, not the tail.
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver)

        writer.prepareSource("s1")
        val base = (1..100).map { m3uEntry("Ch$it", "http://a/$it.ts") }.toMutableList()
        base[79] = m3uEntry("Ch1", "http://a/1.ts") // same id as index 0 → dupe in mid-batch
        writer.writeM3uChunk("s1", base, now = 1L, sortOrderStart = 0L)
        writer.finishSource("s1")

        // 100 fed in, 1 dupe → 99 rows.
        assertEquals(99L, db.contentQueries.countBySource("s1").executeAsOne())
        // And the sync didn't wipe everything — this is the regression
        // guarantee: before INSERT OR IGNORE, this call would leave 0 rows.
        assertTrue(db.contentQueries.countBySource("s1").executeAsOne() > 0)
    }

    // ───── Favorites + watch_history survival across re-sync ─────

    /**
     * Regression: every source sync was wiping the user's favorites and
     * watch history for that source via `ON DELETE CASCADE` on
     * `favorites.content_id` and `watch_history.content_id`.
     * `prepareSource()` runs `DELETE FROM content WHERE source_id = ?` to
     * clear the prior catalog snapshot, the cascade then silently wiped
     * every dependent row, and even though the chunked re-INSERT puts the
     * same content_ids back (deterministic via `ContentIds.*`) the
     * favorites + history were already gone. Symptom: home screen
     * showing only "Recently added" because every other rail filters off
     * favorites or history that no longer exist.
     *
     * Fix toggles `PRAGMA foreign_keys = OFF` across the prepare → chunks
     * → finish window so the cascade doesn't fire on the sync's
     * delete-then-recreate. [finishSource] sweeps actual orphans
     * (content the provider rotated out) and re-enables FK.
     *
     * This test inserts a favorite + history row pointing at content
     * that the next sync still publishes (same content_id), and asserts
     * both rows survive the re-sync.
     */
    @Test
    fun `prepareSource preserves favorites and watch_history when content is recreated`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver)
        val client = xtreamClient()

        // Initial sync — 3 live channels.
        writer.prepareSource("s1")
        writer.writeLiveChunk(
            sourceId = "s1",
            client = client,
            items = listOf(liveStream(1, "Ch 1"), liveStream(2, "Ch 2"), liveStream(3, "Ch 3")),
            categoryNames = mapOf("1" to "News"),
            now = 100L,
            sortOrderStart = 0L,
        )
        writer.finishSource("s1")

        val ch1Id = ContentIds.xtreamLive("s1", "1")
        val ch2Id = ContentIds.xtreamLive("s1", "2")

        // User favorites ch1 and ch2, builds watch history on ch1.
        db.favoritesQueries.insert(id = "fav:$ch1Id", content_id = ch1Id, list_id = "default", added_at = 200L)
        db.favoritesQueries.insert(id = "fav:$ch2Id", content_id = ch2Id, list_id = "default", added_at = 201L)
        db.watchHistoryQueries.upsert(
            id = "wh:$ch1Id",
            content_id = ch1Id,
            episode_id = null,
            position_seconds = 60,
            duration_seconds = 3600,
            watched_at = 300L,
        )
        // Sanity — both favorites + history are persisted.
        assertTrue(db.favoritesQueries.isFavorite(ch1Id).executeAsOne())
        assertTrue(db.favoritesQueries.isFavorite(ch2Id).executeAsOne())
        assertEquals(1, db.watchHistoryQueries.selectByContent(ch1Id).executeAsList().size)

        // Re-sync — same channels published again.
        writer.prepareSource("s1")
        writer.writeLiveChunk(
            sourceId = "s1",
            client = client,
            items = listOf(liveStream(1, "Ch 1"), liveStream(2, "Ch 2"), liveStream(3, "Ch 3")),
            categoryNames = mapOf("1" to "News"),
            now = 200L,
            sortOrderStart = 0L,
        )
        writer.finishSource("s1")

        // The two favorites + the history row must survive.
        assertTrue(db.favoritesQueries.isFavorite(ch1Id).executeAsOne(), "ch1 favorite must survive resync")
        assertTrue(db.favoritesQueries.isFavorite(ch2Id).executeAsOne(), "ch2 favorite must survive resync")
        assertEquals(
            1,
            db.watchHistoryQueries.selectByContent(ch1Id).executeAsList().size,
            "watch_history row for ch1 must survive resync",
        )
    }

    /**
     * Companion to the survival test: when a re-sync DROPS a channel
     * (provider rotated it out), the favorite + history pointing at it
     * should be cleaned up. [finishSource]'s orphan sweep covers this —
     * with the FK off, dependents would otherwise pile up as dead
     * pointers to nothing.
     */
    @Test
    fun `finishSource sweeps orphan favorites and watch_history when content removed`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver)
        val client = xtreamClient()

        // Initial sync publishes ch1 + ch2.
        writer.prepareSource("s1")
        writer.writeLiveChunk(
            sourceId = "s1",
            client = client,
            items = listOf(liveStream(1, "Ch 1"), liveStream(2, "Ch 2")),
            categoryNames = mapOf("1" to "News"),
            now = 100L,
            sortOrderStart = 0L,
        )
        writer.finishSource("s1")

        val ch1Id = ContentIds.xtreamLive("s1", "1")
        val ch2Id = ContentIds.xtreamLive("s1", "2")

        // User favorites both, watches ch2.
        db.favoritesQueries.insert(id = "fav:$ch1Id", content_id = ch1Id, list_id = "default", added_at = 200L)
        db.favoritesQueries.insert(id = "fav:$ch2Id", content_id = ch2Id, list_id = "default", added_at = 201L)
        db.watchHistoryQueries.upsert(
            id = "wh:$ch2Id",
            content_id = ch2Id,
            episode_id = null,
            position_seconds = 30,
            duration_seconds = 1800,
            watched_at = 300L,
        )

        // Re-sync drops ch2 (provider rotated it out). ch1 stays.
        writer.prepareSource("s1")
        writer.writeLiveChunk(
            sourceId = "s1",
            client = client,
            items = listOf(liveStream(1, "Ch 1")),
            categoryNames = mapOf("1" to "News"),
            now = 200L,
            sortOrderStart = 0L,
        )
        writer.finishSource("s1")

        // ch1 is still in catalog — its favorite survives.
        assertTrue(db.favoritesQueries.isFavorite(ch1Id).executeAsOne(), "ch1 favorite must survive")
        // ch2 was dropped — finishSource's orphan sweep removes its favorite + history.
        assertTrue(!db.favoritesQueries.isFavorite(ch2Id).executeAsOne(), "ch2 favorite must be swept as orphan")
        assertEquals(
            0,
            db.watchHistoryQueries.selectByContent(ch2Id).executeAsList().size,
            "ch2 watch_history must be swept as orphan",
        )
    }

    /**
     * MK.23.D.1 — finishSource failure path.
     *
     * Today the catch block:
     *   1. ROLLBACKs the transaction (so the orphan-sweep DELETEs are
     *      rolled back too — favorites for live content are safe).
     *   2. Defensively re-creates the `content_ai` trigger so non-bulk
     *      INSERTs (M3U, Stalker) stay FTS-consistent.
     *   3. Re-enables PRAGMA foreign_keys so the rest of the connection
     *      keeps cascade semantics.
     *
     * A future refactor that drops any of those three could leave the
     * DB in a state where favorites get accidentally wiped, FTS goes
     * stale, or cascade silently stops firing — this test pins all
     * three at once.
     *
     * Failure mode: drop the `content_fts` table BEFORE finishSource
     * runs, so the `INSERT INTO content_fts SELECT…` statement throws
     * "no such table". Real-world this would surface from a corrupted
     * DB or partial schema — different cause, same catch-block path.
     */
    @Test
    fun `finishSource failure path preserves favorites and re-enables FK`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver)
        val client = xtreamClient()

        // Initial happy-path sync to seed content + a favorite.
        writer.prepareSource("s1")
        writer.writeLiveChunk(
            sourceId = "s1",
            client = client,
            items = listOf(liveStream(1, "Ch 1"), liveStream(2, "Ch 2")),
            categoryNames = mapOf("1" to "News"),
            now = 100L,
            sortOrderStart = 0L,
        )
        writer.finishSource("s1")

        val ch1Id = ContentIds.xtreamLive("s1", "1")
        db.favoritesQueries.insert(id = "fav:$ch1Id", content_id = ch1Id, list_id = "default", added_at = 1L)
        assertTrue(db.favoritesQueries.isFavorite(ch1Id).executeAsOne())

        // Now stage a finishSource failure: re-prepare (FK off, content
        // wiped, trigger dropped), write a chunk so content rows exist
        // again, then DROP content_fts to force the next INSERT INTO
        // content_fts ... SELECT to throw "no such table". The favorite
        // still exists (FK is off — wasn't cascaded by prepare).
        writer.prepareSource("s1")
        writer.writeLiveChunk(
            sourceId = "s1",
            client = client,
            items = listOf(liveStream(1, "Ch 1"), liveStream(2, "Ch 2")),
            categoryNames = mapOf("1" to "News"),
            now = 200L,
            sortOrderStart = 0L,
        )
        database.driver.execute(null, "DROP TABLE content_fts", 0)

        var caught: Throwable? = null
        try {
            writer.finishSource("s1")
        } catch (t: Throwable) {
            caught = t
        }
        assertNotNull(caught, "finishSource must rethrow on FTS failure (caller's error path needs to fire)")

        // Post-condition #1: favorite for ch1 is still there. ROLLBACK
        // covered the orphan-sweep DELETEs that finishSource queues
        // before the failure. ch1's content row exists (we wrote it),
        // so even if the orphan sweep had run, ch1 would survive — but
        // the rollback is the actual safety guarantee being tested.
        assertTrue(
            db.favoritesQueries.isFavorite(ch1Id).executeAsOne(),
            "favorites must survive a finishSource failure (catch block ROLLBACKs the tx)",
        )

        // Post-condition #2: PRAGMA foreign_keys must be back ON.
        // Verify by triggering a real cascade — recreate content_fts so
        // a fresh content row can be inserted, then delete it and watch
        // the cascade fire.
        database.driver.execute(
            null,
            "CREATE VIRTUAL TABLE content_fts USING fts4(content_id, title, clean_title, group_name)",
            0,
        )
        val probeId = "probe-after-finish-fail"
        database.driver.execute(
            null,
            "INSERT INTO content (id, source_id, type, title, clean_title, group_name, " +
                "stream_url, logo_url, tvg_id, metadata_json, sort_order, created_at) " +
                "VALUES (?, 's1', 'live', 'P', 'P', 'News', 'http://x', NULL, NULL, NULL, 999, 0)",
            1,
        ) { bindString(0, probeId) }
        db.favoritesQueries.insert(id = "fav:$probeId", content_id = probeId, list_id = "default", added_at = 999L)
        database.driver.execute(null, "DELETE FROM content WHERE id = ?", 1) { bindString(0, probeId) }
        assertFalse(
            db.favoritesQueries.isFavorite(probeId).executeAsOne(),
            "Cascade must fire after a finishSource failure — proves PRAGMA foreign_keys was re-enabled",
        )
    }

    /**
     * MK.23.C.2 — abortSource cross-source FK survival.
     *
     * Direct sibling to MB-220 (source sync was wiping favorites +
     * watch_history via FK cascade). The fix toggles
     * `PRAGMA foreign_keys = OFF` across the sync window; abortSource
     * is the error-path cleanup that must re-enable FK so the rest of
     * the connection lifetime keeps cascade semantics for actual
     * content removal. Without that re-enable, a future code path that
     * relies on cascade (e.g. user removes a source) silently fails to
     * clean up dependents.
     *
     * Scenario:
     *   1. Seed sources A + B; sync each so content rows exist.
     *   2. User favorites a channel in A, builds watch history on A,
     *      AND favorites a channel in B.
     *   3. A "sync of B" begins via prepareSource("b") (FK toggles off,
     *      content for b is wiped).
     *   4. Mid-sync the chunked write fails (simulated by calling
     *      abortSource directly).
     *   5. Assert: source A's favorites + history are intact (the
     *      abort didn't accidentally cascade through to A's content).
     *      Source B's favorite is gone (its content was wiped in
     *      prepare; the abort doesn't restore content).
     *   6. Assert: PRAGMA foreign_keys is back ON.
     *   7. Assert: cascade still fires for genuine content removal —
     *      manually DELETE A's channel content row, observe its
     *      favorite + history follow via cascade.
     */
    @Test
    fun `abortSource preserves cross-source data and re-enables FK`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db, id = "a")
        insertSource(db, id = "b")
        val writer = BulkContentWriter(database.driver)
        val client = xtreamClient()

        // Initial sync of both sources.
        writer.prepareSource("a")
        writer.writeLiveChunk(
            sourceId = "a",
            client = client,
            items = listOf(liveStream(1, "A Ch 1")),
            categoryNames = mapOf("1" to "News"),
            now = 100L,
            sortOrderStart = 0L,
        )
        writer.finishSource("a")
        writer.prepareSource("b")
        writer.writeLiveChunk(
            sourceId = "b",
            client = client,
            items = listOf(liveStream(1, "B Ch 1")),
            categoryNames = mapOf("1" to "News"),
            now = 100L,
            sortOrderStart = 0L,
        )
        writer.finishSource("b")

        val aChId = ContentIds.xtreamLive("a", "1")
        val bChId = ContentIds.xtreamLive("b", "1")

        // User favorites + history on A; favorite on B.
        db.favoritesQueries.insert(id = "fav:$aChId", content_id = aChId, list_id = "default", added_at = 200L)
        db.favoritesQueries.insert(id = "fav:$bChId", content_id = bChId, list_id = "default", added_at = 201L)
        db.watchHistoryQueries.upsert(
            id = "wh:$aChId",
            content_id = aChId,
            episode_id = null,
            position_seconds = 30,
            duration_seconds = 1800,
            watched_at = 300L,
        )

        // Sync of B begins, then mid-sync the chunk write fails. We
        // simulate the failure by calling abortSource directly after
        // prepareSource — no chunk written.
        writer.prepareSource("b")
        writer.abortSource("b")

        // Source A's data must be untouched.
        assertTrue(
            db.favoritesQueries.isFavorite(aChId).executeAsOne(),
            "Source A's favorite must survive abortSource on a different source (B)",
        )
        assertEquals(
            1,
            db.watchHistoryQueries.selectByContent(aChId).executeAsList().size,
            "Source A's watch_history must survive abortSource on a different source (B)",
        )

        // Source B's content was wiped by prepareSource; the favorite
        // pointed at content that no longer exists. With FK still off
        // mid-abort the row may be orphaned; the next finishSource on B
        // would sweep it, but that's not what's under test here. The
        // load-bearing assertion is the next one — FK must be re-armed.

        // FK must be back ON. Verify by inserting a fresh content row
        // for source A, attaching a favorite, deleting the content row,
        // and observing the favorite follows via cascade.
        val probeId = "probe-after-abort"
        database.driver.execute(
            null,
            "INSERT INTO content (id, source_id, type, title, clean_title, group_name, " +
                "stream_url, logo_url, tvg_id, metadata_json, sort_order, created_at) " +
                "VALUES (?, 'a', 'live', 'Probe', 'Probe', 'News', 'http://x', NULL, NULL, NULL, 999, 0)",
            1,
        ) { bindString(0, probeId) }
        db.favoritesQueries.insert(id = "fav:$probeId", content_id = probeId, list_id = "default", added_at = 999L)
        assertTrue(db.favoritesQueries.isFavorite(probeId).executeAsOne(), "probe favorite inserted")

        // Trigger a real cascade (not a sync delete — a direct user-style
        // removal). With FK back ON, the favorite must follow.
        database.driver.execute(null, "DELETE FROM content WHERE id = ?", 1) { bindString(0, probeId) }
        assertTrue(
            !db.favoritesQueries.isFavorite(probeId).executeAsOne(),
            "Cascade must fire after abortSource — proves PRAGMA foreign_keys was re-enabled",
        )
    }
}
