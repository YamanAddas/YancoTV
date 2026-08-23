package com.yancotv.shared.sources

/**
 * MB-363 — decide whether a source flagged `auto_sync_on_start` actually
 * needs syncing right now.
 *
 * Before this, it always did. `selectAutoSyncOnStart` filters on
 * `auto_sync_on_start = 1 AND is_active = 1` and nothing else, and the caller
 * synced every row it returned. On a 272,910-item catalogue that meant every
 * cold start wiped the `content` table and rebuilt it over roughly fifteen
 * minutes, with Home, browse and the guide empty throughout — and it
 * re-downloaded the whole playlist each time.
 *
 * The intent was always interval-based: `sources.auto_sync_interval` has been
 * carried in the schema, the backup format and the restore path since the
 * beginning. Nothing ever read it. This is where it finally gets read.
 *
 * Units are milliseconds, per the project rule that every timestamp and
 * duration in the database is milliseconds except the two media-offset
 * columns on `watch_history`.
 */
object SyncOnStartDecision {
    /** Used when a source carries no explicit interval (the column defaults to 0). */
    const val DEFAULT_INTERVAL_MS: Long = 12L * 60L * 60L * 1000L

    /**
     * @param lastSyncedMs when this source last completed a sync, or null/0 if never.
     * @param autoSyncIntervalMs the source's configured interval; <= 0 means "unset".
     * @param nowMs current wall clock.
     *
     * Returns true when the catalogue is stale enough to be worth the rebuild.
     */
    fun shouldSync(lastSyncedMs: Long?, autoSyncIntervalMs: Long, nowMs: Long, defaultIntervalMs: Long = DEFAULT_INTERVAL_MS): Boolean {
        // Never synced — there is no catalogue yet, so this is not a rebuild,
        // it is the first build. Always run.
        if (lastSyncedMs == null || lastSyncedMs <= 0L) return true

        val interval = if (autoSyncIntervalMs > 0L) autoSyncIntervalMs else defaultIntervalMs

        // Clock moved backwards (timezone change, NTP correction, user edit).
        // Treat a future last_synced as "just synced" rather than syncing on
        // every launch until wall clock catches up.
        if (lastSyncedMs > nowMs) return false

        return (nowMs - lastSyncedMs) >= interval
    }
}
