package com.yancotv.android.ui.shell

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MB-374 — pin-down tests for the coverflow's "page in more?" decision.
 *
 * The bug: the inline guard hard-stopped at `items.size >= 1000`, so any
 * category with more than 1000 entries showed only its first 1000 — the rest
 * were reachable only through search. A 270k-item catalogue had 44 such
 * categories hiding ~63k rows. The guard is now [shouldPageMore] with a
 * memory-backstop ceiling well above any real category.
 *
 * The load-bearing assertion is [pagesPastOneThousand]: regress the ceiling
 * back to 1000 (or forget to raise it) and that test reddens.
 */
class CoverflowPagingTest {
    private fun page(
        isFavorites: Boolean = false,
        loading: Boolean = false,
        loaded: Long,
        total: Long,
        itemsSize: Int = loaded.toInt(),
        reach: Int = loaded.toInt(),
    ) = shouldPageMore(isFavorites, loading, loaded, total, itemsSize, reach)

    @Test fun pagesPastOneThousand() {
        // The whole point of the fix: a large category keeps paging well past
        // the old 1000 ceiling. Uses the REAL default maxItems, so reverting
        // MAX_BROWSE_ITEMS to 1000 fails this.
        assertTrue(
            page(loaded = 1500, total = 9484, reach = 1490),
            "a 9,484-item category must still page at 1,500 loaded — this is the MB-374 regression guard",
        )
    }

    @Test fun stopsAtMemoryCeiling() {
        assertFalse(
            shouldPageMore(false, false, 20_000, 174_000, 20_000, 19_990, maxItems = 20_000),
            "once the memory ceiling is hit, stop paging even though more rows exist",
        )
        assertTrue(
            shouldPageMore(false, false, 19_900, 174_000, 19_900, 19_890, maxItems = 20_000),
            "just under the ceiling still pages",
        )
    }

    @Test fun stopsWhenAllLoaded() {
        assertFalse(page(loaded = 640, total = 640, reach = 630), "loaded == total → nothing left to page")
        assertFalse(page(loaded = 700, total = 640, reach = 690), "loaded > total (defensive) → stop")
    }

    @Test fun doesNotPageWhileFarFromEdge() {
        // reach well behind the loaded frontier: the user is scrolling within
        // already-loaded rows, no prefetch needed yet.
        assertFalse(page(loaded = 500, total = 5000, reach = 100), "far from the edge must not prefetch")
    }

    @Test fun prefetchesWithinTwentyOfEdge() {
        assertTrue(page(loaded = 500, total = 5000, reach = 480), "within 20 of the edge triggers the next page")
        assertTrue(page(loaded = 500, total = 5000, reach = 500), "at the edge triggers the next page")
    }

    @Test fun neverPagesFavoritesOrDuringLoad() {
        assertFalse(page(isFavorites = true, loaded = 10, total = 5000, reach = 10), "favorites is an in-memory list, not paged")
        assertFalse(page(loading = true, loaded = 500, total = 5000, reach = 500), "a load already in flight blocks a second")
    }
}
