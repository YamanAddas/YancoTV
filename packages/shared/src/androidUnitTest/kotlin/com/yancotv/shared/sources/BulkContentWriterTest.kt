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
            override suspend fun getJson(
                url: String,
                options: HttpRequestOptions,
            ): Any? = null

            override suspend fun getText(
                url: String,
                options: HttpRequestOptions,
            ): String = ""
        }

    private fun insertSource(
        db: com.yancotv.shared.db.YancoDb,
        id: String = "s1",
    ) {
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
            created_at = 1L,
            updated_at = 1L,
        )
    }

    private fun m3uEntry(
        title: String,
        url: String,
        group: String = "News",
    ) = M3uEntry(
        duration = -1.0,
        title = title,
        groupTitle = group,
        tvgId = "",
        tvgName = "",
        tvgLogo = "",
        streamUrl = url,
        rawAttributes = "",
    )

    private fun liveStream(
        id: Int,
        name: String,
    ) = XtreamLiveStream(
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

    private fun vodStream(
        id: Int,
        name: String,
    ) = XtreamVodStream(
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

    private fun seriesInfo(
        id: Int,
        name: String,
    ) = XtreamSeriesInfo(
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

    private fun stalkerChannel(
        id: Int,
        name: String,
    ) = StalkerChannel(
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

    private fun stalkerVod(
        id: Int,
        name: String,
    ) = StalkerVodItem(
        id = id,
        name = name,
        cmd = "http://v/$id",
        categoryId = "1",
        logo = "",
        description = "",
    )

    private fun stalkerSeries(
        id: Int,
        name: String,
    ) = StalkerSeriesItem(
        id = id,
        name = name,
        categoryId = "1",
        cover = "",
        plot = "",
        genre = "",
    )

    private fun xtreamClient(sourceId: String = "s1") =
        XtreamClient(
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
}
