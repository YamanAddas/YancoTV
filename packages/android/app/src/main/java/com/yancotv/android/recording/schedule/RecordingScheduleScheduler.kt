package com.yancotv.android.recording.schedule

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.recording.RecordingService
import com.yancotv.shared.recording.RecordingScheduleEntry
import com.yancotv.shared.recording.RecordingScheduleRepository
import com.yancotv.shared.recording.RecordingScheduleState
import java.util.UUID

/**
 * MK.14.3 — public API for scheduled recordings. Pairs DB row writes
 * with `AlarmManager` registrations and ensures the two stay in lockstep.
 *
 * Owned by the EPG long-press flow (slice 4 / MK.14.4) and the
 * RecordingsScreen "Upcoming" cancel button (slice 3). The
 * [RecordingScheduleReceiver] uses [RecordingScheduleRepository]
 * directly for fire-time transitions; this class is the *outer*
 * scheduling/cancellation surface.
 *
 * **Default padding values** (per the user-confirmed plan):
 *   - **Pre = 30 seconds** — absorbs provider clock drift + the
 *     recorder's connection handshake. Catches the kickoff every time
 *     even if the broadcast actually starts a few seconds early.
 *   - **Post = 5 minutes** — absorbs sports stoppage time, talk-show
 *     overruns, and the next programme's overlap. Trade is ~30-60 MB
 *     of extra bytes per recording at IPTV bitrates; far better than
 *     missing the winning goal.
 */
@UnstableApi
class RecordingScheduleScheduler(
    private val context: Context,
    private val repo: RecordingScheduleRepository,
    private val alarmManager: RecordingScheduleAlarmManager,
) {
    /**
     * Create a new schedule. Inserts the DB row in `SCHEDULED`, arms
     * pre-fire + end alarms, then transitions to `ARMED`. Returns the
     * final entry (state = ARMED) so callers don't have to re-fetch.
     *
     * [scheduleId] defaults to a random UUID. Caller can supply a
     * deterministic id if they want to reuse the slot (idempotent
     * EPG long-press: same programme → same schedule id → re-arm
     * replaces prior alarm).
     */
    fun schedule(
        contentId: String?,
        programmeId: String?,
        title: String,
        streamUrl: String,
        scheduledStart: Long,
        scheduledEnd: Long,
        scheduleId: String = "sched-${UUID.randomUUID()}",
        prePaddingSeconds: Long = DEFAULT_PRE_PADDING_S,
        postPaddingSeconds: Long = DEFAULT_POST_PADDING_S,
    ): RecordingScheduleEntry {
        val inserted =
            repo.insert(
                id = scheduleId,
                contentId = contentId,
                programmeId = programmeId,
                title = title,
                streamUrl = streamUrl,
                scheduledStart = scheduledStart,
                scheduledEnd = scheduledEnd,
            )
        alarmManager.arm(
            inserted,
            prePaddingMs = prePaddingSeconds * 1000L,
            postPaddingMs = postPaddingSeconds * 1000L,
        )
        return repo.transitionTo(scheduleId, RecordingScheduleState.ARMED)
    }

    /**
     * Cancel a schedule. Behavior depends on current state:
     *
     *   - `SCHEDULED` / `ARMED` → cancel alarms + transition to CANCELLED.
     *   - `FIRING` → stop the active recording (if any) + cancel end alarm
     *     + transition to CANCELLED. The recording row transitions
     *     independently to COMPLETED-with-bytes (whatever was captured
     *     up to now) or FAILED-with-no-bytes.
     *   - Terminal states (`COMPLETED`/`FAILED`/`CANCELLED`/`MISSED`)
     *     → no-op (logs a warning so unintended cancel-after-finish is
     *     visible in logcat).
     *
     * Always cancels the alarm slot defensively, even for terminal
     * rows — saves a stale alarm from firing after a manual DB edit
     * or migration weirdness.
     */
    fun cancel(scheduleId: String) {
        val entry =
            repo.getById(scheduleId) ?: run {
                Log.w(TAG, "cancel for unknown schedule $scheduleId — no-op")
                return
            }
        // Always cancel alarms — defensive against state-table drift.
        alarmManager.cancel(scheduleId)
        when (entry.state) {
            RecordingScheduleState.SCHEDULED, RecordingScheduleState.ARMED -> {
                runCatching {
                    repo.transitionTo(scheduleId, RecordingScheduleState.CANCELLED)
                }
            }
            RecordingScheduleState.FIRING -> {
                entry.recordingId?.let { recId -> RecordingService.stop(context, recId) }
                runCatching {
                    repo.transitionTo(scheduleId, RecordingScheduleState.CANCELLED)
                }
            }
            else ->
                Log.i(TAG, "cancel for terminal schedule $scheduleId state=${entry.state} — alarms cleared, row untouched")
        }
    }

    /**
     * Re-arm every `ARMED` schedule whose start time is still in the
     * future. Called on app boot (alarms don't survive reboot).
     *
     * Should run AFTER [RecordingScheduleRepository.reconcileAfterBoot]
     * so lapsed-during-reboot rows have already been transitioned to
     * MISSED — we'd otherwise be re-arming alarms for already-passed
     * windows.
     */
    fun rescheduleAll(
        prePaddingSeconds: Long = DEFAULT_PRE_PADDING_S,
        postPaddingSeconds: Long = DEFAULT_POST_PADDING_S,
    ) {
        val now = System.currentTimeMillis()
        val armed = repo.getByState(RecordingScheduleState.ARMED)
        for (schedule in armed) {
            if (schedule.scheduledEnd <= now) {
                // Defensive — reconcileAfterBoot should have caught these.
                continue
            }
            alarmManager.arm(
                schedule,
                prePaddingMs = prePaddingSeconds * 1000L,
                postPaddingMs = postPaddingSeconds * 1000L,
            )
        }
        Log.i(TAG, "rescheduleAll: re-armed ${armed.size} schedule(s) after boot")
    }

    companion object {
        private const val TAG = "YancoSchedScheduler"

        /** 30 seconds — absorbs provider clock drift + connection handshake. */
        const val DEFAULT_PRE_PADDING_S: Long = 30L

        /** 5 minutes — absorbs stoppage time / programme overruns / next-programme overlap. */
        const val DEFAULT_POST_PADDING_S: Long = 5L * 60L
    }
}
