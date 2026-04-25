package com.yancotv.android.ui.settings

import androidx.compose.ui.graphics.Color
import com.yancotv.android.ui.theme.FrostedEmerald
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * MB-110 + MB-112: rules for how a settings chip resolves its
 * (background, border, foreground) tuple from (selected, focused).
 * Pulled out of the composable into [chipColors] so the contract is
 * unit-testable without spinning up Compose. The composable sits behind
 * these tests as a thin wiring layer.
 *
 * The original bug (MB-110): pre-MK.16 chips used three private
 * non-focusable composables that on Fire TV failed to flip foreground +
 * border colour when focus landed on them — there was no `focused`
 * state tracked at all.
 *
 * The follow-up bug (MB-112): we then gave BOTH `focused` and `selected`
 * a 1dp accent border. At Fire TV viewing distance (~3 m) a thin
 * coloured outline reads the same regardless of which accent token it
 * uses — user couldn't tell where the cursor was vs which group was
 * picked. Fix: focus is THE FRAME, selection is THE FILL. Border is
 * only painted when `focused`; selected-but-unfocused communicates
 * "picked" through the background tint alone.
 *
 * These tests lock the rule in so future "minor visual polish" PRs
 * can't put an accent border back onto idle-selected chips.
 */
class SettingsChipColorsTest {
    private val palette = FrostedEmerald

    @Test fun idleChipUsesDeepCanvasMutedTextNoBorder() {
        val c = chipColors(palette, selected = false, focused = false)
        assertEquals(palette.BackgroundDeep, c.background)
        assertEquals(Color.Transparent, c.border)
        assertEquals(palette.TextMuted, c.foreground)
    }

    @Test fun selectedIdleHasTintedBackgroundAndNoBorder() {
        // MB-112: selection paints the FILL (low-alpha Accent wash), not a
        // border. The frame belongs exclusively to focus so the user can
        // always tell where the cursor is at TV distance. Without this
        // test we keep regressing back to "thin accent border on selected"
        // which on Fire TV looks identical to the focus ring.
        val c = chipColors(palette, selected = true, focused = false)
        assertNotEquals(palette.BackgroundDeep, c.background)
        assertEquals(Color.Transparent, c.border)
        assertEquals(palette.TextPrimary, c.foreground)
    }

    @Test fun focusedUnselectedShowsFocusRingBorderAndPrimaryText() {
        val c = chipColors(palette, selected = false, focused = true)
        // Focused state must surface FocusRing on the border so the user
        // can always see where focus is — that's the whole point of MB-107a.
        assertEquals(palette.FocusRing, c.border)
        assertEquals(palette.TextPrimary, c.foreground)
    }

    @Test fun focusedAndSelectedKeepsFocusRingNotAccent() {
        // MB-112 corollary: even when the chip is the picked option,
        // focus must still own the border. Distinguishing focused-and-
        // selected from selected-and-not-focused is exactly what the
        // user complained about ("category and live tv show selection
        // at the same color") — the FRAME tells them where the cursor
        // is, the FILL tells them what's picked.
        val c = chipColors(palette, selected = true, focused = true)
        assertEquals(palette.FocusRing, c.border)
    }

    @Test fun onlyFocusedPaintsBorder() {
        // Truth-table guard: border is Transparent in every state EXCEPT
        // focused. A future PR that adds an accent ring to selected (the
        // exact regression that produced MB-112) will trip here.
        assertEquals(Color.Transparent, chipColors(palette, selected = false, focused = false).border)
        assertEquals(Color.Transparent, chipColors(palette, selected = true, focused = false).border)
        assertEquals(palette.FocusRing, chipColors(palette, selected = false, focused = true).border)
        assertEquals(palette.FocusRing, chipColors(palette, selected = true, focused = true).border)
    }

    @Test fun focusedAndSelectedKeepsSelectedBackground() {
        // Rule: selection wins on the BACKGROUND. The user moving focus
        // away from the picked chip should still see the "I picked this"
        // tint on it; only the border changes when focus comes/goes.
        val selectedFocused = chipColors(palette, selected = true, focused = true)
        val selectedIdle = chipColors(palette, selected = true, focused = false)
        assertEquals(selectedIdle.background, selectedFocused.background)
    }

    @Test fun foregroundIsMutedOnlyWhenBothFlagsAreFalse() {
        // Foreground rule: TextPrimary if either selected or focused; muted
        // otherwise. Encoded once to make sure no future "minor cleanup"
        // collapses the OR into an AND or flips the polarity.
        assertEquals(palette.TextMuted, chipColors(palette, false, false).foreground)
        assertEquals(palette.TextPrimary, chipColors(palette, true, false).foreground)
        assertEquals(palette.TextPrimary, chipColors(palette, false, true).foreground)
        assertEquals(palette.TextPrimary, chipColors(palette, true, true).foreground)
    }
}
