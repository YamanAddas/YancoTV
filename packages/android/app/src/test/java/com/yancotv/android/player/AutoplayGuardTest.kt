package com.yancotv.android.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MB-344 — [shouldReleaseAutoplayGuard] contract.
 *
 * The bug this pins is a latch, and latches are invisible in a single pass: the
 * first autoplay works, and every one after it silently does nothing. There is
 * no crash, no log, no on-screen difference — autoplay just appears to be off
 * until the Activity is recreated. That is why this is worth a test rather than
 * a careful read of the call sites.
 */
class AutoplayGuardTest {

    /** The happy path the original code already handled. */
    @Test fun `a prepared item releases the guard`() {
        assertTrue(shouldReleaseAutoplayGuard(AutoplayGuardEvent.NEW_ITEM_READY))
    }

    /**
     * The actual bug. A terminal error means the item will NEVER reach READY,
     * so if this does not release, autoplay is dead for the life of the
     * Activity.
     */
    @Test fun `a terminal error releases the guard`() {
        assertTrue(
            shouldReleaseAutoplayGuard(AutoplayGuardEvent.TERMINAL_ERROR),
            "a stream that failed to prepare must not block every future advance",
        )
    }

    /** Second latch path: play() threw, so no prepare was ever started. */
    @Test fun `an advance that threw releases the guard`() {
        assertTrue(shouldReleaseAutoplayGuard(AutoplayGuardEvent.ADVANCE_THREW))
    }

    @Test fun `no next episode releases the guard`() {
        assertTrue(shouldReleaseAutoplayGuard(AutoplayGuardEvent.NO_NEXT_EPISODE))
    }

    /**
     * The anti-regression, and the reason the fix is not "clear it on any
     * error". BEHIND_LIVE_WINDOW re-seeks and a transient network error retries;
     * both re-prepare and are expected to reach READY. Releasing here would drop
     * the guard mid-prepare and reopen the double-STATE_ENDED hole it exists to
     * absorb — trading a stuck guard for a skipped episode, which is worse
     * because it loses content silently.
     */
    @Test fun `a recoverable error does NOT release the guard`() {
        assertFalse(
            shouldReleaseAutoplayGuard(AutoplayGuardEvent.RECOVERABLE_ERROR),
            "these branches re-prepare and will reach READY; releasing early reopens the double-ENDED skip",
        )
    }

    @Test fun `an item still preparing does NOT release the guard`() {
        assertFalse(shouldReleaseAutoplayGuard(AutoplayGuardEvent.STILL_PREPARING))
    }

    /**
     * Every event is classified. A new enum constant defaulting to "release"
     * would silently widen the guard's hole — the `when` is exhaustive, so this
     * fails to compile rather than fails at runtime, but the test states the
     * intent for whoever adds one.
     */
    @Test fun `every event has an explicit ruling`() {
        val released = AutoplayGuardEvent.entries.count { shouldReleaseAutoplayGuard(it) }
        val held = AutoplayGuardEvent.entries.count { !shouldReleaseAutoplayGuard(it) }
        assertTrue(released == 4, "expected 4 releasing events, found $released")
        assertTrue(held == 2, "expected 2 holding events, found $held")
    }
}
