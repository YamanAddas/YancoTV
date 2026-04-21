package com.yancotv.android

import android.app.Application
import coil3.SingletonImageLoader
import com.yancotv.android.di.appModule
import com.yancotv.android.reminders.ReminderNotificationChannel
import com.yancotv.android.sync.EpgSyncWorker
import com.yancotv.android.ui.image.buildYancoImageLoader
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class YancoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@YancoApp)
            modules(appModule)
        }
        SingletonImageLoader.setSafe { buildYancoImageLoader(this) }
        // Enqueue the 6-hour periodic EPG refresh. KEEP semantics mean a
        // reinstall or app upgrade doesn't reset the window, and the first
        // run still happens quickly because WorkManager fires periodic work
        // in its first flex period.
        EpgSyncWorker.schedulePeriodic(this)
        // Reminders need a notification channel registered once per install.
        ReminderNotificationChannel.ensureCreated(this)
    }
}
