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
import com.yancotv.android.MainActivity
import com.yancotv.android.R
import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.recording.HlsRecorder
import com.yancotv.shared.recording.MpegTsRecorder
import com.yancotv.shared.recording.RecordInput
import com.yancotv.shared.recording.RecordResult
import com.yancotv.shared.recording.RecorderClock
import com.yancotv.shared.recording.RecordingFormat
import com.yancotv.shared.recording.RecordingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.android.ext.android.inject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
class RecordingService : Service() {
    private val recordings: RecordingsRepository by inject()
    private val http: HttpClient by inject()
    private val prefs: com.yancotv.android.prefs.AppPreferences by inject()
    private val logger: com.yancotv.shared.logger.Logger by inject()

    private val storageResolver by lazy {
        RecordingStorageResolver(applicationContext, prefs)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeOutputs = ConcurrentHashMap<String, RecordingOutput>()
    private val notificationLock = Mutex()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
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
                if (id != null) handleStop(id)
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
    }

    // ── Action handlers ───────────────────────────────────────────

    private fun handleStart(input: RecordInput) {
        if (activeJobs.containsKey(input.recordId)) {
            Log.w(TAG, "duplicate start for ${input.recordId} — ignoring")
            return
        }
        Log.i(TAG, "start[${input.recordId}] format=${input.format}")
        // Become foreground immediately. Android requires startForeground
        // within 5s of startForegroundService; doing it before the
        // recording coroutine even starts is the safest pattern.
        startForegroundIfNeeded()

        // Storage resolution can suspend (it reads contentResolver +
        // creates a DocumentFile), so launch the whole coroutine and
        // treat allocation failure as a markFailed. This also lets the
        // resolver clear a stale SAF pref if the persisted permission
        // got revoked since the user picked the folder.
        val job =
            serviceScope.launch(Dispatchers.IO) {
                // **Single-connection IPTV grace period.** When the user
                // pressed "Record this channel" from the player options
                // sheet, the player's connection has just been released —
                // but ExoPlayer's OkHttp socket close + the server's
                // stream-slot release are both async. Opening a fresh GET
                // immediately makes the server still see two connections,
                // which surfaces as performGet hanging forever (recorder
                // gets 0 bytes). 1 s is overkill for a healthy network
                // but the cheap insurance that lets stop-then-record on
                // a 1-stream provider Just Work. Living here in the
                // service (not the launching composable) means it isn't
                // cancelled when the player activity finishes immediately
                // after firing this Intent.
                delay(GRACE_BEFORE_RECORD_MS)
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
                        // User stopped before resolve completed — let the
                        // cancellation propagate so the launch ends in
                        // Cancelled state. No row was created (markStarted
                        // hasn't run), so there's nothing to transition.
                        // handleStop's cleanup of activeJobs/activeOutputs
                        // already ran when ACTION_STOP was processed.
                        throw c
                    } catch (t: Throwable) {
                        Log.e(TAG, "failed to allocate output for ${input.recordId}", t)
                        // markFailed throws if no row exists yet (resolve
                        // failed before markStarted), so wrap defensively.
                        // This case is "file allocation died on us" — a real
                        // failure mode (no storage / SAF revoked) that we
                        // want surfaced if a row exists, swallowed otherwise.
                        runCatching {
                            recordings.markFailed(
                                id = input.recordId,
                                reason = "file_allocation_failed: ${t.message ?: t::class.simpleName}",
                                bytesWritten = 0L,
                            )
                        }
                        // maybeStop is non-suspending Service-framework
                        // calls; safe from any thread, no need to switch.
                        maybeStop()
                        return@launch
                    }
                activeOutputs[input.recordId] = output

                // Insert the row in RECORDING status. After this point,
                // every exit path must transition the row to a terminal
                // state. Done on IO since it hits SQLDelight.
                recordings.markStarted(
                    id = input.recordId,
                    contentId = input.contentId,
                    title = input.title,
                    streamUrl = input.sourceUrl,
                    filePath = output.storagePath,
                    format = input.format,
                )

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
        // Update the notification's "N in progress" body.
        refreshNotification()
    }

    private fun handleStop(recordId: String) {
        val job = activeJobs[recordId] ?: return
        val output = activeOutputs[recordId]
        // Pull bookkeeping off the maps up front so a duplicate ACTION_STOP
        // arriving while we're awaiting the flush doesn't double-cancel.
        activeJobs.remove(recordId)
        activeOutputs.remove(recordId)

        // The launching coroutine's `output.openSink().use {}` flushes the
        // sink in its `finally` even when cancellation propagates — but that
        // happens *asynchronously* after `job.cancel()`. If we marked the
        // row immediately we'd race the flush and write a stale byte count.
        // cancelAndJoin awaits the use{} cleanup, so File.length() then
        // reflects the real on-disk total.
        serviceScope.launch(Dispatchers.IO) {
            runCatching { job.cancelAndJoin() }
            val bytes = output?.size() ?: 0L
            runCatching {
                val startedAt = recordings.getById(recordId)?.startedAt
                val secs =
                    startedAt?.let { (System.currentTimeMillis() - it) / 1000L }
                        ?.coerceAtLeast(0L) ?: 0L
                if (bytes <= 0L) {
                    // Recorder ran but no bytes hit the disk before stop —
                    // typically because the server never started serving the
                    // request body within the time the user waited. Mark
                    // FAILED so the row reads "Failed · no_response_from_server"
                    // instead of "Saved 0 KB" (which would invite the user to
                    // tap Play and hit the 3003 unrecognized-input error).
                    Log.i(TAG, "stop[$recordId] failed — no bytes from server")
                    recordings.markFailed(
                        id = recordId,
                        reason = "no_response_from_server",
                        bytesWritten = 0L,
                    )
                } else {
                    Log.i(TAG, "stop[$recordId] saved $bytes bytes (${secs}s)")
                    recordings.markCompleted(
                        id = recordId,
                        bytesWritten = bytes,
                        durationSeconds = secs,
                    )
                }
            }.onFailure { Log.w(TAG, "stop[$recordId] transition failed", it) }
            refreshNotification()
            maybeStop()
        }
    }

    private fun handleStopAll() {
        activeJobs.keys.toList().forEach { handleStop(it) }
    }

    private fun onRecorderResult(
        recordId: String,
        result: RecordResult,
    ) {
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

        /** Grace period at the start of every recording so the prior
         *  player connection on the same channel has time to fully close
         *  on the wire before the recorder opens its own GET. See the
         *  delay call in [handleStart]. */
        private const val GRACE_BEFORE_RECORD_MS = 1_000L

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
         * Convenience for the UI / WorkManager: kick off a new
         * recording. Returns the recordId so the caller can correlate
         * with later `recordings` table reads. Generates a UUID when
         * one isn't supplied.
         */
        fun start(
            context: Context,
            input: RecordInput,
        ): String {
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

        fun stop(
            context: Context,
            recordId: String,
        ) {
            val intent =
                Intent(context, RecordingService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_RECORD_ID, recordId)
            context.startService(intent)
        }

        fun stopAll(context: Context) {
            val intent =
                Intent(context, RecordingService::class.java)
                    .setAction(ACTION_STOP_ALL)
            context.startService(intent)
        }

        internal fun formatToString(format: RecordingFormat): String =
            when (format) {
                RecordingFormat.HLS -> "hls"
                RecordingFormat.MPEG_TS -> "mpeg_ts"
            }

        internal fun formatFromString(value: String?): RecordingFormat? =
            when (value?.lowercase()) {
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
