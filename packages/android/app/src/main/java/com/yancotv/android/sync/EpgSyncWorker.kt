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
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.EpgPrefs
import com.yancotv.shared.epg.EpgRepository
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

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
class EpgSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params),
    KoinComponent {
    private val epg: EpgRepository by inject()
    private val importer: AndroidEpgImporter by inject()
    private val prefs: AppPreferences by inject()

    override suspend fun doWork(): Result = try {
        // Stream-based importer keeps peak memory bounded — the shared
        // [EpgRepository.refresh] path materialises the whole XML as a
        // String and OOMs on Fire TV when the provider's feed is large.
        // The importer uses XmlPullParser + incremental batched writes.
        val result =
            importer.refresh { msg ->
                setProgress(workDataOf(KEY_PROGRESS to msg))
            }
        if (result.ok) {
            // MK.EPG.E — stale cutoff honours `EpgPrefs.daysBack` instead
            // of the old 24h hardcoded window. Pre-fix, a user with
            // daysBack=7 saw growing "grey areas" in the Guide because
            // every 6h periodic refresh deleted programmes the screen
            // was still supposed to show. See
            // [computeStaleCutoffSeconds] for the keep-window contract.
            val daysBack = prefs.epgFlow.value.daysBack
            val cutoff = computeStaleCutoffSeconds(System.currentTimeMillis(), daysBack)
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

        /**
         * Computes the Unix-seconds cutoff fed to [EpgRepository.deleteStale].
         * Programmes whose `end_time < cutoff` are deleted; everything
         * `>=` is kept.
         *
         * Keep-window contract:
         *   * Always keep at least [EpgPrefs.CATCHUP_BASELINE_HOURS]
         *     of recent history so a fresh install (daysBack=0) still
         *     surfaces "the show that just ended."
         *   * If [daysBack] > 0, keep at least daysBack-worth — the
         *     user's pref is the floor, never overruled by the sweep.
         *   * Plus a 1-day safety margin so a programme whose
         *     end_time straddles the cutoff while it's still painted
         *     in the Guide isn't yanked mid-render.
         *
         * Pure function: [nowMillis] is the only system-clock
         * dependency, so unit tests can pin behaviour without time
         * mocking. `daysBack` is clamped at 0 (caller passes the
         * pref directly; defensive in case a future refactor adjusts
         * the slider's lower bound).
         */
        fun computeStaleCutoffSeconds(nowMillis: Long, daysBack: Int): Long {
            val baselineSeconds = EpgPrefs.CATCHUP_BASELINE_HOURS.toLong() * 3_600L
            val daysBackSeconds = daysBack.coerceAtLeast(0).toLong() * 86_400L
            val keepSeconds = maxOf(daysBackSeconds, baselineSeconds) + SAFETY_MARGIN_SECONDS
            return (nowMillis / 1_000L) - keepSeconds
        }

        // 1-day cushion on top of the user's daysBack window — see
        // [computeStaleCutoffSeconds] doc.
        private const val SAFETY_MARGIN_SECONDS = 86_400L

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
