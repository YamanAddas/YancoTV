package com.yancotv.android.recommendations

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yancotv.shared.history.WatchHistoryRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * MK.10.1 — periodic refresh of the launcher Recommendations channel.
 *
 * Runs every [PERIODIC_HOURS] hours, capped at WorkManager's minimum
 * frequency (15 min) by the platform — we use 6 h since the Recently
 * watched row tracks user-driven changes that don't update faster
 * than that in practice.
 *
 * Also enqueues a one-shot via [enqueueOnce] from `YancoApp.onCreate`
 * so the channel exists on first launch without waiting for the
 * periodic window.
 */
class RecommendationsWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params),
    KoinComponent {
    private val history: WatchHistoryRepository by inject()

    override suspend fun doWork(): Result {
        return runCatching {
            val sync = RecommendationsSync(applicationContext, history)
            val result = sync.sync()
            Log.i(TAG, "result=$result")
            Result.success()
        }.getOrElse { t ->
            Log.w(TAG, "sync failed", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "YancoRecsWorker"
        private const val UNIQUE_PERIODIC = "yanco.recommendations.periodic"
        private const val UNIQUE_ONESHOT = "yanco.recommendations.oneshot"
        private const val PERIODIC_HOURS: Long = 6L

        fun enqueuePeriodic(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<RecommendationsWorker>(
                    PERIODIC_HOURS,
                    TimeUnit.HOURS,
                ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun enqueueOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<RecommendationsWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONESHOT,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
