package com.yancotv.shared.sources

import com.yancotv.shared.db.YancoDatabase
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.parsers.M3uEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MK.35.1 — first-seen stamping, and the Home rail that reads it.
 *
 * The bug: Home's "Recently added" sorted by `content.created_at`, which cannot
 * mean "when this arrived". A sync is a full replacement — `prepareSource`
 * DELETEs the source's content and the chunked re-INSERT stamps a fresh
 * created_at on every row — so on a 272,419-item catalogue refreshing hourly the
 * rail showed whichever rows the provider's API returned last and reshuffled
 * every refresh. Nothing in it was ever genuinely new.
 *
 * The first two tests are the ones that matter, and they assert EMPTINESS.
 * Treating "first seen" as sufficient on its own is the obvious trap: a fresh
 * install stamps every item within the same second, so ordering by it would
 * still produce arbitrary titles — the same broken rail with a new column
 * behind it. The initial import is excluded, so the rail stays empty until a
 * later sync genuinely brings something.
 */
class ContentFirstSeenTest {
    private companion object {
        const val FIRST_SYNC = 1_700_000_000_000L
        const val SECOND_SYNC = 1_700_000_600_000L
    }

    /** Movie-shaped so the rows land in `recentlyAddedVod`'s type filter. */
    private fun movie(title: String, url: String) = M3uEntry(
        duration = -1.0,
        title = title,
        groupTitle = "MOVIES",
        tvgId = "",
        tvgName = "",
        tvgLogo = "",
        streamUrl = url,
        rawAttributes = "",
    )

    private fun insertSource(db: YancoDb, id: String = "s1") {
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

    private fun sync(database: YancoDatabase, at: Long, entries: List<M3uEntry>) {
        val writer = BulkContentWriter(database.driver, clock = { at })
        writer.prepareSource("s1")
        writer.writeM3uChunk("s1", entries, now = at, sortOrderStart = 0L)
        writer.finishSource("s1")
    }

    @Test
    fun `the first sync is an initial import, so the rail stays empty`() {
        val database = testDatabase()
        insertSource(database.db)
        sync(database, FIRST_SYNC, listOf(movie("Movie A", "http://a/1.mp4"), movie("Movie B", "http://a/2.mp4")))

        assertEquals(0L, database.db.contentFirstSeenQueries.countRecent().executeAsOne())
        assertTrue(
            database.db.contentQueries.recentlyAddedVod(60).executeAsList().isEmpty(),
            "nothing is genuinely new on a fresh install — the rail must stay empty",
        )
    }

    @Test
    fun `re-syncing the SAME catalogue adds nothing`() {
        // The heart of it. Under created_at ordering this second sync rewrote
        // every timestamp and reshuffled the whole rail while the catalogue was
        // byte-for-byte identical.
        val database = testDatabase()
        insertSource(database.db)
        val catalogue = listOf(movie("Movie A", "http://a/1.mp4"), movie("Movie B", "http://a/2.mp4"))
        sync(database, FIRST_SYNC, catalogue)
        sync(database, SECOND_SYNC, catalogue)

        assertEquals(0L, database.db.contentFirstSeenQueries.countRecent().executeAsOne())
        assertTrue(database.db.contentQueries.recentlyAddedVod(60).executeAsList().isEmpty())
    }

    @Test
    fun `only titles the provider actually added later reach the rail`() {
        val database = testDatabase()
        insertSource(database.db)
        sync(database, FIRST_SYNC, listOf(movie("Movie A", "http://a/1.mp4"), movie("Movie B", "http://a/2.mp4")))
        sync(
            database,
            SECOND_SYNC,
            listOf(
                movie("Movie A", "http://a/1.mp4"),
                movie("Movie B", "http://a/2.mp4"),
                movie("Movie C", "http://a/3.mp4"),
            ),
        )

        val rail = database.db.contentQueries.recentlyAddedVod(60).executeAsList()
        assertEquals(1, rail.size, "only the genuinely new title belongs in the rail")
        assertEquals("Movie C", rail.single().title)
    }

    @Test
    fun `stamps survive the DELETE a sync performs, which is the entire point`() {
        // prepareSource DELETEs every content row for the source. A column on
        // `content` would be destroyed here; the side table must not be, which
        // is why it carries no FK back to content(id).
        val database = testDatabase()
        insertSource(database.db)
        val catalogue = listOf(movie("Movie A", "http://a/1.mp4"))
        sync(database, FIRST_SYNC, catalogue)
        val before = database.db.contentFirstSeenQueries.countBySource("s1").executeAsOne()

        sync(database, SECOND_SYNC, catalogue)

        assertTrue(before > 0L, "the first sync must stamp something")
        assertEquals(before, database.db.contentFirstSeenQueries.countBySource("s1").executeAsOne())
    }
}
