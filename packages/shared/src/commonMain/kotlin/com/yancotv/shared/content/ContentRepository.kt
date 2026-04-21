package com.yancotv.shared.content

import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType

/**
 * Read-side facade over the `content` table for shell screens. Writes
 * go through `ContentWriter`; this class is deliberately query-only so
 * the shell never touches `YancoDb` directly.
 */
class ContentRepository(private val db: YancoDb) {

    fun count(type: ContentType, group: String? = null): Long {
        val t = type.dbValue
        return if (group == null) {
            db.contentQueries.countByType(t).executeAsOne()
        } else {
            db.contentQueries.countByTypeAndGroup(t, group).executeAsOne()
        }
    }

    fun groups(type: ContentType): List<String> =
        db.contentQueries.distinctGroupsForType(type.dbValue).executeAsList()

    fun page(type: ContentType, group: String? = null, offset: Long, limit: Long): List<ContentItem> {
        val t = type.dbValue
        val rows = if (group == null) {
            db.contentQueries.listByTypePaged(t, limit, offset).executeAsList()
        } else {
            db.contentQueries.listByTypeAndGroupPaged(t, group, limit, offset).executeAsList()
        }
        return rows.map { row ->
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
            )
        }
    }
}

private val ContentType.dbValue: String
    get() = when (this) {
        ContentType.LIVE -> "live"
        ContentType.MOVIE -> "movie"
        ContentType.SERIES -> "series"
    }

private fun contentTypeFromDb(value: String): ContentType = when (value) {
    "live" -> ContentType.LIVE
    "movie" -> ContentType.MOVIE
    "series" -> ContentType.SERIES
    else -> error("Unknown content type: $value")
}
