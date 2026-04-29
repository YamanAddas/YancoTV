package com.yancotv.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yancotv.android.BuildConfig
import io.sentry.Sentry
import io.sentry.SentryLevel

/**
 * Debug-only verification entry point for the Sentry wire-up. Fires:
 *
 *  1. A Sentry message at INFO level — exercises [Sentry.captureMessage].
 *     Lands in the dashboard's Issues view as a `<message>` event.
 *  2. A Sentry exception capture with a synthetic throwable — exercises
 *     [Sentry.captureException] and the symbolication path. Lands as an
 *     event with a stack trace.
 *
 * After triggering, watch the dashboard at
 * https://catbyte.sentry.io/projects/yancotv-androidtv/ — events typically
 * arrive within ~5 seconds on a healthy network.
 *
 * Trigger:
 *   adb shell am broadcast -a com.yancotv.android.debug.SENTRY_SMOKE_TEST
 *
 * Production builds never see this receiver — it's declared in
 * `app/src/debug/AndroidManifest.xml` and excluded from release variants
 * by AGP's manifest merger.
 */
class SentrySmokeTestReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "YancoSentrySmoke"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!Sentry.isEnabled()) {
            Log.w(TAG, "Sentry is not enabled — DSN missing in local.properties?")
            return
        }
        Log.i(TAG, "Firing Sentry smoke test events…")

        Sentry.captureMessage(
            "YancoTV Sentry smoke test (message) — version ${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
            SentryLevel.INFO,
        )

        try {
            throw SentrySmokeTestException(
                "YancoTV Sentry smoke test (exception) — captured stack trace, no real crash",
            )
        } catch (t: Throwable) {
            Sentry.captureException(t)
        }

        Log.i(TAG, "Sentry smoke test events queued — check dashboard")
    }

    /**
     * Distinct named class so the smoke test events are easy to filter
     * out of the Issues view (search by `error.type:SentrySmokeTestException`).
     */
    private class SentrySmokeTestException(message: String) : RuntimeException(message)
}
