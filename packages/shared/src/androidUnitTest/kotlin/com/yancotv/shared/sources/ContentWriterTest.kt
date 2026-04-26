package com.yancotv.shared.sources

import com.yancotv.shared.parsers.M3uEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ContentWriterTest {
    private fun entry(
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

    @Test
    fun `writeM3u inserts rows + removes prior rows on re-sync`() {
        val db = testDb()
        // Parent source row required for FK integrity.
        db.sourcesQueries.insert(
            id = "s1",
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

        val writer = ContentWriter(db)
        val first =
            writer.writeM3u(
                "s1",
                listOf(
                    entry("BBC News", "http://a/1.ts"),
                    entry("CNN", "http://a/2.ts"),
                ),
                now = 100L,
            )
        assertEquals(2, first)
        assertEquals(2L, db.contentQueries.countBySource("s1").executeAsOne())

        // Re-sync with different set — old rows must be gone.
        val second =
            writer.writeM3u(
                "s1",
                listOf(entry("Sky News", "http://a/3.ts")),
                now = 200L,
            )
        assertEquals(1, second)
        assertEquals(1L, db.contentQueries.countBySource("s1").executeAsOne())
    }

    @Test
    fun `content IDs are stable across re-sync when title + URL unchanged`() {
        val db = testDb()
        db.sourcesQueries.insert(
            id = "s1",
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
        val writer = ContentWriter(db)
        val e = entry("BBC News", "http://a/1.ts")

        writer.writeM3u("s1", listOf(e), now = 100L)
        val firstId =
            db.contentQueries
                .countBySource("s1")
                .executeAsOne()
                .let {
                    assertEquals(1L, it)
                    db
                }.let { ContentIds.m3u("s1", e.title, e.streamUrl) }

        writer.writeM3u("s1", listOf(e), now = 200L)
        val row = db.contentQueries.selectById(firstId).executeAsOneOrNull()
        assertNotNull(row) { "Re-sync must preserve content ID: $firstId" }
    }

    @Test
    fun `FTS search finds rows inserted via triggers`() {
        val db = testDb()
        db.sourcesQueries.insert(
            id = "s1",
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
        ContentWriter(db).writeM3u(
            "s1",
            listOf(
                entry("BBC News HD", "http://a/1.ts", group = "UK"),
                entry("CNN International", "http://a/2.ts", group = "US"),
                entry("Al Jazeera", "http://a/3.ts", group = "QA"),
            ),
            now = 100L,
        )
        val hits = db.contentQueries.searchFts("news", 50).executeAsList()
        assertEquals(1, hits.size)
        assertEquals("BBC News HD", hits[0].title)

        // Confirm FTS also searches clean_title by looking up a term the
        // cleaner strips from the raw title.
        val cnnHits = db.contentQueries.searchFts("cnn", 50).executeAsList()
        assertEquals(1, cnnHits.size)
    }
}
