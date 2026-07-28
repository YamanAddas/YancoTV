package com.yancotv.android.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yancotv.android.MainActivity
import com.yancotv.android.R
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.shared.update.UpdateInfo

/**
 * MK.30.4 — surfaces a found update as a system notification.
 *
 * Until now the only place an available update appeared was Settings → About,
 * which the user had to walk into and look at. [UpdateCheckWorker] already ran
 * every 24h and already knew; it just never told anyone.
 *
 * Three things this deliberately gets right:
 *
 *  1. **At most one notification per release.** The worker re-runs every 24h
 *     and keeps finding the same version, so posting unconditionally would
 *     re-nag daily until the user updated. Guarded on
 *     [AppPreferences.lastNotifiedUpdateVersion].
 *  2. **Never crashes on a missing permission.** POST_NOTIFICATIONS is
 *     runtime-granted from API 33; the app never prompts for it, so on a
 *     phone where the user declined, `notify` would throw. We check and skip
 *     — the sidebar badge still carries the signal, so nothing is lost.
 *  3. **Lands somewhere useful.** Tapping opens Settings → About, which owns
 *     the install flow, rather than dropping the user on Home to go hunting.
 */
object UpdateNotifier {
    private const val TAG = "UpdateNotifier"
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 4301

    /** Extra read by [MainActivity] to route into Settings → About. */
    const val EXTRA_OPEN_UPDATE = "com.yancotv.android.OPEN_UPDATE"

    /**
     * Posts a notification for [info] unless one has already been shown for
     * that version. Safe to call from any thread; never throws.
     */
    suspend fun notifyIfNew(context: Context, info: UpdateInfo, prefs: AppPreferences) {
        if (prefs.updatePrefsFlow.value.lastNotifiedUpdateVersion == info.versionCode) return
        if (!canPost(context)) {
            Log.i(TAG, "POST_NOTIFICATIONS not granted — skipping update notification")
            return
        }

        runCatching {
            ensureChannel(context)
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, build(context, info))
        }.onFailure { t ->
            // A failed notification must never take down the worker: the
            // check itself succeeded and the in-app surfaces already have
            // the result.
            Log.w(TAG, "Failed to post update notification", t)
            return
        }

        // Only recorded after a successful post, so a permission denial or a
        // throwing NotificationManager doesn't permanently suppress the
        // notification for that version.
        prefs.setLastNotifiedUpdateVersion(info.versionCode)
    }

    /** Clears the notification — used once the update is installed. */
    fun clear(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun build(context: Context, info: UpdateInfo): android.app.Notification {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                // SINGLE_TOP + CLEAR_TOP so tapping doesn't stack a second
                // shell on top of a running one; the intent is delivered to
                // onNewIntent instead, which is where the extra is read.
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_UPDATE, true)
            }
        val pending =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                // IMMUTABLE is required from API 31 and correct here — the
                // system has no reason to fill anything in.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val body = context.getString(R.string.update_notification_body, info.versionName)
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.update_notification_title))
                .setContentText(body)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        // Release notes, when the manifest carries them, go in the expanded
        // view rather than the collapsed line — a multi-line changelog in
        // setContentText renders as one ellipsized run.
        info.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
            builder.setStyle(
                NotificationCompat
                    .BigTextStyle()
                    .setBigContentTitle(context.getString(R.string.update_notification_title))
                    .bigText("$body\n\n${notes.trim()}"),
            )
        }
        return builder.build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel_name),
                // DEFAULT rather than LOW: dedupe means this fires at most
                // once per release, so it is not spam, and a silent shade
                // entry is easy to miss on a TV that lives on the home
                // screen. The user can still downgrade the channel in
                // system settings.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.update_channel_description)
            },
        )
    }
}
