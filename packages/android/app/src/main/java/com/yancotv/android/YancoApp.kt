package com.yancotv.android

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import android.os.StrictMode
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkManager
import coil3.SingletonImageLoader
import com.yancotv.android.crash.CrashReporter
import com.yancotv.android.crash.SentryInit
import com.yancotv.android.di.appModule
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.recording.schedule.RecordingScheduleScheduler
import com.yancotv.android.reminders.ReminderNotificationChannel
import com.yancotv.android.sync.EpgSyncWorker
import com.yancotv.android.ui.image.buildYancoImageLoader
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.http.CleartextAllowlistInterceptor
import com.yancotv.shared.recording.RecordingScheduleRepository
import com.yancotv.shared.recording.RecordingsRepository
import com.yancotv.shared.types.ContentType
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

@UnstableApi
class YancoApp : Application() {
    private val playbackController: PlaybackController by inject()
    private val recordingsRepo: RecordingsRepository by inject()
    private val scheduleRepo: RecordingScheduleRepository by inject()
    private val scheduler: RecordingScheduleScheduler by inject()
    private val contentRepo: ContentRepository by inject()
    private val sharedHttpClient: okhttp3.OkHttpClient by inject()
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

        // MK.SEC.B carve-out — Coil bypasses the cleartext allow-list so
        // plain-http channel logos (tvg-logo, served from CDNs outside the
        // user's source hosts) load instead of being blanked by the 469.
        // Images carry no credentials; every credential-bearing path
        // (provider API, streams, EPG, update download) keeps the gate via
        // the shared client. newBuilder() shares the connection pool +
        // dispatcher; only the allow-list interceptor is dropped. See
        // AGENTS.md "Cleartext traffic (Android)" + docs/security/AUDIT_NOTES.md.
        val imageHttpClient =
            sharedHttpClient
                .newBuilder()
                .apply { interceptors().removeAll { it is CleartextAllowlistInterceptor } }
                .build()
        SingletonImageLoader.setSafe { buildYancoImageLoader(this, imageHttpClient) }
        EpgSyncWorker.schedulePeriodic(this)
        // Stage 5.2.2 — sideload update check. The worker honors the
        // user's auto-check pref (no-ops when disabled), so it's safe
        // to schedule unconditionally. cancelPeriodic() runs from the
        // Settings → About toggle when the user opts out.
        com.yancotv.android.update.UpdateCheckWorker.schedulePeriodic(this)
        // MK.30.4 — plus a launch-time check, same reasoning as the
        // Recommendations one-shot below. schedulePeriodic uses KEEP, so on an
        // established install the 24h window survives restarts and no check
        // runs at launch; since UpdateRepository.info is in-memory, the
        // sidebar badge and About banner would then sit blank for up to a day
        // after a release went out. Throttled to once per 6h inside the worker.
        com.yancotv.android.update.UpdateCheckWorker.enqueueStartupCheck(this)
        // MK.10.1 — keep the launcher Recommendations channel current.
        // Periodic refresh + a one-shot so the channel exists on first
        // boot without waiting 6 hours.
        com.yancotv.android.recommendations.RecommendationsWorker.enqueuePeriodic(this)
        com.yancotv.android.recommendations.RecommendationsWorker.enqueueOnce(this)
        ReminderNotificationChannel.ensureCreated(this)
        // Stage 3.1 / MK.14.1c — sweep any RECORDING-status rows that
        // outlived a crash / process death. The repo flips orphans
        // (started_at older than the default 10 min threshold) to
        // FAILED('orphaned_by_app_kill') so the UI doesn't show a
        // stuck row forever. IO-bound; off the main thread.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { recordingsRepo.sweepOrphans() }
        }
        // MK.26.B (audit CAST-DISK-5) — reclaim an orphaned cast-proxy cache. If
        // the process was killed mid-cast (OOM / force-stop / crash) CastProxy.stop
        // never ran, so a whole transcoded movie (potentially GBs) can sit in
        // cacheDir/cast-proxy until the next cast. No cast can be active at process
        // start, so this is race-free. IO-bound; off the main thread.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { java.io.File(cacheDir, "cast-proxy").deleteRecursively() }
        }
        // **MK.REC.RESILIENCE 2026-05-15.** Cold-start schedule
        // reconciliation. Mirrors what RecordingScheduleBootReceiver
        // does on BOOT_COMPLETED, but runs on every cold app start so
        // we're robust to Fire TV dropping BOOT_COMPLETED for
        // non-system apps (a known Fire OS behaviour) and to the
        // receiver itself crashing.
        //
        // Order matters: reconcile FIRST so the rescheduleAll pass
        // doesn't re-arm rows we just transitioned to MISSED. Both
        // operations are idempotent — `reconcileAfterBoot` only
        // touches rows whose start window has actually passed, and
        // `rescheduleAll` uses `FLAG_UPDATE_CURRENT` on its
        // PendingIntents so re-arming an already-armed alarm is a
        // no-op replacement.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { scheduleRepo.reconcileAfterBoot() }
                .onSuccess { swept ->
                    if (swept.total > 0) {
                        android.util.Log.i(
                            "YancoApp",
                            "cold-start schedule reconcile: missed=${swept.markedMissed}, " +
                                "orphaned-firing=${swept.markedFailedFromOrphan}",
                        )
                    }
                }
                .onFailure {
                    android.util.Log.w("YancoApp", "cold-start reconcileAfterBoot failed", it)
                }
            runCatching { scheduler.rescheduleAll() }
                .onFailure {
                    android.util.Log.w("YancoApp", "cold-start rescheduleAll failed", it)
                }
        }
        // Pre-warm the FTS index pages so the first search after launch
        // doesn't pay flash-storage page-in latency. A 1-row query against
        // each content type's slice is enough to walk the FTS B-tree
        // headers and pull the hottest pages into the OS file cache.
        // Off main thread, best-effort — failures are silent because an
        // empty DB or missing FTS rows is normal on first install.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                contentRepo.searchByType("a", ContentType.LIVE, limit = 1)
                contentRepo.searchByType("a", ContentType.MOVIE, limit = 1)
                contentRepo.searchByType("a", ContentType.SERIES, limit = 1)
            }
        }
        // Drop SUCCEEDED/FAILED WorkInfo records that survive across app
        // restarts. GuideSyncPanel observes the unique-work flow and the
        // accumulated history slowly grows the in-memory list it renders
        // from. pruneWork() only touches finished, dependency-free entries
        // so in-flight syncs are unaffected.
        WorkManager.getInstance(this).pruneWork()

        // MK.24.I.7 / MB-230 — heap-watermark Sentry breadcrumb. Polls
        // Runtime memory every 60s; when used/max crosses thresholds, emits
        // a breadcrumb (>75%) or a Sentry event (>90%) so future heap-pressure
        // occurrences self-report. Pre-fix MB-230 hit 376/384 MB (98%) without
        // any Sentry event firing — the app just stalled silently. With this
        // probe the next occurrence captures rich context BEFORE the stall.
        // Single coroutine on a SupervisorJob so a transient Sentry failure
        // doesn't kill the watcher; never cancelled (process-scoped).
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            startHeapWatermarkProbe()
        }

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

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

                override fun onActivityResumed(activity: Activity) {}

                override fun onActivityPaused(activity: Activity) {}

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

                override fun onActivityDestroyed(activity: Activity) {}
            },
        )
    }

    /**
     * MK.24.I.7 / MB-230 — heap-watermark probe. Polls every 60 s; emits a
     * Sentry breadcrumb when used/max heap crosses 75% and a Sentry event
     * (warning level) when it crosses 90%. Each event carries the runtime
     * snapshot (used MB / max MB / GC counts since process start) so we
     * can correlate against navigation patterns or background work.
     *
     * Hysteresis so we don't spam: only re-emit when the watermark CHANGES
     * tier (e.g. NONE → WATCH on first cross of 75%, WATCH → ALERT on first
     * cross of 90%, ALERT → WATCH when it drops below 90% again). A
     * sustained ALERT state stays at one event per crossing, not one per
     * 60s tick.
     *
     * Pre-fix MB-230 saw the process at 376/384 MB (98%) for tens of
     * seconds without Sentry firing once — there was no probe at all. With
     * this in place, the next occurrence's ALERT event lands in Sentry
     * with breadcrumbs from the preceding 60s, which is enough to ask the
     * user to capture an hprof.
     */
    private suspend fun startHeapWatermarkProbe() {
        var tier = HeapTier.NONE
        while (true) {
            delay(60_000L)
            if (!Sentry.isEnabled()) continue
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            val max = rt.maxMemory()
            val pct = used.toDouble() / max * 100
            val newTier =
                when {
                    pct >= 90.0 -> HeapTier.ALERT
                    pct >= 75.0 -> HeapTier.WATCH
                    else -> HeapTier.NONE
                }
            if (newTier == tier) continue
            tier = newTier
            val usedMb = used / (1024 * 1024)
            val maxMb = max / (1024 * 1024)
            val msg = "Heap watermark $newTier: ${pct.toInt()}% (${usedMb}MB / ${maxMb}MB)"
            when (newTier) {
                HeapTier.WATCH -> {
                    val crumb =
                        Breadcrumb().apply {
                            this.level = SentryLevel.WARNING
                            this.category = "memory"
                            this.message = msg
                            setData("heap_pct", pct.toInt())
                            setData("heap_used_mb", usedMb)
                            setData("heap_max_mb", maxMb)
                        }
                    Sentry.addBreadcrumb(crumb)
                }
                HeapTier.ALERT -> {
                    Sentry.captureMessage(msg, SentryLevel.WARNING)
                }
                HeapTier.NONE -> {
                    val crumb =
                        Breadcrumb().apply {
                            this.level = SentryLevel.INFO
                            this.category = "memory"
                            this.message = "Heap watermark recovered: ${pct.toInt()}% (${usedMb}MB / ${maxMb}MB)"
                            setData("heap_pct", pct.toInt())
                        }
                    Sentry.addBreadcrumb(crumb)
                }
            }
        }
    }

    private enum class HeapTier { NONE, WATCH, ALERT }

    /**
     * MK.24.I.7 / MB-230 — release reclaimable caches on system memory pressure.
     *
     * Captured 2026-04-28 on Fire TV: an `am send-trim-memory RUNNING_CRITICAL`
     * released only -0.5 MB Java heap with Graphics + Native unchanged because
     * the app had no `onTrimMemory` override. Coil's bitmap cache (32 MB cap,
     * but native-allocated decoded bitmaps push the Graphics column much
     * higher) was holding everything regardless of system pressure. Over a
     * long session that contributed to the 376/384 MB heap-thrash state where
     * GC ran 101 times back-to-back freeing 0 bytes and every coroutine
     * stalled.
     *
     * Levels:
     *   - `RUNNING_MODERATE` (5) — system caches getting low, app still in
     *     foreground. Mild trim — halve the Coil memory cache.
     *   - `RUNNING_LOW` (10) — system needs memory now, app still foreground.
     *     Halve again.
     *   - `RUNNING_CRITICAL` (15) — system about to start killing background
     *     processes. Drop the entire Coil memory cache (disk cache survives;
     *     re-decode on next request).
     *   - `BACKGROUND` (40) / `MODERATE` (60) / `COMPLETE` (80) — app is
     *     backgrounded; system may kill us. Drop everything.
     *
     * The disk cache is never cleared here — it's a separate budget and
     * surviving it lets logos re-cache fast on the next foreground.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val loader =
            runCatching { SingletonImageLoader.get(this) }.getOrNull() ?: return
        val cache = loader.memoryCache ?: return
        val before = cache.size
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            -> {
                cache.trimToSize(cache.size / 2)
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            -> {
                cache.clear()
            }
            else -> return
        }
        // Sentry breadcrumb so we can confirm trim activity in the wild
        // (paired with the F3 heap-watermark probe).
        if (Sentry.isEnabled()) {
            val crumb =
                Breadcrumb().apply {
                    this.level = SentryLevel.INFO
                    this.category = "memory"
                    this.message =
                        "onTrimMemory level=$level — Coil cache trimmed: ${before / 1024}KB → ${cache.size / 1024}KB"
                    setData("trim_level", level)
                    setData("coil_before_kb", before / 1024)
                    setData("coil_after_kb", cache.size / 1024)
                }
            Sentry.addBreadcrumb(crumb)
        }
    }
}
