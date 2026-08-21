package com.yancotv.shared.history

import com.yancotv.shared.content.toDomain
import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType

/**
 * MK.35.3 — the live channels a user actually watched, most recent first.
 *
 * Deliberately separate from [WatchHistoryRepository] rather than folded into
 * it. `resumePointDecision` returns null for `ContentType.LIVE` because a live
 * stream has no meaningful resume offset, and that is right — writing one would
 * make the player seek into a live edge on the next open. But it meant live
 * content could never reach Home at all unless the user had starred a channel.
 * "Where was I" and "what do I watch" are different questions; only the first
 * needs an offset.
 */
class RecentChannelsRepository(private val db: YancoDb, private val clock: () -> Long) {

    /**
     * Record a watch, if [watchedMs] clears the dwell threshold.
     *
     * Returns true when a row was written, so callers and tests can assert on
     * the decision rather than inferring it from a later read.
     */
    fun record(item: ContentItem, watchedMs: Long): Boolean {
        if (!shouldRecordChannel(item.type, watchedMs)) return false
        db.recentChannelsQueries.recordWatch(content_id = item.id, watched_at = clock())
        db.recentChannelsQueries.trimToNewest(MAX_ROWS)
        return true
    }

    /** Most recently watched channels that still exist in the catalogue. */
    fun recent(limit: Long = 20L): List<ContentItem> = db.recentChannelsQueries.recentChannels(limit).executeAsList().map { it.toDomain() }

    fun clear() {
        db.recentChannelsQueries.clearAll()
    }

    companion object {
        /**
         * Rows kept. Generous — the list is (id, timestamp) pairs — but bounded,
         * because a channel-surfer would otherwise accumulate a row per channel
         * across 53,167 of them.
         */
        const val MAX_ROWS = 100L
    }
}

/**
 * Whether a watch is long enough to count as "watched" rather than "passed
 * through".
 *
 * **This threshold is the entire difference between a useful rail and noise.**
 * The browse coverflow auto-previews LIVE channels on focus after a 400 ms
 * debounce, so without a dwell rule, scrolling the channel list would silently
 * record every channel the cursor rested on and the rail would become a replay
 * of the user's scrolling rather than a list of what they watch.
 *
 * 30 s is chosen against that behaviour, not picked round: it is far longer than
 * any plausible scroll-past, and short enough that genuinely sampling a channel
 * still counts. It is deliberately much larger than the 5 s used for VOD resume
 * points, because a resume point that is slightly wrong costs a seek while a
 * recent-channels entry that is wrong costs a permanently misleading Home rail.
 *
 * Pure and separate from the repository so the rule is testable without a
 * database, mirroring `resumePointDecision`.
 */
fun shouldRecordChannel(type: ContentType, watchedMs: Long, thresholdMs: Long = RECENT_CHANNEL_DWELL_MS): Boolean {
    if (type != ContentType.LIVE) return false
    return watchedMs >= thresholdMs
}

/** See [shouldRecordChannel]. */
const val RECENT_CHANNEL_DWELL_MS = 30_000L
