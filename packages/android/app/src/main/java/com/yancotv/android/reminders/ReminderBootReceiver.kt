package com.yancotv.android.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Fires once after the device finishes booting. AlarmManager entries don't
 * survive a reboot, so we re-arm every unfired reminder from the DB.
 *
 * Registered in the manifest on `BOOT_COMPLETED` (requires
 * `RECEIVE_BOOT_COMPLETED` permission). A `LOCKED_BOOT_COMPLETED` filter
 * isn't needed because the DB lives in credential-encrypted storage and
 * isn't readable until the user unlocks the device anyway.
 */
@UnstableApi
class ReminderBootReceiver : BroadcastReceiver(), KoinComponent {

    private val scheduler: ReminderScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        scheduler.rescheduleAll()
    }
}
