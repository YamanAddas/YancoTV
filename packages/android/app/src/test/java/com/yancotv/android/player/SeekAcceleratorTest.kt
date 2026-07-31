package com.yancotv.android.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MB-338 — [SeekAccelerator] contract.
 *
 * The defect being fixed: LEFT/RIGHT seeked a flat 10s on every key-down with no
 * `repeatCount` filter, so OS auto-repeat produced a series of discrete 10s
 * lurches instead of an accelerating scrub.
 *
 * Negative control (required by docs/FABLE5_SESSION_PLAN.md §3): removing the
 * `repeatCount == 0` branch from [SeekAccelerator.deltaSecondsForTick] — i.e.
 * always consulting the tiers — turns `a fresh press is always one base step`
 * red. Removing the tier lookup instead (always returning BASE_STEP_SEC, the
 * pre-fix behaviour) turns every `holding escalates…` test red. Both were run.
 */
class SeekAcceleratorTest {
    // ───── Tap behaviour must not change ─────

    @Test
    fun `a fresh press is always one base step regardless of a stale hold stamp`() {
        // repeatCount == 0 is a new gesture. Even if a previous gesture left a
        // large elapsed value lying around, the first press must be 10s — this
        // is the muscle-memory guarantee, and the whole reason repeatCount is
        // consulted before the tiers.
        assertEquals(10, SeekAccelerator.deltaSecondsForTick(forward = true, repeatCount = 0, holdElapsedMs = 0L))
        assertEquals(10, SeekAccelerator.deltaSecondsForTick(forward = true, repeatCount = 0, holdElapsedMs = 99_000L))
        assertEquals(-10, SeekAccelerator.deltaSecondsForTick(forward = false, repeatCount = 0, holdElapsedMs = 99_000L))
    }

    @Test
    fun `direction only flips the sign, never the magnitude`() {
        for (elapsed in listOf(0L, 1_000L, 2_500L, 5_000L, 30_000L)) {
            val fwd = SeekAccelerator.deltaSecondsForTick(true, repeatCount = 3, holdElapsedMs = elapsed)
            val back = SeekAccelerator.deltaSecondsForTick(false, repeatCount = 3, holdElapsedMs = elapsed)
            assertEquals(fwd, -back, "asymmetric at ${elapsed}ms: $fwd vs $back")
        }
    }

    // ───── The escalation itself ─────

    @Test
    fun `a repeat tick escalates — the delta itself, not just the tier lookup`() {
        // This test exists because the negative control caught its absence: the
        // first version of this suite asserted escalation only against
        // stepSecondsForHold, so reverting deltaSecondsForTick to a flat 10s —
        // the exact pre-MB-338 behaviour — left the whole suite GREEN. The
        // escalation has to be pinned on the function the receiver actually
        // calls, or the test proves nothing about the bug.
        assertEquals(30, SeekAccelerator.deltaSecondsForTick(true, repeatCount = 1, holdElapsedMs = 2_500L))
        assertEquals(60, SeekAccelerator.deltaSecondsForTick(true, repeatCount = 4, holdElapsedMs = 4_500L))
        assertEquals(300, SeekAccelerator.deltaSecondsForTick(true, repeatCount = 9, holdElapsedMs = 7_000L))
        assertEquals(-300, SeekAccelerator.deltaSecondsForTick(false, repeatCount = 9, holdElapsedMs = 7_000L))
        // And the thing the user reported: a sustained hold must NOT keep
        // delivering flat 10s steps.
        assertTrue(
            SeekAccelerator.deltaSecondsForTick(true, repeatCount = 8, holdElapsedMs = 5_000L) >
                SeekAccelerator.BASE_STEP_SEC,
            "a held key is still stepping at the base rate — this is the MB-338 defect",
        )
    }

    @Test
    fun `holding escalates through the tiers by elapsed time`() {
        assertEquals(10, SeekAccelerator.stepSecondsForHold(0L))
        assertEquals(10, SeekAccelerator.stepSecondsForHold(1_999L))
        assertEquals(30, SeekAccelerator.stepSecondsForHold(2_000L))
        assertEquals(30, SeekAccelerator.stepSecondsForHold(3_999L))
        assertEquals(60, SeekAccelerator.stepSecondsForHold(4_000L))
        assertEquals(60, SeekAccelerator.stepSecondsForHold(5_999L))
        assertEquals(300, SeekAccelerator.stepSecondsForHold(6_000L))
        assertEquals(300, SeekAccelerator.stepSecondsForHold(60_000L))
    }

    @Test
    fun `holding escalates only upward — the step never shrinks as the hold continues`() {
        // Monotonicity is the property that makes the gesture predictable; a
        // non-monotonic tier table would feel like the scrub stuttering.
        var previous = 0
        for (ms in 0L..8_000L step 100L) {
            val step = SeekAccelerator.stepSecondsForHold(ms)
            assertTrue(step >= previous, "step shrank at ${ms}ms: $previous -> $step")
            previous = step
        }
    }

    @Test
    fun `tier boundaries are inclusive at the lower edge`() {
        // Pins the >= comparison. An exclusive boundary would leave a one-tick
        // dead zone at each threshold where the step silently stayed low.
        assertEquals(30, SeekAccelerator.stepSecondsForHold(2_000L))
        assertEquals(60, SeekAccelerator.stepSecondsForHold(4_000L))
        assertEquals(300, SeekAccelerator.stepSecondsForHold(6_000L))
    }

    @Test
    fun `a negative or skewed elapsed time degrades to the base step`() {
        // A stale gesture stamp or a clock adjustment must never be read as a
        // very long hold and turned into a 5-minute jump.
        assertEquals(10, SeekAccelerator.stepSecondsForHold(-1L))
        assertEquals(10, SeekAccelerator.stepSecondsForHold(-500_000L))
        assertEquals(10, SeekAccelerator.deltaSecondsForTick(true, repeatCount = 9, holdElapsedMs = -1L))
    }

    // ───── Clamping — load-bearing once a tick can be 300s ─────

    @Test
    fun `clamping keeps a target inside the content`() {
        assertEquals(0L, SeekAccelerator.clampTargetMs(-5_000L, durationMs = 600_000L))
        assertEquals(600_000L, SeekAccelerator.clampTargetMs(900_000L, durationMs = 600_000L))
        assertEquals(300_000L, SeekAccelerator.clampTargetMs(300_000L, durationMs = 600_000L))
    }

    @Test
    fun `an unknown duration still clamps the lower bound`() {
        // Live, or not yet prepared: duration <= 0 means the upper bound is
        // unknowable, but seeking to a negative position is never valid.
        assertEquals(0L, SeekAccelerator.clampTargetMs(-1L, durationMs = 0L))
        assertEquals(0L, SeekAccelerator.clampTargetMs(-1L, durationMs = -1L))
        assertEquals(50_000L, SeekAccelerator.clampTargetMs(50_000L, durationMs = 0L))
    }

    @Test
    fun `the exact end of the content is a legal target`() {
        // Boundary: equal-to-duration must not be clamped down, or a
        // seek-to-end gesture would land a tick short forever.
        assertEquals(600_000L, SeekAccelerator.clampTargetMs(600_000L, durationMs = 600_000L))
    }

    @Test
    fun `a long hold cannot run off either end of a short clip`() {
        // At 300s/tick a hold overruns a 90-second clip on the first escalated
        // tick; the clamp is what stops seekTo from reporting STATE_ENDED
        // mid-gesture and advancing to the next item.
        val duration = 90_000L
        var pos = 45_000L
        repeat(20) {
            val delta = SeekAccelerator.deltaSecondsForTick(true, repeatCount = 5, holdElapsedMs = 10_000L)
            pos = SeekAccelerator.clampTargetMs(pos + delta * 1_000L, duration)
            assertTrue(pos in 0L..duration, "escaped the clip at $pos")
        }
        assertEquals(duration, pos)
    }
}
