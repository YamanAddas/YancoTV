package com.yancotv.shared.content

import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.EpisodeInfo

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

    fun groups(type: ContentType): List<String> = db.contentQueries.distinctGroupsForType(type.dbValue).executeAsList()

    /**
     * MK.20.2 — Group list bucketed by detected language/region prefix.
     * Top level preserves provider order; multi-child buckets collapse the
     * `AR | …` / `EN | …` siblings into a single `Arabic` / `English`
     * [CategoryNode.Parent]. Single-child buckets stay flat as [CategoryNode.Leaf].
     *
     * Pure: builds on [groups] (already provider-ordered via MK.20.1) and
     * runs the catalog over each entry. iOS gets it for free.
     */
    fun groupsHierarchical(type: ContentType): List<CategoryNode> = CategoryTreeBuilder.build(groups(type))

    fun page(type: ContentType, group: String? = null, offset: Long, limit: Long): List<ContentItem> {
        val t = type.dbValue
        val rows =
            if (group == null) {
                db.contentQueries.listByTypePaged(t, limit, offset).executeAsList()
            } else {
                db.contentQueries.listByTypeAndGroupPaged(t, group, limit, offset).executeAsList()
            }
        return rows.map { it.toDomain() }
    }

    /**
     * Latest VOD rows (movies + series mixed), newest first. Backs the Home
     * dashboard's "Recently added" rail — the Home shell was paging 400 rows
     * client-side and sorting in Kotlin, which was both slow on first catalog
     * load and incorrect past the 200-per-type cap. Pushed down to SQLDelight.
     */
    fun recentlyAddedVod(limit: Long): List<ContentItem> = db.contentQueries
        .recentlyAddedVod(limit)
        .executeAsList()
        .map { it.toDomain() }

    /**
     * FTS4-backed search across `title`, `clean_title`, and `group_name`.
     * Returns results ordered by content type then source's native sort
     * order — so a live channel a user searches for shows up with its
     * sibling live channels before movies/series of the same name.
     *
     * [query] is passed through to SQLite verbatim, so callers can use
     * FTS4 operators (`*` prefix wildcards, quoted phrases). We strip
     * leading/trailing whitespace only.
     */
    fun search(query: String, limit: Long = 100): List<ContentItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        // Prefix wildcard so typing "mar" matches "Marvel". Users typing
        // a full word still match — the `*` only widens, never narrows.
        val ftsQuery =
            buildString {
                val tokens = trimmed.split(Regex("\\s+"))
                tokens.forEachIndexed { i, tok ->
                    if (tok.isEmpty()) return@forEachIndexed
                    if (i > 0) append(' ')
                    // Escape double-quotes per FTS4 syntax (they delimit phrases).
                    append('"').append(tok.replace("\"", "\"\"")).append("\"*")
                }
            }
        return db.contentQueries
            .searchFts(ftsQuery, limit)
            .executeAsList()
            .map { it.toDomain() }
    }

    /**
     * MK.search.rails — per-type FTS search. The unified [search] orders
     * by `c.type` and a heavy live catalog can exhaust the row limit
     * entirely within live before any movie/series row is reached. The
     * search-rails UI calls this once per [ContentType] so each rail
     * gets its own slice.
     */
    fun searchByType(query: String, type: ContentType, limit: Long = 100): List<ContentItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val ftsQuery =
            buildString {
                val tokens = trimmed.split(Regex("\\s+"))
                tokens.forEachIndexed { i, tok ->
                    if (tok.isEmpty()) return@forEachIndexed
                    if (i > 0) append(' ')
                    append('"').append(tok.replace("\"", "\"\"")).append("\"*")
                }
            }
        val typeKey =
            when (type) {
                ContentType.LIVE -> "live"
                ContentType.MOVIE -> "movie"
                ContentType.SERIES -> "series"
            }
        return db.contentQueries
            .searchFtsByType(ftsQuery, typeKey, limit)
            .executeAsList()
            .map { it.toDomain() }
    }

    fun findById(id: String): ContentItem? = db.contentQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toDomain()

    /**
     * Pick the highest-priority live channel that carries [tvgId]. Used by
     * catchup resolution: the Guide keys programmes on tvg_id (one guide row
     * per tvg_id even if several sources carry the same feed), so the catchup
     * path needs to choose a single source to build the replay URL against.
     */
    fun findLiveByTvgId(tvgId: String): ContentItem? {
        if (tvgId.isBlank()) return null
        return db.contentQueries
            .selectLiveByTvgId(tvgId)
            .executeAsOneOrNull()
            ?.toDomain()
    }

    /**
     * MK.13.2 — set or clear the per-channel rename / custom logo overrides.
     * Pass `null` (or a blank string, treated identically downstream) to
     * clear an override; pass a non-blank string to set one. Either field
     * is independently settable via the same call by passing the other
     * unchanged. Survives FTS unchanged — search still matches the M3U
     * title — see Content.sq for the rationale.
     *
     * Caller dispatches: this is a synchronous SQLDelight write; on Android
     * wrap in `Dispatchers.Default` per the threading rule (`Dispatchers.IO`
     * only exists on JVM/Android — `commonMain` has to use the KMP-safe one).
     */
    fun setOverrides(contentId: String, nameOverride: String?, logoOverride: String?) {
        db.contentQueries.setOverrides(
            nameOverride = nameOverride?.takeIf { it.isNotBlank() },
            logoOverride = logoOverride?.takeIf { it.isNotBlank() },
            id = contentId,
        )
    }

    /**
     * Direct lookup of an episode by its stable id. Backs the Home
     * Continue Watching rail's series-resume path: when watch_history
     * carries a non-null episode_id, we resolve it here and hand the
     * row off to PlaybackController.play(Playable.Episode) so the
     * resume offset is honoured. Returns null when the episode row
     * isn't cached locally — caller falls back to opening detail.
     */
    fun episodeById(id: String): EpisodeInfo? = db.episodesQueries.selectById(id).executeAsOneOrNull()?.let { row ->
        EpisodeInfo(
            id = row.id,
            seasonNumber = row.season_number?.toInt() ?: 0,
            episodeNumber = row.episode_number?.toInt() ?: 0,
            title = row.title.orEmpty(),
            streamUrl = row.stream_url,
            duration = row.duration?.toString(),
        )
    }
}

private fun com.yancotv.shared.db.Content.toDomain(): ContentItem = ContentItem(
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
)

private val ContentType.dbValue: String
    get() =
        when (this) {
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
