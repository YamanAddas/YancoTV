package com.yancotv.android.recording.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.R
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.recording.RecordingService
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.recording.RecordInput
import com.yancotv.shared.recording.RecordingFormat
import com.yancotv.shared.recording.RecordingScheduleEntry
import com.yancotv.shared.recording.RecordingScheduleRepository
import com.yancotv.shared.recording.RecordingScheduleState
import com.yancotv.shared.recording.RecordingStatus
import com.yancotv.shared.recording.RecordingsRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 *   activeCount  = recordings.getByStatus(RECORDING).size, or NULL if that read failed
 *
 *   → [preFireRoute] (pure, table-tested in PreFireRouteTest)
 *
 *   TEE_SAME_CHANNEL        : RecordingService.handleStart's URL match auto-routes to live-tee.
 *   MISSED_STATE_UNREADABLE : MB-337 — activeCount was null. Fail CLOSED + notify.
 *   MISSED_CONCURRENT       : 1-stream cap already spent. MISSED + notify.
 *   HEADLESS_FRESH_GET      : MB-209 — nothing playing; let the service open its own connection.
 *   SWITCH_THEN_TEE         : switch the player, then start the service.
 * ```
 *
 * **MB-337 — the guard fails CLOSED, and that is a deliberate trade.** The
 * active-recording read used to be `.getOrDefault(emptyList())`, so a DB read
 * failure was indistinguishable from an idle recorder and the receiver went down
 * SWITCH_THEN_TEE: player yanked off the channel being recorded, a second
 * connection opened against a 1-stream provider, corrupt file plus a rejected
 * connection, silently. `RecordingService` never re-checks the cap, so this read
 * is the only concurrency guard in the system.
 *
 * The cost of failing closed is real and was accepted by the user (2026-07-31): a
 * transient DB error now SKIPS a recording. That is only acceptable because the
 * skip is announced — see [markMissedAndNotify]. Both MISSED paths notify; the
 * concurrent one used to be silent too.
 *
 * **End path (`ACTION_END`):** stops the linked recording and transitions
 * the schedule to COMPLETED. The recording row's terminal status is the
 * source of truth for whether bytes actually landed; the schedule's
 * state is informational ("the user's intent was honored").
 *
 * **Threading (MB-254, corrected 2026-07-28).** `onReceive` runs on the main
 * thread with a ~10-second budget, so the fire path is dispatched through
 * `goAsync()` onto `Dispatchers.IO` instead of running there directly. The
 * previous note here claimed "SQLDelight reads / writes are fast — well within
 * budget"; that was true of the reads and false of the writes. See the comment
 * in [onReceive] for why (WAL serialises writers, and the EPG import holds one
 * transaction across a whole XMLTV feed).
 *
 * Two ordering guarantees survive that move and must keep surviving:
 *   - `PlaybackController` is main-thread-only, so its reads and `play()` hop
 *     back via `withContext(Dispatchers.Main)`.
 *   - MB-208 requires the player switch to have been *applied* before the FGS
 *     intent is dispatched. `withContext` suspends until it has, so the
 *     switch-then-tee path still can't route to fresh-GET by accident.
 *
 * `transitionTo(FIRING)` deliberately stays BEFORE the service start: it is the
 * concurrency guard, because `transitionTo` rejects illegal transitions, so a
 * schedule the user cancelled a moment ago fails it and the recording correctly
 * never starts (MB-214). Do not reorder these for robustness — starting first
 * would record over a cancellation.
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
internal fun tryStartOrFailSchedule(scheduleId: String, tag: String, startService: () -> Unit, transitionFailed: (reason: String) -> Unit) {
    runCatching { startService() }
        .onFailure { t ->
            Log.e(tag, "RecordingService.start failed for $scheduleId", t)
            transitionFailed("service_start_failed: ${t.message ?: t::class.simpleName}")
        }
}

@androidx.annotation.OptIn(UnstableApi::class)
class RecordingScheduleReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val schedules: RecordingScheduleRepository by inject()
    private val recordings: RecordingsRepository by inject()
    private val content: ContentRepository by inject()
    private val playbackController: PlaybackController by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val scheduleId =
            intent.getStringExtra(RecordingScheduleAlarmManager.EXTRA_SCHEDULE_ID) ?: run {
                Log.w(TAG, "received ${intent.action} with no schedule_id — ignoring")
                return
            }
        val action = intent.action
        if (action != RecordingScheduleAlarmManager.ACTION_PRE_FIRE &&
            action != RecordingScheduleAlarmManager.ACTION_END
        ) {
            Log.w(TAG, "unexpected action $action")
            return
        }

        // **MB-254 (2026-07-28).** This used to run the whole fire path
        // synchronously on the main thread, on the reasoning that "SQLDelight
        // reads / writes are fast — well within budget". The reads are: the DB
        // runs in WAL mode (`enableWriteAheadLogging` in DatabaseFactory), so a
        // reader never waits on a writer. The WRITES are not, because WAL still
        // serialises writers — and `BulkEpgWriter.Session` holds ONE
        // `BEGIN IMMEDIATE` transaction across an entire streaming XMLTV import
        // to keep the replace atomic. That can be open for minutes on a large
        // feed, and EpgSyncWorker runs it every 6 hours.
        //
        // So a recording whose pre-fire alarm landed during an EPG refresh hit
        // `transitionTo(FIRING)` — the FIRST thing `startRecording` does — and
        // blocked the main thread on the SQLite write lock until the import
        // committed. Past the ~10 s broadcast budget that is an ANR, and the
        // recording never starts at all. Exactly the "I scheduled it and got
        // nothing" shape.
        //
        // goAsync() moves the work off the main thread while keeping the
        // broadcast alive, so a congested write makes the recording start LATE
        // (absorbed by the pre-padding) instead of not at all, and the UI stays
        // responsive so the import can actually finish.
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                when (action) {
                    RecordingScheduleAlarmManager.ACTION_PRE_FIRE -> handlePreFire(context, scheduleId)
                    else -> handleEnd(context, scheduleId)
                }
            } catch (t: Throwable) {
                // Never let a throw strand the broadcast: without finish() the
                // process is held until the system force-times-out the receiver.
                Log.e(TAG, "$action for $scheduleId failed", t)
            } finally {
                runCatching { pending.finish() }
                scope.cancel()
            }
        }
    }

    private suspend fun handlePreFire(context: Context, scheduleId: String) {
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

        // MB-254 — we're on IO now, and PlaybackController is main-thread-only
        // (packages/android/CLAUDE.md hard rule #2), so hop for the read.
        val currentUrl = withContext(Dispatchers.Main) { playbackController.currentItem.value?.streamUrl }
        val sameChannel = currentUrl != null && currentUrl == schedule.streamUrl
        // MB-337 — null means UNREADABLE, and that is the whole point. This used
        // to be `.getOrDefault(emptyList())`, which made a failed read
        // indistinguishable from an idle recorder and sent the receiver down the
        // switch-then-tee path: player yanked off the channel being recorded, a
        // second connection opened against a 1-stream provider, corrupt file and
        // a rejected connection, silently. RecordingService never re-checks the
        // cap, so this read is the only guard there is.
        val activeRecordings =
            runCatching { recordings.getByStatus(RecordingStatus.RECORDING) }
                .onFailure { Log.e(TAG, "fire[$scheduleId] active-recording read FAILED", it) }
                .getOrNull()

        when (preFireRoute(sameChannel, activeRecordings?.size, currentUrl != null)) {
            PreFireRoute.TEE_SAME_CHANNEL -> {
                // Player is on the scheduled channel. Tee — no second connection,
                // no player switch, no user interruption. RecordingService.handleStart's
                // URL-match check auto-routes to handleStartLiveTee.
                Log.i(TAG, "fire[$scheduleId] path=tee_same_channel")
                startRecording(context, schedule)
            }

            PreFireRoute.MISSED_STATE_UNREADABLE -> {
                // MB-337 — fail CLOSED, by explicit user decision (2026-07-31).
                // We cannot tell whether a recording is in flight, so we refuse
                // to open a connection that might be the second one. The trade
                // is deliberate and was chosen with the cost understood: a
                // transient DB error now costs a SKIPPED recording rather than
                // risking a corrupted one plus a provider rejection.
                //
                // The notification is not optional garnish — it is the half that
                // makes the trade acceptable. A skipped recording the user is
                // told about beats a corrupt one they discover hours later.
                Log.w(TAG, "fire[$scheduleId] path=missed reason=state_unreadable (failing closed)")
                markMissedAndNotify(
                    context = context,
                    schedule = schedule,
                    reason = RecordingScheduleRepository.REASON_RECORDING_STATE_UNREADABLE,
                )
            }

            PreFireRoute.MISSED_CONCURRENT -> {
                // 1-stream IPTV cap exhausted: another channel's recording is
                // already in flight. Don't try to add a second connection — would
                // either fail or kick the existing one. Mark MISSED so the
                // schedule history reflects what happened.
                Log.w(
                    TAG,
                    "fire[$scheduleId] path=missed reason=concurrent " +
                        "(${activeRecordings?.size} active recordings)",
                )
                // MB-337 — this path used to mark MISSED silently. It is the same
                // class of harm as the unreadable case above (a recording the
                // user expected did not happen), so it gets the same
                // notification. Previously the user found out by opening the
                // Recordings tab, typically hours later.
                markMissedAndNotify(
                    context = context,
                    schedule = schedule,
                    reason = RecordingScheduleRepository.REASON_CONCURRENT_RECORDING_ACTIVE,
                )
            }

            PreFireRoute.HEADLESS_FRESH_GET -> {
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

            PreFireRoute.SWITCH_THEN_TEE -> {
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
                // MB-254 — `withContext` suspends until the switch has been
                // applied on the main thread, so the MB-208 ordering guarantee
                // (currentItem is the scheduled URL before the FGS intent is
                // delivered) survives moving this path off the main thread.
                withContext(Dispatchers.Main) {
                    runCatching { playbackController.play(listOf(contentItem), 0) }
                        .onFailure { Log.w(TAG, "playbackController.play failed for $scheduleId", it) }
                }
                startRecording(context, schedule)
            }
        }
    }

    private fun handleEnd(context: Context, scheduleId: String) {
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
        // [startRecording]). There is nothing stored to read: MB-211 removed
        // the `recording_id` column in schema v16, because the value was
        // always NULL and its FK was a live foot-gun — see the FK-timing
        // comment in [startRecording]. Derivation is the only source.
        val recordId = RecordingScheduleScheduler.recordIdForSchedule(scheduleId)
        Log.i(TAG, "end[$scheduleId] stopping recording $recordId")
        // Always send the stop intent — even on the row-missing path —
        // so any in-flight pre-markStarted launch (e.g. resolveOutputOrFail
        // still running) gets cancelled cleanly via cancelAndJoin.
        RecordingService.stop(context, recordId)

        // **MB-212 (2026-04-28).** Schedule transition for the
        // recording-exists path now happens INSIDE
        // `RecordingService.handleStop` — after `cancelAndJoin` +
        // `output.close()` + `output.size()` have landed the actual
        // disk byte count. That's the only place with a non-stale read,
        // so the schedule's terminal state agrees with the recording
        // row's. See `RecordingScheduleRepository.scheduleOutcomeFromBytes`
        // for the pinned decision table.
        //
        // Pre-MB-212 we read `recordings.fileSizeBytes` here and made
        // the call ourselves — but `RecordingService.stop` is an Intent
        // dispatch and the actual flush completes asynchronously, so
        // in a sub-100ms window the read returned 0 bytes while the
        // recording was about to mark COMPLETED. Schedule locked FAILED;
        // recording landed COMPLETED. The two histories disagreed.
        //
        // The receiver still handles ONE case: row missing entirely.
        // That happens when the service was never started, or crashed
        // before `markStarted` (FGS-from-background restriction,
        // process death mid-resolveOutputOrFail). The service's
        // `handleStop` would also try to transition the schedule in
        // that case, but if the row never existed it'll be marked
        // failed by the row-missing path here AND swallowed at the
        // service side via runCatching — whichever runs first wins.
        val row = runCatching { recordings.getById(recordId) }.getOrNull()
        if (row == null) {
            Log.i(TAG, "end[$scheduleId] row=null -> FAILED(recording_never_started)")
            runCatching {
                schedules.transitionTo(
                    scheduleId,
                    RecordingScheduleState.FAILED,
                    errorReason = RecordingScheduleRepository.REASON_RECORDING_NEVER_STARTED,
                )
            }.onFailure { Log.w(TAG, "schedule[$scheduleId] row-missing transition failed", it) }
        } else {
            Log.i(
                TAG,
                "end[$scheduleId] row=${row.id} status=${row.status} — service.handleStop will transition schedule",
            )
        }
    }

    private fun startRecording(context: Context, schedule: RecordingScheduleEntry) {
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
                // Audit catch — pre-fix, a failed scheduled recording
                // wrote the DB row and the user discovered it hours
                // later when they opened the Recordings tab — way past
                // the point of doing anything about it. Post a
                // low-importance persistent notification so a failed
                // 8pm recording surfaces at 8pm, not 11pm.
                postScheduleFailedNotification(
                    context = context,
                    scheduleId = schedule.id,
                    title = schedule.title,
                    reason = reason,
                )
            },
        )
    }

    /**
     * MB-337 — mark a schedule MISSED and tell the user why.
     *
     * Both callers are cases where a recording the user asked for did not
     * happen and nothing was wrong with the schedule itself, so silence is the
     * worst possible outcome: the previous behaviour wrote the DB row and let
     * them discover it whenever they next opened the Recordings tab.
     *
     * The transition is wrapped because a failing transition must not prevent
     * the notification — if the DB is the thing that is broken (which is
     * precisely the [RecordingScheduleRepository.REASON_RECORDING_STATE_UNREADABLE]
     * case) then the notification is the ONLY channel left to reach the user.
     */
    private fun markMissedAndNotify(context: Context, schedule: RecordingScheduleEntry, reason: String) {
        runCatching {
            schedules.transitionTo(
                schedule.id,
                RecordingScheduleState.MISSED,
                errorReason = reason,
            )
        }.onFailure { Log.e(TAG, "markMissed[${schedule.id}] transition failed", it) }
        postScheduleFailedNotification(
            context = context,
            scheduleId = schedule.id,
            title = schedule.title,
            reason = reason,
        )
    }

    /**
     * Audit catch — post a low-importance, dismissible notification when
     * a scheduled recording fails to start. Re-uses the existing
     * RecordingService.CHANNEL_ID ("yanco_recordings") so the channel
     * is already registered + visible in the user's per-app notification
     * settings. Notification ID is the schedule id's hashCode + 1 so
     * two simultaneous failures don't overwrite each other AND a retry
     * of the same schedule re-uses the slot rather than spawning a
     * second notification.
     */
    private fun postScheduleFailedNotification(context: Context, scheduleId: String, title: String, reason: String) {
        runCatching {
            val humanReason = when {
                reason.startsWith("service_start_failed") ->
                    context.getString(R.string.rsf_reason_service_start)
                reason.contains(RecordingScheduleRepository.REASON_CONCURRENT_RECORDING_ACTIVE) ->
                    context.getString(R.string.rsf_reason_concurrent)
                // MB-337 — deliberately its own message, not folded into the
                // concurrent one. "We could not check" and "another recording
                // was running" call for different user actions.
                reason.contains(RecordingScheduleRepository.REASON_RECORDING_STATE_UNREADABLE) ->
                    context.getString(R.string.rsf_reason_state_unreadable)
                reason.contains(RecordingScheduleRepository.REASON_RECORDING_NEVER_STARTED) ->
                    context.getString(R.string.rsf_reason_never_started)
                // Fallback: raw provider/system text. Untranslatable by
                // nature — see SyncDetail.Failure (MK.31.18) for the same
                // reasoning. Capped so a stack-trace-ish reason cannot
                // blow out the notification.
                else -> reason.take(120)
            }
            val nm =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            // Channel is registered by RecordingService.ensureNotificationChannel;
            // call ensure here too so a notification fired without the
            // service having been started this session still has its
            // channel in place. Cheap — channel lookups are idempotent.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val existing = nm.getNotificationChannel(FAILED_CHANNEL_ID)
                if (existing == null) {
                    nm.createNotificationChannel(
                        android.app.NotificationChannel(
                            FAILED_CHANNEL_ID,
                            context.getString(R.string.rsf_channel_name),
                            android.app.NotificationManager.IMPORTANCE_LOW,
                        ).apply {
                            description = context.getString(R.string.rsf_channel_description)
                        },
                    )
                }
            }
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            val pi = launchIntent?.let {
                android.app.PendingIntent.getActivity(
                    context,
                    scheduleId.hashCode(),
                    it,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            val notification = androidx.core.app.NotificationCompat.Builder(context, FAILED_CHANNEL_ID)
                // MK.29.5 — badge-only mark. A notification small icon is
                // rendered from its ALPHA channel alone, tinted flat by the
                // system and drawn in a square status-bar slot: the 16:9
                // badge+wordmark lockup became an unreadable white smear.
                // The mark's alpha is a single solid hex, which tints to a
                // recognisable silhouette.
                .setSmallIcon(com.yancotv.android.R.drawable.ic_logo_mark)
                .setContentTitle(context.getString(R.string.rsf_title))
                .setContentText(context.getString(R.string.rsf_body, title, humanReason))
                .setStyle(
                    androidx.core.app.NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.rsf_body, title, humanReason)),
                )
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .apply { if (pi != null) setContentIntent(pi) }
                .build()
            // Notification ID space: 9001 is the active-recordings FGS slot
            // (RecordingService). Use 10000 + abs(hash) to avoid collision.
            val nid = 10_000 + (kotlin.math.abs(scheduleId.hashCode()) % 10_000)
            nm.notify(nid, notification)
        }.onFailure { t ->
            Log.w(TAG, "postScheduleFailedNotification failed for $scheduleId", t)
        }
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
        // MB-381 — force the switched channel's URL to the one we're recording.
        // The content row's own stream_url can differ from schedule.streamUrl
        // when they were resolved via a shared tvg_id (e.g. content_id=beIN SD
        // but stream_url=beIN HD). SWITCH_THEN_TEE relies on the player landing
        // on schedule.streamUrl so handleStart's URL match routes to live-tee;
        // switching to the content row's URL instead misses and opens a second
        // HTTP connection (fatal on a 1-stream provider). Keep the row's
        // metadata (title/logo) but override the URL. Defensive: also repairs
        // schedules already stored with the divergence before the creation fix.
        if (byId != null) return byId.copy(streamUrl = schedule.streamUrl)
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
        private const val FAILED_CHANNEL_ID = "yanco_recordings_failures"
    }
}
