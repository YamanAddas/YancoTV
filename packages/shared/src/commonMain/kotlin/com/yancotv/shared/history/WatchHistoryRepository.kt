package com.yancotv.shared.history

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.logger.Log
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.HistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
class WatchHistoryRepository(private val db: YancoDb, private val clock: () -> Long) {
    /**
     * Save a resume point. Pass [positionSeconds] = 0 (or equal to
     * [durationSeconds]) to effectively mark a title as watched; caller
     * decides whether to display a resume badge for such rows.
     *
     * Best-effort: any DB failure (FK violation, disk full, transient
     * lock, schema mismatch) is logged and swallowed. Resume points are
     * telemetry — losing one row must never crash the app. The caller
     * gets no signal because there's no caller-actionable response.
     * [CancellationException] is re-thrown so coroutine cancellation
     * still propagates correctly.
     */
    fun upsert(contentId: String, episodeId: String? = null, positionSeconds: Long, durationSeconds: Long? = null) {
        val id = if (episodeId != null) "wh:$contentId:$episodeId" else "wh:$contentId"
        try {
            db.watchHistoryQueries.upsert(
                id = id,
                content_id = contentId,
                episode_id = episodeId,
                position_seconds = positionSeconds,
                duration_seconds = durationSeconds,
                watched_at = clock(),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.l.w(t) { "WatchHistory.upsert failed (contentId=$contentId, episodeId=$episodeId): ${t.message}" }
        }
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
        val contents =
            byContent.keys
                .mapNotNull { id ->
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
                content =
                ContentItem(
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
                    nameOverride = c.name_override,
                    logoOverride = c.logo_override,
                ),
            )
        }
    }

    /**
     * Reactive [recent] — backed by SQLDelight's [asFlow]. Emits the
     * current snapshot on collection, then re-emits every time any
     * `watch_history` write notifier fires (including the
     * `PlaybackController.persistResumePoint` upserts that fire on
     * pause / stop / queue transition / lifecycle hooks).
     *
     * The Home screen's Continue Watching rail subscribes to this so a
     * just-watched movie's new offset reflects the moment the user
     * exits the player — without the rail needing to know writes
     * happened. Mirrors [com.yancotv.shared.favorites.FavoritesRepository.allFlow].
     *
     * Per-row content join runs inside the [map] — re-fetching ~30
     * `content` rows on every history write is cheap (indexed by id)
     * and keeps the Flow snapshot self-contained without a custom
     * SQLDelight join query.
     *
     * Dispatches to [Dispatchers.Default] (NOT [Dispatchers.IO]) so the
     * common-main code compiles for iOS — `Dispatchers.IO` is JVM /
     * Android only.
     */
    fun recentFlow(limit: Long = 30): Flow<List<HistoryEntry>> = db.watchHistoryQueries
        .selectRecent(limit)
        .asFlow()
        .mapToList(Dispatchers.Default)
        .map { rows ->
            if (rows.isEmpty()) return@map emptyList()
            val byContent = rows.groupBy { it.content_id }
            val contents =
                byContent.keys
                    .mapNotNull { id -> db.contentQueries.selectById(id).executeAsOneOrNull() }
                    .associateBy { it.id }
            rows.mapNotNull { row ->
                val c = contents[row.content_id] ?: return@mapNotNull null
                HistoryEntry(
                    id = row.id,
                    contentId = row.content_id,
                    episodeId = row.episode_id,
                    positionSeconds = row.position_seconds.toDouble(),
                    durationSeconds = row.duration_seconds?.toDouble(),
                    watchedAt = row.watched_at,
                    content =
                    ContentItem(
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
                        nameOverride = c.name_override,
                        logoOverride = c.logo_override,
                    ),
                )
            }
        }

    /**
     * Resume position (seconds) for a content-level row — that is, a row
     * with `episode_id IS NULL`. Returns null if there's no such row, or
     * if the row is already at the "finished" threshold (≥95% of duration)
     * so the player starts the title fresh on next open instead of
     * seeking straight to the credits and immediately re-firing STATE_ENDED.
     *
     * Do NOT fall through to episode rows here: a series container must not
     * seek to some arbitrary episode's offset just because that episode
     * happens to be the most recent entry under the same `content_id`.
     */
    fun positionFor(contentId: String): Long? {
        val row = db.watchHistoryQueries.selectByContent(contentId).executeAsList()
            .firstOrNull { it.episode_id == null } ?: return null
        if (isWatchRowFinished(row.position_seconds, row.duration_seconds)) return null
        return row.position_seconds
    }

    /**
     * Resume position (seconds) for an episode's own row, keyed by
     * `episode_id`. Returns null if the user has never opened this episode,
     * if the prior session never reached the persistResumePoint threshold,
     * or if the row is already at the "finished" threshold (≥95% of
     * duration) — re-tapping a finished episode must restart it from the
     * beginning, not seek to credits and chain into autoplay.
     *
     * This is the EPISODE counterpart to [positionFor] — they intentionally
     * use different lookups so a series-container open can't accidentally
     * seek to an arbitrary episode's offset (and vice versa: an episode
     * open can't pull the parent series' content-level row, which would
     * be junk).
     */
    fun positionForEpisode(episodeId: String): Long? {
        val row = db.watchHistoryQueries.selectByEpisode(episodeId).executeAsOneOrNull() ?: return null
        if (isWatchRowFinished(row.position_seconds, row.duration_seconds)) return null
        return row.position_seconds
    }

    /**
     * Most recently watched episode-level row for a series, or null if
     * the user has never watched any episode of [contentId]. Used by the
     * series detail page to label the Play button as "Resume SxEy"
     * (mid-episode) or "Play SxEy" (next episode after a finished one).
     *
     * Returns the episode ID + offset; the caller resolves the actual
     * EpisodeInfo against the loaded episode list. Series containers'
     * own content-level rows are intentionally ignored — `positionFor`
     * is the right primitive for those.
     */
    fun mostRecentEpisode(contentId: String): EpisodeResumeInfo? {
        val rows = db.watchHistoryQueries.selectByContent(contentId).executeAsList()
        val row = rows.firstOrNull { it.episode_id != null } ?: return null
        return EpisodeResumeInfo(
            episodeId = row.episode_id ?: return null,
            positionSeconds = row.position_seconds,
            durationSeconds = row.duration_seconds,
            watchedAt = row.watched_at,
        )
    }

    /**
     * Whether any watch_history row exists for [contentId] — content-
     * level OR any of its episode rows (all share the same `content_id`
     * FK target per the schema's "episodes write seriesId as content_id"
     * convention).
     *
     * Cheaper than fetching the rows for callers that only need the
     * boolean, e.g. the detail screen deciding whether to render the
     * "Reset progress" button.
     */
    fun hasAnyForContent(contentId: String): Boolean =
        db.watchHistoryQueries.selectByContent(contentId).executeAsList().isNotEmpty()

    fun removeForContent(contentId: String) {
        db.watchHistoryQueries.deleteByContent(contentId)
    }

    fun clearAll() {
        db.watchHistoryQueries.clearAll()
    }
}

data class EpisodeResumeInfo(
    val episodeId: String,
    val positionSeconds: Long,
    val durationSeconds: Long?,
    val watchedAt: Long,
) {
    /**
     * Treat ≥95% of duration as "finished". Anything less is mid-episode
     * (Resume). Matches the rule of thumb used by mainstream streaming
     * apps; tighter than 100% to handle credits sequences and trailing
     * tail-end skips that don't represent real continuation intent.
     */
    fun isFinished(): Boolean = isWatchRowFinished(positionSeconds, durationSeconds)
}

/**
 * Whether a watch_history row should be treated as "finished" (≥95% of
 * known duration). Shared between [EpisodeResumeInfo.isFinished] (UI
 * label switch on the series detail page) and [WatchHistoryRepository]'s
 * resume lookups (which return null on finished rows so the player
 * starts fresh instead of seeking to the credits).
 *
 * Rows with unknown / non-positive duration are never "finished" by
 * this rule — there's no denominator to compare against. The player
 * has a separate explicit completion path (`PlaybackController.markCurrentCompleted`)
 * that handles those on STATE_ENDED. Integer math (×100 / ×95)
 * intentional: avoids floating-point comparison quirks at the
 * boundary.
 */
internal fun isWatchRowFinished(positionSeconds: Long, durationSeconds: Long?): Boolean {
    val dur = durationSeconds ?: return false
    if (dur <= 0L) return false
    return positionSeconds * 100L >= dur * 95L
}

private fun contentTypeFromDb(value: String): ContentType = when (value) {
    "live" -> ContentType.LIVE
    "movie" -> ContentType.MOVIE
    "series" -> ContentType.SERIES
    else -> error("Unknown content type: $value")
}
