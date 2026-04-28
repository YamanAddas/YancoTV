package com.yancotv.shared.favorites

import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.sources.testDb
import com.yancotv.shared.types.ContentType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [FavoritesRepository] — star/unstar with a reactive allFlow().
 *
 * Covers:
 *  - Toggle round-trip: false → true → false.
 *  - Ordering: newest-added is first (desktop-consistent).
 *  - Per-type filtering via `allForType`.
 *  - FK CASCADE: deleting a content row sweeps its favorite automatically.
 *  - `allFlow()` emits the current snapshot on subscription.
 */
class FavoritesRepositoryTest {
    @Test fun toggleFlipsStateAndReturnsNewValue() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "m-1", "src-A", type = "movie")
            val repo = FavoritesRepository(db, clock = { 1_000L })

            assertFalse(repo.isFavorite("m-1"))
            assertTrue(repo.toggle("m-1"))
            assertTrue(repo.isFavorite("m-1"))
            assertFalse(repo.toggle("m-1"))
            assertFalse(repo.isFavorite("m-1"))
        }

    @Test fun allReturnsNewestFirst() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "m-1", "src-A", type = "movie")
            insertContent(db, "m-2", "src-A", type = "movie")
            insertContent(db, "m-3", "src-A", type = "movie")

            var t = 1_000L
            val repo = FavoritesRepository(db, clock = { t })
            repo.toggle("m-1")
            t = 2_000L
            repo.toggle("m-2")
            t = 3_000L
            repo.toggle("m-3")

            val ids = repo.all().map { it.content.id }
            assertEquals(listOf("m-3", "m-2", "m-1"), ids)
        }

    @Test fun allForTypePartitions() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "ch-1", "src-A", type = "live")
            insertContent(db, "m-1", "src-A", type = "movie")
            insertContent(db, "s-1", "src-A", type = "series")
            val repo = FavoritesRepository(db, clock = { 0L })
            repo.toggle("ch-1")
            repo.toggle("m-1")
            repo.toggle("s-1")

            assertEquals(1, repo.allForType(ContentType.LIVE).size)
            assertEquals(1, repo.allForType(ContentType.MOVIE).size)
            assertEquals(1, repo.allForType(ContentType.SERIES).size)
            assertEquals(
                "ch-1",
                repo
                    .allForType(ContentType.LIVE)
                    .single()
                    .content.id,
            )
        }

    @Test fun allFlowEmitsCurrentSnapshotOnFirstCollection() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "m-1", "src-A", type = "movie")
            val repo = FavoritesRepository(db, clock = { 0L })
            repo.toggle("m-1")

            val snapshot = repo.allFlow().first()
            assertEquals(1, snapshot.size)
            assertEquals("m-1", snapshot.single().content.id)
        }

    @Test fun isFavoriteFlowReflectsCurrentValue() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "m-1", "src-A", type = "movie")
            val repo = FavoritesRepository(db, clock = { 0L })

            // StateFlow semantics: `.first()` is the current snapshot.
            assertFalse(repo.isFavoriteFlow("m-1").first())
            repo.toggle("m-1")
            assertTrue(repo.isFavoriteFlow("m-1").first())
        }

    @Test fun removeClearsEvenIfNotPresent() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "m-1", "src-A", type = "movie")
            val repo = FavoritesRepository(db, clock = { 0L })
            // Should not throw even if the id was never a favorite.
            repo.remove("m-1")
            assertFalse(repo.isFavorite("m-1"))
        }

    @Test fun cascade_removingContentRemovesFavorite() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "m-1", "src-A", type = "movie")
            val repo = FavoritesRepository(db, clock = { 0L })
            repo.toggle("m-1")
            assertTrue(repo.isFavorite("m-1"))

            // Delete the content — FK CASCADE should sweep the favorite row.
            db.contentQueries.deleteBySource("src-A")
            assertFalse(repo.isFavorite("m-1"), "favorite must cascade-delete with its content")
        }

    // ───── MK.23.C.3 — multi-list (MK.13.4) surface ─────
    //
    // Stage 2.2 / MK.13.4 ships per-list favorites with `favorite_lists`
    // table + `favorites.list_id` FK. Audit (2026-04-28) flagged zero
    // tests — `createList` / `addToList` / `removeFromList` / `deleteList`
    // / `setListSortOrder` / `byListFlow` / `listsFlow` all unguarded.
    // The load-bearing test is `deleteList("default") is silent no-op` —
    // a future schema change that drops the WHERE is_default = 0 guard
    // would wipe every favorite on the device.

    @Test fun createList_returnsStableIdAndTrimsWhitespace() =
        runTest {
            val db = testDb()
            val repo = FavoritesRepository(db, clock = { 1_000L })

            val id = repo.createList("  Sports  ")
            assertTrue(id.startsWith("list:"), "id format should be list:<ts>:<hash>")

            val lists = repo.lists()
            val sports = lists.first { it.id == id }
            assertEquals("Sports", sports.name, "leading/trailing whitespace must be stripped")
            assertFalse(sports.isDefault)
            assertEquals(1_000L, sports.createdAt)
        }

    @Test fun createList_blankNameFallsBackToUntitled() =
        runTest {
            val db = testDb()
            val repo = FavoritesRepository(db, clock = { 0L })

            val id = repo.createList("   ")
            val lists = repo.lists()
            val created = lists.first { it.id == id }
            assertEquals("Untitled list", created.name, "blank input must not yield an empty list name")
        }

    @Test fun lists_alwaysIncludesDefault() =
        runTest {
            val db = testDb()
            val repo = FavoritesRepository(db, clock = { 0L })

            // Fresh DB — only the seeded default list. The `default` row is
            // installed by FavoriteLists.sq's INSERT OR IGNORE on fresh
            // create AND by the v4 → v5 migration; this assertion guards
            // both paths.
            val lists = repo.lists()
            assertEquals(1, lists.size)
            assertEquals("default", lists[0].id)
            assertTrue(lists[0].isDefault)
        }

    @Test fun addToList_isIdempotentOnCollision() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "m-1", "src-A", type = "movie")
            val repo = FavoritesRepository(db, clock = { 1L })
            val sports = repo.createList("Sports")

            repo.addToList(contentId = "m-1", listId = sports)
            repo.addToList(contentId = "m-1", listId = sports) // duplicate — silent no-op
            repo.addToList(contentId = "m-1", listId = sports) // duplicate — silent no-op

            val members = repo.byListFlow(sports).first()
            assertEquals(1, members.size, "addToList must dedupe on same (content, list) pair")
            assertEquals("m-1", members[0].content.id)
        }

    @Test fun removeFromList_isListScopedNotGlobal() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "m-1", "src-A", type = "movie")
            val repo = FavoritesRepository(db, clock = { 1L })

            val sports = repo.createList("Sports")
            repo.addToList(contentId = "m-1", listId = "default")
            repo.addToList(contentId = "m-1", listId = sports)

            repo.removeFromList(contentId = "m-1", listId = sports)

            // Only the sports row gone; default row survives.
            assertEquals(0, repo.byListFlow(sports).first().size, "sports list must be empty after remove")
            assertEquals(1, repo.byListFlow("default").first().size, "default list must keep its row")
            assertTrue(repo.isFavorite("m-1"), "isFavorite is 'in any list' — still favorited via default")
        }

    @Test fun deleteList_defaultIsSilentNoOp() =
        runTest {
            // The load-bearing test. `deleteList("default")` is guarded at
            // the SQL layer via WHERE is_default = 0 — the call is a silent
            // no-op. If a future schema change drops that guard, every
            // favorite on the device gets wiped via FK CASCADE
            // (favorites.list_id → favorite_lists.id ON DELETE CASCADE).
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "m-1", "src-A", type = "movie")
            val repo = FavoritesRepository(db, clock = { 1L })
            repo.addToList(contentId = "m-1", listId = "default")
            assertTrue(repo.isFavorite("m-1"))

            repo.deleteList("default") // must be a silent no-op

            val lists = repo.lists()
            assertEquals(1, lists.size, "default list must still exist")
            assertEquals("default", lists[0].id)
            assertTrue(repo.isFavorite("m-1"), "default-list favorites must survive a deleteList(default) attempt")
        }

    @Test fun deleteList_customListCascadesToMembers() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "m-1", "src-A", type = "movie")
            val repo = FavoritesRepository(db, clock = { 1L })

            val sports = repo.createList("Sports")
            repo.addToList(contentId = "m-1", listId = sports)
            // Same content also in default — proves the cascade is list-scoped.
            repo.addToList(contentId = "m-1", listId = "default")

            repo.deleteList(sports)

            assertEquals(1, repo.lists().size, "sports list must be gone")
            assertEquals(0, repo.byListFlow(sports).first().size, "sports members must follow via cascade")
            assertEquals(
                1,
                repo.byListFlow("default").first().size,
                "default-list membership must survive a custom-list delete",
            )
        }

    @Test fun renameList_trimsAndIgnoresBlank() =
        runTest {
            val db = testDb()
            val repo = FavoritesRepository(db, clock = { 1L })
            val sports = repo.createList("Sports")

            repo.renameList(sports, "  Football  ")
            assertEquals("Football", repo.lists().first { it.id == sports }.name)

            // Blank rename is a silent no-op so a UI accidentally clearing
            // the field doesn't strand an empty list name.
            repo.renameList(sports, "   ")
            assertEquals("Football", repo.lists().first { it.id == sports }.name, "blank rename must be ignored")
        }

    @Test fun setListSortOrder_updatesUpdatedAt() =
        runTest {
            val db = testDb()
            var t = 1L
            val repo = FavoritesRepository(db, clock = { t })
            val a = repo.createList("A")

            t = 5_000L
            repo.setListSortOrder(a, sortOrder = 99)

            val updated = repo.lists().first { it.id == a }
            assertEquals(99, updated.sortOrder)
            assertEquals(5_000L, updated.updatedAt, "updated_at must reflect the setSortOrder clock read")
        }

    @Test fun byListFlow_isReactive() =
        runTest {
            val db = testDb()
            insertSource(db, "src-A")
            insertContent(db, "m-1", "src-A", type = "movie")
            insertContent(db, "m-2", "src-A", type = "movie")
            val repo = FavoritesRepository(db, clock = { 1L })

            val sports = repo.createList("Sports")
            assertEquals(0, repo.byListFlow(sports).first().size)

            repo.addToList(contentId = "m-1", listId = sports)
            assertEquals(1, repo.byListFlow(sports).first().size)

            repo.addToList(contentId = "m-2", listId = sports)
            assertEquals(2, repo.byListFlow(sports).first().size)

            repo.removeFromList(contentId = "m-1", listId = sports)
            val remaining = repo.byListFlow(sports).first()
            assertEquals(1, remaining.size)
            assertEquals("m-2", remaining[0].content.id)
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
}
