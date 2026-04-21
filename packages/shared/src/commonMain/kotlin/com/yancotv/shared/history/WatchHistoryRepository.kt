package com.yancotv.shared.history

import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.HistoryEntry

/**
 * Watch-history writes and resume-point lookups. Live channels are skipped —
 * "resume CNN at 14:32" makes no sense for a linear stream. VOD (movies,
 * episodes) upserts on every pause/stop so closing the app mid-film resumes
 * where you left off.
 *
 * Rows are joined back with [content] when reading so the UI can render
 * title + artwork without a second query. `INNER JOIN` against `content`
 * naturally drops history rows whose content was deleted (e.g. source
 * removed) — the DB still carries the orphan thanks to `ON DELETE CASCADE`
 * on the FK, but this is the belt to that suspenders.
 */
class WatchHistoryRepository(
    private val db: YancoDb,
    private val clock: () -> Long,
) {

    /**
     * Save a resume point. Pass [positionSeconds] = 0 (or equal to
     * [durationSeconds]) to effectively mark a title as watched; caller
     * decides whether to display a resume badge for such rows.
     */
    fun upsert(
        contentId: String,
        episodeId: String? = null,
        positionSeconds: Long,
        durationSeconds: Long? = null,
    ) {
        val id = if (episodeId != null) "wh:$contentId:$episodeId" else "wh:$contentId"
        db.watchHistoryQueries.upsert(
            id = id,
            content_id = contentId,
            episode_id = episodeId,
            position_seconds = positionSeconds,
            duration_seconds = durationSeconds,
            watched_at = clock(),
        )
    }

    /**
     * Most recently watched items (VOD first, since live isn't recorded).
     * The UI uses this to build a "Continue watching" row.
     */
    fun recent(limit: Long = 30): List<HistoryEntry> {
        val rows = db.watchHistoryQueries.selectRecent(limit).executeAsList()
        if (rows.isEmpty()) return emptyList()
        // Join is done client-side because SQLDelight's JOIN syntax with
        // multiple tables requires a dedicated query file; keeping it here
        // avoids a migration just to grow `WatchHistory.sq`.
        val byContent = rows.groupBy { it.content_id }
        val contents = byContent.keys.mapNotNull { id ->
            db.contentQueries.selectById(id).executeAsOneOrNull()
        }.associateBy { it.id }
        return rows.mapNotNull { row ->
            val c = contents[row.content_id] ?: return@mapNotNull null
            HistoryEntry(
                id = row.id,
                contentId = row.content_id,
                episodeId = row.episode_id,
                positionSeconds = row.position_seconds.toDouble(),
                durationSeconds = row.duration_seconds?.toDouble(),
                watchedAt = row.watched_at,
                content = ContentItem(
                    id = c.id,
                    sourceId = c.source_id,
                    type = contentTypeFromDb(c.type),
                    title = c.title,
                    cleanTitle = c.clean_title,
                    groupName = c.group_name,
                    streamUrl = c.stream_url,
                    logoUrl = c.logo_url,
                    tvgId = c.tvg_id,
                    metadataJson = c.metadata_json,
                    sortOrder = c.sort_order.toInt(),
                    createdAt = c.created_at,
                ),
            )
        }
    }

    /**
     * Resume position (seconds) for a content-level row — that is, a row
     * with `episode_id IS NULL`. Returns null if there's no such row.
     *
     * Do NOT fall through to episode rows here: a series container must not
     * seek to some arbitrary episode's offset just because that episode
     * happens to be the most recent entry under the same `content_id`.
     */
    fun positionFor(contentId: String): Long? {
        val rows = db.watchHistoryQueries.selectByContent(contentId).executeAsList()
        return rows.firstOrNull { it.episode_id == null }?.position_seconds
    }

    fun removeForContent(contentId: String) {
        db.watchHistoryQueries.deleteByContent(contentId)
    }

    fun clearAll() {
        db.watchHistoryQueries.clearAll()
    }
}

private fun contentTypeFromDb(value: String): ContentType = when (value) {
    "live" -> ContentType.LIVE
    "movie" -> ContentType.MOVIE
    "series" -> ContentType.SERIES
    else -> error("Unknown content type: $value")
}
