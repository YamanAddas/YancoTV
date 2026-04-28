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
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

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
class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params),
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
        return runCatching { repo.triggerCheck() }
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
        private const val MAX_RETRIES = 3
        private const val UNIQUE_PERIODIC = "update-check-periodic"
        private const val UNIQUE_ONESHOT = "update-check-oneshot"
        private const val PERIODIC_HOURS = 24L

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
