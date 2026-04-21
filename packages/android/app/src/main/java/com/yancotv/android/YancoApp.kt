package com.yancotv.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@YancoApp)
            modules(appModule)
        }
        SingletonImageLoader.setSafe { buildYancoImageLoader(this) }
        EpgSyncWorker.schedulePeriodic(this)
        ReminderNotificationChannel.ensureCreated(this)

        // Pause playback whenever the last visible Activity stops — pressing
        // Home or switching apps should silence the stream immediately. We
        // dropped the MediaSessionService (MK.9.5), so Media3's automatic
        // background-state machine is gone; this is the replacement. Count
        // started activities rather than listening to a single one so the
        // mini→fullscreen handoff (both started briefly) doesn't trip it.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities <= 0) {
                    startedActivities = 0
                    playbackController.player.pause()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
