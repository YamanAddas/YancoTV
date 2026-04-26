package com.yancotv.android.player

import android.os.SystemClock
import android.util.Log
import com.yancotv.android.BuildConfig

/**
 * Stage 1.6 — channel-zap latency probe.
 *
 * D-pad up/down handlers in [PlayerActivity] call [markZapStart] before
 * `controller.next() / previous()`. The same activity's `playerListener`
 * forwards `onRenderedFirstFrame` here. The delta — input dispatched
 * → first frame rendered — is logged to logcat as
 * `ZAP_LATENCY_MS=<n> reason=<why>`.
 *
 * Logcat-driven instrumentation (not Trace / Perfetto) because the
 * Stage 1.6 budget is "≥95% of zaps in ≤400 ms" — a coarse millisecond
 * histogram captured cheaply over hundreds of samples on real Fire TV
 * hardware. Higher-resolution tracing is overkill for that bar.
 *
 * Production builds short-circuit to a no-op via [BuildConfig.DEBUG] so
 * R8 dead-code-eliminates the body — zero runtime cost on release.
 */
object ZapLatencyTracer {
    private const val TAG = "ZapLatency"

    @Volatile private var startElapsedMs: Long = -1L

    @Volatile private var reason: String = ""

    fun markZapStart(why: String) {
        if (!BuildConfig.DEBUG) return
        startElapsedMs = SystemClock.elapsedRealtime()
        reason = why
    }

    fun onFirstFrame() {
        if (!BuildConfig.DEBUG) return
        val started = startElapsedMs
        if (started <= 0L) return
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "ZAP_LATENCY_MS=$elapsed reason=$reason")
        startElapsedMs = -1L
    }
}
