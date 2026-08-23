package com.yancotv.android.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.R

/** Registers the notification channel used by reminder alarms. Idempotent. */
@androidx.annotation.OptIn(UnstableApi::class)
object ReminderNotificationChannel {
    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(ReminderAlarmReceiver.CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                ReminderAlarmReceiver.CHANNEL_ID,
                context.getString(R.string.rem_channel_name),
                // HIGH so Fire TV shows a toast overlay. Users can downgrade in
                // system settings if they find it too intrusive.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.rem_channel_description)
            }
        manager.createNotificationChannel(channel)
    }
}
