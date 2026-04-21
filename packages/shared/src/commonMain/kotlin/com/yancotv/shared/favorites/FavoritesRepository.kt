package com.yancotv.shared.favorites

import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.FavoriteEntry

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
                content = ContentItem(
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
                ),
            )
        }

    fun allForType(type: ContentType): List<FavoriteEntry> =
        all().filter { it.content.type == type }

    fun isFavorite(contentId: String): Boolean =
        db.favoritesQueries.isFavorite(contentId).executeAsOne()

    fun toggle(contentId: String): Boolean {
        // Returns the new state — caller can flip a UI star without re-querying.
        return if (isFavorite(contentId)) {
            db.favoritesQueries.deleteByContent(contentId)
            false
        } else {
            db.favoritesQueries.insert(
                id = "fav:$contentId",
                content_id = contentId,
                added_at = clock() / 1000L,
            )
            true
        }
    }

    fun remove(contentId: String) {
        db.favoritesQueries.deleteByContent(contentId)
    }
}

private fun contentTypeFromDb(value: String): ContentType = when (value) {
    "live" -> ContentType.LIVE
    "movie" -> ContentType.MOVIE
    "series" -> ContentType.SERIES
    else -> error("Unknown content type: $value")
}
