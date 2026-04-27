package com.yancotv.shared.favorites

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.FavoriteEntry
import com.yancotv.shared.types.FavoriteList
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

    // ───── MK.13.4 — multi-list ─────

    /** Snapshot of every list, sorted by [FavoriteList.sortOrder]. */
    fun lists(): List<FavoriteList> =
        db.favoriteListsQueries.selectAll().executeAsList().map { it.toEntity() }

    /** Reactive [lists] for a tab bar that re-renders when the user creates,
     *  renames, or deletes a list. Default list is always present. */
    fun listsFlow(): Flow<List<FavoriteList>> =
        db.favoriteListsQueries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toEntity() } }

    /** Reactive list of entries for [listId]. Empty when the list has no
     *  members (or doesn't exist — caller should still validate against
     *  [listsFlow]). */
    fun byListFlow(listId: String): Flow<List<FavoriteEntry>> =
        db.favoritesQueries
            .selectByList(listId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toFavoriteEntry() } }

    /** Create a new list with [name] at the end of the order. Returns its id. */
    fun createList(name: String): String {
        val trimmed = name.trim().ifEmpty { "Untitled list" }
        val now = clock()
        val nextOrder = (lists().maxOfOrNull { it.sortOrder } ?: 0) + 1
        val id = "list:${now}:${trimmed.hashCode().toUInt().toString(16)}"
        db.favoriteListsQueries.insert(
            id = id,
            name = trimmed,
            sort_order = nextOrder.toLong(),
            is_default = 0,
            created_at = now,
            updated_at = now,
        )
        return id
    }

    fun renameList(
        id: String,
        name: String,
    ) {
        val trimmed = name.trim().ifEmpty { return }
        db.favoriteListsQueries.rename(name = trimmed, updated_at = clock(), id = id)
    }

    /** Delete a user-created list. The default list is guarded at the SQL
     *  layer via `WHERE is_default = 0` — calling this on `default` is a
     *  silent no-op. Members of the deleted list are removed (FK CASCADE). */
    fun deleteList(id: String) {
        db.favoriteListsQueries.deleteById(id)
    }

    fun setListSortOrder(
        id: String,
        sortOrder: Int,
    ) {
        db.favoriteListsQueries.setSortOrder(
            sort_order = sortOrder.toLong(),
            updated_at = clock(),
            id = id,
        )
    }

    /**
     * Add [contentId] to [listId]. If the same content is already in that
     * list this is a silent no-op (PK collision on the synthetic favorite
     * id `fav:<list>:<content>`). The single-list [toggle] above keys on
     * `fav:<content>` for legacy parity; the per-list id lets the same
     * content live in multiple lists simultaneously.
     */
    fun addToList(
        contentId: String,
        listId: String,
    ) {
        val favoriteId = "fav:$listId:$contentId"
        runCatching {
            db.favoritesQueries.insert(
                id = favoriteId,
                content_id = contentId,
                list_id = listId,
                added_at = clock(),
            )
        }
    }

    fun removeFromList(
        contentId: String,
        listId: String,
    ) {
        db.favoritesQueries.deleteByContentInList(content_id = contentId, list_id = listId)
    }

    private fun com.yancotv.shared.db.Favorite_lists.toEntity(): FavoriteList =
        FavoriteList(
            id = id,
            name = name,
            sortOrder = sort_order.toInt(),
            isDefault = is_default == 1L,
            createdAt = created_at,
            updatedAt = updated_at,
        )

    private fun com.yancotv.shared.db.SelectByList.toFavoriteEntry(): FavoriteEntry =
        FavoriteEntry(
            favoriteId = favorite_id,
            addedAt = added_at,
            listId = list_id,
            content =
                ContentItem(
                    id = id,
                    sourceId = source_id,
                    type = contentTypeFromDb(type),
                    title = title,
                    cleanTitle = clean_title,
                    groupName = group_name,
                    streamUrl = stream_url,
                    logoUrl = logo_url,
                    tvgId = tvg_id,
                    metadataJson = metadata_json,
                    sortOrder = sort_order.toInt(),
                    createdAt = created_at,
                    nameOverride = name_override,
                    logoOverride = logo_override,
                ),
        )

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
