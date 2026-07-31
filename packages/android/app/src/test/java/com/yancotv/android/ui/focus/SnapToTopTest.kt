package com.yancotv.android.ui.focus

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MK.30.6 (MB-316) + MB-336 — [shouldSnapToTop] contract.
 *
 * Found on a Chromecast with Google TV after MK.30.1 shipped: the Playback tab
 * still opened with its "Video" header clipped. A BringIntoViewSpec only sees
 * the focused element's rect, and the focusable is a chip ~290px below its
 * section's top, so no capped headroom could reveal the header — travelling up
 * unwound the scroll to ~163px and stopped there.
 *
 * MB-336 added the third argument: the settle must be the end of net-UPWARD
 * travel (`scrollValue < previousSettledValue`). The old any-direction rule
 * fired on downward travel too — on a tab whose total overflow fits inside the
 * window, every DOWN press settled inside 1..threshold, bounced the pane back
 * to 0, and pushed the focused row below the fold.
 */
class SnapToTopTest {
    // 140dp at the device's density 2 — the real measured case. The DEFAULT
    // threshold is now 200dp, but the predicate is threshold-agnostic; keeping
    // the originally-measured number keeps these cases tied to the device data.
    private val threshold = 280

    /** Settled deep, i.e. any value that makes a small settle read as upward. */
    private val fromBelow = 1_000

    @Test
    fun `the measured device case snaps when arriving from below`() {
        // ~163px left after travelling UP through the Playback tab.
        assertTrue(shouldSnapToTop(163, fromBelow, threshold))
    }

    @Test
    fun `the same position does NOT snap when arriving from above`() {
        // MB-336 — the direction gate. DOWN from the top settling at 163px is
        // deliberate downward travel; yanking back to 0 here is the bounce the
        // user reported as "sometimes it scrolls weird". previous=0 means the
        // last settle was the very top.
        assertFalse(shouldSnapToTop(163, 0, threshold))
        // And generally: settling LOWER on the page than before never snaps.
        assertFalse(shouldSnapToTop(200, 150, threshold))
    }

    @Test
    fun `a repeated settle at the same value does not snap`() {
        // Equal is not "upward". An IME toggle or recomposition can re-emit the
        // same settled value; snapping on it would loop.
        assertFalse(shouldSnapToTop(163, 163, threshold))
    }

    @Test
    fun `already at the top does not re-snap`() {
        // Would otherwise fire a pointless animation on every settle, and
        // re-enter the snapshotFlow collector forever.
        assertFalse(shouldSnapToTop(0, fromBelow, threshold))
    }

    @Test
    fun `mid-list positions are left alone even when travelling up`() {
        // The whole point: yanking a user who has scrolled deliberately would
        // be hostile. Anything past the threshold is untouched.
        assertFalse(shouldSnapToTop(threshold + 1, fromBelow, threshold))
        assertFalse(shouldSnapToTop(900, 5_000, threshold))
        assertFalse(shouldSnapToTop(50_000, 100_000, threshold))
    }

    @Test
    fun `the threshold itself is inclusive`() {
        assertTrue(shouldSnapToTop(threshold, fromBelow, threshold))
    }

    @Test
    fun `one pixel of residue still snaps`() {
        // Sub-pixel rounding in the bring-into-view animation regularly leaves
        // a value of 1-2; the header is fully revealed only at exactly 0.
        assertTrue(shouldSnapToTop(1, fromBelow, threshold))
        assertTrue(shouldSnapToTop(2, fromBelow, threshold))
    }

    @Test
    fun `a negative scroll value never snaps`() {
        // ScrollState clamps at 0 so this shouldn't occur, but the predicate
        // must not treat it as "just below the threshold" if it ever does.
        assertFalse(shouldSnapToTop(-1, fromBelow, threshold))
        assertFalse(shouldSnapToTop(-500, fromBelow, threshold))
    }

    @Test
    fun `a zero threshold disables snapping entirely`() {
        // Guards the escape hatch: passing 0.dp must be a clean opt-out, not
        // an accidental always-snap.
        assertFalse(shouldSnapToTop(0, fromBelow, 0))
        assertFalse(shouldSnapToTop(1, fromBelow, 0))
        assertFalse(shouldSnapToTop(163, fromBelow, 0))
    }

    @Test
    fun `interrupted-snap retry reads as upward against the pre-snap value`() {
        // MB-336 resilience: when animateScrollTo(0) is cancelled mid-flight by
        // a competing scroll, the collector keeps `previous` at the PRE-snap
        // settle. The next settle (wherever the interruption left the pane,
        // e.g. 60 after being cancelled on the way down from 163) must still
        // qualify so the snap retries instead of dying.
        assertTrue(shouldSnapToTop(60, 163, threshold))
    }
}
