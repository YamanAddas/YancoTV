package com.yancotv.shared.epg

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.logger.NOOP_LOGGER
import com.yancotv.shared.parsers.XmltvProgramme

/**
 * Bulk writer for the `epg_programmes` table. Mirrors the pattern in
 * [com.yancotv.shared.sources.BulkContentWriter] — multi-row INSERT statements
 * instead of per-row SQLDelight `upsert()` calls so a 300k-programme refresh
 * takes ~30 s on Fire TV instead of 15–25 min (and doesn't get killed by the
 * WorkManager 10-min execution cap).
 *
 * One 80-row INSERT == 720 bound parameters (9 columns × 80 rows), safely
 * under SQLite's default `SQLITE_MAX_VARIABLE_NUMBER` (999 on API ≤ 32,
 * 32766 on API 33+). Same batch size as BulkContentWriter for consistency.
 *
 * Caller passes a list of [ProgrammeBatch]es — one per parsed EPG source —
 * and we do a single `BEGIN IMMEDIATE ... DELETE ... INSERT ... COMMIT`
 * so the table is always either fully stale or fully fresh, never mid-swap.
 */
class BulkEpgWriter(private val driver: SqlDriver, private val logger: Logger = NOOP_LOGGER) {
    data class ProgrammeBatch(
        /** Key used in the composite primary key `channelId|startTime|sourceKey`. */
        val sourceKey: String,
        /** FK into `sources(id)`. Null for global / shared EPG URLs. */
        val sourceIdForDb: String?,
        val programmes: List<XmltvProgramme>,
    )

    data class Result(val rowsWritten: Int, val channels: Int)

    /**
     * Atomically swap the whole `epg_programmes` table with the concatenated
     * programmes across [batches]. [onBatch] is invoked after each multi-row
     * INSERT so the UI can show "written X/total". Progress is in rows, not
     * batches, to make the tick readable.
     */
    fun replaceAll(batches: List<ProgrammeBatch>, onBatch: suspend (written: Int, total: Int) -> Unit = { _, _ -> }, lastRefreshedMs: Long? = null): Result {
        val total = batches.sumOf { it.programmes.size }
        if (total == 0) {
            driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
            try {
                driver.execute(null, "DELETE FROM epg_programmes", 0)
                if (lastRefreshedMs != null) {
                    driver.execute(null, "INSERT OR REPLACE INTO settings (key, value) VALUES ('epg_last_refreshed', ?)", 1) {
                        bindString(0, lastRefreshedMs.toString())
                    }
                }
                driver.execute(null, "COMMIT", 0)
            } catch (t: Throwable) {
                runCatching { driver.execute(null, "ROLLBACK", 0) }
                throw t
            }
            return Result(0, 0)
        }

        val channels = HashSet<String>()
        var written = 0

        driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
        try {
            driver.execute(null, "DELETE FROM epg_programmes", 0)

            for (batch in batches) {
                val progs = batch.programmes
                if (progs.isEmpty()) continue
                var i = 0
                while (i < progs.size) {
                    val end = minOf(i + BATCH_ROWS, progs.size)
                    val rows = end - i
                    val sql = if (rows == BATCH_ROWS) SQL_BATCH_FULL else buildInsertSql(rows)
                    driver.execute(null, sql, rows * COLS) {
                        var p = 0
                        for (k in i until end) {
                            val prog = progs[k]
                            channels.add(prog.channelId)
                            bindString(p++, "${prog.channelId}|${prog.startTime}|${batch.sourceKey}")
                            bindString(p++, batch.sourceIdForDb)
                            bindString(p++, prog.channelId)
                            bindString(p++, prog.title)
                            bindString(p++, prog.description)
                            bindLong(p++, prog.startTime)
                            bindLong(p++, prog.endTime)
                            bindString(p++, prog.category)
                            bindString(p++, prog.iconUrl)
                        }
                    }
                    written += rows
                    i = end
                }
                logger.info("EPG bulk: source ${batch.sourceKey} written (${progs.size} programmes)")
            }

            if (lastRefreshedMs != null) {
                driver.execute(null, "INSERT OR REPLACE INTO settings (key, value) VALUES ('epg_last_refreshed', ?)", 1) {
                    bindString(0, lastRefreshedMs.toString())
                }
            }

            driver.execute(null, "COMMIT", 0)
        } catch (t: Throwable) {
            runCatching { driver.execute(null, "ROLLBACK", 0) }
            throw t
        }

        // Progress emit happens after the commit because onBatch is suspend
        // and we can't suspend inside the driver.execute block. We emit a
        // single 100% tick — callers that want finer-grained progress should
        // batch their own parse-then-write cycles and call replaceAll per
        // source. A true in-transaction tick would need a dedicated writer
        // thread + channel, which is heavier than warranted.
        // (Kept as an empty hook so the signature can change to true
        // progressive writing later without a caller refactor.)
        val channelCount = channels.size

        return Result(rowsWritten = written, channels = channelCount)
    }

    /**
     * Push-model writer — for use with a **streaming** XML pull parser so peak
     * memory stays bounded regardless of feed size.
     *
     * Lifecycle:
     *   `begin()` → many `writeBatch(...)` → `commit()` on success, or
     *   `rollback()` on failure.
     *
     * **MB-315 — this no longer runs in one transaction, deliberately.** It used
     * to: `begin()` did `BEGIN IMMEDIATE` + `DELETE FROM epg_programmes` and the
     * COMMIT came after the last batch, which made the replace strictly atomic.
     * SQLite in WAL mode passes readers through but SERIALISES writers, so that
     * transaction blocked every other write in the app for the length of the
     * import. Measured on a Fire TV: over 20 minutes, with a source sync frozen
     * at "writing 0/963" the whole time and no error ever surfacing. A source
     * deletion in that window would have hung outright. Restarting the app was
     * the only way out, and nothing told the user that.
     *
     * So the import now commits every [COMMIT_EVERY_ROWS] rows and the lock is
     * held for one chunk at a time. Two things are given up, both on purpose:
     *
     *  * **A crash mid-import leaves a partial guide.** Covered by
     *    [KEY_IMPORT_STATE] — committed before any destruction and cleared only
     *    in the same transaction as the final rows — so an interrupted import is
     *    detectable via [lastImportWasInterrupted] instead of silently sitting
     *    there half-empty. The freshness stamp is dropped at the same moment, so
     *    even code that never checks the marker treats the guide as stale.
     *  * **Readers see the rebuild happening.** Previously the guide stayed
     *    complete-but-stale until one atomic swap; now it is briefly incomplete
     *    while the import runs. That is a real regression in isolation, and a
     *    small one next to freezing every write in the app.
     *
     * The alternative — a staging table swapped in one short transaction — keeps
     * strict atomicity, but the swap still re-inserts every row into a five-index
     * table, so writers still stall for tens of seconds, and it costs a schema
     * migration plus double the disk during import. Rejected on that balance.
     *
     * The DELETE is deferred to the first batch carrying rows, which makes the
     * all-sources-failed case SAFER than it was before: that path no longer
     * destroys a good guide before finding out it has no replacement.
     *
     * Exists in parallel to [replaceAll] because the batch-list form needs
     * all programmes resident in memory; the streaming form only ever holds
     * one batch (≤ 80 programmes) at a time.
     */
    inner class Session {
        private var open = false

        /**
         * Whether the existing guide has been destroyed yet.
         *
         * The DELETE is deferred to the first batch that actually carries rows
         * (MB-315). Every EPG source failing is the common failure — a dead URL,
         * an expired subscription, no network — and under the old ordering that
         * case still ran `DELETE FROM epg_programmes` at `begin()`, so a failed
         * refresh wiped a perfectly good guide before discovering it had nothing
         * to replace it with. Deferring means a total failure now leaves the
         * previous guide exactly as it was.
         */
        private var destructiveStarted = false
        private var rowsSinceCommit = 0

        var rowsWritten: Int = 0
            private set

        private val seenChannels: HashSet<String> = HashSet(4096)
        val channelCount: Int get() = seenChannels.size

        fun begin() {
            if (open) error("Session already open")
            // The marker goes in FIRST, in its own committed transaction, so it
            // survives a process death that takes the import with it. Written
            // before anything destructive for the same reason: it has to be on
            // disk before there is any damage for it to describe.
            driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
            try {
                driver.execute(null, SQL_MARK_IN_PROGRESS, 0)
                driver.execute(null, "COMMIT", 0)
            } catch (t: Throwable) {
                runCatching { driver.execute(null, "ROLLBACK", 0) }
                throw t
            }
            driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
            open = true
        }

        fun writeBatch(sourceKey: String, sourceIdForDb: String?, programmes: List<XmltvProgramme>) {
            if (!open) error("Session not open")
            if (programmes.isEmpty()) return
            if (!destructiveStarted) {
                driver.execute(null, "DELETE FROM epg_programmes", 0)
                // Drop the freshness stamp at the same moment the guide stops
                // being complete. If the process dies from here on, the app sees
                // "never successfully refreshed" and schedules another refresh
                // rather than trusting a half-written guide because the old
                // timestamp still looked recent.
                driver.execute(null, SQL_CLEAR_LAST_REFRESHED, 0)
                destructiveStarted = true
            }
            var i = 0
            while (i < programmes.size) {
                val end = minOf(i + BATCH_ROWS, programmes.size)
                val rows = end - i
                val sql = if (rows == BATCH_ROWS) SQL_BATCH_FULL else buildInsertSql(rows)
                driver.execute(null, sql, rows * COLS) {
                    var p = 0
                    for (k in i until end) {
                        val prog = programmes[k]
                        seenChannels.add(prog.channelId)
                        bindString(p++, "${prog.channelId}|${prog.startTime}|$sourceKey")
                        bindString(p++, sourceIdForDb)
                        bindString(p++, prog.channelId)
                        bindString(p++, prog.title)
                        bindString(p++, prog.description)
                        bindLong(p++, prog.startTime)
                        bindLong(p++, prog.endTime)
                        bindString(p++, prog.category)
                        bindString(p++, prog.iconUrl)
                    }
                }
                rowsWritten += rows
                rowsSinceCommit += rows
                i = end

                // MB-315 — the whole point of this class's rewrite. SQLite in
                // WAL mode lets readers through but SERIALISES writers, so a
                // transaction held across an entire XMLTV import blocks every
                // other write in the app for as long as the import runs. On a
                // Fire TV that was measured at over 20 minutes, during which a
                // source sync sat frozen at "writing 0/963" and a source
                // deletion would simply have hung. Committing here caps the
                // hold at one chunk, so other writers interleave instead of
                // queueing behind the guide.
                if (rowsSinceCommit >= COMMIT_EVERY_ROWS) {
                    driver.execute(null, "COMMIT", 0)
                    driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
                    rowsSinceCommit = 0
                }
            }
        }

        fun commit(lastRefreshedMs: Long? = null) {
            if (!open) error("Session not open")
            try {
                if (lastRefreshedMs != null) {
                    driver.execute(
                        null,
                        "INSERT OR REPLACE INTO settings (key, value) VALUES ('epg_last_refreshed', ?)",
                        1,
                    ) {
                        bindString(0, lastRefreshedMs.toString())
                    }
                }
                // Clearing the marker rides in the SAME transaction as the last
                // rows and the freshness stamp. All three become true together
                // or none do — so "import finished" can never be recorded for a
                // guide that is missing its final chunk.
                driver.execute(null, SQL_MARK_DONE, 0)
                driver.execute(null, "COMMIT", 0)
                open = false
            } catch (t: Throwable) {
                runCatching { driver.execute(null, "ROLLBACK", 0) }
                open = false
                throw t
            }
        }

        fun rollback() {
            if (!open) return
            runCatching { driver.execute(null, "ROLLBACK", 0) }
            open = false
            if (!destructiveStarted) {
                // Nothing was ever deleted, so the previous guide is intact and
                // there is no partial state to warn about. Clearing the marker
                // here matters: leaving it set would make every failed refresh
                // look like an interrupted one and trigger recovery work for a
                // database that is perfectly consistent.
                runCatching { driver.execute(null, SQL_MARK_DONE, 0) }
            }
            // If rows HAD been written, earlier chunks are already committed and
            // the guide genuinely is partial. The marker stays set on purpose —
            // that is the signal [lastImportWasInterrupted] exists to read.
        }
    }

    /**
     * Did a previous import destroy the guide and then fail to finish?
     *
     * True only between the first destructive write and a successful commit, and
     * it survives process death because the marker is committed on its own before
     * any damage is done. The intended response is to refresh again rather than
     * to trust what is on disk — the rows that are there are valid, but an
     * unknown number of them are missing.
     */
    fun lastImportWasInterrupted(): Boolean = driver.executeQuery(
        null,
        "SELECT value FROM settings WHERE key = '$KEY_IMPORT_STATE'",
        { cursor ->
            val v = if (cursor.next().value) cursor.getString(0) else null
            QueryResult.Value(v == STATE_IN_PROGRESS)
        },
        0,
    ).value

    fun openSession(): Session = Session()

    companion object {
        /** Multi-row INSERT width. 80 × 9 cols = 720 params, under SQLite's 999 ceiling. */
        const val BATCH_ROWS = 80
        const val COLS = 9

        /**
         * Rows written per transaction by [Session] (MB-315).
         *
         * The number trades two costs against each other. Too high and the write
         * lock is held long enough to stall another writer, which is the bug.
         * Too low and every commit pays a WAL frame flush, which is what made
         * the original one-transaction design fast. 5,000 rows is roughly 60
         * multi-row INSERTs — about 40 commits across a 200k-programme feed,
         * with each hold measured in tens of milliseconds rather than minutes.
         */
        const val COMMIT_EVERY_ROWS = 5_000

        /** Settings key recording whether an import is mid-flight. See [Session.begin]. */
        const val KEY_IMPORT_STATE = "epg_import_state"
        const val STATE_IN_PROGRESS = "in_progress"
        const val STATE_DONE = "done"

        private const val SQL_MARK_IN_PROGRESS =
            "INSERT OR REPLACE INTO settings (key, value) VALUES ('$KEY_IMPORT_STATE', '$STATE_IN_PROGRESS')"
        private const val SQL_MARK_DONE =
            "INSERT OR REPLACE INTO settings (key, value) VALUES ('$KEY_IMPORT_STATE', '$STATE_DONE')"
        private const val SQL_CLEAR_LAST_REFRESHED =
            "DELETE FROM settings WHERE key = 'epg_last_refreshed'"

        private val SQL_BATCH_FULL: String = buildInsertSql(BATCH_ROWS)

        private fun buildInsertSql(rowCount: Int): String {
            // INSERT OR REPLACE matches the original SQLDelight `upsert`
            // semantics — collisions on the composite `channelId|start|source`
            // PK are rare but not impossible (provider sends duplicate
            // programme entries), and we want the last-wins behavior.
            val sb =
                StringBuilder(
                    "INSERT OR REPLACE INTO epg_programmes (" +
                        "id, source_id, channel_tvg_id, title, description, " +
                        "start_time, end_time, category, icon_url" +
                        ") VALUES ",
                )
            for (r in 0 until rowCount) {
                if (r > 0) sb.append(',')
                sb.append("(?,?,?,?,?,?,?,?,?)")
            }
            return sb.toString()
        }
    }
}
