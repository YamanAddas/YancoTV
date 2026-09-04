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
     * The same categories as [groups], each with how many rows it holds.
     *
     * Same ordering (provider order), one aggregate query rather than a
     * count per category — see the note on `groupTalliesForType`.
     */
    fun groupTallies(type: ContentType): List<Pair<String, Int>> = db.contentQueries
        .groupTalliesForType(type.dbValue) { name, count -> name to count.toInt() }
        .executeAsList()

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
     * MK.35.2 — categories ordered largest-first, for Home's category rails.
     *
     * [groups] returns provider order, which is right for Browse (a complete
     * list the user scans) and wrong for Home (three rails, chosen for them).
     * Provider order would put whatever sits first in the playlist on the home
     * screen — on a 272,419-item catalogue as likely to be a near-empty test
     * bucket as anything worth watching. Only a fallback: pins win when the user
     * has any. See `pickCategoryRails`.
     */
    fun topGroups(type: ContentType, limit: Long): List<String> = db.contentQueries
        .topGroupsForType(type.dbValue, limit)
        .executeAsList()
        .mapNotNull { it.group_name }

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

    /**
     * MB-382 — count across a merged category's underlying provider group
     * names ([CategoryMerger]). The list is small (a handful of names), so the
     * `IN` clause stays well under SQLite's bind-variable limit.
     */
    fun countByGroups(type: ContentType, groups: List<String>): Long = if (groups.isEmpty()) {
        0L
    } else {
        db.contentQueries.countByTypeAndGroups(type.dbValue, groups).executeAsOne()
    }

    /** MB-382 — page across a merged category's underlying provider group names. */
    fun pageByGroups(type: ContentType, groups: List<String>, offset: Long, limit: Long): List<ContentItem> = if (groups.isEmpty()) {
        emptyList()
    } else {
        db.contentQueries
            .listByTypeAndGroupsPaged(type.dbValue, groups, limit, offset)
            .executeAsList()
            .map { it.toDomain() }
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
        val fts = ftsQueryOrNull(query) ?: return emptyList()
        return db.contentQueries
            .searchFts(fts, limit)
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
    fun searchByType(query: String, type: ContentType, limit: Long = 100): List<ContentItem> = searchByTypePaged(query, type, offset = 0L, limit = limit)

    /**
     * MK.iOS.PAGE.2 — paged [searchByType].
     *
     * A search that matches more rows than one page used to stop at the
     * limit with nothing saying so — the same silent truncation the browse
     * paging removed. Pair with [searchCountByType] to show how much is
     * being held back.
     */
    fun searchByTypePaged(query: String, type: ContentType, offset: Long, limit: Long): List<ContentItem> {
        val fts = ftsQueryOrNull(query) ?: return emptyList()
        return db.contentQueries
            .searchFtsByTypePaged(fts, type.dbValue, limit, offset)
            .executeAsList()
            .map { it.toDomain() }
    }

    /** MK.iOS.PAGE.2 — total matches for [query] within [type]. */
    fun searchCountByType(query: String, type: ContentType): Long {
        val fts = ftsQueryOrNull(query) ?: return 0L
        return db.contentQueries.countSearchFtsByType(fts, type.dbValue).executeAsOne()
    }

    fun findById(id: String): ContentItem? = db.contentQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toDomain()

    /**
     * MB-381 — resolve a content row by its exact `stream_url`. Used when
     * scheduling a recording / resolving catch-up so the stored `content_id`
     * refers to the SAME channel as the `stream_url` being recorded. Keying on
     * `tvg_id` instead (via [findLiveByTvgId]) picks an arbitrary highest-
     * priority row when many channels share a junk tvg_id, which desynchronised
     * the recorded URL from the content identity. Returns the most recent match
     * when a URL collides (rare). Null when the URL isn't in the catalogue.
     */
    fun findIdByStreamUrl(streamUrl: String): String? {
        if (streamUrl.isBlank()) return null
        return db.contentQueries.findIdByStreamUrl(streamUrl).executeAsOneOrNull()
    }

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
            // `duration` is the provider's own `HH:MM:SS` string everywhere
            // else it appears, and this column holds an integer count of
            // seconds — `toString()` on it produced "2732" where the UI
            // prints a runtime, which was harmless only while the column
            // was written as NULL unconditionally. The seconds have their
            // own field.
            durationSeconds = row.duration?.toInt(),
            stillUrl = row.still_url,
            airDate = row.air_date,
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
            durationSeconds = next.duration?.toInt(),
            stillUrl = next.still_url,
            airDate = next.air_date,
        )
    }
}

// MK.35.3 — widened from `private` to `internal` so RecentChannelsRepository can
// map rows without a second copy of this. Duplicating it would be exactly the
// drift AGENTS.md rule 8 warns about: two mappers, one of which quietly stops
// applying name_override / logo_override the next time those change.
internal fun com.yancotv.shared.db.Content.toDomain(): ContentItem = ContentItem(
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

/**
 * Turns a user's typing into an FTS4 MATCH expression, or null when there
 * is nothing to search for.
 *
 * Was copy-pasted between [ContentRepository.search] and
 * `searchByType` — two copies of the escaping rules, one of which would
 * eventually stop matching the other. `internal` so it can be tested
 * directly: this is where a search stops being a `LIKE` and starts being
 * something a user would call smart, and it is worth pinning down.
 *
 * Three things it does:
 * - **Every token gets a trailing `*`**, so "mar" finds "Marvel". A full
 *   word still matches — the wildcard only widens.
 * - **Every token is quoted**, so punctuation a user types (`beIN:`,
 *   `AR|`) is data rather than FTS operator syntax. An unquoted `-` or
 *   `*` mid-query is otherwise a NOT / wildcard and silently changes what
 *   was asked for.
 * - **Embedded double quotes are doubled**, per FTS4's phrase escaping.
 *
 * Tokens are joined by whitespace, which FTS4 reads as AND — so more
 * words narrows, which is what typing more usually means.
 */
internal fun ftsQueryOrNull(query: String): String? {
    val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") { token ->
        "\"" + token.replace("\"", "\"\"") + "\"*"
    }
}

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
