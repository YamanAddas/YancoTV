package com.yancotv.android.ui.shell

import com.yancotv.android.ui.nav.AppSection
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

    // ---- resolveAutoPreviewIndex ----

    @Test fun resolveAutoPreviewReturnsNullForMovieType() {
        // Auto-preview is LIVE-only; VOD files must not auto-start on focus.
        val visible = liveChannels()
        assertNull(
            resolveAutoPreviewIndex(
                type = ContentType.MOVIE,
                focusedId = "c1",
                visible = visible,
                lockedIds = emptySet(),
                currentlyPlayingId = null,
            ),
        )
    }

    @Test fun resolveAutoPreviewReturnsNullForSeriesType() {
        val visible = liveChannels()
        assertNull(
            resolveAutoPreviewIndex(
                type = ContentType.SERIES,
                focusedId = "c1",
                visible = visible,
                lockedIds = emptySet(),
                currentlyPlayingId = null,
            ),
        )
    }

    @Test fun resolveAutoPreviewReturnsNullWhenNothingFocused() {
        assertNull(
            resolveAutoPreviewIndex(
                type = ContentType.LIVE,
                focusedId = null,
                visible = liveChannels(),
                lockedIds = emptySet(),
                currentlyPlayingId = null,
            ),
        )
    }

    @Test fun resolveAutoPreviewReturnsNullWhenFocusedIsLocked() {
        // Parental lock must gate silent background playback — the PIN
        // prompt only fires through the explicit activate path, so an
        // auto-preview on a locked row would leak restricted content.
        assertNull(
            resolveAutoPreviewIndex(
                type = ContentType.LIVE,
                focusedId = "c2",
                visible = liveChannels(),
                lockedIds = setOf("c2"),
                currentlyPlayingId = null,
            ),
        )
    }

    @Test fun resolveAutoPreviewReturnsNullWhenAlreadyPlaying() {
        // No-op when the focused card is already the live MiniPlayer source.
        // Re-calling play() would trigger a fresh prepare() and re-buffer.
        assertNull(
            resolveAutoPreviewIndex(
                type = ContentType.LIVE,
                focusedId = "c1",
                visible = liveChannels(),
                lockedIds = emptySet(),
                currentlyPlayingId = "c1",
            ),
        )
    }

    @Test fun resolveAutoPreviewReturnsNullWhenFocusedNotInVisible() {
        // Post-debounce re-check needs to survive the case where the
        // focused id got filtered out during the 400ms window (user
        // switched chips, parental list grew, etc).
        assertNull(
            resolveAutoPreviewIndex(
                type = ContentType.LIVE,
                focusedId = "ghost",
                visible = liveChannels(),
                lockedIds = emptySet(),
                currentlyPlayingId = null,
            ),
        )
    }

    @Test fun resolveAutoPreviewReturnsIndexForEligibleLiveFocus() {
        assertEquals(
            1,
            resolveAutoPreviewIndex(
                type = ContentType.LIVE,
                focusedId = "c2",
                visible = liveChannels(),
                lockedIds = emptySet(),
                currentlyPlayingId = "c1",
            ),
        )
    }

    @Test fun resolveAutoPreviewReturnsNullForEmptyVisible() {
        assertNull(
            resolveAutoPreviewIndex(
                type = ContentType.LIVE,
                focusedId = "c1",
                visible = emptyList(),
                lockedIds = emptySet(),
                currentlyPlayingId = null,
            ),
        )
    }

    @Test fun resolveAutoPreviewTreatsTypeGuardAheadOfFocusGuard() {
        // Short-circuits even when all other signals would allow playback.
        // Protects against accidental auto-preview leaking into VOD rails
        // if a future refactor forgets to gate the LaunchedEffect callsite.
        assertNull(
            resolveAutoPreviewIndex(
                type = ContentType.MOVIE,
                focusedId = "c1",
                visible = liveChannels(),
                lockedIds = emptySet(),
                currentlyPlayingId = null,
            ),
        )
    }

    // ---- initialFocusIndex ----

    @Test fun initialFocusReturnsMinusOneForEmptyItems() {
        // -1 signals "no focus yet" — composable caller leaves focusedItem
        // null rather than crashing on an out-of-bounds index.
        assertEquals(-1, initialFocusIndex(emptyList(), savedIndex = 0, currentlyPlayingId = null))
    }

    @Test fun initialFocusPrefersCurrentlyPlayingItem() {
        // Returning to LiveTv while a stream runs in the MiniPlayer must
        // land focus on that channel — otherwise the auto-preview logic
        // would re-prepare() a different stream and clobber playback.
        val items = liveChannels()
        assertEquals(
            2,
            initialFocusIndex(items, savedIndex = 0, currentlyPlayingId = "c3"),
        )
    }

    @Test fun initialFocusFallsBackToSavedIndexWhenNoPlayingId() {
        val items = liveChannels()
        assertEquals(1, initialFocusIndex(items, savedIndex = 1, currentlyPlayingId = null))
    }

    @Test fun initialFocusFallsBackToSavedIndexWhenPlayingIdNotInList() {
        // Playing channel belongs to a different section (e.g. MiniPlayer
        // is showing a LIVE stream while the user is browsing MOVIES). The
        // id lookup misses and we honour the saved cursor for this section.
        val items = liveChannels()
        assertEquals(1, initialFocusIndex(items, savedIndex = 1, currentlyPlayingId = "unrelated"))
    }

    @Test fun initialFocusClampsSavedIndexAboveLastPosition() {
        // A previously-saved cursor can outrun the current rail after a
        // filter drop (e.g. saved was 47, current visible size is 3).
        val items = liveChannels()
        assertEquals(2, initialFocusIndex(items, savedIndex = 99, currentlyPlayingId = null))
    }

    @Test fun initialFocusClampsNegativeSavedIndex() {
        // Defensive: rememberSaveable can theoretically hand back a
        // corrupted int. Clamp to 0 rather than crashing get(-1).
        val items = liveChannels()
        assertEquals(0, initialFocusIndex(items, savedIndex = -5, currentlyPlayingId = null))
    }

    @Test fun initialFocusSingleItemIgnoresSavedIndexOvershoot() {
        val items = listOf(liveChannel("only"))
        assertEquals(0, initialFocusIndex(items, savedIndex = 12, currentlyPlayingId = null))
    }

    @Test fun initialFocusPrefersPlayingEvenWhenSavedIndexIsValid() {
        // Both signals are usable; playing id wins because continuity of
        // the currently-running stream is the stronger UX contract.
        val items = liveChannels()
        assertEquals(
            0,
            initialFocusIndex(items, savedIndex = 2, currentlyPlayingId = "c1"),
        )
    }

    // ---- preview ownership ----

    @Test fun heroPlaybackOnlyReturnsFocusedPlayerItem() {
        val focused = movie(id = "m1", title = "The Matrix")
        val playing = movie(id = "m1", title = "The Matrix")
        assertEquals(playing, heroPlaybackForFocused(focused, playing))
    }

    @Test fun heroPlaybackReturnsNullForCrossTabPlayerItem() {
        val focused = movie(id = "m1", title = "The Matrix")
        val playing = liveChannel("c1")
        assertNull(heroPlaybackForFocused(focused, playing))
    }

    @Test fun homeSectionStopsLivePreviewWhenLeavingLiveTv() {
        assertTrue(shouldStopLivePreviewForSection(AppSection.Movies, liveChannel("c1")))
        assertTrue(shouldStopLivePreviewForSection(AppSection.Series, liveChannel("c1")))
        assertTrue(shouldStopLivePreviewForSection(AppSection.Home, liveChannel("c1")))
        assertTrue(shouldStopLivePreviewForSection(AppSection.Guide, liveChannel("c1")))
        assertTrue(shouldStopLivePreviewForSection(AppSection.Favorites, liveChannel("c1")))
        assertTrue(shouldStopLivePreviewForSection(AppSection.Search, liveChannel("c1")))
        assertTrue(shouldStopLivePreviewForSection(AppSection.Settings, liveChannel("c1")))
    }

    @Test fun homeSectionKeepsLivePreviewInsideLiveTv() {
        assertFalse(shouldStopLivePreviewForSection(AppSection.LiveTv, liveChannel("c1")))
    }

    @Test fun homeSectionDoesNotStopVodWhenLeavingLiveTv() {
        assertFalse(
            shouldStopLivePreviewForSection(
                AppSection.Movies,
                movie(id = "m1", title = "The Matrix"),
            ),
        )
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

    private fun liveChannels(): List<ContentItem> = listOf(
        liveChannel("c1"),
        liveChannel("c2"),
        liveChannel("c3"),
    )

    private fun liveChannel(id: String) = ContentItem(
        id = id,
        sourceId = "src",
        type = ContentType.LIVE,
        title = "Channel $id",
        streamUrl = "http://x/$id",
        sortOrder = 0,
        createdAt = 0L,
    )
}
