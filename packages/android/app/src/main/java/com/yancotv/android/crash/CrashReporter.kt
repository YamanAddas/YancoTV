package com.yancotv.android.crash

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal local crash reporter. No Crashlytics, no Sentry — owns its
 * own data, no DSN management for a personal app.
 *
 * Lifecycle:
 *   1. [install] in `YancoApp.onCreate` — wraps the default uncaught
 *      exception handler. On crash, writes a text dump to
 *      `filesDir/crash.log` then delegates to the original handler so
 *      the OS still knows to kill the process and show the system
 *      "App stopped" dialog.
 *   2. [readAndClear] called once per launch (after Kermit is
 *      initialised). If a previous crash.log exists it is logged at
 *      ERROR level then deleted, so it surfaces in the next session's
 *      `adb logcat` without accumulating across restarts.
 *
 * Why filesDir: it's the app's private internal storage — never
 * exposed via FileProvider, no permission needed, cleared on
 * uninstall. Crash data is yours only.
 *
 * Thread safety: [install] is called once from the main thread.
 * The uncaught handler fires on whichever thread crashed; File I/O
 * in a crash handler is safe because the process is dying — no other
 * thread can interfere with the write.
 */
object CrashReporter {
    private const val CRASH_FILE = "crash.log"
    private const val TAG = "YancoCrash"

    /**
     * Wrap the default uncaught exception handler. Call this early in
     * [android.app.Application.onCreate], before [super.onCreate] if
     * possible — though [filesDir] is only guaranteed accessible after
     * [super.onCreate] has run. The trade-off: a crash in Koin startup
     * (rare) won't be logged; a crash anywhere else will be.
     */
    fun install(context: Context) {
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashLog(context, thread, throwable) }
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * On next launch after a crash: log the previous crash via Kermit
     * at ERROR level (visible in `adb logcat -s YancoCrash`) and
     * delete the file so subsequent launches don't re-report it.
     * No-op when no crash occurred.
     */
    fun readAndClear(context: Context) {
        val file = File(context.filesDir, CRASH_FILE)
        if (!file.exists()) return
        val content = runCatching { file.readText() }.getOrNull() ?: return
        Log.e(TAG, "Crash from previous session:\n$content")
        file.delete()
    }

    private fun writeCrashLog(
        context: Context,
        thread: Thread,
        throwable: Throwable,
    ) {
        val timestamp =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                .format(Date())
        val body =
            buildString {
                appendLine("YancoTV crash — $timestamp")
                appendLine("Thread: ${thread.name}")
                appendLine()
                append(throwable.stackTraceToString())
            }
        // Write atomically: temp → rename. If the write is interrupted
        // mid-crash the old crash.log (if any) survives intact.
        val tmp = File(context.filesDir, "crash.log.tmp")
        tmp.writeText(body)
        tmp.renameTo(File(context.filesDir, CRASH_FILE))
    }
}
