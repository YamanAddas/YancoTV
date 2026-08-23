package com.yancotv.shared.content

import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.sources.testDb
import com.yancotv.shared.types.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

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
    @Test fun findLiveByTvgId_picksHighestPrioritySource() = runTest {
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

    @Test fun findLiveByTvgId_nullForUnknownId() = runTest {
        val db = testDb()
        val repo = ContentRepository(db)
        assertNull(repo.findLiveByTvgId("nonexistent.tv"))
    }

    @Test fun findLiveByTvgId_ignoresBlankTvgId() = runTest {
        val db = testDb()
        val repo = ContentRepository(db)
        // Blank input must short-circuit without a DB hit; desktop does the
        // same to avoid matching rows where tvg_id = ''.
        assertNull(repo.findLiveByTvgId(""))
        assertNull(repo.findLiveByTvgId("   "))
    }

    @Test fun findLiveByTvgId_ignoresVodAndSeriesRows() = runTest {
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

    @Test fun search_ftsFullWordMatch() = runTest {
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

    @Test fun search_emptyQueryReturnsNothing() = runTest {
        val db = testDb()
        insertSource(db, "src-A", priority = 0)
        insertContent(db, "ch-1", "src-A", tvgId = "cnn.us", title = "CNN")
        val repo = ContentRepository(db)
        assertTrue(repo.search("").isEmpty())
        assertTrue(repo.search("   ").isEmpty())
    }

    @Test fun search_respectsLimit() = runTest {
        val db = testDb()
        insertSource(db, "src-A", priority = 0)
        for (i in 0 until 20) insertContent(db, "ch-$i", "src-A", "c$i", "Match $i")
        val repo = ContentRepository(db)
        assertEquals(5, repo.search("Match", limit = 5L).size)
    }

    /**
     * MK.search.rails — per-type FTS slice. Pins the contract that the
     * search-rails UI depends on: each rail (Live / Movies / Series)
     * gets its own slice, so a 100-item live catalog cannot starve the
     * other types the way unified `searchFts` did when it ordered by
     * `c.type` and capped at the same total limit.
     */
    @Test fun searchByType_returnsOnlyMatchingType() = runTest {
        val db = testDb()
        insertSource(db, "src-A", priority = 0)
        insertContent(db, "live-1", "src-A", "x1", "Marvel Live", type = "live")
        insertContent(db, "live-2", "src-A", "x2", "Marvel Live HD", type = "live")
        insertContent(db, "movie-1", "src-A", null, "Marvel Movie", type = "movie")
        insertContent(db, "series-1", "src-A", null, "Marvel Show", type = "series")
        val repo = ContentRepository(db)

        assertEquals(2, repo.searchByType("Marvel", ContentType.LIVE).size)
        assertEquals(1, repo.searchByType("Marvel", ContentType.MOVIE).size)
        assertEquals(1, repo.searchByType("Marvel", ContentType.SERIES).size)
        // Type filter is exclusive — no cross-bleed.
        assertTrue(
            repo.searchByType("Marvel", ContentType.MOVIE).single().title == "Marvel Movie",
        )
    }

    @Test fun searchByType_independentLimit() = runTest {
        val db = testDb()
        insertSource(db, "src-A", priority = 0)
        // 150 live matches — way past the 100 unified-search limit
        // would ever surface alongside movies/series.
        for (i in 0 until 150) {
            insertContent(db, "live-$i", "src-A", "c$i", "Marvel Live $i", type = "live")
        }
        insertContent(db, "movie-1", "src-A", null, "Marvel Movie", type = "movie")
        val repo = ContentRepository(db)

        // Per-type cap of 100: live returns 100 (capped), movies returns
        // its 1 row regardless. The unified search would have surfaced
        // 100 lives + 0 movies; per-type fixes that.
        assertEquals(100, repo.searchByType("Marvel", ContentType.LIVE, limit = 100L).size)
        assertEquals(1, repo.searchByType("Marvel", ContentType.MOVIE, limit = 100L).size)
    }

    @Test fun searchByType_emptyQueryReturnsNothing() = runTest {
        val db = testDb()
        insertSource(db, "src-A", priority = 0)
        insertContent(db, "ch-1", "src-A", "c1", "Channel", type = "live")
        val repo = ContentRepository(db)
        assertTrue(repo.searchByType("", ContentType.LIVE).isEmpty())
        assertTrue(repo.searchByType("   ", ContentType.LIVE).isEmpty())
    }

    @Test fun page_offsetPastEndReturnsEmpty() = runTest {
        val db = testDb()
        insertSource(db, "src-A", priority = 0)
        for (i in 0 until 3) insertContent(db, "ch-$i", "src-A", "c$i", "Ch $i")
        val repo = ContentRepository(db)
        val page = repo.page(ContentType.LIVE, group = null, offset = 100L, limit = 10L)
        assertTrue(page.isEmpty())
    }

    @Test fun count_matchesRowsForType() = runTest {
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

    // ───── MK.13.2 — name / logo overrides ─────

    @Test fun newRow_hasNullOverrides_displayTitleFallsBackToCleanTitle() = runTest {
        val db = testDb()
        insertSource(db, "src-A", priority = 0)
        insertContent(db, "ch-A", "src-A", tvgId = "cnn.us", title = "CNN HD [US]")
        // insertContent uses `clean_title = title` (the raw value); this
        // test covers the "no override set" path.
        val row = ContentRepository(db).findById("ch-A")
        assertNotNull(row)
        assertNull(row.nameOverride)
        assertNull(row.logoOverride)
        // displayTitle / displayLogoUrl fall through to the M3U-shipped
        // fields when overrides are absent.
        assertEquals("CNN HD [US]", row.displayTitle)
        assertNull(row.displayLogoUrl)
    }

    @Test fun setOverrides_renameAndLogo_roundTripViaDisplayHelpers() = runTest {
        val db = testDb()
        insertSource(db, "src-A", priority = 0)
        insertContent(db, "ch-A", "src-A", tvgId = "cnn.us", title = "CNN HD [US]")

        val repo = ContentRepository(db)
        repo.setOverrides("ch-A", nameOverride = "CNN", logoOverride = "https://logos.example/cnn.png")

        val row = repo.findById("ch-A")
        assertNotNull(row)
        assertEquals("CNN", row.nameOverride)
        assertEquals("https://logos.example/cnn.png", row.logoOverride)
        // Override wins over the M3U title/logo without the call site
        // having to branch on null.
        assertEquals("CNN", row.displayTitle)
        assertEquals("https://logos.example/cnn.png", row.displayLogoUrl)
    }

    @Test fun setOverrides_blankIsTreatedAsClear() = runTest {
        val db = testDb()
        insertSource(db, "src-A", priority = 0)
        insertContent(db, "ch-A", "src-A", tvgId = "cnn.us", title = "CNN HD [US]")

        val repo = ContentRepository(db)
        // Clearing via blank string — the UI's TextField.onValueChange may
        // fire with "" before "null" reaches us. Both must mean "remove".
        repo.setOverrides("ch-A", nameOverride = "Custom", logoOverride = "url")
        repo.setOverrides("ch-A", nameOverride = "   ", logoOverride = "")

        val row = repo.findById("ch-A")
        assertNotNull(row)
        assertNull(row.nameOverride)
        assertNull(row.logoOverride)
        assertEquals("CNN HD [US]", row.displayTitle)
    }

    @Test fun setOverrides_partialUpdate_leavesOtherFieldUnchangedWhenCallerKeepsIt() = runTest {
        val db = testDb()
        insertSource(db, "src-A", priority = 0)
        insertContent(db, "ch-A", "src-A", tvgId = "cnn.us", title = "CNN HD [US]")

        val repo = ContentRepository(db)
        repo.setOverrides("ch-A", nameOverride = "CNN", logoOverride = "https://l/cnn.png")
        // Caller wants to update logo only — passes the existing name back
        // through. (The repo doesn't have a single-field setter today; the
        // contract is that callers carry both values.)
        val current = repo.findById("ch-A")!!
        repo.setOverrides("ch-A", nameOverride = current.nameOverride, logoOverride = "https://l/cnn-2.png")

        val row = repo.findById("ch-A")!!
        assertEquals("CNN", row.nameOverride)
        assertEquals("https://l/cnn-2.png", row.logoOverride)
    }

    // ───── fixtures ─────

    @Test fun findIdByStreamUrl_pinsExactChannelWhereTvgIdWouldNot() = runTest {
        // MB-381 — two channels share a junk tvg_id (beIN SD + HD). A tvg_id
        // lookup returns only the priority pick; findIdByStreamUrl resolves the
        // EXACT channel by its url, so a scheduled recording's content_id
        // matches the stream_url it records.
        val db = testDb()
        insertSource(db, "src-A", priority = 0)
        insertContent(db, "bein-sd", "src-A", tvgId = "beIN", title = "beIN SD", sortOrder = 0L)
        insertContent(db, "bein-hd", "src-A", tvgId = "beIN", title = "beIN HD", sortOrder = 1L)
        val repo = ContentRepository(db)
        assertEquals("bein-hd", repo.findIdByStreamUrl("http://stream/bein-hd"))
        assertEquals("bein-sd", repo.findIdByStreamUrl("http://stream/bein-sd"))
        assertNull(repo.findIdByStreamUrl("http://stream/missing"), "unknown url → null")
        assertNull(repo.findIdByStreamUrl(""), "blank url → null, no query")
    }

    private fun insertSource(db: YancoDb, id: String, priority: Int) {
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
            referer = null,
            last_synced = null,
            last_sync_error = null,
            is_active = true,
            priority = priority.toLong(),
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
        tvgId: String?,
        title: String,
        type: String = "live",
        groupName: String? = null,
        sortOrder: Long = 0L,
    ) {
        db.contentQueries.insert(
            id = id,
            source_id = sourceId,
            type = type,
            title = title,
            clean_title = title,
            group_name = groupName,
            stream_url = "http://stream/$id",
            logo_url = null,
            tvg_id = tvgId,
            metadata_json = null,
            sort_order = sortOrder,
            created_at = 0L,
        )
    }

    // MK.20.2 — Hierarchy builder. With a mix of prefixed + unprefixed
    // groups, the tree should bucket multi-child prefixes under a Parent,
    // leave singletons as Leaves, and preserve provider order at every
    // level (parent slot = first appearance of any child).
    @Test fun groupsHierarchical_bucketsMultiChildPrefixesAndPreservesOrder() = runTest {
        val db = testDb()
        insertSource(db, "src-A", priority = 0)
        // Provider order: Sports (no prefix), AR | Movies, EN | News,
        // AR | Sports, AR | Kids, EN | Drama, FR | Cinema (single → Leaf).
        insertContent(db, "ch-1", "src-A", "c1", "Ch1", groupName = "Sports", sortOrder = 0L)
        insertContent(db, "ch-2", "src-A", "c2", "Ch2", groupName = "AR | Movies", sortOrder = 1L)
        insertContent(db, "ch-3", "src-A", "c3", "Ch3", groupName = "EN | News", sortOrder = 2L)
        insertContent(db, "ch-4", "src-A", "c4", "Ch4", groupName = "AR | Sports", sortOrder = 3L)
        insertContent(db, "ch-5", "src-A", "c5", "Ch5", groupName = "AR | Kids", sortOrder = 4L)
        insertContent(db, "ch-6", "src-A", "c6", "Ch6", groupName = "EN | Drama", sortOrder = 5L)
        insertContent(db, "ch-7", "src-A", "c7", "Ch7", groupName = "FR | Cinema", sortOrder = 6L)

        val tree = ContentRepository(db).groupsHierarchical(ContentType.LIVE)

        // Expected top-level shape:
        //   [Leaf "Sports", Parent "Arabic", Parent "English", Leaf "FR | Cinema"]
        // Sports first (provider order); then Arabic bucket (first-seen at
        // sortOrder=1); then English (first-seen sortOrder=2); FR has only
        // one child so it stays flat as Leaf with original group_name.
        assertEquals(4, tree.size)
        assertTrue(tree[0] is CategoryNode.Leaf)
        assertEquals("Sports", (tree[0] as CategoryNode.Leaf).groupName)

        val arabic = tree[1]
        assertTrue(arabic is CategoryNode.Parent)
        assertEquals("Arabic", arabic.label)
        assertEquals(PrefixCatalog.Kind.Language, arabic.kind)
        // Arabic children in provider order: Movies, Sports, Kids.
        assertEquals(
            listOf("AR | Movies", "AR | Sports", "AR | Kids"),
            arabic.children.map { it.groupName },
        )

        val english = tree[2]
        assertTrue(english is CategoryNode.Parent)
        assertEquals("English", english.label)
        assertEquals(
            listOf("EN | News", "EN | Drama"),
            english.children.map { it.groupName },
        )

        // FR | Cinema collapses to Leaf because it's a single-child bucket.
        assertTrue(tree[3] is CategoryNode.Leaf)
        assertEquals("FR | Cinema", (tree[3] as CategoryNode.Leaf).groupName)
    }

    @Test fun groupsHierarchical_emptyReturnsEmpty() = runTest {
        val db = testDb()
        insertSource(db, "src-A", priority = 0)
        assertEquals(emptyList(), ContentRepository(db).groupsHierarchical(ContentType.LIVE))
    }

    // MK.20.1 — provider-order group sort. Insert three groups in non-
    // alphabetical arrival order; assert groups() returns insertion order
    // (Sports, AR Movies, News), NOT the legacy alphabetical order
    // (AR Movies, News, Sports).
    @Test fun groups_returnInProviderOrderNotAlphabetical() = runTest {
        val db = testDb()
        insertSource(db, "src-A", priority = 0)
        insertContent(db, "ch-1", "src-A", "c1", "Ch1", groupName = "Sports", sortOrder = 0L)
        insertContent(db, "ch-2", "src-A", "c2", "Ch2", groupName = "Sports", sortOrder = 1L)
        insertContent(db, "ch-3", "src-A", "c3", "Ch3", groupName = "AR Movies", sortOrder = 2L)
        insertContent(db, "ch-4", "src-A", "c4", "Ch4", groupName = "News", sortOrder = 3L)

        val repo = ContentRepository(db)
        val groups = repo.groups(ContentType.LIVE)

        assertEquals(listOf("Sports", "AR Movies", "News"), groups)
    }
}
