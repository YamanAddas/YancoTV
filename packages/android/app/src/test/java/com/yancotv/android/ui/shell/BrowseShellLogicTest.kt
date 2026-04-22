package com.yancotv.android.ui.shell

import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the pure helpers extracted from [BrowseShell]. The
 * composable itself can't be unit-tested without the Compose runtime,
 * but the data transforms that drive its rail and chip bar are pure
 * functions — this suite pins their contract so future refactors of
 * the section/type navigation (which was crashing on Movies→Series
 * and Series→Favorites before the 2026-04-22 fix) can't regress them
 * silently.
 */
class BrowseShellLogicTest {

    // ---- resolveGroupFilter ----

    @Test fun resolveGroupFilterReturnsNullForAllChip() {
        assertNull(resolveGroupFilter(ALL_GROUPS))
    }

    @Test fun resolveGroupFilterReturnsNullForFavoritesChip() {
        // Favorites swaps the data source entirely — the SQL group filter
        // must be null so the repo sees "no filter" rather than a literal
        // "__favorites__" string that will match zero rows.
        assertNull(resolveGroupFilter(FAVORITES_GROUP))
    }

    @Test fun resolveGroupFilterPassesThroughRealGroupName() {
        assertEquals("Kids", resolveGroupFilter("Kids"))
    }

    @Test fun resolveGroupFilterPreservesWhitespaceInRealName() {
        // Group names can contain spaces (e.g. "AL - ARKIVA 1980/2023" in
        // real user-supplied M3U playlists). Passing through verbatim is
        // critical — a trim would silently drop valid rows.
        assertEquals("AL - ARKIVA", resolveGroupFilter("AL - ARKIVA"))
    }

    // ---- isFavoritesFilter ----

    @Test fun isFavoritesFilterTrueOnlyForFavoritesChip() {
        assertTrue(isFavoritesFilter(FAVORITES_GROUP))
        assertFalse(isFavoritesFilter(ALL_GROUPS))
        assertFalse(isFavoritesFilter("Kids"))
        assertFalse(isFavoritesFilter(""))
    }

    // ---- visibleGroupsFor ----

    @Test fun visibleGroupsForReturnsAllWhenNoneHidden() {
        val all = listOf("Kids", "Sports", "News")
        val visible = visibleGroupsFor(all, emptySet())
        assertEquals(all, visible)
    }

    @Test fun visibleGroupsForReturnsSameInstanceWhenNoneHidden() {
        // Optimisation: no allocation when nothing to filter. Verifies we
        // don't accidentally copy-on-read every recomposition.
        val all = listOf("Kids", "Sports")
        assertSame(all, visibleGroupsFor(all, emptySet()))
    }

    @Test fun visibleGroupsForExcludesHidden() {
        val all = listOf("Kids", "Adult", "Sports")
        val visible = visibleGroupsFor(all, setOf("Adult"))
        assertEquals(listOf("Kids", "Sports"), visible)
    }

    @Test fun visibleGroupsForPreservesOriginalOrder() {
        val all = listOf("Zulu", "Alpha", "Mike")
        val visible = visibleGroupsFor(all, setOf("Alpha"))
        // Must not sort — the user's chip bar ordering matches the M3U's
        // declaration order and reordering would scramble the UI.
        assertEquals(listOf("Zulu", "Mike"), visible)
    }

    @Test fun visibleGroupsForHandlesAllHidden() {
        val all = listOf("Kids", "Sports")
        assertTrue(visibleGroupsFor(all, setOf("Kids", "Sports")).isEmpty())
    }

    @Test fun visibleGroupsForHandlesEmptyInput() {
        assertTrue(visibleGroupsFor(emptyList(), setOf("Adult")).isEmpty())
    }

    // ---- applyParentalFilters ----

    @Test fun applyParentalFiltersReturnsUnchangedWhenNothingFiltered() {
        val items = sampleItems()
        val out = applyParentalFilters(items, hiddenIds = emptySet(), hideAdult = false)
        assertEquals(items, out)
    }

    @Test fun applyParentalFiltersDropsHiddenIds() {
        val items = sampleItems()
        val out = applyParentalFilters(items, hiddenIds = setOf("m1"), hideAdult = false)
        assertEquals(listOf("m2", "s1"), out.map { it.id })
    }

    @Test fun applyParentalFiltersPreservesOrder() {
        val items = sampleItems()
        val out = applyParentalFilters(items, hiddenIds = setOf("m2"), hideAdult = false)
        assertEquals(listOf("m1", "s1"), out.map { it.id })
    }

    @Test fun applyParentalFiltersHidesAdultWhenEnabled() {
        val items = listOf(
            movie(id = "clean", title = "Frozen"),
            // AdultContentFilter keys off common explicit keywords; "XXX"
            // is the canonical test token for the heuristic.
            movie(id = "adult", title = "XXX Lives"),
        )
        val out = applyParentalFilters(items, hiddenIds = emptySet(), hideAdult = true)
        assertEquals(listOf("clean"), out.map { it.id })
    }

    @Test fun applyParentalFiltersKeepsAdultWhenDisabled() {
        val items = listOf(movie(id = "adult", title = "XXX Lives"))
        val out = applyParentalFilters(items, hiddenIds = emptySet(), hideAdult = false)
        assertEquals(listOf("adult"), out.map { it.id })
    }

    @Test fun applyParentalFiltersStacksHiddenAndAdult() {
        // Both filters together — hidden first, then adult on the survivors.
        val items = listOf(
            movie(id = "a", title = "Frozen"),
            movie(id = "b", title = "XXX Lives"),
            movie(id = "c", title = "Toy Story"),
        )
        val out = applyParentalFilters(items, hiddenIds = setOf("a"), hideAdult = true)
        assertEquals(listOf("c"), out.map { it.id })
    }

    @Test fun applyParentalFiltersHandlesEmptyInput() {
        val out = applyParentalFilters(emptyList(), hiddenIds = setOf("x"), hideAdult = true)
        assertTrue(out.isEmpty())
    }

    // ---- helpers ----

    private fun sampleItems(): List<ContentItem> = listOf(
        movie(id = "m1", title = "The Matrix"),
        movie(id = "m2", title = "Inception"),
        ContentItem(
            id = "s1",
            sourceId = "src",
            type = ContentType.SERIES,
            title = "Stranger Things",
            streamUrl = "http://x/s1",
            sortOrder = 0,
            createdAt = 0L,
        ),
    )

    private fun movie(id: String, title: String) = ContentItem(
        id = id,
        sourceId = "src",
        type = ContentType.MOVIE,
        title = title,
        streamUrl = "http://x/$id",
        sortOrder = 0,
        createdAt = 0L,
    )
}
