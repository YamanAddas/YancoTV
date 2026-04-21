package com.yancotv.android.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.yancotv.shared.reminders.Reminder
import com.yancotv.shared.reminders.ReminderRepository
import com.yancotv.shared.types.EpgProgramme

/**
 * Pairs ReminderRepository writes with `AlarmManager` schedules so the two
 * stay in lockstep. Owning them here (not in the repository) keeps the
 * shared module free of `android.*` imports.
 *
 * Alarm guarantees we actually rely on:
 *   - setExactAndAllowWhileIdle — fires even in Doze, accurate to the minute.
 *     Without `allowWhileIdle` a TV left idle all night would miss every
 *     reminder until the screensaver broke.
 *   - PendingIntent requestCode = stable hash of programme id — replacing a
 *     reminder on the same programme replaces its alarm instead of stacking.
 */
class ReminderScheduler(
    private val context: Context,
    private val repo: ReminderRepository,
) {

    private val alarms: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun isSet(programmeId: String): Boolean = repo.forProgramme(programmeId) != null

    fun set(channelTvgId: String, programme: EpgProgramme, leadSeconds: Long = 0L): Reminder {
        val reminder = repo.upsert(channelTvgId, programme, leadSeconds)
        scheduleAlarm(reminder)
        return reminder
    }

    fun cancel(programmeId: String) {
        val existing = repo.forProgramme(programmeId)
        repo.deleteByProgrammeId(programmeId)
        if (existing != null) cancelAlarm(existing.id)
    }

    /**
     * Called from [ReminderBootReceiver] after the device boots — alarms
     * don't survive reboot, so we re-arm everything that hasn't fired yet.
     * Also purges reminders whose programme already ended while we were off.
     */
    fun rescheduleAll() {
        repo.purgeStale()
        for (r in repo.allUnfired()) {
            scheduleAlarm(r)
        }
    }

    private fun scheduleAlarm(reminder: Reminder) {
        val triggerMs = reminder.fireAt * 1000L
        if (triggerMs <= System.currentTimeMillis()) {
            // Programme already starting or past — no point scheduling; the
            // repository keeps the row so Guide UI shows the "set" state
            // until the next purgeStale pass.
            return
        }
        val pi = pendingIntentFor(reminder.id, reminder.programmeId, reminder.title)
        // API 31+ requires USE_EXACT_ALARM or user-granted SCHEDULE_EXACT_ALARM
        // for exact scheduling. We hold USE_EXACT_ALARM (manifest) which can't
        // be revoked, but on some OEM builds the check still fails — fall
        // back to an inexact while-idle alarm rather than crashing. Worst case
        // the reminder fires a few minutes late instead of to the second.
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarms.canScheduleExactAlarms()
        } else {
            true
        }
        if (canExact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    private fun cancelAlarm(reminderId: String) {
        alarms.cancel(pendingIntentFor(reminderId, programmeId = null, title = null))
    }

    private fun pendingIntentFor(
        reminderId: String,
        programmeId: String?,
        title: String?,
    ): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_REMINDER_ID, reminderId)
            if (programmeId != null) putExtra(EXTRA_PROGRAMME_ID, programmeId)
            if (title != null) putExtra(EXTRA_TITLE, title)
        }
        // FLAG_UPDATE_CURRENT so set() on the same programme replaces the
        // payload (e.g. if the user edits the lead time later).
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, reminderId.hashCode(), intent, flags)
    }

    companion object {
        const val ACTION_FIRE = "com.yancotv.android.action.REMINDER_FIRE"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_PROGRAMME_ID = "programme_id"
        const val EXTRA_TITLE = "title"
    }
}
