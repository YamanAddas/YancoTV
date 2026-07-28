package com.yancotv.android.update

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
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.shared.update.UpdateCheckOutcome
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Stage 5.2.2 — periodic update-check worker. Runs once every
 * [PERIODIC_HOURS] hours and delegates to [UpdateRepository.triggerCheck];
 * the actual fetch + parse logic lives there + in
 * [com.yancotv.shared.update.UpdateChecker]. This class is the WorkManager
 * shim that schedules itself.
 *
 * Mirrors the [com.yancotv.android.sync.EpgSyncWorker] shape:
 *   - `schedulePeriodic(context)` from [com.yancotv.android.YancoApp] uses
 *     `KEEP` so app restarts don't reset the 24h window.
 *   - `enqueueOnce(context)` runs an immediate check (Settings → About
 *     "Check now" button).
 *   - `cancelPeriodic(context)` is called when the user toggles
 *     auto-check off so we don't keep polling against the user's wishes.
 *
 * Honors the user's auto-check preference: if [AppPreferences.updatePrefsFlow]
 * has `autoCheckEnabled == false`, the worker is a no-op (returns
 * `Result.success()` immediately so WorkManager doesn't retry). The
 * one-shot path bypasses this gate so a manual "Check now" works
 * even when periodic checks are disabled.
 */
class UpdateCheckWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params),
    KoinComponent {
    private val repo: UpdateRepository by inject()
    private val prefs: AppPreferences by inject()

    override suspend fun doWork(): Result {
        // Periodic runs respect the user's opt-out. One-shot runs (the
        // Settings → About "Check now" button) carry KEY_FORCE = true so
        // they bypass the gate.
        val force = inputData.getBoolean(KEY_FORCE, false)
        if (!force && !prefs.updatePrefsFlow.value.autoCheckEnabled) {
            return Result.success()
        }
        // MK.30.4 — the startup run is throttled on top of the pref gate.
        // UpdateRepository.info is in-memory by design, so after a cold start
        // nothing knows about a pending update until a check runs — which left
        // the sidebar badge blank for up to 24h. A launch-time check fixes
        // that, but every launch hitting the network would be gratuitous, so
        // it only proceeds when the last successful check is genuinely old.
        if (inputData.getBoolean(KEY_STARTUP, false)) {
            val lastCheck = prefs.updatePrefsFlow.value.lastCheckedAt
            val minAgeMs = TimeUnit.HOURS.toMillis(STARTUP_MIN_AGE_HOURS)
            if (lastCheck != null && System.currentTimeMillis() - lastCheck < minAgeMs) {
                return Result.success()
            }
        }
        return runCatching {
            // MK.30.4 — the check has always run here; until now nothing told
            // the user about the result unless they walked into Settings →
            // About. notifyIfNew dedupes on versionCode, so this fires once
            // per release rather than on every 24h tick.
            //
            // Uses the detailed outcome rather than triggerCheck() because
            // that collapses "up to date" and "couldn't check" into null, and
            // those need opposite handling: only a confirmed UpToDate means a
            // previously-posted notification is stale (the user installed it,
            // since notifications survive an app update). Clearing on a failed
            // check would drop a legitimately pending notice over a network
            // hiccup.
            when (val outcome = repo.triggerCheckOutcome()) {
                is UpdateCheckOutcome.Available ->
                    UpdateNotifier.notifyIfNew(applicationContext, outcome.info, prefs)
                is UpdateCheckOutcome.UpToDate -> UpdateNotifier.clear(applicationContext)
                else -> Unit
            }
        }
            .fold(
                onSuccess = { Result.success() },
                onFailure = {
                    if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
                    // Note: even on retry-exhausted failure we return success
                    // rather than failure. UpdateRepository.triggerCheck
                    // returns null on errors (it never throws by contract);
                    // a thrown error here means something below it crashed,
                    // and we don't want WorkManager to mark this periodic
                    // job as permanently failed and stop scheduling.
                },
            )
    }

    companion object {
        const val KEY_FORCE = "force"

        /** Marks the launch-time run so [STARTUP_MIN_AGE_HOURS] applies. */
        const val KEY_STARTUP = "startup"
        private const val MAX_RETRIES = 3
        private const val UNIQUE_PERIODIC = "update-check-periodic"
        private const val UNIQUE_ONESHOT = "update-check-oneshot"
        private const val UNIQUE_STARTUP = "update-check-startup"
        private const val PERIODIC_HOURS = 24L

        /**
         * How stale the last check must be for a launch-time check to run.
         * Well under the 24h periodic window so a user who opens the app daily
         * still gets a fresh answer, but short launch-quit-launch cycles make
         * at most one request.
         */
        private const val STARTUP_MIN_AGE_HOURS = 6L

        fun schedulePeriodic(context: Context) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            val request =
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                    PERIODIC_HOURS,
                    TimeUnit.HOURS,
                ).setConstraints(constraints)
                    .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                // KEEP — don't reset the 24h window every app start.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
        }

        /**
         * MK.30.4 — launch-time check, so the sidebar badge and the About
         * banner reflect reality in the session the user is actually in
         * rather than whenever the 24h periodic job next happens to fire.
         *
         * Does NOT carry [KEY_FORCE]: opting out of auto-check must opt out
         * of this too. Throttled by [STARTUP_MIN_AGE_HOURS] inside `doWork`
         * so relaunching the app repeatedly doesn't mean a request each time.
         */
        fun enqueueStartupCheck(context: Context) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            val request =
                OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                    .setConstraints(constraints)
                    .setInputData(androidx.work.workDataOf(KEY_STARTUP to true))
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_STARTUP,
                // KEEP — if a startup check from this launch is still pending
                // (e.g. offline, waiting on the network constraint), don't
                // replace it and reset its wait.
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * One-shot check. Carries [KEY_FORCE] = true so the worker
         * bypasses the auto-check pref — used by Settings → About
         * "Check now" so the user can pull on demand even with
         * periodic polling disabled.
         */
        fun enqueueOnce(context: Context) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            val request =
                OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                    .setConstraints(constraints)
                    .setInputData(androidx.work.workDataOf(KEY_FORCE to true))
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONESHOT,
                // REPLACE — manual "Check now" should override any in-flight
                // one-shot from a previous tap (the user wants fresh data).
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
