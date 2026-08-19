package com.yancotv.android.player

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MB-345 — [shouldResetDockAutoHide] contract.
 *
 * The bug in one line: D-pad traversal across the dock did not postpone the 4 s
 * auto-hide, so the dock could vanish while the user was still travelling to the
 * control they wanted. Confirmed on a Fire TV while verifying MB-343 — see the
 * function KDoc for why the original "not a defect" refutation missed it.
 *
 * Negative control (run): dropping the `repeatCount == 0` line turns `an
 * auto-repeat does not keep the dock open forever` red; dropping the
 * `action != ACTION_DOWN` line turns `key-up does not reset` red; dropping the
 * `!dockVisible` line turns `a hidden dock is never kept alive` red. Ran all
 * three: 4 tests, exactly 1 failed each time, and a different one each time — so
 * no guard is redundant and none is doing another's job.
 */
class DockAutoHideTest {
    @Test
    fun `a fresh key press while the dock is up postpones the hide`() {
        // The reported case: RIGHT to traverse toward NEXT must buy more time.
        assertTrue(shouldResetDockAutoHide(dockVisible = true, action = KeyEvent.ACTION_DOWN, repeatCount = 0))
    }

    @Test
    fun `key-up does not reset`() {
        // Every press delivers DOWN then UP; resetting on both would double the
        // work and buy nothing.
        assertFalse(shouldResetDockAutoHide(dockVisible = true, action = KeyEvent.ACTION_UP, repeatCount = 0))
    }

    @Test
    fun `an auto-repeat does not keep the dock open forever`() {
        // A held key must not pin the dock open. Traversal is discrete presses,
        // so this costs the fix nothing.
        assertFalse(shouldResetDockAutoHide(dockVisible = true, action = KeyEvent.ACTION_DOWN, repeatCount = 1))
        assertFalse(shouldResetDockAutoHide(dockVisible = true, action = KeyEvent.ACTION_DOWN, repeatCount = 47))
    }

    @Test
    fun `a hidden dock is never kept alive`() {
        // Arming a timer for a dock nobody can see would leave a stray job, and
        // this is the state MB-338's accelerated seek runs in.
        assertFalse(shouldResetDockAutoHide(dockVisible = false, action = KeyEvent.ACTION_DOWN, repeatCount = 0))
        assertFalse(shouldResetDockAutoHide(dockVisible = false, action = KeyEvent.ACTION_DOWN, repeatCount = 3))
    }
}
