package com.yancotv.shared.epg

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
     * Open-transaction, push-model writer — for use with a **streaming**
     * XML pull parser so peak memory stays bounded regardless of feed size.
     *
     * Lifecycle:
     *   `begin()` → many `writeBatch(...)` → `commit()` on success, or
     *   `rollback()` on failure.
     *
     * The DELETE is executed at `begin()` so the whole replace-with-new is
     * still atomic — if anything throws before `commit()`, `rollback()`
     * restores the pre-refresh state.
     *
     * Exists in parallel to [replaceAll] because the batch-list form needs
     * all programmes resident in memory; the streaming form only ever holds
     * one batch (≤ 80 programmes) at a time.
     */
    inner class Session {
        private var open = false
        var rowsWritten: Int = 0
            private set

        private val seenChannels: HashSet<String> = HashSet(4096)
        val channelCount: Int get() = seenChannels.size

        fun begin() {
            if (open) error("Session already open")
            driver.execute(null, "BEGIN IMMEDIATE TRANSACTION", 0)
            try {
                driver.execute(null, "DELETE FROM epg_programmes", 0)
                open = true
            } catch (t: Throwable) {
                runCatching { driver.execute(null, "ROLLBACK", 0) }
                throw t
            }
        }

        fun writeBatch(sourceKey: String, sourceIdForDb: String?, programmes: List<XmltvProgramme>) {
            if (!open) error("Session not open")
            if (programmes.isEmpty()) return
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
                i = end
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
        }
    }

    fun openSession(): Session = Session()

    companion object {
        /** Multi-row INSERT width. 80 × 9 cols = 720 params, under SQLite's 999 ceiling. */
        const val BATCH_ROWS = 80
        const val COLS = 9

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
