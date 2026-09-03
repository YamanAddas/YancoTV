package com.yancotv.shared.downloads

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.yancotv.shared.db.YancoDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * State machine for a single `downloads` row.
 *
 * ```
 *   (created) ──enqueue──> QUEUED ──start──> DOWNLOADING ──complete──> COMPLETED
 *                             │                  │  ▲
 *                             │                  │  └──resume───┐
 *                             ├──pause───────────┴──pause──> PAUSED
 *                             │
 *                             ├──fail──> FAILED
 *                             └──cancel──> CANCELLED
 * ```
 *
 * Unlike a recording, a download is **resumable**, so `PAUSED` is a real
 * waiting state rather than an ending: a paused row goes back to
 * `DOWNLOADING` with the bytes it already has. Only `COMPLETED`,
 * `FAILED` and `CANCELLED` are terminal and write-once, for the same
 * reason as [com.yancotv.shared.recording.RecordingStatus]: the UI treats
 * a finished row as finished, and a transition out of one would be a
 * service-layer bug worth failing loudly.
 */
enum class DownloadStatus(val sql: String) {
    QUEUED("queued"),
    DOWNLOADING("downloading"),
    PAUSED("paused"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ;

    fun isTerminal(): Boolean = this == COMPLETED || this == FAILED || this == CANCELLED

    /** True while the transfer is meant to be making progress. */
    fun isActive(): Boolean = this == QUEUED || this == DOWNLOADING

    companion object {
        fun fromSql(value: String): DownloadStatus = entries.firstOrNull { it.sql == value }
            ?: error("Unknown download status: $value")
    }
}

/**
 * Domain view of one `downloads` row, kept separate from the SQLDelight
 * codegen type so the UI does not depend on the generated shape.
 */
data class DownloadEntry(
    val id: String,
    val contentId: String?,
    val episodeId: String?,
    val title: String,
    val streamUrl: String,
    val filePath: String,
    val status: DownloadStatus,
    val queuedAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val bytesDownloaded: Long,
    val bytesTotal: Long?,
    val error: String?,
    /** Whether the transfer can be picked up where it stopped. */
    val resumable: Boolean,
) {
    /** 0..1, or null when the server never said how big the file is. */
    val fraction: Double?
        get() {
            val total = bytesTotal ?: return null
            if (total <= 0L) return null
            return (bytesDownloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        }
}

/**
 * Offline downloads of finite files — films and episodes.
 *
 * The repository owns the row lifecycle only. **What performs the
 * transfer is deliberately absent**, because the two platforms cannot
 * share one: Android has a foreground worker, and iOS has the system's
 * background transfer service, which continues while the app is
 * suspended and hands the finished file back at the next launch. Both
 * report progress and outcomes through this.
 *
 * The clock is injected for the same reason it is everywhere else here:
 * tests must not wait for wall time.
 */
class DownloadsRepository(
    private val db: YancoDb,
    private val clock: () -> Long,
) {

    /** Adds a row in `QUEUED`. The caller supplies the id. */
    fun enqueue(
        id: String,
        contentId: String?,
        episodeId: String?,
        title: String,
        streamUrl: String,
        filePath: String,
    ): DownloadEntry {
        db.downloadsQueries.insert(
            id = id,
            content_id = contentId,
            episode_id = episodeId,
            title = title,
            stream_url = streamUrl,
            file_path = filePath,
            status = DownloadStatus.QUEUED.sql,
            queued_at = clock(),
            started_at = null,
            completed_at = null,
            bytes_downloaded = 0L,
            bytes_total = null,
            error = null,
            resumable = false,
        )
        return getById(id) ?: error("insert succeeded but row missing: $id")
    }

    /** The transfer has begun. Idempotent for a row already downloading. */
    fun markStarted(id: String): DownloadEntry {
        val row = require(id)
        if (row.status == DownloadStatus.DOWNLOADING) return row
        rejectTerminal(row, DownloadStatus.DOWNLOADING)
        return write(
            row,
            status = DownloadStatus.DOWNLOADING,
            startedAt = row.startedAt ?: clock(),
        )
    }

    /**
     * Progress, called often.
     *
     * Writes only, no read-back: this is the one call on a hot path — the
     * platform reports it every few hundred kilobytes — and the round-trip
     * `getById` that the other transitions use for their defensive
     * "return what the DB now holds" would double the cost for a value
     * nobody reads.
     */
    fun updateProgress(id: String, bytesDownloaded: Long, bytesTotal: Long?) {
        val row = getById(id) ?: return
        if (row.status.isTerminal()) return
        db.downloadsQueries.updateProgress(
            status = DownloadStatus.DOWNLOADING.sql,
            bytes_downloaded = bytesDownloaded,
            bytes_total = bytesTotal ?: row.bytesTotal,
            started_at = row.startedAt ?: clock(),
            completed_at = null,
            error = null,
            id = id,
        )
    }

    /**
     * Stopped, but keeping what it has.
     *
     * [resumable] is the platform's answer to "can this be picked up where
     * it stopped" — on iOS, whether the system handed back resume data.
     * A paused row that is not resumable restarts from zero when the
     * viewer asks again, and the UI is entitled to say so.
     */
    fun markPaused(id: String, bytesDownloaded: Long, resumable: Boolean): DownloadEntry {
        val row = require(id)
        rejectTerminal(row, DownloadStatus.PAUSED)
        db.downloadsQueries.updateResumable(resumable = resumable, id = id)
        return write(row, status = DownloadStatus.PAUSED, bytesDownloaded = bytesDownloaded)
    }

    /** Back to waiting for the transfer to pick it up. */
    fun markQueued(id: String): DownloadEntry {
        val row = require(id)
        rejectTerminal(row, DownloadStatus.QUEUED)
        return write(row, status = DownloadStatus.QUEUED)
    }

    fun markCompleted(id: String, bytesTotal: Long): DownloadEntry {
        val row = require(id)
        rejectTerminal(row, DownloadStatus.COMPLETED)
        return write(
            row,
            status = DownloadStatus.COMPLETED,
            bytesDownloaded = bytesTotal,
            bytesTotal = bytesTotal,
            completedAt = clock(),
        )
    }

    fun markFailed(id: String, reason: String, bytesDownloaded: Long): DownloadEntry {
        val row = require(id)
        rejectTerminal(row, DownloadStatus.FAILED)
        return write(
            row,
            status = DownloadStatus.FAILED,
            bytesDownloaded = bytesDownloaded,
            completedAt = clock(),
            error = reason,
        )
    }

    fun markCancelled(id: String): DownloadEntry {
        val row = require(id)
        rejectTerminal(row, DownloadStatus.CANCELLED)
        return write(row, status = DownloadStatus.CANCELLED, completedAt = clock())
    }

    fun getById(id: String): DownloadEntry? = db.downloadsQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toEntry()

    fun getAll(): List<DownloadEntry> = db.downloadsQueries
        .selectAll()
        .executeAsList()
        .map { it.toEntry() }

    fun getByStatus(status: DownloadStatus): List<DownloadEntry> = db.downloadsQueries
        .selectByStatus(status.sql)
        .executeAsList()
        .map { it.toEntry() }

    fun allFlow(): Flow<List<DownloadEntry>> = db.downloadsQueries
        .selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)
        .map { rows -> rows.map { it.toEntry() } }

    fun deleteById(id: String) {
        db.downloadsQueries.deleteById(id)
    }

    /**
     * Reconciles the rows against the transfers the platform actually has
     * in flight, at app start.
     *
     * **Not the recordings sweep.** A recording dies with the process, so
     * every `RECORDING` row at launch is an orphan. A download does not:
     * iOS keeps background transfers running while the app is suspended
     * and even after it is killed, so a `DOWNLOADING` row at launch is
     * usually still going. Only the rows the platform does *not* know
     * about are stranded, and those are parked in `PAUSED` — keeping the
     * bytes and letting the viewer resume — rather than failed, because
     * "the app was killed" is not a failure of the download.
     *
     * @param activeIds ids the platform reports as still transferring.
     * @return how many rows were parked.
     */
    fun reconcile(activeIds: Set<String>): Int {
        val stranded = (getByStatus(DownloadStatus.DOWNLOADING) + getByStatus(DownloadStatus.QUEUED))
            .filter { it.id !in activeIds }
        stranded.forEach { row ->
            db.downloadsQueries.updateResumable(resumable = row.bytesDownloaded > 0L, id = row.id)
            write(row, status = DownloadStatus.PAUSED)
        }
        return stranded.size
    }

    // ------------------------------------------------------------------

    private fun require(id: String): DownloadEntry =
        getById(id) ?: error("no such download: $id")

    private fun rejectTerminal(row: DownloadEntry, to: DownloadStatus) {
        if (row.status.isTerminal()) {
            error("download ${row.id} is already ${row.status.sql}; refusing ${to.sql}")
        }
    }

    /**
     * One write path, so every transition carries the whole row forward
     * rather than nulling a column it did not mean to touch —
     * `updateProgress` sets six columns at once and the fields not being
     * changed have to be re-supplied.
     */
    private fun write(
        row: DownloadEntry,
        status: DownloadStatus,
        bytesDownloaded: Long = row.bytesDownloaded,
        bytesTotal: Long? = row.bytesTotal,
        startedAt: Long? = row.startedAt,
        completedAt: Long? = row.completedAt,
        error: String? = row.error,
    ): DownloadEntry {
        db.downloadsQueries.updateProgress(
            status = status.sql,
            bytes_downloaded = bytesDownloaded,
            bytes_total = bytesTotal,
            started_at = startedAt,
            completed_at = completedAt,
            error = error,
            id = row.id,
        )
        return getById(row.id) ?: error("row vanished mid-update: ${row.id}")
    }

    private fun com.yancotv.shared.db.Downloads.toEntry() = DownloadEntry(
        id = id,
        contentId = content_id,
        episodeId = episode_id,
        title = title,
        streamUrl = stream_url,
        filePath = file_path,
        status = DownloadStatus.fromSql(status),
        queuedAt = queued_at,
        startedAt = started_at,
        completedAt = completed_at,
        bytesDownloaded = bytes_downloaded,
        bytesTotal = bytes_total,
        error = error,
        resumable = resumable,
    )
}
