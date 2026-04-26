package com.yancotv.shared.favorites

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.FavoriteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Favorites CRUD. A favorite is a pointer into [content] — when the content
 * row is wiped (source removed or resynced), the `ON DELETE CASCADE` in the
 * schema cleans up the favorite automatically.
 *
 * The UI pins a synthetic "Favorites" category at the top of the rail; this
 * repo feeds that category. Order is newest-added first, matching desktop.
 */
class FavoritesRepository(
    private val db: YancoDb,
    private val clock: () -> Long,
) {
    fun all(): List<FavoriteEntry> =
        db.favoritesQueries.selectAll().executeAsList().map { row ->
            FavoriteEntry(
                favoriteId = row.favorite_id,
                addedAt = row.added_at,
                listId = row.list_id,
                content =
                    ContentItem(
                        id = row.id,
                        sourceId = row.source_id,
                        type = contentTypeFromDb(row.type),
                        title = row.title,
                        cleanTitle = row.clean_title,
                        groupName = row.group_name,
                        streamUrl = row.stream_url,
                        logoUrl = row.logo_url,
                        tvgId = row.tvg_id,
                        metadataJson = row.metadata_json,
                        sortOrder = row.sort_order.toInt(),
                        createdAt = row.created_at,
                        nameOverride = row.name_override,
                        logoOverride = row.logo_override,
                    ),
            )
        }

    fun allForType(type: ContentType): List<FavoriteEntry> = all().filter { it.content.type == type }

    fun isFavorite(contentId: String): Boolean = db.favoritesQueries.isFavorite(contentId).executeAsOne()

    /**
     * Reactive [all] — backed by SQLDelight's [asFlow]. Emits the current
     * snapshot immediately on collection, then re-emits every time a write
     * through any `favoritesQueries` binding fires a notifier. Dispatches
     * the terminal query to a background dispatcher so collectors on
     * Main.immediate don't block — `Dispatchers.Default` is KMP-safe
     * (`Dispatchers.IO` only exists on JVM/Android and would fail the iOS
     * metadata compile).
     */
    fun allFlow(): Flow<List<FavoriteEntry>> =
        db.favoritesQueries.selectAll().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { row ->
                FavoriteEntry(
                    favoriteId = row.favorite_id,
                    addedAt = row.added_at,
                    listId = row.list_id,
                    content =
                        ContentItem(
                            id = row.id,
                            sourceId = row.source_id,
                            type = contentTypeFromDb(row.type),
                            title = row.title,
                            cleanTitle = row.clean_title,
                            groupName = row.group_name,
                            streamUrl = row.stream_url,
                            logoUrl = row.logo_url,
                            tvgId = row.tvg_id,
                            metadataJson = row.metadata_json,
                            sortOrder = row.sort_order.toInt(),
                            createdAt = row.created_at,
                            nameOverride = row.name_override,
                            logoOverride = row.logo_override,
                        ),
                )
            }
        }

    /**
     * Reactive [isFavorite] for a single content id. Lets a star button
     * reflect state changes made from other screens without a focus round-trip.
     */
    fun isFavoriteFlow(contentId: String): Flow<Boolean> =
        db.favoritesQueries
            .isFavorite(contentId)
            .asFlow()
            .mapToOne(Dispatchers.Default)

    fun toggle(contentId: String): Boolean {
        // Returns the new state — caller can flip a UI star without re-querying.
        return if (isFavorite(contentId)) {
            db.favoritesQueries.deleteByContent(contentId)
            false
        } else {
            // Stage 2.2 — bare star toggle adds to the user's default list.
            // The long-press list-picker (MK.13.4 / Stage 4.4) will pass an
            // explicit list_id via a new repo method; this method stays the
            // shorthand for "just add it to my favorites".
            db.favoritesQueries.insert(
                id = "fav:$contentId",
                content_id = contentId,
                list_id = DEFAULT_LIST_ID,
                added_at = clock(),
            )
            true
        }
    }

    fun remove(contentId: String) {
        db.favoritesQueries.deleteByContent(contentId)
    }

    private companion object {
        // Stage 2.2 — id of the seeded "default" list. Created by the v4 → v5
        // migration AND by FavoriteLists.sq's INSERT OR IGNORE on fresh
        // install, so every device has it from launch one.
        const val DEFAULT_LIST_ID = "default"
    }
}

private fun contentTypeFromDb(value: String): ContentType =
    when (value) {
        "live" -> ContentType.LIVE
        "movie" -> ContentType.MOVIE
        "series" -> ContentType.SERIES
        else -> error("Unknown content type: $value")
    }
