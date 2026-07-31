package com.yancotv.android.ui.focus

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MK.30.6 (MB-316) — [shouldSnapToTop] contract.
 *
 * Found on a Chromecast with Google TV after MK.30.1 shipped: the Playback tab
 * still opened with its "Video" header clipped. A BringIntoViewSpec only sees
 * the focused element's rect, and the focusable is a chip ~290px below its
 * section's top, so no capped headroom could reveal the header — travelling up
 * unwound the scroll to ~163px and stopped there.
 *
 * The container knows what the spec cannot: how much scroll remains. These
 * tests pin the rule that closes that last gap without touching mid-list
 * behaviour.
 */
class SnapToTopTest {
    // 140dp at the device's density 2 — the real measured case.
    private val threshold = 280

    @Test
    fun `the measured device case snaps`() {
        // ~163px left after travelling up through the Playback tab.
        assertTrue(shouldSnapToTop(163, threshold))
    }

    @Test
    fun `already at the top does not re-snap`() {
        // Would otherwise fire a pointless animation on every settle, and
        // re-enter the snapshotFlow collector forever.
        assertFalse(shouldSnapToTop(0, threshold))
    }

    @Test
    fun `mid-list positions are left alone`() {
        // The whole point: yanking a user who has scrolled deliberately would
        // be hostile. Anything past the threshold is untouched.
        assertFalse(shouldSnapToTop(threshold + 1, threshold))
        assertFalse(shouldSnapToTop(900, threshold))
        assertFalse(shouldSnapToTop(50_000, threshold))
    }

    @Test
    fun `the threshold itself is inclusive`() {
        assertTrue(shouldSnapToTop(threshold, threshold))
    }

    @Test
    fun `one pixel of residue still snaps`() {
        // Sub-pixel rounding in the bring-into-view animation regularly leaves
        // a value of 1-2; the header is fully revealed only at exactly 0.
        assertTrue(shouldSnapToTop(1, threshold))
        assertTrue(shouldSnapToTop(2, threshold))
    }

    @Test
    fun `a negative scroll value never snaps`() {
        // ScrollState clamps at 0 so this shouldn't occur, but the predicate
        // must not treat it as "just below the threshold" if it ever does.
        assertFalse(shouldSnapToTop(-1, threshold))
        assertFalse(shouldSnapToTop(-500, threshold))
    }

    @Test
    fun `a zero threshold disables snapping entirely`() {
        // Guards the escape hatch: passing 0.dp must be a clean opt-out, not
        // an accidental always-snap.
        assertFalse(shouldSnapToTop(0, 0))
        assertFalse(shouldSnapToTop(1, 0))
        assertFalse(shouldSnapToTop(163, 0))
    }
}
