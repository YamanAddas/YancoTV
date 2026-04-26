package com.yancotv.android.recording.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.yancotv.shared.recording.RecordingScheduleEntry

/**
 * MK.14.3 — pairs schedule rows with `AlarmManager` registrations so the
 * two stay in lockstep. Owning them here (not in [com.yancotv.shared.recording.RecordingScheduleRepository])
 * keeps the shared module free of `android.*` imports.
 *
 * **Two alarms per schedule.** A pre-fire alarm at
 * `scheduled_start - prePaddingMs` (begins recording) and an end alarm
 * at `scheduled_end + postPaddingMs` (stops recording). Both use
 * `setExactAndAllowWhileIdle` so they fire even when Fire TV is in
 * standby / Doze — the user explicitly scheduled this; we should not
 * silently miss it because the screen was off.
 *
 * **PendingIntent identity** is `(scheduleId + action).hashCode()` —
 * each (schedule × action) pair gets its own slot, so the pre-fire
 * and end alarms for the same schedule don't replace each other.
 * `FLAG_UPDATE_CURRENT` lets re-arm replace the pending intent atomically
 * (on boot reconciliation, on padding-prefs changes).
 *
 * **API 31+ exact-alarm permission** — we hold `USE_EXACT_ALARM` in the
 * manifest (already required for [com.yancotv.android.reminders.ReminderScheduler]),
 * which can't be revoked. Defensive fallback to `setAndAllowWhileIdle`
 * if the OEM check still rejects exact scheduling — the recording
 * fires a few minutes late instead of the alarm-registration crashing
 * the schedule-create flow.
 */
@UnstableApi
class RecordingScheduleAlarmManager(
    private val context: Context,
) {
    private val alarms: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Register both alarms for a schedule. Idempotent — `FLAG_UPDATE_CURRENT`
     * on the PendingIntents replaces any prior registration with the same id.
     *
     * Pre-fire trigger time is clamped to [System.currentTimeMillis] when
     * `scheduled_start - prePadding` is in the past — handles the
     * "schedule for a programme starting in 30 seconds with 60-second
     * pre-padding" case by firing immediately.
     */
    fun arm(
        schedule: RecordingScheduleEntry,
        prePaddingMs: Long,
        postPaddingMs: Long,
    ) {
        val now = System.currentTimeMillis()
        val preFireAt = (schedule.scheduledStart - prePaddingMs).coerceAtLeast(now)
        val endFireAt = schedule.scheduledEnd + postPaddingMs
        scheduleAlarm(schedule.id, ACTION_PRE_FIRE, preFireAt)
        // End alarm is only useful if the recording will actually be in
        // flight by the time it fires — guard against absurd end times.
        if (endFireAt > preFireAt) {
            scheduleAlarm(schedule.id, ACTION_END, endFireAt)
        }
    }

    /** Cancel both alarms for a schedule. Safe to call when alarms
     *  weren't registered — `AlarmManager.cancel` is a no-op then. */
    fun cancel(scheduleId: String) {
        alarms.cancel(pendingIntentFor(scheduleId, ACTION_PRE_FIRE))
        alarms.cancel(pendingIntentFor(scheduleId, ACTION_END))
    }

    private fun scheduleAlarm(
        scheduleId: String,
        action: String,
        triggerMs: Long,
    ) {
        val pi = pendingIntentFor(scheduleId, action)
        val canExact =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarms.canScheduleExactAlarms()
            } else {
                true
            }
        try {
            if (canExact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            } else {
                // Inexact fallback — fires within Doze maintenance windows.
                // A few-minute delay is acceptable; a refused-create is not
                // (would silently lose the schedule).
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            }
        } catch (t: SecurityException) {
            // Some OEMs revoke USE_EXACT_ALARM despite manifest declaration.
            // Fall back to inexact rather than letting the schedule-create
            // flow crash on the user.
            Log.w(TAG, "exact alarm denied for $scheduleId/$action; falling back to inexact", t)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    private fun pendingIntentFor(
        scheduleId: String,
        action: String,
    ): PendingIntent {
        val intent =
            Intent(context, RecordingScheduleReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            }
        // Stable per (scheduleId, action). Different actions on the same
        // schedule get distinct slots so cancel(scheduleId) can wipe both.
        val requestCode = (scheduleId + "::" + action).hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    companion object {
        private const val TAG = "YancoSchedAlarm"

        /** Pre-fire alarm — receiver decides path (tee / switch+tee /
         *  missed) and starts the RecordingService. */
        const val ACTION_PRE_FIRE = "com.yancotv.android.action.SCHEDULE_PRE_FIRE"

        /** End alarm — receiver tells RecordingService to stop the
         *  linked recording id. */
        const val ACTION_END = "com.yancotv.android.action.SCHEDULE_END"

        const val EXTRA_SCHEDULE_ID = "schedule_id"
    }
}
