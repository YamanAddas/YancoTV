package com.yancotv.android

import android.app.Application
import androidx.media3.common.util.UnstableApi
import coil3.SingletonImageLoader
import com.yancotv.android.di.appModule
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.reminders.ReminderNotificationChannel
import com.yancotv.android.sync.EpgSyncWorker
import com.yancotv.android.ui.image.buildYancoImageLoader
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

@UnstableApi
class YancoApp : Application() {

    private val playbackController: PlaybackController by inject()

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
        // Kick off the async bind to PlaybackService. The controller buffers
        // any play() calls issued before the connection resolves so the
        // shell never has to wait — the first tap on a channel works even
        // if the service is still starting.
        playbackController.connect()
    }
}
