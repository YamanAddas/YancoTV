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
/** MK.35.1 — fixed clock so first-seen stamping is deterministic in tests. */
private const val FIXED_NOW = 1_700_000_000_000L

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
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })

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

    // ───── MK.36.3 — playlist banner rows ─────

    @Test
    fun `writeM3uChunk drops banner rows and keeps everything else`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })

        writer.prepareSource("s1")
        val written =
            writer.writeM3uChunk(
                "s1",
                listOf(
                    m3uEntry("##### beIN SP⚽RTS ᴴᴰ #####", "http://a/1.ts"),
                    m3uEntry("BBC News", "http://a/2.ts"),
                    m3uEntry("=== SPORTS ===", "http://a/3.ts"),
                    // Not banners: a run at one end only, and a mid-string dash.
                    m3uEntry("### SPORTS", "http://a/4.ts"),
                    m3uEntry("Ping-Pong -- Live", "http://a/5.ts"),
                ),
                now = 100L,
                sortOrderStart = 0L,
            )
        writer.finishSource("s1")

        assertEquals(3, written)
        assertEquals(3L, db.contentQueries.countBySource("s1").executeAsOne())
        val titles = db.contentQueries.selectByType("live").executeAsList().map { it.title }
        // The two banners are gone; the three near-misses all survive.
        // `### SPORTS` survives deliberately — a run at ONE end is not a banner,
        // and "no title starts with #" would have been the wrong rule to assert.
        assertEquals(setOf("BBC News", "### SPORTS", "Ping-Pong -- Live"), titles.toSet())
    }

    @Test
    fun `writeLiveChunk drops banner rows`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })

        writer.prepareSource("s1")
        writer.writeLiveChunk(
            sourceId = "s1",
            client = xtreamClient(),
            items = listOf(
                liveStream(1, "###### RELAX ᵁᴴᴰ 3840P ######"),
                liveStream(2, "Al Hayat"),
            ),
            categoryNames = mapOf("1" to "News"),
            now = 100L,
            sortOrderStart = 0L,
        )
        writer.finishSource("s1")

        assertEquals(1L, db.contentQueries.countBySource("s1").executeAsOne())
    }

    // ───── Xtream ─────

    @Test
    fun `writeLiveChunk is dupe-tolerant on duplicate stream_id`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })
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
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })
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
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })

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
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })

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
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })

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
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })

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
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })

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
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })
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
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })
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
     * MB-289 (Critical, data loss) — the counterpart to the orphan-sweep test
     * above, and the reason that sweep is guarded.
     *
     * A provider outage does not usually look like an error: the endpoint
     * answers HTTP 200 with an empty body (`[]`, `{"js":""}`, a captive-portal
     * page, or an expired subscription's empty catalog). `prepareSource` has
     * already deleted this source's rows by then, so an unguarded
     * `DELETE FROM favorites WHERE content_id NOT IN (SELECT id FROM content)`
     * matches *every* favorite and *every* watch_history row for the source.
     * The catalog comes back on the next good sync; the favorites and resume
     * points do not. Silent, permanent, user-visible data loss caused by
     * someone else's server having a bad minute.
     *
     * This pins the guard: an empty sync must leave user data completely
     * untouched.
     */
    @Test
    fun `finishSource must not sweep favorites when the provider returns an empty catalog`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        val client = xtreamClient()

        // Healthy sync publishes ch1 + ch2, and the user invests in them.
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
        db.favoritesQueries.insert(id = "fav:$ch1Id", content_id = ch1Id, list_id = "default", added_at = 200L)
        db.favoritesQueries.insert(id = "fav:$ch2Id", content_id = ch2Id, list_id = "default", added_at = 201L)
        db.watchHistoryQueries.upsert(
            id = "wh:$ch1Id",
            content_id = ch1Id,
            episode_id = null,
            position_seconds = 42,
            duration_seconds = 1800,
            watched_at = 300L,
        )

        // Provider outage: 200 OK, zero items. No chunk is written;
        // finishSource still runs.
        writer.prepareSource("s1")
        writer.finishSource("s1")

        // MB-353 — this assertion used to read `0`, and the comment above it
        // used to say "prepareSource wipes the catalog". That WAS the
        // behaviour: the deletion committed before the provider had been
        // asked for anything, so an outage cost the user their catalogue and
        // the only question left was whether favourites survived it.
        //
        // The deletion is now deferred to the first chunk that carries rows,
        // so an empty response destroys nothing at all. The favourite
        // assertions below still matter and still pass — they are simply no
        // longer the last line of defence.
        assertEquals(
            2,
            db.contentQueries.countBySource("s1").executeAsOne().toInt(),
            "an empty provider response must leave the previous catalogue standing",
        )
        assertTrue(
            db.favoritesQueries.isFavorite(ch1Id).executeAsOne(),
            "an empty provider response must NOT delete favorites — they are unrecoverable",
        )
        assertTrue(
            db.favoritesQueries.isFavorite(ch2Id).executeAsOne(),
            "an empty provider response must NOT delete favorites — they are unrecoverable",
        )
        assertEquals(
            1,
            db.watchHistoryQueries.selectByContent(ch1Id).executeAsList().size,
            "an empty provider response must NOT delete watch history (resume points)",
        )
    }

    // ───── MB-353: never destroy a catalogue before a replacement exists ─────

    /**
     * The failure that cost a real user 272,419 items: a sync that starts and
     * then never delivers.
     *
     * `prepareSource` used to DELETE and COMMIT before the provider had been
     * asked for anything, so a dead URL, an expired subscription, no network,
     * or a process kill all left the catalogue permanently empty. Observed on a
     * Fire TV, where a stalled sync left the database at 126 MB instead of
     * 352 MB and the guide down to 22 channels.
     *
     * Nothing here writes a chunk — that is the point.
     */
    @Test
    fun `a sync that never delivers rows leaves the previous catalogue intact`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        val client = xtreamClient()

        writer.prepareSource("s1")
        writer.writeLiveChunk("s1", client, listOf(liveStream(1, "Ch 1"), liveStream(2, "Ch 2")), mapOf("1" to "News"), 100L, 0L)
        writer.finishSource("s1")
        assertEquals(2, db.contentQueries.countBySource("s1").executeAsOne().toInt())

        // Next sync starts and dies before a single row arrives.
        val second = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        second.prepareSource("s1")
        second.abortSource("s1")

        assertEquals(
            2,
            db.contentQueries.countBySource("s1").executeAsOne().toInt(),
            "a sync that delivered nothing must not have destroyed anything",
        )
    }

    /** The deletion still happens — deferred, not dropped. */
    @Test
    fun `the first chunk replaces the previous catalogue rather than adding to it`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val client = xtreamClient()

        val first = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        first.prepareSource("s1")
        first.writeLiveChunk("s1", client, listOf(liveStream(1, "Ch 1"), liveStream(2, "Ch 2")), mapOf("1" to "News"), 100L, 0L)
        first.finishSource("s1")

        // A second sync where the provider now offers only one channel. Content
        // ids are deterministic and the insert is INSERT OR IGNORE, so if the
        // stale rows were not cleared first they would survive AND shadow the
        // fresh copy — the catalogue would only ever grow.
        val second = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        second.prepareSource("s1")
        second.writeLiveChunk("s1", client, listOf(liveStream(3, "Ch 3")), mapOf("1" to "News"), 200L, 0L)
        second.finishSource("s1")

        assertEquals(
            1,
            db.contentQueries.countBySource("s1").executeAsOne().toInt(),
            "the rotated-out channels must be gone, not merged with the new one",
        )
        assertTrue(db.contentQueries.selectById(ContentIds.xtreamLive("s1", "3")).executeAsOneOrNull() != null)
    }

    /** An interrupted sync is detectable; a completed one is not. */
    @Test
    fun `the in-progress marker survives an interrupted sync and clears on a completed one`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val client = xtreamClient()

        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        writer.prepareSource("s1")
        assertTrue(
            BulkContentWriter.syncWasInterrupted(database.driver, "s1"),
            "the marker must be set for the whole sync, not just on failure",
        )

        // Process death: no finishSource, no abortSource.
        assertTrue(
            BulkContentWriter.syncWasInterrupted(database.driver, "s1"),
            "a sync killed mid-flight must stay detectable — sources.channel_count still reports the old size",
        )

        val second = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        second.prepareSource("s1")
        second.writeLiveChunk("s1", client, listOf(liveStream(1, "Ch 1")), mapOf("1" to "News"), 100L, 0L)
        second.finishSource("s1")
        assertFalse(
            BulkContentWriter.syncWasInterrupted(database.driver, "s1"),
            "a completed sync must clear the marker",
        )
        assertEquals(1, db.contentQueries.countBySource("s1").executeAsOne().toInt())
    }

    /** Aborting after rows were written still cleans up the half-written set. */
    @Test
    fun `abort after a chunk has written still clears the partial catalogue`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val client = xtreamClient()

        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        writer.prepareSource("s1")
        writer.writeLiveChunk("s1", client, listOf(liveStream(1, "Ch 1")), mapOf("1" to "News"), 100L, 0L)
        assertEquals(1, db.contentQueries.countBySource("s1").executeAsOne().toInt())

        writer.abortSource("s1")

        assertEquals(
            0,
            db.contentQueries.countBySource("s1").executeAsOne().toInt(),
            "half-written rows are still swept — only the never-written case is now spared",
        )
        assertFalse(BulkContentWriter.syncWasInterrupted(database.driver, "s1"), "abort completes the cleanup, so the marker clears")
    }

    /**
     * MB-315 — the clear must span more than one transaction, and must still
     * finish the job.
     *
     * Batching is the only lever measured to shorten the write-lock hold (a
     * single statement cannot be interrupted; a loop can). The risk it
     * introduces is a loop that stops early and silently leaves half a
     * catalogue behind, which would look exactly like the data loss MB-353 was
     * filed for. So: seed more rows than one batch, and assert none survive.
     */
    @Test
    fun `the clear removes every row even when it spans many batches`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val client = xtreamClient()

        val many = (1..(BulkContentWriter.CLEAR_BATCH_ROWS * 2 + 7)).map { liveStream(it, "Ch $it") }
        val first = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        first.prepareSource("s1")
        first.writeLiveChunk("s1", client, many, mapOf("1" to "News"), 100L, 0L)
        first.finishSource("s1")
        assertEquals(many.size.toLong(), db.contentQueries.countBySource("s1").executeAsOne())

        // Next sync brings a single channel — everything else must be cleared,
        // which takes three batches at 1,000 per transaction.
        val second = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        second.prepareSource("s1")
        second.writeLiveChunk("s1", client, listOf(liveStream(99_001, "Only One")), mapOf("1" to "News"), 200L, 0L)
        second.finishSource("s1")

        assertEquals(
            1L,
            db.contentQueries.countBySource("s1").executeAsOne(),
            "a multi-batch clear that stops early would leave a partial catalogue behind",
        )
        assertEquals(
            1L,
            db.contentQueries.countByType("live").executeAsOne(),
            "and the FTS/content pair must stay in step across batches",
        )
    }

    /**
     * MB-402 — the FTS wipe must run ONCE per clear, not once per batch.
     *
     * `content_fts` is an fts4 virtual table and fts4 indexes no column, so
     * `WHERE content_id IN (...)` can only be answered by scanning every row in
     * the index. Running that inside MB-315's 1000-row loop made the clear
     * quadratic: 275 batches over a 274k catalogue each re-scanned the whole
     * index, and the clear grew to 367 s — 59% of a ten-minute sync. The schema
     * comment on the deleted `content_ad` trigger records the same blow-up from
     * MK.6.d, which is what makes this a regression of a fix rather than a new
     * discovery.
     *
     * Correctness alone cannot catch this: the batched version cleared the same
     * rows, just slowly, so every existing assertion passed. The statement count
     * IS the bug, so the statement count is what this asserts.
     */
    @Test
    fun `the FTS wipe runs once per clear, not once per batch`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val client = xtreamClient()

        val many = (1..(BulkContentWriter.CLEAR_BATCH_ROWS * 3 + 11)).map { liveStream(it, "Ch $it") }
        val seed = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        seed.prepareSource("s1")
        seed.writeLiveChunk("s1", client, many, mapOf("1" to "News"), 100L, 0L)
        seed.finishSource("s1")

        val counting = CountingDriver(database.driver)
        val second = BulkContentWriter(counting, clock = { FIXED_NOW })
        second.prepareSource("s1")
        second.writeLiveChunk("s1", client, listOf(liveStream(99_001, "Only One")), mapOf("1" to "News"), 200L, 0L)

        // More than three content batches were needed to drain the seed, so a
        // per-batch FTS delete would show up as four or more here.
        assertEquals(
            1,
            counting.ftsDeletes,
            "the fts4 wipe scans the entire index; running it per batch is the O(N^2) MB-402 measured",
        )
        assertTrue(
            counting.contentDeletes >= 4,
            "guard the guard: if the content clear stopped batching, this test would pass for the " +
                "wrong reason (was ${counting.contentDeletes} batches)",
        )
    }

    /**
     * The hoisted wipe lost its `LIMIT`, so it is now a single unbounded
     * statement — and an unbounded delete on a shared table is exactly the shape
     * that empties data belonging to someone else. The subquery is predicated on
     * `source_id`, and this pins that: a second source's searchable rows must
     * survive the first source's clear.
     */
    @Test
    fun `clearing one source leaves another source's FTS rows searchable`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        insertSource(db, id = "s2")
        val client = xtreamClient()

        val a = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        a.prepareSource("s1")
        a.writeLiveChunk("s1", client, listOf(liveStream(1, "Alpha Channel")), mapOf("1" to "News"), 100L, 0L)
        a.finishSource("s1")

        val b = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        b.prepareSource("s2")
        b.writeLiveChunk("s2", client, listOf(liveStream(2, "Beta Channel")), mapOf("1" to "News"), 100L, 0L)
        b.finishSource("s2")

        assertEquals(1, db.contentQueries.searchFts("Beta", 10).executeAsList().size)

        // Re-sync s1 only. s2 must be untouched in BOTH tables.
        val again = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        again.prepareSource("s1")
        again.writeLiveChunk("s1", client, listOf(liveStream(3, "Gamma Channel")), mapOf("1" to "News"), 200L, 0L)
        again.finishSource("s1")

        assertEquals(1L, db.contentQueries.countBySource("s2").executeAsOne())
        assertEquals(
            1,
            db.contentQueries.searchFts("Beta", 10).executeAsList().size,
            "the unbounded FTS delete must stay predicated on source_id",
        )
        // Counted straight out of `content_fts`, NOT through `searchFts`.
        // `searchFts` CROSS JOINs to `content`, so an orphaned index row is
        // invisible to it — asserting through search would pass whether the
        // row was deleted or merely stranded, which is the failure this test
        // exists to see. s1 now holds exactly one row (Gamma) and s2 one
        // (Beta); anything above two is a leak.
        assertEquals(
            2L,
            ftsRowCount(database.driver),
            "a stale index row survives the clear and is invisible to search",
        )
    }

    /**
     * A chunk carrying no rows must not trigger the clear.
     *
     * Found by a negative control, not by design: moving `clearIfFirstWrite`
     * above the `items.isEmpty()` guard broke nothing in the suite, because
     * every existing test that exercises "sync delivers nothing" never calls a
     * chunk writer at all. Providers do call it — a category that returns 200 OK
     * with zero items reaches `writeXxxChunk(items = emptyList())` — and if that
     * cleared the catalogue, MB-353 would be back through a door no test watched.
     */
    @Test
    fun `a chunk with no rows must not clear the catalogue`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val client = xtreamClient()

        val first = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        first.prepareSource("s1")
        first.writeLiveChunk("s1", client, listOf(liveStream(1, "Ch 1")), mapOf("1" to "News"), 100L, 0L)
        first.finishSource("s1")

        val second = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        second.prepareSource("s1")
        second.writeLiveChunk("s1", client, emptyList(), mapOf("1" to "News"), 200L, 0L)
        second.abortSource("s1")

        assertEquals(
            1L,
            db.contentQueries.countBySource("s1").executeAsOne(),
            "an empty chunk carries no replacement, so it must destroy nothing",
        )
    }

    /**
     * The search index must match the catalogue after a replacing sync.
     *
     * Also found by a negative control: swapping the order of the two DELETEs in
     * the batched clear (content before FTS) orphans every batch's index rows,
     * and the whole suite stayed green because nothing asserted on FTS at all.
     * The user-visible symptom would be search returning titles the catalogue no
     * longer has, plus duplicates once finishSource repopulates.
     */
    @Test
    fun `a replacing sync leaves the search index consistent`() {
        val database = testDatabase()
        val db = database.db
        insertSource(db)
        val client = xtreamClient()

        val first = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        first.prepareSource("s1")
        first.writeLiveChunk("s1", client, listOf(liveStream(1, "Zebra"), liveStream(2, "Keeper")), mapOf("1" to "News"), 100L, 0L)
        first.finishSource("s1")
        assertEquals(1, db.contentQueries.searchFts("Zebra", 50).executeAsList().size)

        // Second sync drops Zebra, KEEPS Keeper (same deterministic id), adds one.
        // Keeper is the load-bearing case: a channel present in both syncs is the
        // only way an uncleared index becomes visible, because searchFts INNER
        // JOINs content and so hides orphan rows whose content is gone. Leave it
        // out and index rows can pile up unnoticed forever.
        val second = BulkContentWriter(database.driver, clock = { FIXED_NOW })
        second.prepareSource("s1")
        second.writeLiveChunk("s1", client, listOf(liveStream(2, "Keeper"), liveStream(3, "Aardvark")), mapOf("1" to "News"), 200L, 0L)
        second.finishSource("s1")

        assertEquals(
            0,
            db.contentQueries.searchFts("Zebra", 50).executeAsList().size,
            "a title the provider dropped must not remain findable",
        )
        assertEquals(
            1,
            db.contentQueries.searchFts("Keeper", 50).executeAsList().size,
            "a channel present in BOTH syncs must be findable exactly once — twice means its old index row was never cleared",
        )
        assertEquals(
            1,
            db.contentQueries.searchFts("Aardvark", 50).executeAsList().size,
            "the new title must be findable exactly once",
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
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })
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
        val writer = BulkContentWriter(database.driver, clock = { FIXED_NOW })
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

    /** Direct `content_fts` row count — see the note at its call site. */
    private fun ftsRowCount(driver: app.cash.sqldelight.db.SqlDriver): Long = driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM content_fts",
        { cursor ->
            cursor.next()
            app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        0,
    ).value

    /**
     * Counts the two DELETE shapes the clear issues, so MB-402's claim can be
     * asserted rather than described. Delegation is by `by`, so any driver
     * method this test does not care about keeps its real behaviour and new
     * SqlDriver members do not silently become no-ops.
     */
    private class CountingDriver(private val delegate: app.cash.sqldelight.db.SqlDriver) : app.cash.sqldelight.db.SqlDriver by delegate {
        var ftsDeletes = 0
            private set
        var contentDeletes = 0
            private set

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
        ): app.cash.sqldelight.db.QueryResult<Long> {
            if (sql.startsWith("DELETE FROM content_fts")) ftsDeletes++
            if (sql.startsWith("DELETE FROM content WHERE id IN")) contentDeletes++
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }
}
