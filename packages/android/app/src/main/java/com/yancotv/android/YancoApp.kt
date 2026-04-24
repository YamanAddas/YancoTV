package com.yancotv.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
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
        // StrictMode FIRST — before Koin starts, before any IO. Debug-only.
        // Native-android-mk skill: "Never call a packages/shared/ repository
        // directly from a Compose lambda. SQLDelight blocks." StrictMode
        // is the policeman for that rule. We use penaltyLog (not Death) so
        // false positives in third-party libs (Coil disk cache warm-up,
        // Media3's first preparation, WorkManager init) don't kill debug
        // builds; instead they show up as "StrictMode policy violation"
        // in logcat and we triage. Filter with `adb logcat -s StrictMode:*`.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectActivityLeaks()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .detectLeakedSqlLiteObjects()
                    .detectFileUriExposure()
                    .penaltyLog()
                    .build(),
            )
        }
        super.onCreate()
        startKoin {
            androidContext(this@YancoApp)
            modules(appModule)
        }
        SingletonImageLoader.setSafe { buildYancoImageLoader(this) }
        EpgSyncWorker.schedulePeriodic(this)
        ReminderNotificationChannel.ensureCreated(this)
        // Drop SUCCEEDED/FAILED WorkInfo records that survive across app
        // restarts. GuideSyncPanel observes the unique-work flow and the
        // accumulated history slowly grows the in-memory list it renders
        // from. pruneWork() only touches finished, dependency-free entries
        // so in-flight syncs are unaffected.
        WorkManager.getInstance(this).pruneWork()

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
