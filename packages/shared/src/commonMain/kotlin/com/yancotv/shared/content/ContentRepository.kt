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
    /**
     * @param sourceId MK.33.1 — when non-null, restrict to one playlist. Needed
     *   because two playlists routinely ship a group with the same name, so a
     *   group name alone stopped being a unique filter once the category rail
     *   started offering groups per playlist.
     */
    fun count(type: ContentType, group: String? = null, sourceId: String? = null): Long {
        val t = type.dbValue
        return when {
            group == null && sourceId == null -> db.contentQueries.countByType(t).executeAsOne()
            group == null -> db.contentQueries.countByTypeAndSource(t, sourceId!!).executeAsOne()
            sourceId == null -> db.contentQueries.countByTypeAndGroup(t, group).executeAsOne()
            else -> db.contentQueries.countByTypeGroupAndSource(t, group, sourceId).executeAsOne()
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

    /**
     * MK.33.1 — this content type's groups, split per playlist.
     *
     * Returns one entry per playlist that actually contributes rows of [type],
     * in the order [orderedSources] gives (the caller passes the user's
     * `sources` order so the rail matches the Sources screen). Groups within a
     * playlist stay in provider order.
     *
     * A playlist with no rows of this type is omitted rather than returned
     * empty: an Xtream account with no series should not put an empty "Series"
     * dropdown in the rail.
     *
     * @param orderedSources id-to-display-name pairs. Names come from the
     *   `sources` table — a `source_id` is a UUID and is never rendered.
     */
    fun groupsBySource(type: ContentType, orderedSources: List<Pair<String, String>>): List<SourceCategoryTreeBuilder.SourceGroups> {
        val rows = db.contentQueries.distinctGroupsBySourceForType(type.dbValue).executeAsList()
        // Bucket first, then emit in the caller's order. Iterating the rows
        // directly would emit in source_id (i.e. UUID) order, which is
        // arbitrary from the user's point of view.
        val bySource = LinkedHashMap<String, MutableList<String>>()
        for (row in rows) {
            val group = row.group_name ?: continue
            bySource.getOrPut(row.source_id) { mutableListOf() }.add(group)
        }
        return orderedSources.mapNotNull { (id, name) ->
            val groups = bySource[id] ?: return@mapNotNull null
            SourceCategoryTreeBuilder.SourceGroups(sourceId = id, sourceName = name, groups = groups)
        }
    }

    /** @param sourceId see [count]. */
    fun page(type: ContentType, group: String? = null, offset: Long, limit: Long, sourceId: String? = null): List<ContentItem> {
        val t = type.dbValue
        val rows =
            when {
                group == null && sourceId == null ->
                    db.contentQueries.listByTypePaged(t, limit, offset).executeAsList()
                group == null ->
                    db.contentQueries.listByTypeAndSourcePaged(t, sourceId!!, limit, offset).executeAsList()
                sourceId == null ->
                    db.contentQueries.listByTypeAndGroupPaged(t, group, limit, offset).executeAsList()
                else ->
                    db.contentQueries
                        .listByTypeGroupAndSourcePaged(t, group, sourceId, limit, offset)
                        .executeAsList()
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

    /**
     * Episode immediately after [currentEpisodeId] within the same series,
     * ordered by `(season_number, episode_number)`. Returns null when the
     * current episode is the last one cached locally, or when the episode
     * id can't be located in the series' list (stale id, sync drift).
     *
     * Backs the autoplay-next-episode flow: when an episode hits
     * STATE_ENDED and the user has the autoplay pref on, PlayerActivity
     * resolves the next episode here and hands it off to
     * `PlaybackController.play(Playable.Episode)` for seamless playback.
     */
    fun nextEpisodeAfter(seriesId: String, currentEpisodeId: String): EpisodeInfo? {
        val rows = db.episodesQueries.selectByContent(seriesId).executeAsList()
        val idx = rows.indexOfFirst { it.id == currentEpisodeId }
        if (idx < 0 || idx >= rows.size - 1) return null
        val next = rows[idx + 1]
        return EpisodeInfo(
            id = next.id,
            seasonNumber = next.season_number?.toInt() ?: 0,
            episodeNumber = next.episode_number?.toInt() ?: 0,
            title = next.title.orEmpty(),
            streamUrl = next.stream_url,
            duration = next.duration?.toString(),
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
