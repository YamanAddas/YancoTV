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
import com.yancotv.android.sources.SourceSyncCoordinator
import com.yancotv.shared.epg.EpgRepository
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * MB-299 — why a refresh was requested. Decides whether it may run while a
 * catalog sync is rebuilding the channel table.
 */
enum class EpgSyncReason {
    /** Startup one-shot and the 6-hour periodic. Deferrable. */
    AUTO,

    /** The user tapped "Refresh EPG". Must never silently no-op. */
    USER,

    /**
     * [com.yancotv.android.sources.SourceSyncCoordinator] kicking a refresh
     * the moment a catalog lands. Runs while the coordinator's `state` is
     * still non-null — it clears in a `finally` AFTER the kick — so this must
     * bypass the active-sync guard or EPG would never refresh at all.
     */
    POST_SYNC,

    ;

    companion object {
        fun fromRaw(raw: String?): EpgSyncReason = entries.firstOrNull { it.name == raw } ?: AUTO
    }
}

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
 *   - `enqueueOnce(context, reason)` runs an immediate refresh (e.g. after the
 *     user adds a source or taps "Refresh EPG" in settings).
 *
 * MB-299 — a run tagged [EpgSyncReason.AUTO] is dropped while a catalog sync is
 * rebuilding the channel table; see [shouldSkipForActiveSync].
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
    private val syncCoordinator: SourceSyncCoordinator by inject()

    override suspend fun doWork(): Result {
        val reason = EpgSyncReason.fromRaw(inputData.getString(KEY_REASON))
        if (shouldSkipForActiveSync(reason, syncCoordinator.state.value != null)) {
            // MB-299 — a catalog sync is mid-flight. `BulkContentWriter.prepareSource`
            // has already DELETEd this source's content rows, so the importer would
            // match against an empty channel table: measured on AFTMM it downloaded
            // 77,444,321 B, parsed for 64 s and reported
            // `scanned 226822, kept 0 (0 exact + 0 normalised), dropped 226822`.
            // Pure waste — and worse, that 77 MB fetch overlapped the 279k-item
            // catalog sync that was already the tightest point in the heap.
            //
            // `success()` rather than `retry()`: retry burns `runAttemptCount`
            // against MAX_RETRIES, which a ~4.5-minute sync would exhaust, and it
            // is safe to drop because the coordinator kicks a POST_SYNC refresh as
            // soon as the catalog lands. Returning immediately also frees the
            // UNIQUE_ONESHOT slot, so that POST_SYNC kick isn't swallowed by
            // `ExistingWorkPolicy.KEEP`.
            //
            // Logged, not silent: "my EPG didn't refresh" is otherwise
            // indistinguishable from a broken feed in a bug report.
            android.util.Log.i(
                "Yanco",
                "EPG refresh skipped — catalog sync in progress (reason=$reason); " +
                    "the coordinator re-kicks EPG when the catalog lands",
            )
            return Result.success()
        }
        return runRefresh()
    }

    private suspend fun runRefresh(): Result = try {
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
        /**
         * MB-299 — may this refresh be dropped because a catalog sync is
         * rebuilding the channel table?
         *
         * Only [EpgSyncReason.AUTO] is deferrable. [EpgSyncReason.USER] must
         * run or a "Refresh EPG" tap becomes a silent no-op, and
         * [EpgSyncReason.POST_SYNC] must run because the coordinator fires it
         * while its own `state` is still non-null — skipping that one would
         * mean EPG never refreshes at all.
         *
         * Pure so the policy is unit-testable without WorkManager.
         */
        fun shouldSkipForActiveSync(reason: EpgSyncReason, catalogSyncActive: Boolean): Boolean = catalogSyncActive && reason == EpgSyncReason.AUTO

        const val KEY_ERROR = "error"
        const val KEY_REASON = "reason"
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
                    // MB-299 — the 6-hour tick is deferrable: if it happens to
                    // land during a catalog sync it would match against a table
                    // being rebuilt, and the coordinator refreshes EPG anyway
                    // once the catalog settles.
                    .setInputData(workDataOf(KEY_REASON to EpgSyncReason.AUTO.name))
                    .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                // KEEP — don't reset the 6h window every app start. The user
                // gets a one-shot on first boot via enqueueOnce in YancoApp.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * @param reason MB-299 — defaults to [EpgSyncReason.AUTO], which is
         *   dropped while a catalog sync is running. Pass [EpgSyncReason.USER]
         *   from anything the user tapped, and [EpgSyncReason.POST_SYNC] from
         *   the sync coordinator.
         */
        fun enqueueOnce(context: Context, reason: EpgSyncReason = EpgSyncReason.AUTO) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            val request =
                OneTimeWorkRequestBuilder<EpgSyncWorker>()
                    .setConstraints(constraints)
                    .setInputData(workDataOf(KEY_REASON to reason.name))
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
