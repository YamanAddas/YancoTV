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
