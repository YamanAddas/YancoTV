package com.yancotv.android.crash

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel

/**
 * Bridges Kermit log output to Sentry. Installed as an additional `LogWriter`
 * on the global Kermit logger in [com.yancotv.android.YancoApp]; the platform-
 * default Logcat writer keeps running alongside, so this is purely additive.
 *
 * Mapping:
 *  - INFO / WARN / DEBUG / VERBOSE → Sentry breadcrumb on the active scope.
 *    Breadcrumbs are stored locally and ship attached to the next captured
 *    event, so they're free until something actually goes wrong.
 *  - ERROR / ASSERT with a [Throwable] → captured as a Sentry event right
 *    away. Same call surfaces a stack trace under Issues.
 *  - ERROR / ASSERT without a [Throwable] → captured as a Sentry message
 *    event (no stack trace, but still reaches Issues).
 *
 * `commonMain/` code logging via `Log.l.e { ... }` lands here too — Kermit's
 * `Logger.withTag("yanco")` is a global singleton; adding writers on the
 * Android side affects all callers in-process, including shared KMP code.
 *
 * No-op when Sentry isn't initialised. `Sentry.isEnabled()` is the flag —
 * a clean checkout with an empty DSN won't post anything.
 */
class SentryKermitWriter : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        if (!Sentry.isEnabled()) return

        val sentryLevel =
            when (severity) {
                Severity.Verbose, Severity.Debug -> SentryLevel.DEBUG
                Severity.Info -> SentryLevel.INFO
                Severity.Warn -> SentryLevel.WARNING
                Severity.Error -> SentryLevel.ERROR
                Severity.Assert -> SentryLevel.FATAL
            }

        if (severity >= Severity.Error) {
            if (throwable != null) {
                Sentry.captureException(throwable)
            } else {
                Sentry.captureMessage(message, sentryLevel)
            }
            return
        }

        val crumb =
            Breadcrumb().apply {
                this.level = sentryLevel
                this.message = message
                this.category = tag
            }
        Sentry.addBreadcrumb(crumb)
    }
}
