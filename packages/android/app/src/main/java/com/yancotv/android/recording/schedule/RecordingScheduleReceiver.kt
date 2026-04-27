package com.yancotv.android.recording.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.recording.RecordingService
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.recording.RecordingScheduleEntry
import com.yancotv.shared.recording.RecordingScheduleRepository
import com.yancotv.shared.recording.RecordingScheduleState
import com.yancotv.shared.recording.RecordingStatus
import com.yancotv.shared.recording.RecordingsRepository
import com.yancotv.shared.recording.RecordingFormat
import com.yancotv.shared.recording.RecordInput
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * MK.14.3 — fires when a schedule's pre-fire or end alarm triggers.
 *
 * **Pre-fire path (`ACTION_PRE_FIRE`):** decides the recording strategy
 * for the user's 1-stream provider reality without asking for input
 * (scheduling IS consent — see the design doc red-team round 2).
 *
 * Decision tree:
 *
 * ```
 *   schedule = repo.getById(id)
 *   if state != ARMED → no-op (race / duplicate / cancelled before fire)
 *
 *   playerCurrentUrl = controller.currentItem?.streamUrl
 *   sameChannel = playerCurrentUrl == schedule.streamUrl
 *
 *   if sameChannel:
 *     → tee path. RecordingService.handleStart's URL match auto-routes to live-tee.
 *
 *   else if any recording is currently RECORDING (1-stream cap):
 *     → MISSED, reason=concurrent_recording_active.
 *
 *   else:
 *     → switch player to scheduled channel (PlaybackController.play),
 *       then start RecordingService (live-tee path takes over once the
 *       player attaches).
 * ```
 *
 * **End path (`ACTION_END`):** stops the linked recording and transitions
 * the schedule to COMPLETED. The recording row's terminal status is the
 * source of truth for whether bytes actually landed; the schedule's
 * state is informational ("the user's intent was honored").
 *
 * **Threading.** `BroadcastReceiver.onReceive` runs on the main thread
 * with a ~10-second budget. PlaybackController is main-thread-only
 * (so the player switch is safe to call directly). RecordingService.start
 * fires-and-forgets via `startForegroundService`. SQLDelight reads /
 * writes are fast — well within budget.
 *
 * **Foreground-service-from-receiver permission.** API 31+ restricts
 * starting foreground services from background contexts, but exact
 * alarms (which we use) get an exemption window — see
 * https://developer.android.com/develop/background-work/services/foreground-services#background-start-restrictions
 */
/**
 * MB-215 — testable extraction of the FGS-start-or-fail logic.
 * Top-level (not a method) so a JVM unit test can verify that a failed
 * `startService` lambda routes to `transitionFailed` with a
 * `service_start_failed:` reason — no Context, no Receiver, no Service
 * required. Production call site lives in [RecordingScheduleReceiver.startRecording].
 */
internal fun tryStartOrFailSchedule(
    scheduleId: String,
    tag: String,
    startService: () -> Unit,
    transitionFailed: (reason: String) -> Unit,
) {
    runCatching { startService() }
        .onFailure { t ->
            Log.e(tag, "RecordingService.start failed for $scheduleId", t)
            transitionFailed("service_start_failed: ${t.message ?: t::class.simpleName}")
        }
}

@UnstableApi
class RecordingScheduleReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val schedules: RecordingScheduleRepository by inject()
    private val recordings: RecordingsRepository by inject()
    private val content: ContentRepository by inject()
    private val playbackController: PlaybackController by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val scheduleId =
            intent.getStringExtra(RecordingScheduleAlarmManager.EXTRA_SCHEDULE_ID) ?: run {
                Log.w(TAG, "received ${intent.action} with no schedule_id — ignoring")
                return
            }
        when (intent.action) {
            RecordingScheduleAlarmManager.ACTION_PRE_FIRE -> handlePreFire(context, scheduleId)
            RecordingScheduleAlarmManager.ACTION_END -> handleEnd(context, scheduleId)
            else -> Log.w(TAG, "unexpected action ${intent.action}")
        }
    }

    private fun handlePreFire(
        context: Context,
        scheduleId: String,
    ) {
        val schedule =
            schedules.getById(scheduleId) ?: run {
                Log.w(TAG, "pre-fire for missing schedule $scheduleId — ignoring")
                return
            }
        if (schedule.state != RecordingScheduleState.ARMED) {
            // Race / duplicate / cancelled-before-fire. Common cases:
            //   - User cancelled at T-2s; alarm still queued in OS.
            //   - System fired the same alarm twice (rare but possible).
            // Both are benign no-ops.
            Log.i(TAG, "pre-fire for schedule $scheduleId in state ${schedule.state} — no-op")
            return
        }

        val currentUrl = playbackController.currentItem.value?.streamUrl
        val sameChannel = currentUrl != null && currentUrl == schedule.streamUrl
        val activeRecordings =
            runCatching {
                recordings.getByStatus(RecordingStatus.RECORDING)
            }.getOrDefault(emptyList())

        when {
            sameChannel -> {
                // Player is on the scheduled channel. Tee — no second connection,
                // no player switch, no user interruption. RecordingService.handleStart's
                // URL-match check auto-routes to handleStartLiveTee.
                Log.i(TAG, "fire[$scheduleId] path=tee_same_channel")
                startRecording(context, schedule)
            }

            activeRecordings.isNotEmpty() -> {
                // 1-stream IPTV cap exhausted: another channel's recording is
                // already in flight. Don't try to add a second connection — would
                // either fail or kick the existing one. Mark MISSED so the
                // schedule history reflects what happened.
                Log.w(
                    TAG,
                    "fire[$scheduleId] path=missed reason=concurrent " +
                        "(${activeRecordings.size} active recordings)",
                )
                runCatching {
                    schedules.transitionTo(
                        scheduleId,
                        RecordingScheduleState.MISSED,
                        errorReason = RecordingScheduleRepository.REASON_CONCURRENT_RECORDING_ACTIVE,
                    )
                }
            }

            currentUrl == null -> {
                // **MB-209 (2026-04-27) — headless / standby path.**
                // No active playback. Don't kick ExoPlayer here: in a
                // fresh process (app was closed when the alarm fired)
                // or with the TV in standby, there's no PlayerActivity
                // and no video Surface attached. ExoPlayer would pull
                // bytes only until its buffer fills (~15–45s) and then
                // pause loading — the recording would freeze short.
                //
                // Skip play() entirely and let RecordingService's
                // URL-match check naturally route to fresh-GET, which
                // opens its own HTTP connection via MpegTsRecorder /
                // HlsRecorder and writes bytes straight to disk — no
                // Surface, no decoder, full duration. The "1-stream
                // cap" rationale that made tee preferred only applies
                // when the user is actively watching; with nothing
                // playing, fresh-GET is correct.
                Log.i(TAG, "fire[$scheduleId] path=headless_fresh_get")
                startRecording(context, schedule)
            }

            else -> {
                // Player is on a different channel (and active). Switch
                // the player to the scheduled channel; once it attaches,
                // RecordingService.handleStart's URL match routes to
                // live-tee. This avoids opening a second HTTP connection
                // (1-stream IPTV cap).
                //
                // Scheduling IS consent — we don't ask the user before
                // switching. If they're actively watching something else,
                // they'll see the channel change. Their recorded
                // programme matters more than the current viewing
                // decision they made earlier; that was the implicit
                // trade when they scheduled this.
                Log.i(TAG, "fire[$scheduleId] path=switch_then_tee from=$currentUrl")
                val contentItem = resolveContentItem(schedule)
                // **MB-208 (2026-04-27).** Receiver onReceive runs on
                // the main thread already, so call play() directly
                // instead of deferring through getMainExecutor. The
                // deferral let RecordingService.handleStart's
                // currentItem.value read fire BEFORE the player swap
                // was applied, sometimes routing to fresh-GET (a
                // second HTTP connection to the same channel — kills
                // 1-stream IPTV plans). Synchronous play() guarantees
                // controller.currentItem is the scheduled URL by the
                // time the FGS intent is delivered.
                runCatching { playbackController.play(listOf(contentItem), 0) }
                    .onFailure { Log.w(TAG, "playbackController.play failed for $scheduleId", it) }
                startRecording(context, schedule)
            }
        }
    }

    private fun handleEnd(
        context: Context,
        scheduleId: String,
    ) {
        val schedule =
            schedules.getById(scheduleId) ?: run {
                Log.w(TAG, "end for missing schedule $scheduleId — ignoring")
                return
            }
        if (schedule.state != RecordingScheduleState.FIRING) {
            // Schedule already terminal (cancelled / missed / completed by an
            // earlier path). End alarm is no-op.
            Log.i(TAG, "end[$scheduleId] schedule in state ${schedule.state} — no-op")
            return
        }
        // Derive the recordId from the schedule id (same as
        // [startRecording]). schedule.recordingId is intentionally null
        // — see the FK-timing comment in [startRecording] — so we
        // can't read it here.
        val recordId =
            schedule.recordingId
                ?: RecordingScheduleScheduler.recordIdForSchedule(scheduleId)
        Log.i(TAG, "end[$scheduleId] stopping recording $recordId")
        RecordingService.stop(context, recordId)

        // **MB-208 (2026-04-27).** Don't unconditionally transition the
        // schedule to COMPLETED — that lied about successful capture
        // when the recording never actually started (process death
        // mid-window, FGS-from-background restriction, or
        // resolveOutputOrFail throwing before markStarted). Check the
        // recording row instead:
        //
        //   - row missing                → FAILED (`recording_never_started`)
        //   - row present but 0 bytes    → FAILED (`zero_bytes_captured`)
        //   - row present with bytes     → COMPLETED (recording row's own
        //                                   status reflects whether the
        //                                   stop-flush succeeded)
        //
        // RecordingService.stop dispatches an Intent; the actual flush
        // happens asynchronously, so reading row status here would race.
        // Row presence + size is enough to distinguish "never recorded"
        // from "did record" without needing to wait for the flush.
        val finalState: RecordingScheduleState
        val reason: String?
        val row = runCatching { recordings.getById(recordId) }.getOrNull()
        when {
            row == null -> {
                finalState = RecordingScheduleState.FAILED
                reason = "recording_never_started"
            }
            (row.fileSizeBytes ?: 0L) <= 0L -> {
                finalState = RecordingScheduleState.FAILED
                reason = "zero_bytes_captured"
            }
            else -> {
                finalState = RecordingScheduleState.COMPLETED
                reason = null
            }
        }
        Log.i(
            TAG,
            "end[$scheduleId] row=${row?.id} status=${row?.status} bytes=${row?.fileSizeBytes} -> $finalState",
        )
        runCatching {
            schedules.transitionTo(scheduleId, finalState, errorReason = reason)
        }.onFailure { Log.w(TAG, "schedule[$scheduleId] $finalState-transition failed", it) }
    }

    private fun startRecording(
        context: Context,
        schedule: RecordingScheduleEntry,
    ) {
        // **MK.14.3 fix (2026-04-26 hands-on bug)**. The original receiver
        // called `schedules.linkRecording(scheduleId, recordId)` BEFORE
        // `RecordingService.start` inserted the recordings row. The
        // schema's `recording_schedules.recording_id REFERENCES recordings(id)`
        // FK is enforced at UPDATE time — the row didn't exist yet, so
        // SQLite threw a foreign-key constraint failure. The throw was
        // caught by the surrounding `runCatching` and the schedule
        // never transitioned. The user saw the schedule stuck in ARMED
        // with the alarm-fire silently no-op'd.
        //
        // Fix: don't touch `recording_id` at all. Derive the recordId
        // deterministically from the schedule id via
        // [RecordingScheduleScheduler.recordIdForSchedule]. State
        // transition is decoupled from the FK link — receiver moves
        // ARMED → FIRING via `transitionTo`, which only writes `state`
        // (no FK constraint involved). Cancel/end paths re-derive the
        // recordId from the schedule id without needing the DB link.
        val recordId = RecordingScheduleScheduler.recordIdForSchedule(schedule.id)
        try {
            schedules.transitionTo(schedule.id, RecordingScheduleState.FIRING)
        } catch (t: Throwable) {
            Log.e(TAG, "transitionTo(FIRING) failed for ${schedule.id}; aborting fire", t)
            return
        }
        // **MB-208 (2026-04-27).** Wrap the FGS start in runCatching so
        // a thrown `ForegroundServiceStartNotAllowedException` (Fire TV
        // background-restriction edge), `SecurityException` (revoked
        // FGS_DATA_SYNC permission on some OEMs), or any other crash
        // here transitions the schedule to FAILED instead of leaving it
        // in FIRING for the end alarm to optimistically COMPLETE. The
        // exemption window granted by setExactAndAllowWhileIdle is real
        // but not universally honoured.
        //
        // MB-215 — extracted via [tryStartOrFailSchedule] so the
        // failure-path contract is unit-testable without standing up a
        // BroadcastReceiver / Context / FGS infrastructure.
        tryStartOrFailSchedule(
            scheduleId = schedule.id,
            tag = TAG,
            startService = {
                RecordingService.start(
                    context = context,
                    input =
                        RecordInput(
                            recordId = recordId,
                            sourceUrl = schedule.streamUrl,
                            title = schedule.title,
                            format = detectRecordingFormat(schedule.streamUrl),
                            contentId = schedule.contentId,
                        ),
                )
            },
            transitionFailed = { reason ->
                runCatching {
                    schedules.transitionTo(
                        schedule.id,
                        RecordingScheduleState.FAILED,
                        errorReason = reason,
                    )
                }
            },
        )
    }


    /**
     * Resolve the [ContentItem] to hand to [PlaybackController.play] when
     * the receiver needs to switch the player. Prefers the live row in
     * `content` (full metadata for the player UI). Falls back to a
     * minimal synthesized item if the row was deleted between schedule
     * creation and fire — the schema's `ON DELETE SET NULL` on
     * `recording_schedules.content_id` handles the cleanup, but the
     * schedule's denormalized title + stream_url still let us play the
     * stream (recording will succeed, even if the player UI looks bare).
     */
    private fun resolveContentItem(schedule: RecordingScheduleEntry): ContentItem {
        val byId = schedule.contentId?.let { runCatching { content.findById(it) }.getOrNull() }
        if (byId != null) return byId
        return ContentItem(
            id = schedule.contentId ?: "sched_synth_${schedule.id}",
            sourceId = "sched_synth",
            type = ContentType.LIVE,
            title = schedule.title,
            cleanTitle = schedule.title,
            groupName = null,
            streamUrl = schedule.streamUrl,
            logoUrl = null,
            tvgId = null,
            metadataJson = null,
            sortOrder = 0,
            createdAt = 0L,
        )
    }

    /**
     * Mirror of `detectRecordingFormat` from `PlayerOptionsSheet.kt`.
     * Kept inline because a one-line URL heuristic doesn't justify a
     * shared utility (and the UI panel's copy is the canonical home for
     * the format-detection comment).
     */
    private fun detectRecordingFormat(streamUrl: String): RecordingFormat {
        val withoutQuery = streamUrl.substringBefore('?').substringBefore('#')
        return if (withoutQuery.endsWith(".m3u8", ignoreCase = true)) {
            RecordingFormat.HLS
        } else {
            RecordingFormat.MPEG_TS
        }
    }

    companion object {
        private const val TAG = "YancoSchedReceiver"
    }
}
