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
        fun fromSql(value: String): RecordingStatus = values().firstOrNull { it.sql == value }
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
    /**
     * MB-219 — boot-recovery hook for [sweepOrphans].
     *
     * `RecordingService.handleStop` runs in this order:
     *   1. `cancelAndJoin` (recorder's `finally` flushes bytes to disk)
     *   2. `output.close()` (flips MediaStore `IS_PENDING=0` so the
     *      file is visible to Gallery / queries)
     *   3. `output.size()` (reads the on-disk byte count)
     *   4. `markCompleted` / `markFailed` (status flip)
     *
     * Process death anywhere between (1) and (4) leaves the file on
     * disk but the row in `RECORDING`. Without this hook, [sweepOrphans]
     * blindly transitions every stale `RECORDING` row to
     * `FAILED("orphaned_by_app_kill")` — which loses a perfectly-
     * playable file behind a "Failed" badge in the Recordings list.
     *
     * When non-null, [sweepOrphans] queries the file at the row's
     * `file_path` first; if the file exists with bytes ≥
     * [MIN_RECOVERED_BYTES] the row lands as `COMPLETED` instead.
     * Default `null` preserves prior behaviour for tests / KMP
     * targets that don't have a platform file API wired up.
     *
     * Android binds this in `AppModules.kt` to a ContentResolver-
     * backed query for `content://` URIs and a `File.length()` for
     * file paths.
     */
    private val fileBytesIfExists: ((fileUri: String) -> Long?)? = null,
) {
    /** Insert a fresh `RECORDING` row. Caller supplies an id (typically
     *  a UUID); throws if the id collides. */
    fun markStarted(id: String, contentId: String?, title: String, streamUrl: String, filePath: String, format: RecordingFormat): RecordingEntry {
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

    fun markCompleted(id: String, bytesWritten: Long, durationSeconds: Long): RecordingEntry =
        transitionTerminal(id, RecordingStatus.COMPLETED, bytesWritten, durationSeconds, error = null)

    fun markFailed(id: String, reason: String, bytesWritten: Long): RecordingEntry = transitionTerminal(
        id,
        RecordingStatus.FAILED,
        bytesWritten,
        durationSeconds = null,
        error = reason,
    )

    fun markCancelled(id: String, bytesWritten: Long): RecordingEntry = transitionTerminal(
        id,
        RecordingStatus.CANCELLED,
        bytesWritten,
        durationSeconds = null,
        error = null,
    )

    fun getById(id: String): RecordingEntry? = db.recordingsQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toEntry()

    fun getAll(): List<RecordingEntry> = db.recordingsQueries
        .selectAll()
        .executeAsList()
        .map { it.toEntry() }

    fun getByStatus(status: RecordingStatus): List<RecordingEntry> = db.recordingsQueries
        .selectByStatus(status.sql)
        .executeAsList()
        .map { it.toEntry() }

    /** Reactive list — backs the RecordingsScreen. Inserts /
     *  transitions / deletes anywhere in the app refresh subscribers
     *  without manual reload. Terminal query dispatches off main on
     *  `Dispatchers.Default` (KMP-safe — `Dispatchers.IO` only exists
     *  on JVM/Android). */
    fun allFlow(): Flow<List<RecordingEntry>> = db.recordingsQueries
        .selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)
        .map { rows -> rows.map { it.toEntry() } }

    fun deleteById(id: String) {
        db.recordingsQueries.deleteById(id)
    }

    /**
     * Recovery sweep called by the service on app start: any rows
     * left in `RECORDING` after a crash get transitioned out of the
     * non-terminal state so they don't appear stuck in the UI forever.
     * Returns the count swept so the caller can surface a one-time
     * toast.
     *
     * Per the design spec §2 Q10, recordings ≥ [orphanThresholdMs]
     * old at sweep time are reaped; younger ones may still be picked
     * up by the service for resume (out of scope for v1.0).
     *
     * MB-219 — when [fileBytesIfExists] is wired up, each orphan's
     * file is probed before deciding the terminal state:
     *   - File present with ≥ [MIN_RECOVERED_BYTES] → `COMPLETED`,
     *     with `duration_seconds` derived from `(now - started_at)`
     *     and `file_size_bytes` set to the on-disk byte count.
     *     The recorder's `finally` ran successfully; only the
     *     status flip was missed.
     *   - File missing / smaller than the floor / hook unset →
     *     `FAILED("orphaned_by_app_kill")` (existing behaviour).
     *
     * The 64 KB floor filters out empty files and very small
     * partial writes (HTTP error response bodies, header-only
     * captures) that wouldn't play back even if the row were
     * COMPLETED. A real stream that wrote ≥ 64 KB has at least
     * captured a few TS packets / one HLS segment.
     */
    fun sweepOrphans(orphanThresholdMs: Long = ORPHAN_THRESHOLD_MS_DEFAULT): Int {
        val now = clock()
        val orphans =
            getByStatus(RecordingStatus.RECORDING)
                .filter { now - it.startedAt >= orphanThresholdMs }
        orphans.forEach { entry ->
            val recoveredBytes = fileBytesIfExists?.invoke(entry.filePath)
            if (recoveredBytes != null && recoveredBytes >= MIN_RECOVERED_BYTES) {
                // Process died between handleStop's cancelAndJoin and
                // markCompleted — recorder finished writing to disk
                // but the status flip never landed. Salvage as
                // COMPLETED so the user can play the recording.
                val secs = ((now - entry.startedAt) / 1000L).coerceAtLeast(0L)
                db.recordingsQueries.updateStatus(
                    status = RecordingStatus.COMPLETED.sql,
                    ended_at = now,
                    duration_seconds = secs,
                    file_size_bytes = recoveredBytes,
                    error = null,
                    id = entry.id,
                )
            } else {
                db.recordingsQueries.updateStatus(
                    status = RecordingStatus.FAILED.sql,
                    ended_at = now,
                    duration_seconds = entry.durationSeconds,
                    file_size_bytes = entry.fileSizeBytes,
                    error = "orphaned_by_app_kill",
                    id = entry.id,
                )
            }
        }
        return orphans.size
    }

    // ── internal ──────────────────────────────────────────────────

    private fun transitionTerminal(id: String, target: RecordingStatus, bytesWritten: Long, durationSeconds: Long?, error: String?): RecordingEntry {
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

    private fun Recordings.toEntry(): RecordingEntry = RecordingEntry(
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

    private fun formatToSql(format: RecordingFormat): String = when (format) {
        RecordingFormat.HLS -> "hls"
        RecordingFormat.MPEG_TS -> "mpeg_ts"
    }

    private fun formatFromSql(value: String?): RecordingFormat? = when (value) {
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

        // MB-219 — minimum on-disk byte count for an orphan row to
        // be salvaged as COMPLETED instead of FAILED. Filters HTTP
        // error response bodies (a few KB), header-only captures,
        // and truncated stream prefixes that wouldn't play. A real
        // recording that made it past the recorder's first flush
        // has captured at least one HLS segment (~250 KB+) or
        // several TS packets (188 B × N).
        const val MIN_RECOVERED_BYTES: Long = 64L * 1024L
    }
}
