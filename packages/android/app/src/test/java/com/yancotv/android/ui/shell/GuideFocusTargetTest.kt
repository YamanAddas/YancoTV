package com.yancotv.android.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * MB-400 — truth table for [guideFocusTarget], the resolver that decides
 * where Guide entry focus lands.
 *
 * The bug this pins: `GuideScreen` used to branch straight off
 * [PanelFocus], so `Categories` meant "focus the CategoryRail's selected
 * pill" even when the rail wasn't mounted. Entering the Guide with no
 * programme data (zero sources, or sources with no EPG yet) left the pane
 * with ZERO focused nodes — `PlacedFocusAnchor.awaitAndRequest()` waited
 * forever on a node that would never be placed — while the sync panel
 * beside it held four reachable controls ("Refresh EPG now", "Re-sync
 * sources", the EPG-URL field, "Save"). Device-observed on Fire TV
 * AFTDCT31 running 1.6.7.
 *
 * The contract is that the resolver can only ever name a composable the
 * screen actually renders: `railHasGroups` mirrors the rail's mount gate,
 * `guideEmpty` and `guideLoading` mirror the right pane's two nested
 * branches.
 */
class GuideFocusTargetTest {
    // ---- the MB-400 regression cases ----

    @Test fun categoriesWithNoRailFallsThroughToTheSyncPanel() {
        // The repro state: no sources → no EPG groups → no rail, and the
        // right pane is the sync panel. Categories is not a destination.
        assertEquals(
            GuideFocusTarget.SyncPanel,
            guideFocusTarget(PanelFocus.Categories, railHasGroups = false, guideEmpty = true, guideLoading = false),
        )
    }

    @Test fun contentWithAnEmptyGuideTargetsTheSyncPanel() {
        // The shell's Content routing for a data-less Guide. Same answer as
        // above — which is what makes the shell's choice between Categories
        // and Content safe rather than load-bearing.
        assertEquals(
            GuideFocusTarget.SyncPanel,
            guideFocusTarget(PanelFocus.Content, railHasGroups = false, guideEmpty = true, guideLoading = false),
        )
    }

    @Test fun categoriesWithNoRailButAPopulatedGuideTargetsTheGrid() {
        // A provider whose channels carry no group names: the guide has
        // rows to show but the rail still has nothing to mount. Focus
        // belongs in the grid, not on a pill that doesn't exist.
        assertEquals(
            GuideFocusTarget.Grid,
            guideFocusTarget(PanelFocus.Categories, railHasGroups = false, guideEmpty = false, guideLoading = false),
        )
    }

    // ---- the reload flicker ("Refresh EPG now" from inside the panel) ----

    @Test fun aLoadingEmptyPaneHasNothingToFocus() {
        // The text-only loading placeholder has no focusable child, so
        // targeting the sync panel here would await an anchor that isn't
        // in the tree.
        assertEquals(
            GuideFocusTarget.None,
            guideFocusTarget(PanelFocus.Content, railHasGroups = false, guideEmpty = true, guideLoading = true),
        )
    }

    @Test fun theReloadRoundTripChangesTargetSoFocusCanComeBack() {
        // Pressing "Refresh EPG now" bumps the reload tick: panel → loading
        // placeholder → panel. The focus effect is keyed on the target, so
        // the middle state MUST differ or the effect never re-fires and the
        // button the user just pressed comes back unfocused.
        val parked = guideFocusTarget(PanelFocus.Content, railHasGroups = false, guideEmpty = true, guideLoading = false)
        val reloading = guideFocusTarget(PanelFocus.Content, railHasGroups = false, guideEmpty = true, guideLoading = true)
        assertNotEquals(reloading, parked)
        assertEquals(GuideFocusTarget.SyncPanel, parked)
    }

    @Test fun loadingIsIgnoredOnceTheGuideHasRows() {
        // `loading` only guards the initial fetch; a populated grid stays
        // the target through a background page load.
        assertEquals(
            GuideFocusTarget.Grid,
            guideFocusTarget(PanelFocus.Content, railHasGroups = true, guideEmpty = false, guideLoading = true),
        )
    }

    // ---- the cascade's normal path must not move ----

    @Test fun categoriesWithARailStillTargetsTheRail() {
        assertEquals(
            GuideFocusTarget.Rail,
            guideFocusTarget(PanelFocus.Categories, railHasGroups = true, guideEmpty = false, guideLoading = false),
        )
    }

    @Test fun categoriesKeepsTheRailWhenAGroupFilterEmptiesTheGrid() {
        // Picking a group with no channels in the window empties the grid
        // and swaps the right pane to the sync panel, but the rail is still
        // mounted — the user has to be able to pick a different group.
        assertEquals(
            GuideFocusTarget.Rail,
            guideFocusTarget(PanelFocus.Categories, railHasGroups = true, guideEmpty = true, guideLoading = false),
        )
    }

    @Test fun contentTargetsTheGridWheneverTheGuideHasData() {
        assertEquals(
            GuideFocusTarget.Grid,
            guideFocusTarget(PanelFocus.Content, railHasGroups = true, guideEmpty = false, guideLoading = false),
        )
        assertEquals(
            GuideFocusTarget.Grid,
            guideFocusTarget(PanelFocus.Content, railHasGroups = false, guideEmpty = false, guideLoading = false),
        )
    }

    @Test fun contentTargetsTheSyncPanelWheneverTheGuideIsEmpty() {
        // Rail mounted or not, an empty grid means the sync panel is what's
        // rendered in the pane — so that's what Content has to focus.
        assertEquals(
            GuideFocusTarget.SyncPanel,
            guideFocusTarget(PanelFocus.Content, railHasGroups = true, guideEmpty = true, guideLoading = false),
        )
    }

    // ---- the sidebar always wins ----

    @Test fun sidebarNeverStealsFocusRegardlessOfGuideState() {
        // The Guide must not pull focus out of the shell sidebar just
        // because its data changed underneath. All eight combinations.
        forEachGuideState { railHasGroups, guideEmpty, guideLoading ->
            assertEquals(
                GuideFocusTarget.None,
                guideFocusTarget(PanelFocus.Sidebar, railHasGroups, guideEmpty, guideLoading),
                "Sidebar must own focus (rail=$railHasGroups empty=$guideEmpty loading=$guideLoading)",
            )
        }
    }

    // ---- the invariant behind all of it ----

    @Test fun everyStateResolvesToSomethingThatIsActuallyRendered() {
        // The whole point of the resolver: no combination may name a
        // composable the screen isn't showing. Rail only when the rail is
        // mounted; SyncPanel only when the guide is empty AND settled;
        // Grid only when the guide has rows.
        for (panelFocus in PanelFocus.entries) {
            forEachGuideState { railHasGroups, guideEmpty, guideLoading ->
                val where = "panelFocus=$panelFocus rail=$railHasGroups empty=$guideEmpty loading=$guideLoading"
                when (guideFocusTarget(panelFocus, railHasGroups, guideEmpty, guideLoading)) {
                    GuideFocusTarget.Rail ->
                        assertEquals(true, railHasGroups, "Rail target with no rail mounted: $where")
                    GuideFocusTarget.SyncPanel -> {
                        assertEquals(true, guideEmpty, "SyncPanel target with a populated grid: $where")
                        assertEquals(false, guideLoading, "SyncPanel target while the placeholder is up: $where")
                    }
                    GuideFocusTarget.Grid ->
                        assertEquals(false, guideEmpty, "Grid target with an empty guide: $where")
                    GuideFocusTarget.None -> Unit
                }
            }
        }
    }

    private fun forEachGuideState(body: (railHasGroups: Boolean, guideEmpty: Boolean, guideLoading: Boolean) -> Unit) {
        for (railHasGroups in listOf(true, false)) {
            for (guideEmpty in listOf(true, false)) {
                for (guideLoading in listOf(true, false)) {
                    body(railHasGroups, guideEmpty, guideLoading)
                }
            }
        }
    }
}
