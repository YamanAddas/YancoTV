package com.yancotv.shared.history

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.yancotv.shared.db.SelectRecentWithContent
import com.yancotv.shared.db.Watch_history
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.logger.Log
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.HistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
     *
     * Audit catch — previously did SELECT * FROM watch_history then a
     * per-content_id selectById loop (N+1: 1 + ~30 PK lookups per
     * emit). Now uses the JOIN-backed selectRecentWithContent so the
     * Flow does ONE query. Same algorithm for both `recent()` and
     * `recentFlow()`.
     */
    fun recent(limit: Long = 30): List<HistoryEntry> = db.watchHistoryQueries
        .selectRecentWithContent(limit)
        .executeAsList()
        .map { row -> row.toHistoryEntry() }

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
        .selectRecentWithContent(limit)
        .asFlow()
        .mapToList(Dispatchers.Default)
        .map { rows -> rows.map { it.toHistoryEntry() } }

    /**
     * MK.28.1 — Batched reactive tile-progress lookup. Returns a map keyed
     * by `contentId` whose values describe the most relevant watch_history
     * row for that content: prefer the content-level (container) row, fall
     * back to the most-recent episode-level row.
     *
     * Why prefer container over episode: a movie's only row is its own
     * container row (no episodes). A series container has no playable
     * stream of its own, so its tile should reflect the user's
     * most-recent-episode progress instead — that's the Netflix-style
     * "you're 24 min into S2E5" affordance.
     *
     * Empty input short-circuits to an empty emission so a coverflow with
     * no visible items doesn't fire a `WHERE col IN ()` (invalid SQL).
     *
     * Re-emits on every watch_history write (via SQLDelight's
     * notification graph), so any tile subscribed via this flow
     * auto-updates the instant the player persists a new offset — same
     * mechanism the Home Continue Watching rail uses.
     *
     * Dispatches to [Dispatchers.Default] (NOT [Dispatchers.IO]) so this
     * compiles for iOS — `Dispatchers.IO` is JVM/Android only.
     */
    fun entriesByContentFlow(contentIds: Set<String>): Flow<Map<String, WatchProgress>> {
        if (contentIds.isEmpty()) return flowOf(emptyMap())
        return db.watchHistoryQueries
            .selectByContentIds(contentIds)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> resolveContentProgress(rows) }
    }

    /**
     * MB-374 — reactive tile-progress map over the ENTIRE watch_history table,
     * keyed by `content_id`. Same container-preferred resolution as
     * [entriesByContentFlow] but with no `IN`-list: the browse coverflow
     * subscribes once and looks each visible tile up by id, so the number of
     * items a category has paged in never feeds into the SQLite bind-variable
     * count (999 on API <= 30). watch_history holds one row per watched title —
     * a small table — so selecting it whole is cheap, and it re-emits on every
     * write exactly like the batched variant.
     */
    fun allProgressFlow(): Flow<Map<String, WatchProgress>> = db.watchHistoryQueries
        .selectAllProgress()
        .asFlow()
        .mapToList(Dispatchers.Default)
        .map { rows -> resolveContentProgress(rows) }

    /**
     * The same map, read once.
     *
     * SwiftUI has no `Flow`, so iOS reads this per browse page rather than
     * subscribing. Whole-table for the reason [allProgressFlow] gives: an
     * `IN` list would put the number of paged-in items into SQLite's
     * bind-variable count, and `watch_history` holds one row per watched
     * title — a small table, cheap to select entire.
     */
    fun allProgress(): Map<String, WatchProgress> =
        resolveContentProgress(db.watchHistoryQueries.selectAllProgress().executeAsList())

    /**
     * Container-preferred resolution shared by [entriesByContentFlow] and
     * [allProgressFlow] (AGENTS rule 8 — one mapper, no drift). Prefer the
     * content-level (container) row; fall back to the most-recent episode row.
     * Rows arrive `watched_at DESC`, so `.first()` on the episode-only fallback
     * is the newest episode.
     */
    private fun resolveContentProgress(rows: List<Watch_history>): Map<String, WatchProgress> {
        if (rows.isEmpty()) return emptyMap()
        return rows.groupBy { it.content_id }.mapValues { (_, group) ->
            val row = group.firstOrNull { it.episode_id == null } ?: group.first()
            WatchProgress(
                contentId = row.content_id,
                episodeId = row.episode_id,
                positionSeconds = row.position_seconds,
                durationSeconds = row.duration_seconds,
                watchedAt = row.watched_at,
            )
        }
    }

    /**
     * MK.28.1 — Batched reactive lookup keyed by `episode_id`. Used by the
     * series detail screen to render a per-episode progress stripe + ✓
     * mark on the episode list.
     *
     * Empty input short-circuits to an empty emission. Re-emits on every
     * watch_history write. `WHERE episode_id IN (...)` filters out
     * content-level (null episode_id) rows at the SQL layer.
     */
    fun entriesByEpisodeFlow(episodeIds: Set<String>): Flow<Map<String, WatchProgress>> {
        if (episodeIds.isEmpty()) return flowOf(emptyMap())
        return db.watchHistoryQueries
            .selectByEpisodeIds(episodeIds)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map(::resolveEpisodeProgress)
    }

    /**
     * The same map, read once — the shape SwiftUI needs, which has no
     * `Flow`. Mirrors [allProgress] beside [allProgressFlow].
     */
    fun entriesByEpisode(episodeIds: Set<String>): Map<String, WatchProgress> {
        if (episodeIds.isEmpty()) return emptyMap()
        return resolveEpisodeProgress(
            db.watchHistoryQueries.selectByEpisodeIds(episodeIds).executeAsList(),
        )
    }

    /** One mapper for both readers (AGENTS rule 8 — no drift). */
    private fun resolveEpisodeProgress(rows: List<Watch_history>): Map<String, WatchProgress> {
        if (rows.isEmpty()) return emptyMap()
        // groupBy by the non-null episode_id, then pick the most-recent row
        // per group (already DESC). Rows are guaranteed non-null on
        // episode_id because SQL's `IN` on a non-null column filters NULLs
        // — but defensively keep only non-null.
        return rows.mapNotNull { row ->
            val key = row.episode_id ?: return@mapNotNull null
            key to row
        }.groupBy({ it.first }, { it.second }).mapValues { (_, group) ->
            val row = group.first()
            WatchProgress(
                contentId = row.content_id,
                episodeId = row.episode_id,
                positionSeconds = row.position_seconds,
                durationSeconds = row.duration_seconds,
                watchedAt = row.watched_at,
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
     * Watch-history info for a *specific* episode (by episode id). Used
     * by the series detail screen's episode-tile click handler to
     * decide whether tapping a tile should resume from the saved offset
     * or restart from 0 — Netflix-style: mid-stream → resume; finished
     * (≥95%) → restart.
     *
     * Returns null when no row exists for [episodeId]. Callers should
     * treat that the same as "finished" (no progress to resume to —
     * play from 0).
     */
    fun episodeInfo(episodeId: String): EpisodeResumeInfo? {
        val row = db.watchHistoryQueries.selectByEpisode(episodeId).executeAsOneOrNull() ?: return null
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
    fun hasAnyForContent(contentId: String): Boolean = db.watchHistoryQueries.selectByContent(contentId).executeAsList().isNotEmpty()

    fun removeForContent(contentId: String) {
        db.watchHistoryQueries.deleteByContent(contentId)
    }

    fun clearAll() {
        db.watchHistoryQueries.clearAll()
    }
}

/**
 * MK.28.1 — Lean tile-progress record. Holds the bare minimum needed to
 * render a Netflix-style progress stripe + "Resume / Watched" badge on a
 * VOD tile, without dragging the full [HistoryEntry] join through the
 * batched lookup path.
 *
 * Keep the shape minimal so the [entriesByContentFlow] /
 * [entriesByEpisodeFlow] payload is cheap to recompute on every
 * watch_history write — a busy player session can fire 1 Hz upserts.
 */
data class WatchProgress(
    /** Content row this progress belongs to. Always populated. */
    val contentId: String,
    /** Non-null when the row tracks an episode; null for movie / live container rows. */
    val episodeId: String?,
    /** Media offset in seconds (NOT ms — `watch_history.position_seconds` is seconds). */
    val positionSeconds: Long,
    /** Media duration in seconds. Null when unknown (live, some VOD without metadata). */
    val durationSeconds: Long?,
    /** Wall clock (ms since epoch) of the last persist. Used for "watched at" recency UI. */
    val watchedAt: Long,
) {
    /**
     * 0..1 progress ratio for the progress stripe. Returns 0 when
     * duration is unknown so the UI can hide / show a "watched" indicator
     * without a known endpoint to compare against.
     */
    val ratio: Float
        get() {
            val d = durationSeconds ?: return 0f
            if (d <= 0L) return 0f
            return (positionSeconds.toFloat() / d.toFloat()).coerceIn(0f, 1f)
        }

    /** True when the row is at the ≥95% "finished" threshold. */
    fun isFinished(): Boolean = isWatchRowFinished(positionSeconds, durationSeconds)

    /** Remaining seconds, coerced ≥ 0. Returns null when duration unknown. */
    fun remainingSeconds(): Long? {
        val d = durationSeconds ?: return null
        return (d - positionSeconds).coerceAtLeast(0L)
    }
}

data class EpisodeResumeInfo(val episodeId: String, val positionSeconds: Long, val durationSeconds: Long?, val watchedAt: Long) {
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

/**
 * Maps a single joined `watch_history × content` row into [HistoryEntry].
 * Shared between [WatchHistoryRepository.recent] and [recentFlow] so
 * both paths use identical decoding (audit catch — was duplicated body
 * across the two functions).
 */
private fun SelectRecentWithContent.toHistoryEntry(): HistoryEntry = HistoryEntry(
    id = wh_id,
    contentId = wh_content_id,
    episodeId = wh_episode_id,
    positionSeconds = wh_position_seconds.toDouble(),
    durationSeconds = wh_duration_seconds?.toDouble(),
    watchedAt = wh_watched_at,
    content = ContentItem(
        id = c_id,
        sourceId = c_source_id,
        type = contentTypeFromDb(c_type),
        title = c_title,
        cleanTitle = c_clean_title,
        groupName = c_group_name,
        streamUrl = c_stream_url,
        logoUrl = c_logo_url,
        tvgId = c_tvg_id,
        metadataJson = c_metadata_json,
        sortOrder = c_sort_order.toInt(),
        createdAt = c_created_at,
        nameOverride = c_name_override,
        logoOverride = c_logo_override,
    ),
)
