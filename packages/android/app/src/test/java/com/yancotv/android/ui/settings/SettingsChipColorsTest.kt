package com.yancotv.android.ui.settings

import androidx.compose.ui.graphics.Color
import com.yancotv.android.ui.theme.FrostedEmerald
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * MB-110: rules for how a settings chip resolves its (background, border,
 * foreground) tuple from (selected, focused). Pulled out of the composable
 * into [chipColors] so the contract is unit-testable without spinning up
 * Compose. The composable sits behind these tests as a thin wiring layer.
 *
 * The bug we're guarding against: the pre-MK.16 chips used three private
 * non-focusable composables that on Fire TV failed to flip foreground +
 * border colour when focus landed on them — there was no `focused` state
 * tracked at all. The shared [SettingsChip] now passes focus through, and
 * these tests lock in the visible-state rules so future palette swaps or
 * "small visual cleanup" PRs can't silently regress them.
 */
class SettingsChipColorsTest {
    private val palette = FrostedEmerald

    @Test fun idleChipUsesDeepCanvasMutedTextNoBorder() {
        val c = chipColors(palette, selected = false, focused = false)
        assertEquals(palette.BackgroundDeep, c.background)
        assertEquals(Color.Transparent, c.border)
        assertEquals(palette.TextMuted, c.foreground)
    }

    @Test fun selectedIdleHasTintedBackgroundAndAccentBorder() {
        val c = chipColors(palette, selected = true, focused = false)
        // Selected tint reuses Accent at low alpha — non-zero, distinct from idle.
        assertNotEquals(palette.BackgroundDeep, c.background)
        assertNotEquals(Color.Transparent, c.border)
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
        // Rule: focus wins over selection on the BORDER. The user must be
        // able to see "I'm on this chip" even when it's the currently
        // picked option. If selection won, navigating onto the selected
        // chip would visually look like the focus disappeared.
        val c = chipColors(palette, selected = true, focused = true)
        assertEquals(palette.FocusRing, c.border)
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
