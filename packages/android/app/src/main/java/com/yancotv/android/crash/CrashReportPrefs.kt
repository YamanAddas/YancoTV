package com.yancotv.android.crash

import android.content.Context
import android.content.SharedPreferences

/**
 * Stage 5.6 / privacy posture — user-toggleable opt-out for the Sentry
 * crash + breadcrumb pipeline.
 *
 * **Why SharedPreferences and not [com.yancotv.android.prefs.AppPreferences]:**
 * `SentryInit.install` runs from `YancoApp.onCreate` BEFORE
 * `startKoin`, so the rest of the app's pref store (which is
 * Koin-injected) isn't ready yet. SharedPreferences is plain Context
 * I/O — available from any Context the moment the application object
 * exists. Each Sentry event also queries this on its `beforeSend`
 * callback, so we want a synchronous read, not a Flow.
 *
 * Default is `enabled = true`. Crash reports help us catch regressions
 * fast; a small fraction of users will opt out and that's expected.
 * The toggle in `Settings → About` flips this; future Sentry events
 * are dropped at the SDK boundary the moment it changes.
 *
 * Stored under the same SharedPreferences file as `CrashReporter`'s
 * own crumbs (the `crash` namespace) so all crash-reporting state
 * sits in one place.
 */
object CrashReportPrefs {
    private const val PREFS_NAME = "yanco_crash"
    private const val KEY_ENABLED = "crash_reports_enabled"
    private const val DEFAULT_ENABLED = true

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Lets UI surfaces register a listener so an external change (e.g.
     * the Settings → About toggle) propagates without a manual reload.
     * Returns the listener for caller-side bookkeeping; the caller is
     * responsible for unregistering when its scope ends.
     */
    fun observe(
        context: Context,
        onChanged: (Boolean) -> Unit,
    ): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                if (key == KEY_ENABLED) onChanged(p.getBoolean(key, DEFAULT_ENABLED))
            }
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun unregister(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
