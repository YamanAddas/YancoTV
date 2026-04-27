package com.yancotv.android.recording.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.yancotv.shared.recording.RecordingScheduleRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * MK.14.3 — fires once on `BOOT_COMPLETED`.
 *
 * Two responsibilities:
 *
 *   1. **Reconcile lapsed-during-reboot schedules.** Calls
 *      [RecordingScheduleRepository.reconcileAfterBoot] which:
 *        - marks `ARMED` rows whose start window passed → MISSED
 *          (`device_offline`)
 *        - marks `FIRING` rows whose recording got orphaned → FAILED
 *          (`orphaned_by_app_kill`)
 *      Returns counts so we can log non-zero results.
 *
 *   2. **Re-arm future schedules.** AlarmManager doesn't persist its
 *      registered alarms across reboot. Calls
 *      [RecordingScheduleScheduler.rescheduleAll] to walk every still-
 *      `ARMED` row whose start is in the future and re-register the
 *      pre-fire + end alarms.
 *
 * Order matters: reconcile FIRST so the rescheduleAll pass doesn't
 * re-arm rows we just transitioned to MISSED.
 *
 * `LOCKED_BOOT_COMPLETED` is NOT registered. The DB lives in
 * credential-encrypted storage; if we ran in direct-boot mode, the
 * SQLDelight driver would fail to open. We accept the small delay
 * before the user unlocks, matching the [com.yancotv.android.reminders.ReminderBootReceiver]
 * pattern that's already in production.
 */
@UnstableApi
class RecordingScheduleBootReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val repo: RecordingScheduleRepository by inject()
    private val scheduler: RecordingScheduleScheduler by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        // Wrap each step independently — if reconcileAfterBoot throws
        // (e.g. DB corruption, partially-restored backup state), the
        // rescheduleAll pass must still run or every ARMED row loses
        // its alarms across reboot. Conversely, a rescheduleAll failure
        // mustn't block the reconciliation that's already happened.
        runCatching { repo.reconcileAfterBoot() }
            .onSuccess { swept ->
                if (swept.total > 0) {
                    Log.i(
                        TAG,
                        "boot reconcile: missed=${swept.markedMissed}, " +
                            "orphaned-firing=${swept.markedFailedFromOrphan}",
                    )
                }
            }
            .onFailure { Log.w(TAG, "reconcileAfterBoot failed: ${it.message}", it) }

        runCatching { scheduler.rescheduleAll() }
            .onFailure { Log.w(TAG, "rescheduleAll failed: ${it.message}", it) }
    }

    companion object {
        private const val TAG = "YancoSchedBoot"
    }
}
