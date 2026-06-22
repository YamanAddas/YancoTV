package com.yancotv.android.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.MainActivity
import com.yancotv.android.R
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.recording.schedule.RecordingScheduleScheduler
import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.redactErrorMessage
import com.yancotv.shared.recording.HlsRecorder
import com.yancotv.shared.recording.MpegTsRecorder
import com.yancotv.shared.recording.RecordInput
import com.yancotv.shared.recording.RecordResult
import com.yancotv.shared.recording.RecorderClock
import com.yancotv.shared.recording.RecordingFormat
import com.yancotv.shared.recording.RecordingScheduleRepository
import com.yancotv.shared.recording.RecordingsRepository
import com.yancotv.shared.recording.scheduleOutcomeFromBytes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.android.ext.android.inject

/**
 * Foreground service that owns every in-flight recording.
 *
 * **Why one service for many recordings** (rather than one service
 * per recording): the design spec §1 covers it — single foreground
 * notification updates as recordings start/stop; dispatching N
 * services would also hit Android's foreground-service-start cap
 * faster.
 *
 * **Concurrency**: each recording runs as a coroutine on
 * [Dispatchers.IO] inside [serviceScope]. Cancellation = `Job.cancel()`.
 * The service stops itself once the last recording completes — see
 * [maybeStop].
 *
 * **Lifecycle**: started via [start]; stopped via [stop] (single
 * recording) or [stopAll]. The service auto-stops itself when
 * [activeJobs] is empty after a completion. Process death mid-
 * recording leaves rows in `RECORDING` status; [RecordingsRepository.sweepOrphans]
 * handles them on next launch (called from `YancoApp.onCreate` —
 * not this class).
 */
@UnstableApi
class RecordingService : Service() {
    private val recordings: RecordingsRepository by inject()

    // MB-212 — schedule repo lookup happens in handleStop so the schedule
    // transitions to its terminal state from the same coroutine that
    // finalises the recording row's bytes. Avoids the receiver-side
    // race where the schedule could lock as FAILED ahead of the row's
    // markCompleted call.
    private val schedules: RecordingScheduleRepository by inject()
    private val http: HttpClient by inject()
    private val prefs: com.yancotv.android.prefs.AppPreferences by inject()
    private val logger: com.yancotv.shared.logger.Logger by inject()

    // MK.14.8 — PlaybackController and RecordingDataSink expose Media3
    // types (DataSink, ExoPlayer config), so this service is annotated
    // @UnstableApi at the class level. Callers (UI / WorkManager) reach
    // it through the static start / stop / stopAll companion functions
    // which are themselves opt-in-tagged.
    private val controller: PlaybackController by inject()
    private val recordingSink: RecordingDataSink by inject()

    private val storageResolver by lazy {
        RecordingStorageResolver(applicationContext, prefs)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeOutputs = ConcurrentHashMap<String, RecordingOutput>()
    private val notificationLock = Mutex()

    /**
     * **MB-209 hardening (2026-04-27).** High-perf Wi-Fi lock held while
     * any recording is in flight. Fire TV usually keeps Wi-Fi up during
     * standby (Alexa wake-word) so the FGS's implicit CPU wake lock is
     * sufficient on AFTDCT31, but generic Android TV boxes / mobile
     * devices in doze can power-save Wi-Fi to the point that the OkHttp
     * read stalls mid-recording. The lock is acquired the first time
     * `activeJobs` becomes non-empty and released when it drains,
     * matching the recording lifetime exactly.
     *
     * `WIFI_MODE_FULL_HIGH_PERF` requests no power saving on the radio
     * — appropriate for sustained-throughput streaming. Reference-counted
     * so concurrent recordings (catch-up + scheduled, theoretically)
     * don't double-acquire / drop early.
     */
    private val wifiLock: android.net.wifi.WifiManager.WifiLock by lazy {
        @Suppress("DEPRECATION")
        (getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
            .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "yanco:recording")
            .also { it.setReferenceCounted(false) }
    }

    /**
     * **MB-210 (2026-04-27).** Partial CPU wake lock held for the
     * lifetime of the recording set. The FGS's implicit wake lock isn't
     * enough on Fire TV — Amazon's ActivityManager enforces an "app
     * idle" foreground-service stop after ~60 s when the app process
     * isn't user-active (logcat: "Stopping service due to app idle").
     * The explicit PARTIAL_WAKE_LOCK is the canonical "do not idle"
     * signal Android and Amazon's fork honour for long-running
     * background work. Reference-counted disabled — single acquire on
     * first start, single release in maybeStop / onDestroy.
     */
    private val cpuWakeLock: android.os.PowerManager.WakeLock by lazy {
        (getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "yanco:recording")
            .also { it.setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val input = intent.toRecordInput() ?: run {
                    Log.w(TAG, "ACTION_START with no/invalid input — ignoring")
                    maybeStop()
                    return START_NOT_STICKY
                }
                handleStart(input)
            }
            ACTION_STOP -> {
                val id = intent.getStringExtra(EXTRA_RECORD_ID)
                val userInitiated = intent.getBooleanExtra(EXTRA_USER_INITIATED, false)
                if (id != null) handleStop(id, userInitiated = userInitiated)
            }
            ACTION_STOP_ALL -> handleStopAll()
            else -> Log.w(TAG, "RecordingService received unknown action ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel everything still running; the recorders will flush and
        // mark themselves Failed via their finally blocks. Synchronous
        // join isn't possible from onDestroy (no coroutine context),
        // so we accept that ongoing rows may need a sweepOrphans pass
        // on next launch.
        serviceScope.cancel()
        activeJobs.clear()
        activeOutputs.clear()
        // MB-209 / MB-210 hardening — final-line releases. If maybeStop()
        // didn't run (process killed, abrupt destroy), Android's
        // lock-leak detection logs a warning at process exit but the
        // locks are GCd either way. Releasing here keeps the system
        // cleaner.
        runCatching { if (wifiLock.isHeld) wifiLock.release() }
            .onFailure { Log.w(TAG, "wifiLock.release in onDestroy failed", it) }
        runCatching { if (cpuWakeLock.isHeld) cpuWakeLock.release() }
            .onFailure { Log.w(TAG, "cpuWakeLock.release in onDestroy failed", it) }
    }

    // ── Action handlers ───────────────────────────────────────────

    private fun handleStart(input: RecordInput) {
        if (activeJobs.containsKey(input.recordId)) {
            Log.w(TAG, "duplicate start for ${input.recordId} — ignoring")
            return
        }
        // **MK.14.8 routing decision.** If the user is recording the channel
        // they're currently watching, the player already has an HTTP GET
        // open to that URL — opening a second one fails on 1-stream IPTV
        // accounts (the original Stage 3.1 architecture). Tee the bytes
        // ExoPlayer is already pulling instead via [RecordingDataSink].
        // For any other URL (catch-up, scheduled recording on a different
        // channel) the legacy fresh-GET path runs — `MpegTsRecorder` /
        // `HlsRecorder` open their own HTTP request. Those URLs aren't
        // currently being played, so there's no second-connection conflict.
        val playingUrl = controller.currentItem.value?.streamUrl
        // MB-213 — routing decision lives in [RecordingRouting] so a
        // JVM unit test can pin the table without standing up the
        // service. Same string-equality semantics; behaviour unchanged.
        val useLiveTee = RecordingRouting.decide(playingUrl, input.sourceUrl) == RecordingPath.LiveTee
        Log.i(TAG, "start[${input.recordId}] format=${input.format} path=${if (useLiveTee) "live-tee" else "fresh-get"}")
        // Become foreground immediately. Android requires startForeground
        // within 5s of startForegroundService; doing it before the
        // recording coroutine even starts is the safest pattern.
        startForegroundIfNeeded()
        // MB-209 hardening — pin Wi-Fi at full power while any
        // recording is in flight. Non-refcounted: idempotent acquire
        // here, single release in maybeStop() once activeJobs drains.
        runCatching { if (!wifiLock.isHeld) wifiLock.acquire() }
            .onFailure { Log.w(TAG, "wifiLock.acquire failed", it) }
        // MB-210 hardening — explicit PARTIAL_WAKE_LOCK so Fire TV's
        // ActivityManager doesn't reap the FGS after ~60 s of app
        // idle. Released alongside the Wi-Fi lock in maybeStop.
        runCatching { if (!cpuWakeLock.isHeld) cpuWakeLock.acquire() }
            .onFailure { Log.w(TAG, "cpuWakeLock.acquire failed", it) }

        if (useLiveTee) {
            handleStartLiveTee(input)
        } else {
            handleStartFreshGet(input)
        }
        // Update the notification's "N in progress" body.
        refreshNotification()
    }

    /**
     * **MK.14.8 live-tee path.** The user is recording the channel they're
     * currently watching. ExoPlayer's data-source chain already routes
     * HTTP traffic through [RecordingDataSink]; we just need to:
     *
     *   1. Allocate the output file.
     *   2. Insert the RECORDING row.
     *   3. Call `recordingSink.begin(stream)` to start capturing bytes.
     *   4. Park a job in `activeJobs` so [handleStop]'s lookup still works.
     *
     * The job suspends via [awaitCancellation] until [handleStop]'s
     * `cancelAndJoin` triggers it; the `finally` then calls
     * `recordingSink.end()` to flush + close the stream. After that,
     * `output.size()` reflects the final byte count on disk.
     *
     * No grace delay — there's no second HTTP GET to race with.
     */
    private fun handleStartLiveTee(input: RecordInput) {
        val job =
            serviceScope.launch(Dispatchers.IO) {
                // [resolveOutputOrFail] handles the full markStarted /
                // markFailed contract internally — by the time it returns
                // a non-null output, a RECORDING row exists in the DB. On
                // null, a FAILED row was already inserted so the user
                // sees the failure in the Recordings list.
                val output = resolveOutputOrFail(input) ?: return@launch
                activeOutputs[input.recordId] = output

                // Open the output stream and arm the tee. The sink owns
                // the stream from this point — it's closed by `end()` in
                // the finally below.
                val stream = output.openOutputStream()
                recordingSink.begin(stream)
                try {
                    // Park until handleStop cancels us. Bytes flow through
                    // the tee in parallel on ExoPlayer's load thread.
                    awaitCancellation()
                } finally {
                    // Symmetric end — flushes the stream so file.length()
                    // reflects every captured byte. Idempotent: a noop if
                    // the sink was already ended elsewhere.
                    val captured = recordingSink.end()
                    Log.i(TAG, "tee[${input.recordId}] ended after $captured bytes (in-process counter)")
                }
            }
        activeJobs[input.recordId] = job
    }

    /**
     * **Fresh-GET path** for catch-up / scheduled recordings — and the
     * pre-MK.14.8 default. Opens its own HTTP request via
     * `MpegTsRecorder` / `HlsRecorder`. The recorded URL is NOT the
     * currently-playing URL, so there's no concurrent-connection conflict.
     */
    private fun handleStartFreshGet(input: RecordInput) {
        val job =
            serviceScope.launch(Dispatchers.IO) {
                // See [resolveOutputOrFail] — markStarted / markFailed is
                // handled internally so a RECORDING row is guaranteed by
                // the time we get a non-null output (and a FAILED row is
                // visible in the Recordings list when alloc throws).
                val output = resolveOutputOrFail(input) ?: return@launch
                activeOutputs[input.recordId] = output

                val result =
                    output.openSink().use { boundSink ->
                        when (input.format) {
                            RecordingFormat.HLS ->
                                HlsRecorder(http, RealClock, logger = logger).record(input, boundSink)
                            RecordingFormat.MPEG_TS ->
                                MpegTsRecorder(http, RealClock, logger = logger).record(input, boundSink)
                        }
                    }
                onRecorderResult(input.recordId, result)
            }
        activeJobs[input.recordId] = job
    }

    /**
     * **MB-204 (audit follow-up).** Resolve a destination AND make the
     * recording row visible in the Recordings list — both for success
     * and failure. Shared by [handleStartLiveTee] and [handleStartFreshGet].
     *
     * Contract:
     *   - Returns a non-null [RecordingOutput] iff a `RECORDING`-state
     *     row has been inserted into the recordings table with the
     *     resolved file_path. Callers can rely on the row existing for
     *     all subsequent transitions ([RecordingsRepository.markCompleted]
     *     / [RecordingsRepository.markFailed]).
     *   - Returns `null` when storage allocation throws after exhausting
     *     the resolver's app-private fallback chain. In that case a
     *     `FAILED`-state row HAS been inserted (with empty file_path
     *     and `reason = file_allocation_failed: …`) so the user sees
     *     the failure in the Recordings list.
     *   - Re-throws [kotlinx.coroutines.CancellationException] so
     *     structured concurrency works — e.g. the user stops the
     *     recording before allocation completes.
     *
     * Pre-MB-204 the row was inserted by the callers AFTER allocation;
     * if allocation failed, no row was created and `markFailed` would
     * itself throw because the row didn't exist. The failure was
     * swallowed by `runCatching` and the user saw nothing — Recordings
     * list empty AND `activeJobs` empty AND the player options sheet's
     * Record tab still showing "Record" instead of "Stop recording".
     * Real-world incident: 2026-04-26 Public mode + missing
     * WRITE_EXTERNAL_STORAGE on Fire TV API 28.
     */
    private suspend fun resolveOutputOrFail(input: RecordInput): RecordingOutput? {
        val output =
            try {
                storageResolver.resolve(
                    recordId = input.recordId,
                    title = input.title,
                    format = input.format,
                    onPermissionLost = {
                        Log.w(TAG, "SAF tree URI permission lost — clearing pref")
                        runCatching { prefs.setRecordingFolderUri(null) }
                    },
                )
            } catch (c: kotlinx.coroutines.CancellationException) {
                // User stopped before resolve completed — propagate so
                // the launch ends in Cancelled state. No row was inserted
                // yet (we haven't reached markStarted below), so there's
                // nothing to transition.
                throw c
            } catch (t: Throwable) {
                // Resolver exhausted its fallback chain. Insert a row
                // and immediately transition to FAILED so the user sees
                // the failure surfaced in the Recordings list.
                Log.e(TAG, "failed to allocate output for ${input.recordId}", t)
                runCatching {
                    recordings.markStarted(
                        id = input.recordId,
                        contentId = input.contentId,
                        title = input.title,
                        streamUrl = input.sourceUrl,
                        filePath = "",
                        format = input.format,
                    )
                    recordings.markFailed(
                        id = input.recordId,
                        reason = "file_allocation_failed: ${redactErrorMessage(t)}",
                        bytesWritten = 0L,
                    )
                }.onFailure { Log.w(TAG, "markFailed bookkeeping failed for ${input.recordId}", it) }
                maybeStop()
                return null
            }

        // Allocation succeeded — insert the row in RECORDING state.
        // Every exit path below this is the caller's responsibility to
        // transition (handleStop -> markCompleted/markFailed via
        // file-size check; onRecorderResult for the fresh-GET path).
        try {
            recordings.markStarted(
                id = input.recordId,
                contentId = input.contentId,
                title = input.title,
                streamUrl = input.sourceUrl,
                filePath = output.storagePath,
                format = input.format,
            )
        } catch (t: Throwable) {
            // markStarted itself threw — almost certainly a duplicate
            // recordId (developer error) or a DB-level corruption. Bail
            // gracefully: the output isn't useful without a row to
            // transition. Best-effort delete the partially-allocated
            // file so we don't leak.
            Log.e(TAG, "markStarted failed for ${input.recordId}", t)
            runCatching { output.delete() }
            maybeStop()
            return null
        }
        return output
    }

    private fun handleStop(recordId: String, userInitiated: Boolean = false) {
        val job = activeJobs[recordId] ?: return
        val output = activeOutputs[recordId]
        // Pull bookkeeping off the maps up front so a duplicate ACTION_STOP
        // arriving while we're awaiting the flush doesn't double-cancel.
        activeJobs.remove(recordId)
        activeOutputs.remove(recordId)

        // Both paths flush their stream from a `finally` in the launched
        // coroutine — fresh-GET via `output.openSink().use {}`, live-tee
        // via `recordingSink.end()` in the awaitCancellation block. The
        // flush is asynchronous from `job.cancel()`, so we cancelAndJoin
        // before reading `output.size()` to avoid racing a stale byte count.
        serviceScope.launch(Dispatchers.IO) {
            runCatching { job.cancelAndJoin() }
            // MK.14.X audit revision + MB-218 (AutoCloseable) — flip
            // MediaStore IS_PENDING=0 (or whatever the backend's finalize
            // step is) AFTER cancelAndJoin returns so the file is fully
            // flushed when the system marks it visible. No-op for File /
            // SAF backends. close() is the AutoCloseable surface.
            runCatching { output?.close() }
            val bytes = output?.size() ?: 0L
            runCatching {
                val startedAt = recordings.getById(recordId)?.startedAt
                val secs =
                    startedAt?.let { (System.currentTimeMillis() - it) / 1000L }
                        ?.coerceAtLeast(0L) ?: 0L
                when {
                    userInitiated -> {
                        // Explicit user Cancel (UI button or
                        // scheduler.cancel). Mark CANCELLED regardless
                        // of bytes — a deliberate stop must not flip to
                        // FAILED just because the recorder hadn't
                        // received any bytes yet, or to COMPLETED if
                        // the user wanted to discard the partial.
                        // Real bytes-on-disk are still written to the
                        // row's file_size_bytes so the UI can show
                        // "Cancelled · X MB" accurately and orphan-
                        // file cleanup has a non-zero count to work
                        // with.
                        Log.i(TAG, "stop[$recordId] cancelled by user — $bytes bytes (${secs}s)")
                        recordings.markCancelled(id = recordId, bytesWritten = bytes)
                    }
                    bytes <= 0L -> {
                        // End-alarm / natural-finish path with no
                        // bytes — the server never started serving the
                        // request body. Mark FAILED so the row reads
                        // "Failed · no_response_from_server" instead
                        // of "Saved 0 KB" (which would invite the
                        // user to tap Play and hit the 3003
                        // unrecognized-input error).
                        Log.i(TAG, "stop[$recordId] failed — no bytes from server")
                        recordings.markFailed(
                            id = recordId,
                            reason = "no_response_from_server",
                            bytesWritten = 0L,
                        )
                    }
                    else -> {
                        Log.i(TAG, "stop[$recordId] saved $bytes bytes (${secs}s)")
                        recordings.markCompleted(
                            id = recordId,
                            bytesWritten = bytes,
                            durationSeconds = secs,
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "stop[$recordId] transition failed", it) }

            // MB-212 — schedule transition lives HERE (post-flush) so
            // the schedule's terminal state agrees with the recording
            // row's bytes count. Pre-fix the receiver made this call
            // BEFORE the async flush completed; in a sub-100ms window
            // the schedule could lock FAILED while the row eventually
            // transitioned to COMPLETED.
            //
            // scheduleId is derived from the recordId via the
            // deterministic prefix (`sched-rec-<id>`) so we don't have
            // to plumb it through RecordInput / Intent extras / the
            // activeJobs map. Manual record-now recordings have no
            // prefix → null → we skip the schedule call entirely.
            //
            // Race-tolerance: if the schedule is already terminal
            // (user cancelled, or receiver-fallback transitioned a
            // row-missing case to FAILED earlier in this handleEnd
            // call), `transitionTo` throws IllegalArgumentException
            // and runCatching swallows. MB-214 pins this exact
            // behaviour at the repo layer.
            RecordingScheduleScheduler.scheduleIdFromRecordId(recordId)?.let { schedId ->
                if (userInitiated) {
                    // Schedule was pre-claimed CANCELLED by
                    // RecordingScheduleScheduler.cancel() before this
                    // ACTION_STOP was dispatched. scheduleOutcomeFromBytes
                    // would compute FAILED/COMPLETED here, but transitionTo
                    // on a CANCELLED (terminal) row would be rejected by
                    // the repo guard and runCatching would swallow it.
                    // Skip the call entirely — keeps the debug log clean.
                    Log.d(TAG, "schedule[$schedId] terminal transition skipped (user-cancelled)")
                } else {
                    val outcome = scheduleOutcomeFromBytes(bytes)
                    runCatching {
                        schedules.transitionTo(schedId, outcome.state, errorReason = outcome.reason)
                    }.onFailure {
                        Log.d(
                            TAG,
                            "schedule[$schedId] transition rejected — likely already terminal: ${it.message}",
                        )
                    }
                }
            }

            refreshNotification()
            maybeStop()
        }
    }

    private fun handleStopAll() {
        activeJobs.keys.toList().forEach { handleStop(it) }
    }

    private fun onRecorderResult(recordId: String, result: RecordResult) {
        try {
            when (result) {
                is RecordResult.Success ->
                    recordings.markCompleted(
                        id = recordId,
                        bytesWritten = result.bytesWritten,
                        durationSeconds = result.secondsElapsed,
                    )
                is RecordResult.Failure ->
                    recordings.markFailed(
                        id = recordId,
                        reason = result.reason,
                        bytesWritten = result.bytesWritten,
                    )
            }
        } catch (t: IllegalArgumentException) {
            // Already-terminal — handleStop got there first.
            Log.d(TAG, "recorder result for $recordId arrived after manual stop", t)
        } catch (t: IllegalStateException) {
            Log.w(TAG, "recorder result for $recordId — row missing?", t)
        }
        activeJobs.remove(recordId)
        activeOutputs.remove(recordId)
        refreshNotification()
        maybeStop()
    }

    private fun maybeStop() {
        if (activeJobs.isEmpty()) {
            // MB-209 / MB-210 hardening — release locks now that no
            // recording remains. isHeld guards so we don't fault when
            // no acquire ever ran (fresh service whose first intent
            // had a malformed input).
            runCatching { if (wifiLock.isHeld) wifiLock.release() }
                .onFailure { Log.w(TAG, "wifiLock.release failed", it) }
            runCatching { if (cpuWakeLock.isHeld) cpuWakeLock.release() }
                .onFailure { Log.w(TAG, "cpuWakeLock.release failed", it) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ── Notification ──────────────────────────────────────────────

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.recording_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.recording_channel_description)
                    setSound(null, null)
                    enableVibration(false)
                }
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundIfNeeded() {
        val notification = buildNotification(activeJobs.size)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            // Pre-Q: no FGS type required — the service-element
            // attribute is ignored by the framework.
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshNotification() {
        // Lock so concurrent finishes don't issue out-of-order updates.
        serviceScope.launch {
            notificationLock.withLock {
                val nm = getSystemService(NotificationManager::class.java) ?: return@withLock
                if (activeJobs.isEmpty()) return@withLock
                nm.notify(NOTIFICATION_ID, buildNotification(activeJobs.size))
            }
        }
    }

    private fun buildNotification(count: Int): Notification {
        val openAppIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stopAllIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, RecordingService::class.java).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val title =
            if (count == 1) {
                getString(R.string.recording_notification_title_one)
            } else {
                getString(R.string.recording_notification_title_many, count)
            }
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string.recording_notification_body))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.recording_notification_stop_all),
                stopAllIntent,
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private object RealClock : RecorderClock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }

    companion object {
        private const val TAG = "YancoRecordingSvc"
        private const val CHANNEL_ID = "yanco_recordings"
        private const val NOTIFICATION_ID = 9001

        const val ACTION_START = "com.yancotv.android.recording.START"
        const val ACTION_STOP = "com.yancotv.android.recording.STOP"
        const val ACTION_STOP_ALL = "com.yancotv.android.recording.STOP_ALL"

        const val EXTRA_RECORD_ID = "record_id"
        const val EXTRA_CONTENT_ID = "content_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_FORMAT = "format" // "hls" or "mpeg_ts"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_MAX_DURATION_MS = "max_duration_ms"

        /**
         * Signals that ACTION_STOP was triggered by an explicit user
         * action (UI Cancel button or scheduler.cancel), as opposed to
         * the end-alarm path. Routes `handleStop` to `markCancelled`
         * regardless of bytes — a deliberate Cancel must read as
         * CANCELLED, not as `FAILED:no_response_from_server` when no
         * bytes happened to land first.
         */
        const val EXTRA_USER_INITIATED = "user_initiated"

        /**
         * Convenience for the UI / WorkManager: kick off a new
         * recording. Returns the recordId so the caller can correlate
         * with later `recordings` table reads. Generates a UUID when
         * one isn't supplied.
         */
        fun start(context: Context, input: RecordInput): String {
            val effectiveId =
                input.recordId.ifBlank { UUID.randomUUID().toString() }
            val intent =
                Intent(context, RecordingService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RECORD_ID, effectiveId)
                    .putExtra(EXTRA_CONTENT_ID, input.contentId)
                    .putExtra(EXTRA_TITLE, input.title)
                    .putExtra(EXTRA_STREAM_URL, input.sourceUrl)
                    .putExtra(EXTRA_FORMAT, formatToString(input.format))
                    .putExtra(EXTRA_USER_AGENT, input.userAgent)
                    .putExtra(EXTRA_REFERER, input.referer)
            input.maxDurationMs?.let { intent.putExtra(EXTRA_MAX_DURATION_MS, it) }
            // Foreground service start — the service must call
            // startForeground within 5s; we do that synchronously in
            // handleStart on the main thread.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return effectiveId
        }

        /**
         * Stop an in-flight recording. Set [userInitiated] when the
         * caller is an explicit user Cancel (RecordingsScreen Cancel
         * button, `RecordingScheduleScheduler.cancel`), so the row
         * lands as CANCELLED regardless of bytes-on-disk. Defaults to
         * `false` for end-alarm / completion paths where the row
         * should be marked COMPLETED-or-FAILED based on the byte
         * count `handleStop` reads.
         */
        fun stop(context: Context, recordId: String, userInitiated: Boolean = false) {
            val intent =
                Intent(context, RecordingService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_RECORD_ID, recordId)
                    .putExtra(EXTRA_USER_INITIATED, userInitiated)
            context.startService(intent)
        }

        fun stopAll(context: Context) {
            val intent =
                Intent(context, RecordingService::class.java)
                    .setAction(ACTION_STOP_ALL)
            context.startService(intent)
        }

        internal fun formatToString(format: RecordingFormat): String = when (format) {
            RecordingFormat.HLS -> "hls"
            RecordingFormat.MPEG_TS -> "mpeg_ts"
        }

        internal fun formatFromString(value: String?): RecordingFormat? = when (value?.lowercase()) {
            "hls" -> RecordingFormat.HLS
            "mpeg_ts", "ts" -> RecordingFormat.MPEG_TS
            else -> null
        }
    }
}

/**
 * Hydrate a [RecordInput] from an Intent's extras. Returns null if
 * required fields are missing — caller logs and bails.
 */
@UnstableApi
private fun Intent.toRecordInput(): RecordInput? {
    val id = getStringExtra(RecordingService.EXTRA_RECORD_ID) ?: return null
    val title = getStringExtra(RecordingService.EXTRA_TITLE) ?: return null
    val streamUrl = getStringExtra(RecordingService.EXTRA_STREAM_URL) ?: return null
    val format =
        RecordingService.formatFromString(getStringExtra(RecordingService.EXTRA_FORMAT))
            ?: return null
    val maxDuration =
        if (hasExtra(RecordingService.EXTRA_MAX_DURATION_MS)) {
            getLongExtra(RecordingService.EXTRA_MAX_DURATION_MS, -1L).takeIf { it >= 0L }
        } else {
            null
        }
    return RecordInput(
        recordId = id,
        sourceUrl = streamUrl,
        title = title,
        format = format,
        contentId = getStringExtra(RecordingService.EXTRA_CONTENT_ID),
        maxDurationMs = maxDuration,
        userAgent = getStringExtra(RecordingService.EXTRA_USER_AGENT),
        referer = getStringExtra(RecordingService.EXTRA_REFERER),
    )
}
