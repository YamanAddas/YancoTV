package com.yancotv.shared.recording

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.yancotv.shared.db.Recordings
import com.yancotv.shared.db.YancoDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * State machine for a single `recordings` row. Transitions are
 * directed and one-way:
 *
 * ```
 *   (created) ──insert──> RECORDING ──complete──> COMPLETED
 *                              │
 *                              ├─fail──> FAILED
 *                              │
 *                              └─cancel──> CANCELLED
 * ```
 *
 * Terminal states (`COMPLETED` / `FAILED` / `CANCELLED`) are write-once.
 * The repo rejects updates that would leave a terminal row, both to
 * catch service-layer bugs and to keep the UI's "this row is done"
 * assumption invariant.
 */
enum class RecordingStatus(val sql: String) {
    RECORDING("recording"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ;

    fun isTerminal(): Boolean = this != RECORDING

    companion object {
        fun fromSql(value: String): RecordingStatus =
            values().firstOrNull { it.sql == value }
                ?: error("Unknown recording status: $value")
    }
}

/**
 * Domain-friendly view of one `recordings` row. Distinct from the
 * SQLDelight-generated [Recordings] type so the UI doesn't depend
 * on the codegen shape.
 */
data class RecordingEntry(
    val id: String,
    val contentId: String?,
    val title: String,
    val streamUrl: String,
    val filePath: String,
    val status: RecordingStatus,
    val startedAt: Long,
    val endedAt: Long?,
    val durationSeconds: Long?,
    val fileSizeBytes: Long?,
    val error: String?,
    val format: RecordingFormat?,
)

/**
 * Wraps `recordingsQueries` with state-transition validation +
 * domain-type translation. The Android `RecordingService` calls
 * [markStarted] before kicking off the recorder, then
 * [markCompleted] / [markFailed] / [markCancelled] when the
 * recorder returns. Tests assert the transitions; the service
 * trusts the repo to reject illegal moves.
 */
class RecordingsRepository(
    private val db: YancoDb,
    private val clock: () -> Long,
) {
    /** Insert a fresh `RECORDING` row. Caller supplies an id (typically
     *  a UUID); throws if the id collides. */
    fun markStarted(
        id: String,
        contentId: String?,
        title: String,
        streamUrl: String,
        filePath: String,
        format: RecordingFormat,
    ): RecordingEntry {
        val now = clock()
        db.recordingsQueries.insert(
            id = id,
            content_id = contentId,
            title = title,
            stream_url = streamUrl,
            file_path = filePath,
            status = RecordingStatus.RECORDING.sql,
            started_at = now,
            ended_at = null,
            duration_seconds = null,
            file_size_bytes = null,
            error = null,
            format = formatToSql(format),
        )
        // Round-trip via selectById so we return whatever the row
        // looks like post-insert (defensive — keeps the contract
        // "the entry I get back matches what's in the DB").
        return getById(id) ?: error("insert succeeded but row missing: $id")
    }

    fun markCompleted(
        id: String,
        bytesWritten: Long,
        durationSeconds: Long,
    ): RecordingEntry = transitionTerminal(id, RecordingStatus.COMPLETED, bytesWritten, durationSeconds, error = null)

    fun markFailed(
        id: String,
        reason: String,
        bytesWritten: Long,
    ): RecordingEntry =
        transitionTerminal(
            id,
            RecordingStatus.FAILED,
            bytesWritten,
            durationSeconds = null,
            error = reason,
        )

    fun markCancelled(
        id: String,
        bytesWritten: Long,
    ): RecordingEntry =
        transitionTerminal(
            id,
            RecordingStatus.CANCELLED,
            bytesWritten,
            durationSeconds = null,
            error = null,
        )

    fun getById(id: String): RecordingEntry? =
        db.recordingsQueries
            .selectById(id)
            .executeAsOneOrNull()
            ?.toEntry()

    fun getAll(): List<RecordingEntry> =
        db.recordingsQueries
            .selectAll()
            .executeAsList()
            .map { it.toEntry() }

    fun getByStatus(status: RecordingStatus): List<RecordingEntry> =
        db.recordingsQueries
            .selectByStatus(status.sql)
            .executeAsList()
            .map { it.toEntry() }

    /** Reactive list — backs the RecordingsScreen. Inserts /
     *  transitions / deletes anywhere in the app refresh subscribers
     *  without manual reload. Terminal IO dispatches off main. */
    fun allFlow(): Flow<List<RecordingEntry>> =
        db.recordingsQueries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map { it.toEntry() } }

    fun deleteById(id: String) {
        db.recordingsQueries.deleteById(id)
    }

    /**
     * Recovery sweep called by the service on app start: any rows
     * left in `RECORDING` after a crash get marked
     * `FAILED("orphaned_by_app_kill")` so they don't appear stuck
     * in the UI forever. Returns the count swept so the caller can
     * surface a one-time toast.
     *
     * Per the design spec §2 Q10, recordings ≥ [orphanThresholdMs]
     * old at sweep time are reaped; younger ones may still be picked
     * up by the service for resume (out of scope for v1.0).
     */
    fun sweepOrphans(orphanThresholdMs: Long = ORPHAN_THRESHOLD_MS_DEFAULT): Int {
        val now = clock()
        val orphans =
            getByStatus(RecordingStatus.RECORDING)
                .filter { now - it.startedAt >= orphanThresholdMs }
        orphans.forEach { entry ->
            db.recordingsQueries.updateStatus(
                status = RecordingStatus.FAILED.sql,
                ended_at = now,
                duration_seconds = entry.durationSeconds,
                file_size_bytes = entry.fileSizeBytes,
                error = "orphaned_by_app_kill",
                id = entry.id,
            )
        }
        return orphans.size
    }

    // ── internal ──────────────────────────────────────────────────

    private fun transitionTerminal(
        id: String,
        target: RecordingStatus,
        bytesWritten: Long,
        durationSeconds: Long?,
        error: String?,
    ): RecordingEntry {
        require(target.isTerminal()) {
            "transitionTerminal must move to a terminal state, got $target"
        }
        val current =
            getById(id)
                ?: error("recording $id missing — cannot transition to $target")
        require(!current.status.isTerminal()) {
            "recording $id is already ${current.status.name}; cannot move to $target"
        }
        val now = clock()
        db.recordingsQueries.updateStatus(
            status = target.sql,
            ended_at = now,
            duration_seconds = durationSeconds,
            file_size_bytes = bytesWritten,
            error = error,
            id = id,
        )
        return getById(id) ?: error("update succeeded but row missing: $id")
    }

    private fun Recordings.toEntry(): RecordingEntry =
        RecordingEntry(
            id = id,
            contentId = content_id,
            title = title,
            streamUrl = stream_url,
            filePath = file_path,
            status = RecordingStatus.fromSql(status),
            startedAt = started_at,
            endedAt = ended_at,
            durationSeconds = duration_seconds,
            fileSizeBytes = file_size_bytes,
            error = error,
            format = formatFromSql(format),
        )

    private fun formatToSql(format: RecordingFormat): String =
        when (format) {
            RecordingFormat.HLS -> "hls"
            RecordingFormat.MPEG_TS -> "mpeg_ts"
        }

    private fun formatFromSql(value: String?): RecordingFormat? =
        when (value) {
            "hls" -> RecordingFormat.HLS
            "mpeg_ts" -> RecordingFormat.MPEG_TS
            null -> null
            else -> null // unrecognised tag from a future build; treat as unknown
        }

    companion object {
        // 10 minutes — matches design spec §2 Q10. Rows with
        // started_at older than this at sweep time are reaped
        // outright; v1.0 doesn't attempt resume.
        const val ORPHAN_THRESHOLD_MS_DEFAULT: Long = 10L * 60_000L
    }
}
