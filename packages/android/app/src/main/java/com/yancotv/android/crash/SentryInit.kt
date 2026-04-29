package com.yancotv.android.crash

import android.content.Context
import android.util.Log
import co.touchlab.kermit.Logger
import co.touchlab.kermit.platformLogWriter
import com.yancotv.android.BuildConfig
import io.sentry.android.core.SentryAndroid

/**
 * Sentry bootstrap. Called from [com.yancotv.android.YancoApp.onCreate]
 * very early — after [CrashReporter.install] (so a Sentry init failure
 * still gets local-disk-dumped) but before [org.koin.core.context.startKoin]
 * (so any Koin-startup crash is reported).
 *
 * Init is silently skipped when [BuildConfig.SENTRY_DSN] is empty — a
 * clean checkout without `sentry.dsn` in `local.properties` doesn't fail
 * to launch, just runs without remote crash reporting. The local
 * [CrashReporter] still captures crashes into `filesDir/crash.log`.
 *
 * After init, the Kermit global logger gets a [SentryKermitWriter] added
 * alongside its platform default — every `Log.l.x { ... }` call in shared
 * KMP code becomes either a Sentry breadcrumb (INFO / WARN / DEBUG) or a
 * captured event (ERROR / ASSERT).
 */
object SentryInit {
    private const val TAG = "YancoSentry"

    fun install(context: Context) {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) {
            Log.i(TAG, "Sentry DSN absent — remote crash reporting disabled")
            return
        }

        // Prime the opt-out cache once before Sentry init so the hot-path
        // beforeSend / beforeBreadcrumb callbacks read from memory instead
        // of hitting SharedPreferences on every event (was the source of
        // a StrictMode disk-read warning during cold start).
        CrashReportPrefs.prime(context)

        SentryAndroid.init(context) { options ->
            options.dsn = dsn
            // Stage 5.6 — privacy opt-out. The user can toggle off
            // crash reports in Settings → About. We initialize Sentry
            // unconditionally so the SDK is ready if the user later
            // opts back in, but every event passes through this gate
            // first; a `null` return drops the event before it leaves
            // the device. Cached @Volatile read — setEnabled (the only
            // writer) updates the cache atomically before the disk
            // write, so the gate flips immediately when the user
            // toggles the setting.
            options.setBeforeSend { event, _ ->
                if (CrashReportPrefs.isEnabledCached()) event else null
            }
            options.setBeforeBreadcrumb { breadcrumb, _ ->
                if (CrashReportPrefs.isEnabledCached()) breadcrumb else null
            }
            // Release tag groups events by app version in the Sentry UI.
            // Format matches Sentry's expectations: package@versionName+versionCode.
            options.release =
                "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
            // Performance monitoring is off — Stage 1.3 only does crash +
            // error reporting per the plan ("Crash reporting, error
            // breadcrumbs, network failure tracking"). Tracing has its own
            // CPU/network cost and we'd want to budget it deliberately.
            options.tracesSampleRate = 0.0
            // Privacy: no PII flag captured, no IP addresses sent.
            options.isSendDefaultPii = false
            // Debug logging in dev so init issues surface in `adb logcat -s
            // Sentry`. Off in release so release builds don't chatty-log.
            options.isDebug = BuildConfig.DEBUG

            // ANR detection — explicit (the meta-package's default is
            // conservative on Fire TV / API 28, which falls back to the
            // in-process watchdog AnrIntegration). 2026-04-25 Stage 1.2
            // verification hit a real ANR that wasn't captured; these
            // settings make the legacy detector fire reliably and report
            // in debug builds (default suppresses ANR reports under a
            // debugger to avoid breakpoint false-positives).
            options.isAnrEnabled = true
            options.anrTimeoutIntervalMillis = 5_000L
            options.isAnrReportInDebug = true
            // Thread dumps attached to ANR events so we can see what was
            // blocking the main thread, not just that it was blocked.
            options.isAttachThreads = true
        }

        // Wire shared Kermit -> Sentry. setLogWriters replaces the writer
        // list, so we re-add the platform default (Logcat on Android) so
        // existing log statements still print to logcat.
        Logger.setLogWriters(platformLogWriter(), SentryKermitWriter())

        Log.i(TAG, "Sentry initialised — env=${if (BuildConfig.DEBUG) "debug" else "release"}")
    }
}
