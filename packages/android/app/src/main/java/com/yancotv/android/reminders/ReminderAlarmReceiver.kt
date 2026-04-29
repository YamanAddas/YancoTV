package com.yancotv.android.reminders

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.MainActivity
import com.yancotv.shared.reminders.ReminderRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Fires when an [AlarmManager] trigger for a reminder arrives. Posts a
 * high-importance notification so the user gets a TV/phone toast even if
 * the app isn't in focus, and marks the reminder as fired so it doesn't
 * re-post after subsequent reboots.
 *
 * We do NOT auto-tune the channel — TV notifications are transient by
 * default; jumping to a channel uninvited would be obnoxious. The user
 * taps the notification to open the app and select the channel manually.
 */
@UnstableApi
class ReminderAlarmReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val repo: ReminderRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_FIRE) return
        val reminderId = intent.getStringExtra(ReminderScheduler.EXTRA_REMINDER_ID) ?: return
        val programmeId = intent.getStringExtra(ReminderScheduler.EXTRA_PROGRAMME_ID)
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: "Upcoming programme"

        runCatching { repo.markFired(reminderId) }
        postNotification(context, reminderId, programmeId, title)
    }

    private fun postNotification(context: Context, reminderId: String, programmeId: String?, title: String) {
        val tapIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_TAP_PROGRAMME_ID, programmeId)
            }
        // FLAG_IMMUTABLE was added in API 23 (M); minSdk = 24 so the gate is
        // dead. Targeting Android 12+ requires FLAG_IMMUTABLE on every
        // PendingIntent or the system throws on creation.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(context, reminderId.hashCode(), tapIntent, flags)

        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                // Proper app icon lands in MK.12 with the rest of the launcher
                // assets. Using the platform reminder icon keeps notifications
                // functional without polluting res/drawable with placeholder art.
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText("Starting now on YancoTV")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(reminderId.hashCode(), notification)
    }

    companion object {
        const val CHANNEL_ID = "yanco_reminders"
        const val EXTRA_TAP_PROGRAMME_ID = "programme_id"
    }
}
