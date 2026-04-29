package com.yancotv.android.player

import android.os.SystemClock
import android.util.Log

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
 * **Stage 5.4 update (2026-04-28):** the original `BuildConfig.DEBUG`
 * gate was removed so release-build measurement runs can capture the
 * histogram for the v1.0 perf-budget gate. Steady-state cost is one
 * volatile read + one nanosecond diff + one `Log.i` per channel zap
 * — well below the noise floor on Fire TV-class hardware. Logcat lines
 * are tagged `ZapLatency` so the user can filter them out of any
 * grep-based diagnostics.
 */
object ZapLatencyTracer {
    private const val TAG = "ZapLatency"

    @Volatile private var startElapsedMs: Long = -1L

    @Volatile private var reason: String = ""

    fun markZapStart(why: String) {
        startElapsedMs = SystemClock.elapsedRealtime()
        reason = why
    }

    fun onFirstFrame() {
        val started = startElapsedMs
        if (started <= 0L) return
        val elapsed = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "ZAP_LATENCY_MS=$elapsed reason=$reason")
        startElapsedMs = -1L
    }
}
