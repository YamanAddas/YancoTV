package com.yancotv.shared.recording

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.yancotv.shared.db.Recording_schedules
import com.yancotv.shared.db.YancoDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * MK.14.3 — state machine for a single `recording_schedules` row.
 *
 * Decoupled from [RecordingStatus] (the recording row's state) on
 * purpose: a schedule lives independently of the eventual recording it
 * spawns. After firing, the schedule retains its history entry; the
 * recording it produced is found by DERIVING the id
 * (`RecordingScheduleScheduler.recordIdForSchedule`), not by storing a
 * link. MB-211 removed the stored `recording_id` column in schema v16.
 *
 * Transition graph:
 *
 * ```
 *   (created) ──insert──> SCHEDULED ──arm──> ARMED ──fire──> FIRING
 *                              │              │                │
 *                              ├──cancel──────┤                ├──complete──> COMPLETED
 *                              │              │                ├──fail──────> FAILED
 *                              │              │                └──cancel────> CANCELLED
 *                              └────────────miss/cancel──────────────────────> MISSED
 * ```
 *
 * **SCHEDULED → ARMED**: when the alarm has been registered with the
 * OS. Survives across app restart only if persisted; this is the
 * source of truth for whether a fire is "live" in the system.
 *
 * **ARMED → FIRING**: when the alarm fires. The recording row is created
 * at this point; its id is derived from the schedule id rather than
 * stored (MB-208, and MB-211 which removed the stored column).
 *
 * **MISSED**: terminal state for "we couldn't run this". Causes:
 *   - device was off when the alarm window passed (`device_offline`)
 *   - 1-stream-cap collision at fire time (`concurrent_recording_active`)
 *   - the channel/content was deleted before fire time (`channel_deleted`)
 *
 * Terminal states (`COMPLETED` / `FAILED` / `CANCELLED` / `MISSED`)
 * are write-once. The repo rejects updates that would leave a
 * terminal row, both to catch service-layer bugs and to keep the
 * UI's "this row is done" assumption invariant.
 */
enum class RecordingScheduleState(val sql: String) {
    /** Inserted, alarm not yet armed. Brief — set() arms immediately. */
    SCHEDULED("scheduled"),

    /** Alarm registered with AlarmManager. */
    ARMED("armed"),

    /** Alarm fired; recording is in progress. */
    FIRING("firing"),

    /** Recording completed successfully. */
    COMPLETED("completed"),

    /** Recording started but errored mid-flight. */
    FAILED("failed"),

    /** User cancelled before firing, or while firing (recording stopped). */
    CANCELLED("cancelled"),

    /** Couldn't run — device off when alarm window expired, or
     *  blocked by another active recording at fire time. */
    MISSED("missed"),
    ;

    fun isTerminal(): Boolean = this in TERMINAL

    companion object {
        private val TERMINAL = setOf(COMPLETED, FAILED, CANCELLED, MISSED)

        fun fromSql(value: String): RecordingScheduleState = values().firstOrNull { it.sql == value }
            ?: error("Unknown recording_schedules.state: $value")
    }
}

/**
 * Domain-friendly view of one `recording_schedules` row. Mirrors the
 * SQLDelight-generated [Recording_schedules] type but flattens the
 * state enum + nullables and isolates UI consumers from codegen.
 */
data class RecordingScheduleEntry(
    val id: String,
    val contentId: String?,
    val programmeId: String?,
    val title: String,
    val streamUrl: String,
    val scheduledStart: Long,
    val scheduledEnd: Long,
    val state: RecordingScheduleState,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** MK.14.6 — set when the schedule was created via manual series
     *  binding ("Record all on this channel"). Null for one-off schedules.
     *  Format: `<channel_id>::<title>`. */
    val seriesKey: String?,
)

/**
 * Wraps `recordingSchedulesQueries` with state-transition validation
 * and domain-type translation. Owned by Android `RecordingScheduleScheduler`
 * (which pairs DB writes with `AlarmManager` registrations) and
 * `RecordingScheduleReceiver` (which transitions FIRING / terminal
 * states from the broadcast context).
 *
 * Tests (commonTest mirror in androidUnitTest) assert the transitions;
 * the alarm/receiver layer trusts the repo to reject illegal moves.
 */
class RecordingScheduleRepository(private val db: YancoDb, private val clock: () -> Long) {
    /** Insert a new SCHEDULED row. The Android scheduler arms the
     *  alarm immediately and transitions to ARMED via [transitionTo]. */
    fun insert(
        id: String,
        contentId: String?,
        programmeId: String?,
        title: String,
        streamUrl: String,
        scheduledStart: Long,
        scheduledEnd: Long,
        seriesKey: String? = null,
    ): RecordingScheduleEntry {
        require(scheduledEnd > scheduledStart) {
            "scheduled_end ($scheduledEnd) must be > scheduled_start ($scheduledStart)"
        }
        val now = clock()
        db.recordingSchedulesQueries.insert(
            id = id,
            content_id = contentId,
            programme_id = programmeId,
            title = title,
            stream_url = streamUrl,
            scheduled_start = scheduledStart,
            scheduled_end = scheduledEnd,
            state = RecordingScheduleState.SCHEDULED.sql,
            error = null,
            created_at = now,
            updated_at = now,
            series_key = seriesKey,
        )
        return getById(id) ?: error("insert succeeded but row missing: $id")
    }

    fun getById(id: String): RecordingScheduleEntry? = db.recordingSchedulesQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toEntry()

    fun getAll(): List<RecordingScheduleEntry> = db.recordingSchedulesQueries
        .selectAll()
        .executeAsList()
        .map { it.toEntry() }

    fun getByState(state: RecordingScheduleState): List<RecordingScheduleEntry> = db.recordingSchedulesQueries
        .selectByState(state.sql)
        .executeAsList()
        .map { it.toEntry() }

    /** MK.14.6 — fetch every schedule (any state) tagged with [seriesKey]. */
    fun getBySeriesKey(seriesKey: String): List<RecordingScheduleEntry> = db.recordingSchedulesQueries
        .selectBySeriesKey(seriesKey)
        .executeAsList()
        .map { it.toEntry() }

    /**
     * Reactive list — backs the RecordingsScreen's "Upcoming" section.
     * Inserts / state transitions / deletes anywhere in the app refresh
     * subscribers without manual reload. Terminal query dispatches off
     * main on `Dispatchers.Default` (KMP-safe — `Dispatchers.IO` only
     * exists on JVM/Android).
     */
    fun allFlow(): Flow<List<RecordingScheduleEntry>> = db.recordingSchedulesQueries
        .selectAll()
        .asFlow()
        .mapToList(Dispatchers.Default)
        .map { rows -> rows.map { it.toEntry() } }

    /**
     * Transition to a new state. Validates legal moves:
     *   - SCHEDULED → ARMED, CANCELLED, MISSED
     *   - ARMED → FIRING, CANCELLED, MISSED
     *   - FIRING → COMPLETED, FAILED, CANCELLED
     *   - Terminal states reject all transitions
     *
     * [errorReason] captures the cause for FAILED/MISSED (`device_offline`,
     * `concurrent_recording_active`, etc.); it's stored in the `error`
     * column and surfaced in the UI's failure tooltip.
     */
    fun transitionTo(id: String, target: RecordingScheduleState, errorReason: String? = null): RecordingScheduleEntry {
        val current = getById(id) ?: error("schedule $id missing — cannot transition to $target")
        require(!current.state.isTerminal()) {
            "schedule $id is already ${current.state.name}; cannot move to $target"
        }
        require(isLegalTransition(current.state, target)) {
            "illegal transition: ${current.state} → $target"
        }
        db.recordingSchedulesQueries.setState(
            state = target.sql,
            updated_at = clock(),
            error = errorReason,
            id = id,
        )
        return getById(id) ?: error("update succeeded but row missing: $id")
    }

    fun deleteById(id: String) {
        db.recordingSchedulesQueries.deleteById(id)
    }

    /**
     * Reap terminal-state rows older than [keepMs]. Guards the
     * "Upcoming" tab against piling up missed-from-when-the-TV-was-off
     * schedules indefinitely. Caller in app boot path.
     *
     * Default 30 days — long enough that the user can review what
     * the TV missed last week, short enough that history doesn't
     * grow unboundedly across years.
     */
    fun reapOlderThan(keepMs: Long = REAP_DEFAULT_MS): Int {
        val cutoff = clock() - keepMs
        val toReap =
            getAll().count { it.state.isTerminal() && it.updatedAt < cutoff }
        db.recordingSchedulesQueries.deleteOlderThan(cutoff)
        return toReap
    }

    /**
     * Reconciliation sweep called on app boot. Resolves the two cases
     * an offline / rebooted device can leave behind:
     *
     *   1. **ARMED schedules whose start window passed while the
     *      device was off** → MISSED (`device_offline`). Without this
     *      sweep they'd stay ARMED forever — no alarm registered (OS
     *      lost it on reboot), no fire-time ever, but the UI would
     *      show "scheduled" and the user wouldn't know.
     *
     *   2. **FIRING schedules whose recording row was orphaned by
     *      [RecordingsRepository.sweepOrphans]** → FAILED. The
     *      schedule + recording must agree on the outcome.
     *
     * Returns `MissedSweepResult` so the caller can log/notify on
     * non-zero counts.
     *
     * Should run AFTER [RecordingsRepository.sweepOrphans] so the
     * recording-row state is current when we read it here.
     *
     * @param graceMs how late an ARMED row can be before we declare
     * it missed. Default 5 minutes covers normal alarm-firing jitter
     * + the sub-1-minute boot reconciliation latency.
     */
    fun reconcileAfterBoot(graceMs: Long = MISSED_GRACE_MS): MissedSweepResult {
        val now = clock()
        val all = getAll()
        var missedCount = 0
        var failedCount = 0
        for (entry in all) {
            when (entry.state) {
                RecordingScheduleState.ARMED ->
                    if (entry.scheduledStart + graceMs < now) {
                        // Alarm window has passed without firing → device was off.
                        db.recordingSchedulesQueries.setState(
                            state = RecordingScheduleState.MISSED.sql,
                            updated_at = now,
                            error = REASON_DEVICE_OFFLINE,
                            id = entry.id,
                        )
                        missedCount++
                    }
                RecordingScheduleState.FIRING -> {
                    // Was firing pre-reboot. RecordingsRepository.sweepOrphans
                    // has already run and marked the linked recording row
                    // FAILED with reason="orphaned_by_app_kill". Mirror that
                    // here so the schedule shows the same failure.
                    db.recordingSchedulesQueries.setState(
                        state = RecordingScheduleState.FAILED.sql,
                        updated_at = now,
                        error = REASON_ORPHANED_BY_APP_KILL,
                        id = entry.id,
                    )
                    failedCount++
                }
                else -> Unit
            }
        }
        return MissedSweepResult(missedCount, failedCount)
    }

    // ── internals ─────────────────────────────────────────────────

    private fun isLegalTransition(from: RecordingScheduleState, to: RecordingScheduleState): Boolean = when (from) {
        RecordingScheduleState.SCHEDULED ->
            to == RecordingScheduleState.ARMED ||
                to == RecordingScheduleState.CANCELLED ||
                to == RecordingScheduleState.MISSED
        RecordingScheduleState.ARMED ->
            to == RecordingScheduleState.FIRING ||
                to == RecordingScheduleState.CANCELLED ||
                to == RecordingScheduleState.MISSED
        RecordingScheduleState.FIRING ->
            to == RecordingScheduleState.COMPLETED ||
                to == RecordingScheduleState.FAILED ||
                to == RecordingScheduleState.CANCELLED
        else -> false
    }

    private fun Recording_schedules.toEntry(): RecordingScheduleEntry = RecordingScheduleEntry(
        id = id,
        contentId = content_id,
        programmeId = programme_id,
        title = title,
        streamUrl = stream_url,
        scheduledStart = scheduled_start,
        scheduledEnd = scheduled_end,
        state = RecordingScheduleState.fromSql(state),
        error = error,
        createdAt = created_at,
        updatedAt = updated_at,
        seriesKey = series_key,
    )

    /** Result counts from [reconcileAfterBoot] — surfaces to the
     *  caller for "we missed N recordings while the TV was off"
     *  toasts / notifications. */
    data class MissedSweepResult(val markedMissed: Int, val markedFailedFromOrphan: Int) {
        val total: Int get() = markedMissed + markedFailedFromOrphan
    }

    companion object {
        /** Default retention for terminal-state schedule rows. */
        const val REAP_DEFAULT_MS: Long = 30L * 24L * 60L * 60_000L

        /** How late an ARMED schedule can be before [reconcileAfterBoot]
         *  declares it missed (5 minutes). */
        const val MISSED_GRACE_MS: Long = 5L * 60_000L

        const val REASON_DEVICE_OFFLINE = "device_offline"
        const val REASON_ORPHANED_BY_APP_KILL = "orphaned_by_app_kill"
        const val REASON_CONCURRENT_RECORDING_ACTIVE = "concurrent_recording_active"

        /**
         * MB-337 — the in-flight recording query failed, so whether a recording
         * was already running could not be determined.
         *
         * Distinct from [REASON_CONCURRENT_RECORDING_ACTIVE] on purpose: that
         * one means "we know another recording is running", this one means "we
         * could not find out". Same outcome (skip), very different diagnosis —
         * collapsing them would hide a broken database behind what looks like
         * ordinary cap contention.
         */
        const val REASON_RECORDING_STATE_UNREADABLE = "recording_state_unreadable"
        const val REASON_CHANNEL_DELETED = "channel_deleted"
        const val REASON_FRESH_GET_FAILED_NO_FALLBACK = "fresh_get_failed_no_fallback"

        /** MB-212 — failure reason when the schedule's end alarm fires
         *  before any recording row exists for the derived recordId.
         *  Means the service was never started, or crashed before
         *  reaching markStarted. */
        const val REASON_RECORDING_NEVER_STARTED = "recording_never_started"

        /** MB-212 — failure reason when the recording row exists but
         *  finalised with zero bytes (server stalled, network died, or
         *  user stopped before any payload arrived). */
        const val REASON_NO_RESPONSE_FROM_SERVER = "no_response_from_server"
    }
}

/**
 * MB-212 / pure-function extraction (analog to MK.23.C.1, MK.24.E.3,
 * MK.24.G.2) — maps a finalised on-disk byte count to the schedule's
 * terminal state + reason. Called by Android `RecordingService.handleStop`
 * after `cancelAndJoin` + `output.close()` + `output.size()` have
 * landed the actual byte count, so the schedule's terminal state
 * matches the recording row's terminal state exactly.
 *
 * Pre-MB-212 the `RecordingScheduleReceiver.handleEnd` made this
 * decision based on a stale read of `recordings.fileSizeBytes` BEFORE
 * the asynchronous `handleStop` had finished flushing. In a sub-100ms
 * window the schedule could lock as FAILED while the recording row
 * eventually transitioned to COMPLETED — the two histories disagreed.
 * Post-fix the decision moves into the service's `handleStop` coroutine
 * (single source of truth for "the recording is done"), and this pure
 * function is shared for unit-test pinning.
 *
 * Bytes <= 0 → FAILED + `REASON_NO_RESPONSE_FROM_SERVER`.
 * Bytes  > 0 → COMPLETED, no error reason.
 */
data class ScheduleOutcomeFromBytes(val state: RecordingScheduleState, val reason: String?)

fun scheduleOutcomeFromBytes(bytesWritten: Long): ScheduleOutcomeFromBytes = if (bytesWritten <= 0L) {
    ScheduleOutcomeFromBytes(
        RecordingScheduleState.FAILED,
        RecordingScheduleRepository.REASON_NO_RESPONSE_FROM_SERVER,
    )
} else {
    ScheduleOutcomeFromBytes(
        RecordingScheduleState.COMPLETED,
        null,
    )
}
