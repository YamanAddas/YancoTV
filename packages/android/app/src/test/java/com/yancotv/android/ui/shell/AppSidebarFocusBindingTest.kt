package com.yancotv.android.ui.shell

import androidx.compose.ui.focus.FocusRequester
import com.yancotv.android.ui.nav.AppSection
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Regression coverage for MB-106 — the rule that decides which sidebar
 * row holds the FocusRequester used by BACK / detail-close /
 * onExitToSidebar to land focus.
 *
 * v1 (commit 1e9fdec) attached the requester to the wrapper Box; in
 * Compose 1.7 the descendant search through the modifier chain was
 * unreliable, so requestFocus() landed on a non-focusable node and the
 * inner Row's MutableInteractionSource never flipped → no focused
 * gradient or ring until the user nudged the D-pad.
 *
 * v2 (commit be3d413) binds the requester directly to the focusable
 * inner Row. The contract this test pins down: pass the requester only
 * to the row matching `current`, and only when one was supplied.
 * Anything else risks requestFocus() landing on whichever row Compose
 * visited first (defeating "BACK lands on active row") or attempting
 * to bind a null requester (would crash on focusRequester()).
 */
class AppSidebarFocusBindingTest {
    private val requester = FocusRequester()

    @Test fun matchingSectionGetsRequester() {
        assertSame(
            requester,
            bindActiveRowFocus(AppSection.LiveTv, AppSection.LiveTv, requester),
        )
    }

    @Test fun nonMatchingSectionGetsNull() {
        assertNull(bindActiveRowFocus(AppSection.Movies, AppSection.LiveTv, requester))
    }

    @Test fun nullRequesterPropagates() {
        assertNull(bindActiveRowFocus(AppSection.LiveTv, AppSection.LiveTv, null))
    }

    @Test fun nullRequesterAlsoPropagatesForNonMatching() {
        assertNull(bindActiveRowFocus(AppSection.Movies, AppSection.LiveTv, null))
    }

    @Test fun everySectionEnumIsCovered() {
        // Sweep every AppSection as `current`. Exactly one row must hold
        // the requester per composition; everything else must be null.
        // Adding a new AppSection without thinking through MB-106 will
        // fail this — the new section either doesn't bind to itself
        // (programmer error) or matches more than one current (impossible
        // with `==`, but guards against accidental sentinel comparisons).
        for (current in AppSection.entries) {
            val matches = AppSection.entries.count { section ->
                bindActiveRowFocus(section, current, requester) === requester
            }
            kotlin.test.assertEquals(
                1,
                matches,
                "current=$current should bind requester to exactly one row, got $matches",
            )
        }
    }
}
