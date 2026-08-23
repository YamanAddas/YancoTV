package com.yancotv.android.ui.shell

import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // ── MB-379: EPG now/next window follows the focused tile ──
    private fun ci(i: Int, tvg: String? = "tvg$i") =
        ContentItem(id = "c$i", sourceId = "s", type = ContentType.LIVE, title = "ch$i", streamUrl = "u$i", tvgId = tvg, sortOrder = i, createdAt = 0L)

    @Test fun epgWindow_centersOnFocusAndIsBounded() {
        val items = (0 until 300).map { ci(it) }
        val ids = epgWindowTvgIds(items, focusedIndex = 150, half = 10)
        assertEquals(21, ids.size, "window is focus ± half inclusive")
        assertEquals("tvg140", ids.first())
        assertEquals("tvg160", ids.last())
        // The old take-from-start behaviour would have returned tvg0..; prove it follows focus.
        assertTrue(ids.none { it == "tvg0" }, "must not be anchored to the start of the list")
    }

    @Test fun epgWindow_clampsAtEdgesAndHandlesEmpty() {
        val items = (0 until 30).map { ci(it) }
        assertEquals("tvg0", epgWindowTvgIds(items, focusedIndex = 0, half = 10).first())
        assertEquals("tvg29", epgWindowTvgIds(items, focusedIndex = 29, half = 10).last())
        assertTrue(epgWindowTvgIds(emptyList(), focusedIndex = 5, half = 10).isEmpty())
    }

    @Test fun epgWindow_dropsBlankTvgIdsAndDedupes() {
        val items = listOf(ci(0, "a"), ci(1, null), ci(2, ""), ci(3, "a"), ci(4, "b"))
        val ids = epgWindowTvgIds(items, focusedIndex = 2, half = 10)
        assertEquals(listOf("a", "b"), ids, "blank/null dropped, distinct preserved in order")
    }
}
