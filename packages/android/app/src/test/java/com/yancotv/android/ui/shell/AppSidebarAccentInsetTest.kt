package com.yancotv.android.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the sidebar accent-bar padding math.
 *
 * The sidebar's selected-section accent bar shrinks from full height down
 * to 0 using a spring animation. The raw spring progress can overshoot
 * past 1.0 (damping < 1), which was silently producing a negative Dp
 * value passed into `Modifier.padding` — Compose rejects that with
 * `IllegalArgumentException: Padding must be non-negative` and the app
 * dies on every section change.
 *
 * See AppSidebar.kt, 2026-04-22 fix. Every launch had to be followed by
 * navigating to Guide / Movies→Series / Series→Favorites to trigger
 * the crash; the logcat trace always pointed at AppSidebar.kt:220.
 */
class AppSidebarAccentInsetTest {

    @Test fun zeroProgressGivesFullInset() {
        assertEquals(1f, accentInsetFraction(0f))
    }

    @Test fun oneProgressGivesZeroInset() {
        assertEquals(0f, accentInsetFraction(1f))
    }

    @Test fun halfProgressGivesHalfInset() {
        assertEquals(0.5f, accentInsetFraction(0.5f))
    }

    @Test fun springOvershootPastOneIsClampedToZero() {
        // A 20% overshoot is a realistic figure for dampingRatio = 0.8 —
        // this is the exact condition that was crashing the app.
        assertEquals(0f, accentInsetFraction(1.2f))
    }

    @Test fun springOvershootBelowZeroIsClampedToOne() {
        // Reversal animations can briefly dip below zero. That would send
        // the inset above 1.0 (larger than the parent box) — not a crash
        // but a visual glitch. Clamp both ends.
        assertEquals(1f, accentInsetFraction(-0.1f))
    }

    @Test fun extremeNegativeProgressIsClampedToOne() {
        assertEquals(1f, accentInsetFraction(-100f))
    }

    @Test fun extremePositiveProgressIsClampedToZero() {
        assertEquals(0f, accentInsetFraction(100f))
    }

    @Test fun resultIsAlwaysInZeroToOneInclusive() {
        // Sweep the raw spring range that animateFloatAsState can produce
        // under realistic damping. No value should escape [0, 1].
        val samples = (-50..150).map { it / 100f }
        for (progress in samples) {
            val inset = accentInsetFraction(progress)
            assertTrue(inset in 0f..1f, "inset=$inset out of range at progress=$progress")
        }
    }
}
