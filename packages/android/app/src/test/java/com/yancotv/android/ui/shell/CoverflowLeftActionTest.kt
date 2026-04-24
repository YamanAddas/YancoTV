package com.yancotv.android.ui.shell

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pin-down test for the coverflow wheel's LEFT-key decision.
 *
 * Background — there was a regression where LEFT on any orb popped back to
 * the category rail instead of scrolling to the previous orb. The root
 * cause was an outer `Column.onKeyEvent { LEFT → onExitToCategories() }`
 * that fired BEFORE Compose's default focus navigation (preview top-down
 * → onKeyEvent bottom-up → focus-nav last). That outer handler is gone,
 * replaced with a Watch-CTA-scoped preview handler + this Box-scoped
 * predicate that only exits when there's no earlier orb to scroll to.
 *
 * The predicate is trivial but load-bearing: regressing it to `< 0` would
 * trap LEFT at index 0 and require CENTER/BACK to escape; regressing to
 * `>= 0` would re-ship the original bug. This test catches both directions.
 */
class CoverflowLeftActionTest {
    @Test fun firstOrbExits() {
        assertTrue(
            shouldExitCoverflowOnLeft(0),
            "LEFT at index 0 must consume → rail, otherwise BACK is the only escape",
        )
    }

    @Test fun middleOrbsScroll() {
        for (i in 1..20) {
            assertFalse(
                shouldExitCoverflowOnLeft(i),
                "LEFT at index $i must flow through to LazyRow focus-nav",
            )
        }
    }

    @Test fun negativeIndexExits() {
        // Belt-and-braces: a stale focusedIndex during a category swap
        // (items list just replaced, focusedIndex not yet reset) should
        // still behave as "no earlier orb" and exit rather than trap.
        assertTrue(shouldExitCoverflowOnLeft(-1))
        assertTrue(shouldExitCoverflowOnLeft(Int.MIN_VALUE))
    }
}
