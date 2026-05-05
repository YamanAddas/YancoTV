package com.yancotv.android.sync

import com.yancotv.android.prefs.EpgPrefs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * MK.EPG.E — pin [EpgSyncWorker.computeStaleCutoffSeconds].
 *
 * Pre-MK.EPG.E the worker hardcoded a 24h stale window; users who
 * cranked `EpgPrefs.daysBack` up past 1 day saw "grey areas" appear
 * in the Guide on every periodic refresh as the sweep dropped
 * programmes that were still supposed to render.
 *
 * The fix moves cutoff math into a pure function. These tests pin
 * its three contract clauses:
 *
 *   * daysBack=0 → keep CATCHUP_BASELINE_HOURS + safety margin (no
 *     regression to pre-MK.EPG.E behaviour for fresh installs).
 *   * daysBack>0 → user's pref is the floor (the bug fix).
 *   * Negative input is treated as 0 (defensive — no slider change
 *     can underflow the sweep into "delete everything").
 *
 * Pure JVM tests; no Robolectric, no Worker harness, no Koin.
 */
class EpgSyncWorkerCutoffTest {
    // Arbitrary fixed wall-clock anchor so assertions are stable.
    // 2024-10-27T00:53:20Z — picked so dividing by 1000 lands cleanly.
    private val nowMillis = 1_730_000_000_000L
    private val nowSeconds = nowMillis / 1_000L
    private val secondsPerDay = 86_400L

    // ───── daysBack = 0 (default fresh install) ─────

    @Test fun daysBackZeroKeepsCatchupBaselinePlusOneDayMargin() {
        val cutoff = EpgSyncWorker.computeStaleCutoffSeconds(nowMillis, daysBack = 0)
        // CATCHUP_BASELINE_HOURS (2h) + 1 day margin.
        val expectedKeep = EpgPrefs.CATCHUP_BASELINE_HOURS.toLong() * 3_600L + secondsPerDay
        assertEquals(nowSeconds - expectedKeep, cutoff)
    }

    @Test fun daysBackZeroKeepsAtLeastOneDayMatchingPreFixBehaviour() {
        // Sanity check: daysBack=0 still keeps ≥ 24h of history. Pre-fix
        // hardcoded floor was 24h; fresh installs must not regress
        // below that even if CATCHUP_BASELINE_HOURS shrinks.
        val cutoff = EpgSyncWorker.computeStaleCutoffSeconds(nowMillis, daysBack = 0)
        val keep = nowSeconds - cutoff
        assertTrue(keep >= secondsPerDay, "fresh installs must keep ≥ 24h history; got $keep s")
    }

    // ───── daysBack > 0 — user's pref is the floor ─────

    @Test fun daysBackOneKeepsTwoDays() {
        // daysBack=1 → 1 day floor + 1 day margin = 2 days kept.
        val cutoff = EpgSyncWorker.computeStaleCutoffSeconds(nowMillis, daysBack = 1)
        assertEquals(nowSeconds - 2L * secondsPerDay, cutoff)
    }

    @Test fun daysBackSevenKeepsEightDays() {
        // The bug-driving case: user wanted 7 days of catchup, sweep
        // wiped everything past 1 day. Now: 7 days + margin = 8 days.
        val cutoff = EpgSyncWorker.computeStaleCutoffSeconds(nowMillis, daysBack = 7)
        assertEquals(nowSeconds - 8L * secondsPerDay, cutoff)
    }

    @Test fun daysBackFourteenKeepsFifteenDays() {
        // Slider's max value — same shape as the 7-day case, just at
        // the upper bound. No silent clamp/cap.
        val cutoff = EpgSyncWorker.computeStaleCutoffSeconds(nowMillis, daysBack = 14)
        assertEquals(nowSeconds - 15L * secondsPerDay, cutoff)
    }

    // ───── Defensive clamping ─────

    @Test fun negativeDaysBackTreatedAsZero() {
        val expected = EpgSyncWorker.computeStaleCutoffSeconds(nowMillis, daysBack = 0)
        val actual = EpgSyncWorker.computeStaleCutoffSeconds(nowMillis, daysBack = -3)
        assertEquals(expected, actual, "negative daysBack must be coerced to 0 — no underflow")
    }

    @Test fun cutoffAlwaysInPastForEveryValidPrefValue() {
        // The slider's [0, 14] range exhaustively. Cutoff must always
        // be < now or `deleteStale` would purge future-dated rows
        // (e.g. tomorrow's EPG that just landed).
        for (db in 0..14) {
            val cutoff = EpgSyncWorker.computeStaleCutoffSeconds(nowMillis, daysBack = db)
            assertTrue(cutoff < nowSeconds, "cutoff must be in the past for daysBack=$db; got cutoff=$cutoff now=$nowSeconds")
        }
    }

    // ───── Monotonicity — bigger daysBack means earlier cutoff ─────

    @Test fun cutoffMonotonicallyDecreasesAsDaysBackGrows() {
        // Sweeping with a larger window must move the cutoff strictly
        // backwards (or hold steady at the baseline floor). A regression
        // that flipped the comparison would silently wipe more history
        // for users who increased the slider.
        var prev = Long.MAX_VALUE
        for (db in 0..14) {
            val cutoff = EpgSyncWorker.computeStaleCutoffSeconds(nowMillis, daysBack = db)
            assertTrue(cutoff <= prev, "cutoff must be non-increasing in daysBack; daysBack=$db gave $cutoff vs prev=$prev")
            prev = cutoff
        }
    }
}
