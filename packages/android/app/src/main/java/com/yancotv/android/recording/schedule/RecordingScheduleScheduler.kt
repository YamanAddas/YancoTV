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
        seriesKey: String? = null,
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
                seriesKey = seriesKey,
            )
        alarmManager.arm(
            inserted,
            prePaddingMs = prePaddingSeconds * 1000L,
            postPaddingMs = postPaddingSeconds * 1000L,
        )
        return repo.transitionTo(scheduleId, RecordingScheduleState.ARMED)
    }

    /**
     * MK.14.6 — manual series binding. Creates one schedule per upcoming
     * programme on [channelTvgId] whose title equals [title], within
     * `[now, now + lookaheadMs)`. Returns the count actually scheduled
     * (skips programmes that already have an active schedule for the
     * same `programme_id`, so calling this again is idempotent).
     *
     * The shared `series_key` is `<channelTvgId>::<title>`, snapshotted
     * here so that EPG title drift between bind and cancel doesn't break
     * the binding. [cancelSeries] uses the same key.
     *
     * Caller must already have:
     *   - resolved [streamUrl] from the channel's `ContentItem`,
     *   - looked up the future programme list via
     *     [com.yancotv.shared.epg.EpgRepository.findFutureByChannelAndTitle].
     *
     * `programmes` is `(programmeId, scheduledStart, scheduledEnd)`.
     */
    fun scheduleSeries(
        contentId: String?,
        channelTvgId: String,
        title: String,
        streamUrl: String,
        programmes: List<Triple<String, Long, Long>>,
        prePaddingSeconds: Long = DEFAULT_PRE_PADDING_S,
        postPaddingSeconds: Long = DEFAULT_POST_PADDING_S,
    ): SeriesBindResult {
        val seriesKey = seriesKeyFor(channelTvgId, title)
        // Skip programmes already covered by a non-terminal schedule for
        // this series — keeps re-binding idempotent.
        val existingProgrammeIds =
            repo.getBySeriesKey(seriesKey)
                .filter { !it.state.isTerminal() }
                .mapNotNull { it.programmeId }
                .toSet()
        var created = 0
        for ((programmeId, start, end) in programmes) {
            if (programmeId in existingProgrammeIds) continue
            schedule(
                contentId = contentId,
                programmeId = programmeId,
                title = title,
                streamUrl = streamUrl,
                scheduledStart = start,
                scheduledEnd = end,
                prePaddingSeconds = prePaddingSeconds,
                postPaddingSeconds = postPaddingSeconds,
                seriesKey = seriesKey,
            )
            created++
        }
        Log.i(TAG, "scheduleSeries[$seriesKey] created=$created skipped=${programmes.size - created}")
        return SeriesBindResult(seriesKey, created, programmes.size - created)
    }

    /**
     * Cancel every non-terminal schedule tagged with [seriesKey].
     * Terminal rows (already-fired, missed, etc.) stay as-is — those
     * are history. Returns the number actually cancelled.
     */
    fun cancelSeries(seriesKey: String): Int {
        val targets = repo.getBySeriesKey(seriesKey).filter { !it.state.isTerminal() }
        for (entry in targets) cancel(entry.id)
        Log.i(TAG, "cancelSeries[$seriesKey] cancelled=${targets.size}")
        return targets.size
    }

    data class SeriesBindResult(val seriesKey: String, val created: Int, val skipped: Int)

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
                // Recording id is derived deterministically from the
                // schedule id (see [recordIdForSchedule] for the
                // rationale — schedule.recording_id stays NULL because
                // the schema's FK can't be set before the recordings
                // row exists). Use the derivation here so cancel-during-fire
                // can reach the right active recording.
                val recId = entry.recordingId ?: recordIdForSchedule(scheduleId)
                // **MB-219 (2026-05-15, revised).** Pre-claim CANCELLED on the
                // SCHEDULE only — not the recording row — and pass
                // `userInitiated = true` through `RecordingService.stop` so
                // `handleStop` routes to `markCancelled(real_bytes)` instead
                // of `markFailed(reason=no_response_from_server)`.
                //
                // The earlier revision pre-claimed `markCancelled(id, 0L)`
                // on the recording row; that won the terminal-state race
                // against `handleStop` but with a 0-byte file_size_bytes,
                // which is wrong for live-tee recordings whose on-disk
                // file may hold hours of captured bytes. The schedule has
                // no byte count to lose, so its pre-claim is still
                // correct — that fixes the "Cancel → FAILED" visible bug
                // without throwing away the row's real byte count.
                runCatching {
                    repo.transitionTo(scheduleId, RecordingScheduleState.CANCELLED)
                }.onFailure {
                    Log.w(TAG, "schedule[$scheduleId] pre-claim CANCELLED failed", it)
                }
                RecordingService.stop(context, recId, userInitiated = true)
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
    fun rescheduleAll(prePaddingSeconds: Long = DEFAULT_PRE_PADDING_S, postPaddingSeconds: Long = DEFAULT_POST_PADDING_S) {
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

        /**
         * Deterministic recording id derived from a schedule id.
         *
         * The naive flow ("generate fresh recordId, set
         * `recording_schedules.recording_id = recordId`, start the
         * service which inserts the recordings row with that id")
         * trips the schema's FK constraint:
         * `recording_schedules.recording_id REFERENCES recordings(id)`
         * is enforced at update-time, but the recordings row doesn't
         * exist yet when the receiver runs. The throw is caught by
         * the receiver's `runCatching`, the schedule never transitions,
         * and nothing fires.
         *
         * Solution (no schema change): make recordId deterministic
         * from scheduleId. The receiver doesn't need to store the
         * mapping in the DB — cancel/end paths can re-derive the
         * recordId from the schedule id. The schedule's `recording_id`
         * column stays NULL throughout the lifecycle (we'll wire a
         * cleanup migration when we add recurring schedules and need
         * a fire-counter discriminator).
         *
         * Format: `sched-rec-<scheduleId>`. The prefix differentiates
         * scheduled-recording rows from manual-recording rows in
         * logcat/Recordings UI scans.
         */
        fun recordIdForSchedule(scheduleId: String): String = "sched-rec-$scheduleId"

        /**
         * MB-212 — inverse of [recordIdForSchedule]. Returns the originating
         * scheduleId when [recordId] was generated by a scheduled recording
         * (i.e. starts with the `sched-rec-` prefix). Returns null for
         * ad-hoc record-now recordings, manual UI captures, or any other
         * id that doesn't carry the schedule prefix — those have no
         * schedule to transition.
         *
         * Used by `RecordingService.handleStop` to derive the schedule
         * id at finalize time without plumbing it through `RecordInput` /
         * Intent extras / `activeJobs` — keeps the cross-process state
         * surface to a minimum. The naming convention is the source of
         * truth; pinned by `ScheduleIdFromRecordIdTest`.
         */
        fun scheduleIdFromRecordId(recordId: String): String? = if (recordId.startsWith("sched-rec-")) {
            recordId.removePrefix("sched-rec-")
        } else {
            null
        }

        /** Canonical series-binding key. Snapshot at bind time; cancel
         *  uses the same key regardless of later EPG title drift. */
        fun seriesKeyFor(channelTvgId: String, title: String): String = "$channelTvgId::$title"
    }
}
