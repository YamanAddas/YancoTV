package com.yancotv.android.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yancotv.shared.epg.EpgRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Periodic + one-shot EPG refresh. Fetches every configured source's XMLTV,
 * parses, and atomically swaps the `epg_programmes` table. Wrapped in
 * WorkManager so the OS can coalesce across apps, defer on metered data, and
 * retry with backoff when the network is flaky.
 *
 * Scheduling strategy:
 *   - `schedulePeriodic(context)` is called once from [com.yancotv.android.YancoApp]
 *     and uses `ExistingPeriodicWorkPolicy.KEEP` so app restarts don't reset
 *     the interval window.
 *   - `enqueueOnce(context)` runs an immediate refresh (e.g. after the user
 *     adds a source or taps "Refresh EPG" in settings).
 *
 * The 6-hour cadence matches the desktop `epg-service.ts` default. Shorter
 * intervals waste battery + bandwidth; longer ones mean the "now playing"
 * band on the guide can be hours stale after a full day uptime.
 */
class EpgSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params),
    KoinComponent {
    private val epg: EpgRepository by inject()
    private val importer: AndroidEpgImporter by inject()

    override suspend fun doWork(): Result =
        try {
            // Stream-based importer keeps peak memory bounded — the shared
            // [EpgRepository.refresh] path materialises the whole XML as a
            // String and OOMs on Fire TV when the provider's feed is large.
            // The importer uses XmlPullParser + incremental batched writes.
            val result =
                importer.refresh { msg ->
                    setProgress(workDataOf(KEY_PROGRESS to msg))
                }
            if (result.ok) {
                val cutoff = (System.currentTimeMillis() / 1000L) - STALE_WINDOW_SECONDS
                runCatching { epg.deleteStale(cutoff) }
                Result.success()
            } else if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (result.error ?: "EPG refresh failed")))
            }
        } catch (t: Throwable) {
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (t.message ?: t::class.simpleName ?: "unknown")))
            }
        }

    companion object {
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS = "progress"
        private const val MAX_RETRIES = 3
        private const val UNIQUE_PERIODIC = "epg-sync-periodic"
        private const val UNIQUE_ONESHOT = "epg-sync-oneshot"
        private const val PERIODIC_HOURS = 6L

        // 24 hours of history kept on-device so Guide can show "just finished"
        // programmes; anything older is dead weight.
        private const val STALE_WINDOW_SECONDS = 24L * 60L * 60L

        fun schedulePeriodic(context: Context) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            val request =
                PeriodicWorkRequestBuilder<EpgSyncWorker>(
                    PERIODIC_HOURS,
                    TimeUnit.HOURS,
                ).setConstraints(constraints)
                    .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                // KEEP — don't reset the 6h window every app start. The user
                // gets a one-shot on first boot via enqueueOnce in YancoApp.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun enqueueOnce(context: Context) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            val request =
                OneTimeWorkRequestBuilder<EpgSyncWorker>()
                    .setConstraints(constraints)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONESHOT,
                // KEEP — if a refresh is already in flight, don't pile on.
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
