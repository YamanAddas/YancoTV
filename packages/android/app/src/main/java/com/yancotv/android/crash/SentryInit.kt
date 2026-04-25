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

        SentryAndroid.init(context) { options ->
            options.dsn = dsn
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
        }

        // Wire shared Kermit -> Sentry. setLogWriters replaces the writer
        // list, so we re-add the platform default (Logcat on Android) so
        // existing log statements still print to logcat.
        Logger.setLogWriters(platformLogWriter(), SentryKermitWriter())

        Log.i(TAG, "Sentry initialised — env=${if (BuildConfig.DEBUG) "debug" else "release"}")
    }
}
