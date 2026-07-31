package com.yancotv.android.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.sources.SyncDetail
import com.yancotv.shared.sources.SyncProgress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.lastOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Background sync for a single source. Scheduled per-source when the user
 * sets a non-zero `autoSyncInterval`; also enqueued as one-shot from the
 * Sources settings screen.
 *
 * Uses `uniqueWorkName = "source-sync-$sourceId"` so repeated enqueues
 * coalesce instead of stacking up concurrent refreshes of the same source.
 *
 * Progress is NOT surfaced via `setProgress` here — the UI observes the
 * SQLDelight row's `last_synced` / `last_sync_error` directly. We only need
 * the terminal state (success or retry) from WorkManager's perspective.
 */
class SourceSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params),
    KoinComponent {
    private val repo: SourceRepository by inject()

    override suspend fun doWork(): Result {
        val sourceId =
            inputData.getString(KEY_SOURCE_ID)
                ?: return Result.failure(workDataOf(KEY_ERROR to "missing $KEY_SOURCE_ID"))

        val terminal = repo.syncSource(sourceId).lastOrNull()
        return when (terminal?.phase) {
            SyncProgress.Phase.DONE -> Result.success()
            // Network hiccups get retried by WorkManager's exponential backoff.
            // Everything else (bad credentials, portal down for days) becomes
            // a terminal failure visible in `last_sync_error`.
            SyncProgress.Phase.ERROR ->
                if (runAttemptCount < MAX_RETRIES) {
                    Result.retry()
                } else {
                    // MK.31.18 — KEY_ERROR is WorkManager diagnostic output read by logs and
                    // tests, never rendered, so it stays untranslated and keeps the
                    // raw provider text rather than a localized string.
                    Result.failure(
                        workDataOf(
                            KEY_ERROR to (
                                (terminal.detail as? SyncDetail.Failure)?.text
                                    ?: "sync failed"
                                ),
                        ),
                    )
                }
            else -> Result.failure(workDataOf(KEY_ERROR to "sync produced no terminal event"))
        }
    }

    companion object {
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_ERROR = "error"
        private const val MAX_RETRIES = 3
        private const val UNIQUE_PREFIX = "source-sync-"

        fun enqueuePeriodic(context: Context, sourceId: String, intervalMinutes: Long) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val request =
                PeriodicWorkRequestBuilder<SourceSyncWorker>(
                    intervalMinutes,
                    TimeUnit.MINUTES,
                ).setInputData(workDataOf(KEY_SOURCE_ID to sourceId))
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PREFIX + sourceId,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context, sourceId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PREFIX + sourceId)
        }
    }
}
