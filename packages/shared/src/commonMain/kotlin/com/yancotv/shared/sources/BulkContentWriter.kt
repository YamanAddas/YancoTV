package com.yancotv.shared.sources

import app.cash.sqldelight.db.SqlDriver
import com.yancotv.shared.content.classifyEntry
import com.yancotv.shared.content.cleanTitle
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER
import com.yancotv.shared.parsers.M3uEntry
import com.yancotv.shared.stalker.StalkerChannel
import com.yancotv.shared.stalker.StalkerSeriesItem
import com.yancotv.shared.stalker.StalkerVodItem
import com.yancotv.shared.types.ContentMetadata
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.xtream.XtreamClient
import com.yancotv.shared.xtream.XtreamLiveStream
import com.yancotv.shared.xtream.XtreamSeriesInfo
import com.yancotv.shared.xtream.XtreamStreamType
import com.yancotv.shared.xtream.XtreamVodStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Chunk-streaming bulk writer for Xtream catalog syncs. Replaces SQLDelight's
 * generated per-row inserts on the hot path.
 *
 * Lifecycle (caller is expected to follow this order):
 *   1. [prepareSource] — one tiny transaction: clear previous rows for this
 *      source and drop the `content_ai` FTS trigger. FTS will be rebuilt
 *      in one pass at the end.
 *   2. Any number of [writeLiveChunk] / [writeVodChunk] / [writeSeriesChunk]
 *      calls — each chunk is its own short transaction with one or more
 *      multi-row INSERTs (80 rows per statement). The write lock is
 *      released between chunks so the UI can keep querying.
 *   3. [finishSource] — bulk populate FTS for this source in one
 *      `INSERT … SELECT` and recreate the trigger.
 *
 * Why the chunked-transaction design:
 *   - A single "mega transaction" wrapping everything held the WAL write
 *     lock for the entire sync and froze the Guide/Home screens for
 *     minutes. Per-chunk transactions let SQLite release the lock
 *     between 500-row batches.
 *   - Multi-row INSERT still amortises statement prepare + SQL parse
 *     across 80 rows, which is the real per-row cost.
 *   - Dropping the FTS AFTER-INSERT trigger skips per-row tokenization
 *     during writes; one `INSERT … SELECT` at the end walks the table
 *     once in a tight C loop.
 *
 * Indexes are intentionally *not* dropped — B-tree inserts are fast, and
 * leaving the schema stable on a crash is more important than the small
 * theoretical speedup.
 */
class BulkContentWriter(
    private val driver: SqlDriver,
    /**
     * MK.35.1 — ms since epoch for first-seen stamping.
     *
     * Required and injected rather than defaulted: `shared` deliberately has no
     * kotlinx-datetime dependency, and the module's convention is a
     * platform-supplied clock (WatchHistoryRepository, SourceRepository,
     * EpgRepository all take this exact shape). Being injectable also makes the
     * initial-import-vs-new-titles rule testable without wall-clock waits.
     */
    private val clock: () -> Long,
    private val logger: Logger = NOOP_LOGGER,
) {
    /** MB-353 — which source this instance has already cleared. See [clearIfFirstWrite]. */
    private var clearedFor: String? = null

    private val json =
        Json {
            encodeDefaults = false
            explicitNulls = false
        }

    private fun encodeMeta(meta: ContentMetadata): String? = if (meta == ContentMetadata()) null else json.encodeToString(meta)

    // ───── Sync lifecycle ─────

    /**
     * MB-353 — this no longer deletes anything.
     *
     * It used to DELETE every `content` and `content_fts` row for the source
     * and **COMMIT**, before a single replacement row had been fetched. Every
     * failure after that point — a dead provider URL, an expired subscription,
     * no network, a process kill, or simply never getting the write connection
     * back (MB-315) — left the catalogue permanently empty, with no error shown
     * and `sources.channel_count` still reporting the pre-delete figure.
     *
     * Observed on a Fire TV: a sync stalled here, and five hours later the
     * user's 272,419-item catalogue was gone, the database had fallen from
     * 352 MB to 126 MB, and the guide had collapsed to 22 channels because the
     * EPG importer filters against live channels that no longer existed.
     *
     * The destruction is now deferred to [clearIfFirstWrite], which the chunk
     * writers call only when they have rows to write — so it happens once
     * replacement rows are actually in hand, and a total failure costs nothing at
     * all. Partial failure is still possible (some of the clear and some chunks
     * commit, the rest never arrive) and is what [SYNC_MARKER_PREFIX] exists to
     * make detectable.
     *
     * **Foreign-key handling — the favorites/history-survival fix.**
     * Schema declares `favorites.content_id` and `watch_history.content_id`
     * as `REFERENCES content(id) ON DELETE CASCADE`. With `foreign_keys=ON`
     * (the production default — see DatabaseFactory.android.kt's onOpen),
     * the `DELETE FROM content WHERE source_id = ?` in [clearIfFirstWrite] cascades and
     * silently wipes every favorite + history row pointing at this
     * source's content. Since `ContentIds.*` are deterministic, the
     * chunked re-INSERT recreates the same content_ids — but the
     * favorites are already gone.
     *
     * Sync's intent is "replace the catalog snapshot for this source";
     * the FK cascade's intent is "if the user removed a content row,
     * clean up its dependents." Sync isn't an actual content removal, so
     * we toggle the FK off across the entire prepare → chunks → finish
     * window and let [finishSource] sweep up genuinely-stale dependents
     * (favorites pointing at content that's no longer in the catalog
     * because the provider rotated it out). [abortSource] re-enables FK
     * on the error path; if the process crashes mid-sync, FK is re-armed
     * by the `setForeignKeyConstraintsEnabled(true)` call in
     * `DatabaseFactory.android.kt`'s `onOpen` on next launch.
     *
     * `PRAGMA foreign_keys` is a no-op inside a transaction (per SQLite
     * docs), so it must be issued BEFORE `BEGIN`.
     * The trigger DROP stays here: it must precede the inserts, and it is
     * self-healing on the next open via `DatabaseFactory`'s `onOpen` (MB-290).
     *
     * `PRAGMA foreign_keys` is a no-op inside a transaction (per SQLite
     * docs), so it must be issued BEFORE `BEGIN`.
     */
    fun prepareSource(sourceId: String) {
        clearedFor = null
        // The marker is committed on its own, BEFORE anything destructive can
        // run, so it survives a process kill that takes the sync with it.
        driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
        try {
            driver.execute(
                null,
                "INSERT OR REPLACE INTO settings (key, value) VALUES ('$SYNC_MARKER_PREFIX' || ?, '1')",
                1,
            ) { bindString(0, sourceId) }
            driver.execute(null, "COMMIT", 0)
        } catch (t: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0) }
            throw t
        }

        driver.execute(null, "PRAGMA foreign_keys = OFF", 0)
        driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
        try {
            driver.execute(null, "DROP TRIGGER IF EXISTS content_ai", 0)
            driver.execute(null, "COMMIT", 0)
        } catch (t: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0) }
            // Re-enable FK on the error path so the session doesn't leave
            // FK off if the caller doesn't call abortSource.
            runCatching { driver.execute(null, "PRAGMA foreign_keys = ON", 0) }
            throw t
        }
    }

    /**
     * Drop the source's previous catalogue, once, in batched transactions of
     * [CLEAR_BATCH_ROWS] (MB-353 for WHEN it runs, MB-315 for HOW).
     *
     * Every chunk writer calls this BEFORE opening its own transaction, and only
     * when it actually has rows to write. That ordering is MB-353's guarantee and
     * the important half: nothing is destroyed until a replacement is in hand, so
     * a sync that fails to deliver — dead URL, expired subscription, no network,
     * process kill before first byte — costs nothing at all.
     *
     * **It deliberately does NOT run inside the caller's transaction, and that is
     * a weakening of MB-353's original shape.** The first version ran under the
     * chunk's `BEGIN`, so the deletion and the first batch of replacement rows
     * landed atomically. That cannot be batched — the whole point of batching is
     * to COMMIT partway — so the atomicity is traded for a lock that other
     * writers can get between batches. Measured on a Fire TV, clearing a
     * 272,419-item catalogue is ~110 s during which no other write in the app can
     * proceed; batching cuts the longest continuous hold roughly proportionally
     * to the batch size, for about +30% total time at 5,000 rows.
     *
     * What that costs: a sync interrupted mid-clear leaves a partially deleted
     * catalogue. Detectable only via the `sync_in_progress` marker written by
     * [prepareSource], which is why that marker is not optional.
     *
     * Later calls are a field comparison and cost nothing.
     *
     * Deliberately NOT idempotent across instances: a fresh [BulkContentWriter]
     * is constructed per sync (`SourceRepository`), so [clearedFor] is scoped to
     * one sync by construction rather than by bookkeeping.
     *
     * The old rows must go before the new ones are written, not after: the insert
     * is `INSERT OR IGNORE` and content ids are deterministic, so leaving the old
     * rows in place would silently keep every stale row and drop the fresh copy.
     */
    private fun clearIfFirstWrite(sourceId: String) {
        if (clearedFor == sourceId) return
        var batches = 0
        while (batches < MAX_CLEAR_BATCHES) {
            driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
            val removed =
                try {
                    // FTS first, then the content rows it looked them up by.
                    // Both statements use the same unordered `LIMIT` subquery
                    // and nothing between them modifies `content`, so within
                    // this transaction they resolve to the SAME batch of ids.
                    // Reverse the order and the second statement would have no
                    // rows left to find, orphaning that batch's FTS entries.
                    driver.execute(
                        null,
                        "DELETE FROM content_fts WHERE content_id IN " +
                            "(SELECT id FROM content WHERE source_id = ? LIMIT $CLEAR_BATCH_ROWS)",
                        1,
                    ) { bindString(0, sourceId) }
                    val n =
                        driver.execute(
                            null,
                            "DELETE FROM content WHERE id IN " +
                                "(SELECT id FROM content WHERE source_id = ? LIMIT $CLEAR_BATCH_ROWS)",
                            1,
                        ) { bindString(0, sourceId) }.value
                    driver.execute(null, "COMMIT", 0)
                    n
                } catch (t: Throwable) {
                    runCatching { driver.execute(null, "ROLLBACK", 0) }
                    throw t
                }
            if (removed <= 0L) break
            batches++
        }
        clearedFor = sourceId
    }

    /**
     * Bulk-populates the FTS table for this source in a single
     * `INSERT … SELECT`, then recreates the trigger and sweeps up
     * orphan favorites + watch_history rows whose content_ids no longer
     * exist in the catalog (provider rotated them out). Re-enables FK
     * after the cleanup. Called once at the end of a successful sync.
     *
     * Safety-net: if [prepareSource] ran but [finishSource] is never
     * called (caller crashed mid-sync), the next call to
     * [prepareSource] for this source will DELETE the half-written rows
     * before reinstalling clean data. FK is re-enabled on next process
     * launch via DatabaseFactory.android.kt's `onOpen`. The trigger is
     * recreated defensively on the error path too so non-bulk inserts
     * (M3U, Stalker) stay FTS-consistent.
     */
    /**
     * How many rows this source has already stamped in `content_first_seen`.
     *
     * Zero means the sync that just finished is this source's first, so what it
     * wrote is an initial catalogue import rather than a set of new titles.
     */
    private fun countFirstSeen(sourceId: String): Long = driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM content_first_seen WHERE source_id = ?",
        { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
        1,
    ) { bindString(0, sourceId) }.value

    fun finishSource(sourceId: String) {
        // MK.35.1 — read BEFORE the transaction opens. Whether this source has
        // ever been stamped is what decides if the catalogue it just wrote is an
        // initial import or a set of genuine additions, and reading it after the
        // INSERT below would always answer "yes".
        val alreadyStamped = countFirstSeen(sourceId) > 0L
        driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
        try {
            driver.execute(
                null,
                "INSERT INTO content_fts (content_id, title, clean_title, group_name) " +
                    "SELECT id, title, clean_title, group_name FROM content WHERE source_id = ?",
                1,
            ) { bindString(0, sourceId) }
            // MK.35.1 — stamp first-seen for this source's catalogue.
            //
            // `INSERT OR IGNORE` is what makes this both idempotent and cheap:
            // rows stamped by an earlier sync keep their ORIGINAL timestamp, and
            // only content_ids that were not there before get a row. One bulk
            // statement over the source, not a per-row loop across 272k items.
            //
            // The flag marks a source's FIRST sync as an initial import so Home
            // excludes it. Nothing is genuinely new when the whole catalogue
            // arrives at once, and without this the rail would show 60 arbitrary
            // titles again — the same bug with a new column behind it.
            driver.execute(
                null,
                "INSERT OR IGNORE INTO content_first_seen " +
                    "(content_id, source_id, first_seen_at, from_initial_import) " +
                    "SELECT id, ?, ?, ? FROM content WHERE source_id = ?",
                4,
            ) {
                bindString(0, sourceId)
                bindLong(1, clock())
                bindLong(2, if (alreadyStamped) 0L else 1L)
                bindString(3, sourceId)
            }
            driver.execute(
                null,
                "CREATE TRIGGER IF NOT EXISTS content_ai AFTER INSERT ON content BEGIN " +
                    "INSERT INTO content_fts (content_id, title, clean_title, group_name) " +
                    "VALUES (new.id, new.title, new.clean_title, new.group_name); END",
                0,
            )
            // Sweep up genuine orphans — favorites + history rows whose
            // content_id is no longer in the catalog because the provider
            // dropped that channel/movie. Without FK enforcement during
            // sync, these would otherwise pile up forever.
            //
            // MB-289 (Critical, data loss) — the `EXISTS` guard is the whole
            // point of these two statements' current shape. [prepareSource]
            // has already deleted every row for this source, so if the
            // provider answered with an EMPTY catalog — HTTP 200 carrying
            // `[]`, `{"js":""}`, an HTML captive-portal page, or an expired
            // subscription's empty response — then `content` holds nothing
            // for this source and an unguarded `NOT IN (SELECT id FROM
            // content)` matches EVERY favorite and EVERY watch_history row
            // belonging to it. They are deleted, committed, and gone: the
            // catalog re-populates on the next good sync but the user's
            // favorites and resume points never come back. A provider
            // outage silently destroyed data the user cannot regenerate.
            //
            // The guard makes the sweep a no-op unless this source actually
            // wrote rows, which is exactly the condition under which an
            // orphan is meaningful. Doing it in SQL rather than via a
            // `wroteRows` parameter keeps it impossible to get wrong at a
            // call site (there are three, plus ~20 in tests) and costs one
            // indexed existence check per sync.
            //
            // Conservative by design: when this source came back empty we
            // skip the sweep for *all* sources, so another source's genuine
            // orphans simply wait for its own next sync. Leaving an orphan
            // is harmless; deleting a favorite is not.
            driver.execute(
                null,
                "DELETE FROM favorites WHERE content_id NOT IN (SELECT id FROM content) " +
                    "AND EXISTS (SELECT 1 FROM content WHERE source_id = ?)",
                1,
            ) { bindString(0, sourceId) }
            driver.execute(
                null,
                "DELETE FROM watch_history WHERE content_id NOT IN (SELECT id FROM content) " +
                    "AND EXISTS (SELECT 1 FROM content WHERE source_id = ?)",
                1,
            ) { bindString(0, sourceId) }
            // MB-353 — the marker clears in the SAME transaction that completes
            // the sync, so "the catalogue is complete" and "no sync is in
            // progress" can never disagree on disk.
            clearSyncMarkerIn(sourceId)
            driver.execute(null, "COMMIT", 0)
        } catch (t: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0) }
            runCatching {
                // Defensive: even if FTS populate failed, make sure the
                // trigger is back so subsequent non-bulk inserts stay
                // consistent.
                driver.execute(
                    null,
                    "CREATE TRIGGER IF NOT EXISTS content_ai AFTER INSERT ON content BEGIN " +
                        "INSERT INTO content_fts (content_id, title, clean_title, group_name) " +
                        "VALUES (new.id, new.title, new.clean_title, new.group_name); END",
                    0,
                )
            }
            // Re-enable FK so the next normal write re-arms cascade
            // semantics for actual content removal.
            runCatching { driver.execute(null, "PRAGMA foreign_keys = ON", 0) }
            throw t
        }
        // Success path — re-enable FK after the orphan sweep committed.
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
    }

    /**
     * Called from the error path when a sync aborts after [prepareSource]
     * but before [finishSource]. Brings the schema back to a sane state:
     * clears any partially-written rows for this source, reinstalls the
     * FTS trigger, and re-enables FK. Safe to call even if `prepareSource`
     * never ran.
     *
     * **MB-353 — the cleanup is now conditional, and that condition is the
     * whole point of the fix.** The deletes below exist to remove HALF-WRITTEN
     * rows. Since [prepareSource] no longer destroys anything, a sync that
     * aborts before its first chunk has written nothing — the previous
     * catalogue is intact and complete. Running the deletes unconditionally
     * would then destroy a perfectly good catalogue on the error path, which is
     * exactly the failure this bug is about, reintroduced one function along.
     * Total failures (dead URL, expired subscription, no network) all land here.
     */
    fun abortSource(sourceId: String) {
        if (clearedFor == sourceId) {
            runCatching {
                driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
                try {
                    driver.execute(
                        null,
                        "DELETE FROM content_fts WHERE content_id IN (SELECT id FROM content WHERE source_id = ?)",
                        1,
                    ) { bindString(0, sourceId) }
                    driver.execute(null, "DELETE FROM content WHERE source_id = ?", 1) {
                        bindString(0, sourceId)
                    }
                    driver.execute(null, "COMMIT", 0)
                } catch (t: Throwable) {
                    runCatching { driver.execute(null, "ROLLBACK", 0) }
                }
            }
        }
        // The trigger is recreated unconditionally: prepareSource drops it
        // before any chunk runs, so it is missing on every abort path whether
        // or not rows were written.
        runCatching {
            driver.execute(
                null,
                "CREATE TRIGGER IF NOT EXISTS content_ai AFTER INSERT ON content BEGIN " +
                    "INSERT INTO content_fts (content_id, title, clean_title, group_name) " +
                    "VALUES (new.id, new.title, new.clean_title, new.group_name); END",
                0,
            )
        }
        // Marker last: it says "this source's catalogue may be incomplete", and
        // that stops being true only once the cleanup above has run.
        runCatching { clearSyncMarker(sourceId) }
        // Always re-enable FK on the abort path. If prepareSource ran and
        // turned FK off, leaving it off would silently break cascade
        // semantics for the rest of the connection lifetime.
        runCatching { driver.execute(null, "PRAGMA foreign_keys = ON", 0) }
    }

    // ───── Per-chunk writers ─────

    /**
     * Writes [items] into `content` in batches of [BATCH_ROWS] per
     * multi-row INSERT, wrapped in a single short transaction. Returns
     * how many rows were written.
     *
     * [sortOrderStart] is the absolute sort_order for the first row —
     * the caller keeps a running counter per content-type to preserve
     * globally-ordered rows across streaming chunks.
     */
    fun writeLiveChunk(
        sourceId: String,
        client: XtreamClient,
        items: List<XtreamLiveStream>,
        categoryNames: Map<String, String>,
        now: Long,
        sortOrderStart: Long,
    ): Int {
        if (items.isEmpty()) return 0
        clearIfFirstWrite(sourceId)
        driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
        try {
            var i = 0
            var sortOrder = sortOrderStart
            while (i < items.size) {
                val end = minOf(i + BATCH_ROWS, items.size)
                val rows = end - i
                val sql = if (rows == BATCH_ROWS) sqlBatch else buildInsertSql(rows)
                driver.execute(null, sql, rows * COLS) {
                    var p = 0
                    for (k in i until end) {
                        val s = items[k]
                        val meta =
                            ContentMetadata(
                                streamId = s.streamId.toLong(),
                                tvArchive = if (s.tvArchive != 0) s.tvArchive else null,
                                tvArchiveDuration = if (s.tvArchiveDuration != 0) s.tvArchiveDuration else null,
                            )
                        val groupName = categoryNames[s.categoryId] ?: s.categoryId.ifBlank { null }
                        bindString(p++, ContentIds.xtreamLive(sourceId, s.streamId.toString()))
                        bindString(p++, sourceId)
                        bindString(p++, "live")
                        bindString(p++, s.name)
                        bindString(p++, cleanTitle(s.name))
                        bindString(p++, groupName)
                        bindString(p++, client.buildStreamUrl(s.streamId, XtreamStreamType.LIVE))
                        bindString(p++, s.streamIcon.ifBlank { null })
                        bindString(p++, s.epgChannelId.ifBlank { null })
                        bindString(p++, encodeMeta(meta))
                        bindLong(p++, sortOrder)
                        bindLong(p++, now)
                        sortOrder++
                    }
                }
                i = end
            }
            driver.execute(null, "COMMIT", 0)
            return items.size
        } catch (t: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0) }
            throw t
        }
    }

    fun writeVodChunk(
        sourceId: String,
        client: XtreamClient,
        items: List<XtreamVodStream>,
        categoryNames: Map<String, String>,
        now: Long,
        sortOrderStart: Long,
    ): Int {
        if (items.isEmpty()) return 0
        clearIfFirstWrite(sourceId)
        driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
        try {
            var i = 0
            var sortOrder = sortOrderStart
            while (i < items.size) {
                val end = minOf(i + BATCH_ROWS, items.size)
                val rows = end - i
                val sql = if (rows == BATCH_ROWS) sqlBatch else buildInsertSql(rows)
                driver.execute(null, sql, rows * COLS) {
                    var p = 0
                    for (k in i until end) {
                        val v = items[k]
                        val meta =
                            ContentMetadata(
                                streamId = v.streamId.toLong(),
                                rating = v.rating.ifBlank { null },
                            )
                        val groupName = categoryNames[v.categoryId] ?: v.categoryId.ifBlank { null }
                        bindString(p++, ContentIds.xtreamVod(sourceId, v.streamId.toString()))
                        bindString(p++, sourceId)
                        bindString(p++, "movie")
                        bindString(p++, v.name)
                        bindString(p++, cleanTitle(v.name))
                        bindString(p++, groupName)
                        bindString(p++, client.buildStreamUrl(v.streamId, XtreamStreamType.MOVIE, v.containerExtension))
                        bindString(p++, v.streamIcon.ifBlank { null })
                        bindString(p++, null) // tvg_id
                        bindString(p++, encodeMeta(meta))
                        bindLong(p++, sortOrder)
                        bindLong(p++, now)
                        sortOrder++
                    }
                }
                i = end
            }
            driver.execute(null, "COMMIT", 0)
            return items.size
        } catch (t: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0) }
            throw t
        }
    }

    fun writeSeriesChunk(sourceId: String, items: List<XtreamSeriesInfo>, categoryNames: Map<String, String>, now: Long, sortOrderStart: Long): Int {
        if (items.isEmpty()) return 0
        clearIfFirstWrite(sourceId)
        driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
        try {
            var i = 0
            var sortOrder = sortOrderStart
            while (i < items.size) {
                val end = minOf(i + BATCH_ROWS, items.size)
                val rows = end - i
                val sql = if (rows == BATCH_ROWS) sqlBatch else buildInsertSql(rows)
                driver.execute(null, sql, rows * COLS) {
                    var p = 0
                    for (k in i until end) {
                        val sr = items[k]
                        val meta =
                            ContentMetadata(
                                seriesId = sr.seriesId.toLong(),
                                plot = sr.plot.ifBlank { null },
                                cast = sr.cast.ifBlank { null },
                                director = sr.director.ifBlank { null },
                                genre = sr.genre.ifBlank { null },
                                releaseDate = sr.releaseDate.ifBlank { null },
                                rating = sr.rating.ifBlank { null },
                            )
                        val groupName = categoryNames[sr.categoryId] ?: sr.categoryId.ifBlank { null }
                        bindString(p++, ContentIds.xtreamSeries(sourceId, sr.seriesId.toString()))
                        bindString(p++, sourceId)
                        bindString(p++, "series")
                        bindString(p++, sr.name)
                        bindString(p++, cleanTitle(sr.name))
                        bindString(p++, groupName)
                        bindString(p++, "xtream-series://${sr.seriesId}")
                        bindString(p++, sr.cover.ifBlank { null })
                        bindString(p++, null) // tvg_id
                        bindString(p++, encodeMeta(meta))
                        bindLong(p++, sortOrder)
                        bindLong(p++, now)
                        sortOrder++
                    }
                }
                i = end
            }
            driver.execute(null, "COMMIT", 0)
            return items.size
        } catch (t: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0) }
            throw t
        }
    }

    /**
     * Writes a chunk of classified M3U entries. The caller is responsible for
     * slicing the parsed entry list into chunks (typical: 500-row chunks).
     * Each chunk is one short transaction, matching the Xtream chunk pattern —
     * a 100k-entry playlist used to hold the WAL write lock for minutes in a
     * single giant transaction, freezing the Guide screen.
     *
     * [sortOrderStart] is the absolute sort_order for the first row. M3U has
     * no separate live/VOD/series endpoints, so sort order is a single
     * monotonic counter across the whole playlist.
     */
    fun writeM3uChunk(sourceId: String, items: List<M3uEntry>, now: Long, sortOrderStart: Long): Int {
        if (items.isEmpty()) return 0
        clearIfFirstWrite(sourceId)
        driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
        try {
            var i = 0
            var sortOrder = sortOrderStart
            while (i < items.size) {
                val end = minOf(i + BATCH_ROWS, items.size)
                val rows = end - i
                val sql = if (rows == BATCH_ROWS) sqlBatch else buildInsertSql(rows)
                driver.execute(null, sql, rows * COLS) {
                    var p = 0
                    for (k in i until end) {
                        val e = items[k]
                        val type = serializeType(classifyEntry(e))
                        val meta =
                            ContentMetadata(
                                catchupType = e.catchupType,
                                catchupSource = e.catchupSource,
                                tvArchiveDuration = e.catchupDays,
                                catchupCorrection = e.catchupCorrection,
                            )
                        bindString(p++, ContentIds.m3u(sourceId, e.title, e.streamUrl))
                        bindString(p++, sourceId)
                        bindString(p++, type)
                        bindString(p++, e.title)
                        bindString(p++, cleanTitle(e.title))
                        bindString(p++, e.groupTitle.ifBlank { null })
                        bindString(p++, e.streamUrl)
                        bindString(p++, e.tvgLogo.ifBlank { null })
                        bindString(p++, e.tvgId.ifBlank { null })
                        bindString(p++, encodeMeta(meta))
                        bindLong(p++, sortOrder)
                        bindLong(p++, now)
                        sortOrder++
                    }
                }
                i = end
            }
            driver.execute(null, "COMMIT", 0)
            return items.size
        } catch (t: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0) }
            throw t
        }
    }

    fun writeStalkerLiveChunk(sourceId: String, items: List<StalkerChannel>, categoryNames: Map<String, String>, now: Long, sortOrderStart: Long): Int {
        if (items.isEmpty()) return 0
        clearIfFirstWrite(sourceId)
        driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
        try {
            var i = 0
            var sortOrder = sortOrderStart
            while (i < items.size) {
                val end = minOf(i + BATCH_ROWS, items.size)
                val rows = end - i
                val sql = if (rows == BATCH_ROWS) sqlBatch else buildInsertSql(rows)
                driver.execute(null, sql, rows * COLS) {
                    var p = 0
                    for (k in i until end) {
                        val c = items[k]
                        val meta =
                            ContentMetadata(
                                stalkerId = c.id.toString(),
                                tvArchive = if (c.tvArchive != 0) c.tvArchive else null,
                                tvArchiveDuration = if (c.tvArchiveDuration != 0) c.tvArchiveDuration else null,
                            )
                        val groupName = categoryNames[c.tvGenreId] ?: c.tvGenreId.ifBlank { null }
                        bindString(p++, ContentIds.stalkerLive(sourceId, c.id.toString()))
                        bindString(p++, sourceId)
                        bindString(p++, "live")
                        bindString(p++, c.name)
                        bindString(p++, cleanTitle(c.name))
                        bindString(p++, groupName)
                        bindString(p++, c.cmd)
                        bindString(p++, c.logo.ifBlank { null })
                        bindString(p++, c.epgId.ifBlank { null })
                        bindString(p++, encodeMeta(meta))
                        bindLong(p++, sortOrder)
                        bindLong(p++, now)
                        sortOrder++
                    }
                }
                i = end
            }
            driver.execute(null, "COMMIT", 0)
            return items.size
        } catch (t: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0) }
            throw t
        }
    }

    fun writeStalkerVodChunk(sourceId: String, items: List<StalkerVodItem>, categoryNames: Map<String, String>, now: Long, sortOrderStart: Long): Int {
        if (items.isEmpty()) return 0
        clearIfFirstWrite(sourceId)
        driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
        try {
            var i = 0
            var sortOrder = sortOrderStart
            while (i < items.size) {
                val end = minOf(i + BATCH_ROWS, items.size)
                val rows = end - i
                val sql = if (rows == BATCH_ROWS) sqlBatch else buildInsertSql(rows)
                driver.execute(null, sql, rows * COLS) {
                    var p = 0
                    for (k in i until end) {
                        val v = items[k]
                        val meta =
                            ContentMetadata(
                                stalkerId = v.id.toString(),
                                description = v.description.ifBlank { null },
                            )
                        val groupName = categoryNames[v.categoryId] ?: v.categoryId.ifBlank { null }
                        bindString(p++, ContentIds.stalkerVod(sourceId, v.id.toString()))
                        bindString(p++, sourceId)
                        bindString(p++, "movie")
                        bindString(p++, v.name)
                        bindString(p++, cleanTitle(v.name))
                        bindString(p++, groupName)
                        bindString(p++, v.cmd)
                        bindString(p++, v.logo.ifBlank { null })
                        bindString(p++, null) // tvg_id
                        bindString(p++, encodeMeta(meta))
                        bindLong(p++, sortOrder)
                        bindLong(p++, now)
                        sortOrder++
                    }
                }
                i = end
            }
            driver.execute(null, "COMMIT", 0)
            return items.size
        } catch (t: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0) }
            throw t
        }
    }

    fun writeStalkerSeriesChunk(sourceId: String, items: List<StalkerSeriesItem>, categoryNames: Map<String, String>, now: Long, sortOrderStart: Long): Int {
        if (items.isEmpty()) return 0
        clearIfFirstWrite(sourceId)
        driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
        try {
            var i = 0
            var sortOrder = sortOrderStart
            while (i < items.size) {
                val end = minOf(i + BATCH_ROWS, items.size)
                val rows = end - i
                val sql = if (rows == BATCH_ROWS) sqlBatch else buildInsertSql(rows)
                driver.execute(null, sql, rows * COLS) {
                    var p = 0
                    for (k in i until end) {
                        val sr = items[k]
                        val meta =
                            ContentMetadata(
                                stalkerId = sr.id.toString(),
                                plot = sr.plot.ifBlank { null },
                                genre = sr.genre.ifBlank { null },
                            )
                        val groupName = categoryNames[sr.categoryId] ?: sr.categoryId.ifBlank { null }
                        bindString(p++, ContentIds.stalkerSeries(sourceId, sr.id.toString()))
                        bindString(p++, sourceId)
                        bindString(p++, "series")
                        bindString(p++, sr.name)
                        bindString(p++, cleanTitle(sr.name))
                        bindString(p++, groupName)
                        bindString(p++, "stalker-series://${sr.id}")
                        bindString(p++, sr.cover.ifBlank { null })
                        bindString(p++, null) // tvg_id
                        bindString(p++, encodeMeta(meta))
                        bindLong(p++, sortOrder)
                        bindLong(p++, now)
                        sortOrder++
                    }
                }
                i = end
            }
            driver.execute(null, "COMMIT", 0)
            return items.size
        } catch (t: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0) }
            throw t
        }
    }

    private fun serializeType(type: ContentType): String = when (type) {
        ContentType.LIVE -> "live"
        ContentType.MOVIE -> "movie"
        ContentType.SERIES -> "series"
    }

    /** Clear the in-progress marker inside the caller's open transaction. */
    private fun clearSyncMarkerIn(sourceId: String) {
        driver.execute(null, "DELETE FROM settings WHERE key = '$SYNC_MARKER_PREFIX' || ?", 1) {
            bindString(0, sourceId)
        }
    }

    /** Clear the in-progress marker in its own transaction. */
    private fun clearSyncMarker(sourceId: String) {
        driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
        try {
            clearSyncMarkerIn(sourceId)
            driver.execute(null, "COMMIT", 0)
        } catch (t: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0) }
            throw t
        }
    }

    companion object {
        /**
         * MB-353 — settings-key prefix marking a sync that started and has not
         * finished. Full key is `$SYNC_MARKER_PREFIX<sourceId>`.
         *
         * Lives in the existing key/value `settings` table on purpose: it needs
         * no schema migration, and this project currently has no working
         * migration gate (CI is blocked, see MB-349), so a column would have
         * shipped unverified.
         *
         * Set — and committed — before anything destructive can run, cleared in
         * the same transaction that completes the sync. A set marker therefore
         * means exactly one thing: this source's catalogue may be missing rows,
         * and only a successful sync can be trusted to fix it.
         */
        const val SYNC_MARKER_PREFIX = "sync_in_progress:"

        /**
         * Rows removed per transaction when clearing a source's old catalogue
         * (MB-315).
         *
         * Replacing a 272,419-item catalogue is ~110 s of deletion (62 s of FTS
         * index maintenance, 48 s of `content` with its seven indexes). Done in
         * one transaction that is 110 s during which no other writer in the app
         * can proceed — a favourite toggle, a resume point, another source's
         * sync. Measured on a Fire TV, and it is not a bad query plan: an
         * unqualified `DELETE FROM content_fts` benchmarked at 0.41 s against
         * the predicated form's 0.47 s on an identical 60k-row table, so there
         * is no fast path being missed. It is simply that much work.
         *
         * A single statement cannot be interrupted, but a loop can. Benchmarked
         * on the same fixture, batching costs about +30% total time at 5,000
         * rows and cuts the longest continuous lock hold 6x; 1,000 trades more
         * total time for a proportionally shorter hold, which is the right way
         * round — syncs run unattended in the background, stalls happen while
         * someone is using the app.
         */
        const val CLEAR_BATCH_ROWS = 1_000

        /**
         * Backstop so a malformed predicate cannot spin forever. 10,000 batches
         * is 10 million rows — far beyond any real catalogue, so hitting it
         * means the loop is not making progress rather than that the source is
         * large.
         */
        const val MAX_CLEAR_BATCHES = 10_000

        /**
         * Did a previous sync for [sourceId] start and never finish?
         *
         * Survives process death because the marker is committed up front. The
         * intended response is to re-sync: the rows that are present are valid,
         * but an unknown number are missing, and nothing else on disk reveals
         * that — `sources.channel_count` still reports the figure from the last
         * COMPLETED sync, so a gutted source still advertises its old size.
         */
        fun syncWasInterrupted(driver: SqlDriver, sourceId: String): Boolean = driver.executeQuery(
            null,
            "SELECT 1 FROM settings WHERE key = '$SYNC_MARKER_PREFIX' || ?",
            { cursor -> app.cash.sqldelight.db.QueryResult.Value(cursor.next().value) },
            1,
        ) { bindString(0, sourceId) }.value

        /**
         * Rows per multi-row INSERT statement. 80 × 12 columns = 960
         * parameters, under SQLite's default SQLITE_MAX_VARIABLE_NUMBER
         * (999 on Android up to ~API 32, 32766 on newer).
         */
        const val BATCH_ROWS = 80
        const val COLS = 12

        // Pre-built SQL for the hot-path 80-row statement. JIT-friendly —
        // SQLite's statement cache sees the exact same string for every
        // full batch and can skip reparsing.
        private val sqlBatch: String = buildInsertSql(BATCH_ROWS)

        private fun buildInsertSql(rowCount: Int): String {
            // OR IGNORE: providers regularly send the same channel twice (our
            // M3u parser already logs "duplicate URLs collapsed"), and a short
            // 32-bit FNV hash in ContentIds.m3u means ID collisions are likely
            // on large playlists. Without this, one dupe PK fails the whole
            // 80-row INSERT, rolls back the chunk, and abortSource() wipes
            // every row written so far — surfacing as a mysterious sync error.
            // prepareSource() already cleared this source's rows, so every ID
            // in the batch is fresh from the wire; IGNORE is correct.
            val sb =
                StringBuilder(
                    "INSERT OR IGNORE INTO content (" +
                        "id, source_id, type, title, clean_title, group_name, " +
                        "stream_url, logo_url, tvg_id, metadata_json, sort_order, created_at" +
                        ") VALUES ",
                )
            for (r in 0 until rowCount) {
                if (r > 0) sb.append(',')
                sb.append("(?,?,?,?,?,?,?,?,?,?,?,?)")
            }
            return sb.toString()
        }
    }
}
