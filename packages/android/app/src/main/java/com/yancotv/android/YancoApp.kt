package com.yancotv.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import coil3.SingletonImageLoader
import com.yancotv.android.crash.CrashReporter
import com.yancotv.android.crash.SentryInit
import com.yancotv.android.di.appModule
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.reminders.ReminderNotificationChannel
import com.yancotv.android.sync.EpgSyncWorker
import com.yancotv.android.ui.image.buildYancoImageLoader
import com.yancotv.shared.recording.RecordingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

@UnstableApi
class YancoApp : Application() {
    private val playbackController: PlaybackController by inject()
    private val recordingsRepo: RecordingsRepository by inject()
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
        // Crash reporter: install BEFORE super.onCreate so crashes in Koin
        // startup are caught. filesDir is accessible because the Application
        // context is partially ready at this point even before super fires.
        CrashReporter.install(this)
        // Stage 1.3 — Sentry sits alongside the local CrashReporter. Java
        // crashes go to both (defense in depth: Sentry gets aggregated remote
        // visibility; CrashReporter survives offline). Native crashes inside
        // libffmpegJNI (FFmpeg renderer segfaults) are caught by Sentry's
        // NDK signal handler, not the Java UncaughtExceptionHandler — that's
        // the gap CrashReporter alone can't close.
        SentryInit.install(this)

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy
                    .Builder()
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
        // Read + clear the previous session's crash.log (if any) so it
        // shows up in adb logcat -s YancoCrash immediately after launch.
        CrashReporter.readAndClear(this)

        SingletonImageLoader.setSafe { buildYancoImageLoader(this) }
        EpgSyncWorker.schedulePeriodic(this)
        ReminderNotificationChannel.ensureCreated(this)
        // Stage 3.1 / MK.14.1c — sweep any RECORDING-status rows that
        // outlived a crash / process death. The repo flips orphans
        // (started_at older than the default 10 min threshold) to
        // FAILED('orphaned_by_app_kill') so the UI doesn't show a
        // stuck row forever. IO-bound; off the main thread.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { recordingsRepo.sweepOrphans() }
        }
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
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
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

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) {}

                override fun onActivityResumed(activity: Activity) {}

                override fun onActivityPaused(activity: Activity) {}

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) {}

                override fun onActivityDestroyed(activity: Activity) {}
            },
        )
    }
}
