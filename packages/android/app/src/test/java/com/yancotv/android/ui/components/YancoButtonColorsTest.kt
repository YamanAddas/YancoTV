package com.yancotv.android.ui.components

import androidx.compose.ui.graphics.Color
import com.yancotv.android.ui.theme.FrostedEmerald
import com.yancotv.android.ui.theme.MidnightSapphire
import com.yancotv.android.ui.theme.WarmAmber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Locks the (border, text) contract for every button in the shared
 * Yanco{Primary,Secondary,Danger}Button family. Mirrors
 * [com.yancotv.android.ui.settings.SettingsChipColorsTest] — these
 * resolvers are pulled out of the composable so the rule is unit-testable
 * without spinning up Compose.
 *
 * The contract that matters at 10 ft on Fire TV:
 *
 *  1. **Focus is the FRAME.** Every button's focused state must surface
 *     `FocusRing` on the border (Primary, Secondary) or the brighter
 *     `Error` colour (Danger). That's how the user finds the cursor at
 *     TV distance. A future "minor polish" PR that drops FocusRing back
 *     to a generic hairline trips here.
 *
 *  2. **Disabled looks different from idle.** Disabled state must muffle
 *     the border and the text — `TextMuted` for text, dimmed accent for
 *     borders. Without this, a Save button reads as fully tappable while
 *     it's mid-network-call.
 *
 *  3. **Brightness step is real, not just a hue swap.** Solid primary
 *     resting border is white-at-18%; focused border is FocusRing
 *     (palette-specific, but always brighter than rest). The pre-MK.UI.BTN
 *     bug was that ContentDetailScreen's old PrimaryButton used Accent →
 *     AccentGlow which on Fire TV read as "same colour, slightly lighter"
 *     rather than a clear focus jump. The shared family avoids that by
 *     having rest paint white-translucent and focus paint the bright
 *     palette token.
 *
 *  4. **Translucent primary is its own thing, not a coloured secondary.**
 *     Its idle text is `Accent` (the brand colour, full saturation)
 *     because the button IS the action; focused goes to `AccentGlow` and
 *     the border to `FocusRing`. A secondary's idle text is
 *     `TextPrimary`. Don't collapse the two.
 */
class YancoButtonColorsTest {
    private val palette = FrostedEmerald

    // ── solid primary ───────────────────────────────────────────────

    @Test fun solidPrimaryIdleHasHairlineBorderAndDarkInk() {
        val c = primarySolidColors(palette, focused = false, enabled = true)
        // White-at-18% is the canonical "I'm a button, but not the cursor"
        // hairline on a gradient surface. NOT FocusRing — that's reserved
        // for the focused state.
        assertEquals(Color.White.copy(alpha = 0.18f), c.borderColor)
        // Dark ink on a bright gradient — the design's high-contrast
        // call-out colour. NOT BackgroundDeep (which is theme-tied and
        // would drift as palettes change).
        assertEquals(Color(0xFF04130C), c.textColor)
        assertEquals(false, c.borderWidthIsFocus)
    }

    @Test fun solidPrimaryFocusedShowsFocusRingBorder() {
        val c = primarySolidColors(palette, focused = true, enabled = true)
        assertEquals(palette.FocusRing, c.borderColor)
        // Text stays dark ink — the focus signal is the ring + halo +
        // scale, not a text-colour swap. Swapping the text colour on
        // focus would fight the dark-on-bright contrast.
        assertEquals(Color(0xFF04130C), c.textColor)
        assertEquals(true, c.borderWidthIsFocus)
    }

    @Test fun solidPrimaryDisabledMutesText() {
        val c = primarySolidColors(palette, focused = false, enabled = false)
        assertEquals(palette.TextMuted, c.textColor)
        // Even if Compose lets a focusable land here while disabled,
        // the focus indicator should NOT light up (focus + disabled is a
        // contradiction). borderWidthIsFocus stays false.
        assertEquals(false, c.borderWidthIsFocus)
    }

    @Test fun solidPrimaryFocusedWhileDisabledShowsDimmedFrame() {
        // MB-395 — focused + disabled is a REAL state now: buttons stay
        // focusable while logically disabled (removing focusability is
        // what threw TV focus onto the main sidebar when "Check now" /
        // Save disabled themselves mid-press). The cursor parked on a
        // disabled button must stay visible — but at a dimmed alpha of
        // the ring, never the full FocusRing, so it doesn't promise an
        // action the button won't perform. Text stays muted.
        val c = primarySolidColors(palette, focused = true, enabled = false)
        assertEquals(palette.FocusRing.copy(alpha = DISABLED_FOCUS_FRAME_ALPHA), c.borderColor)
        assertNotEquals(palette.FocusRing, c.borderColor)
        assertEquals(palette.TextMuted, c.textColor)
        // Focus width still applies — thickness + dim ring is the
        // parked-cursor signature.
        assertEquals(true, c.borderWidthIsFocus)
    }

    // ── translucent primary ────────────────────────────────────────

    @Test fun translucentPrimaryIdleUsesAccentForTextAndSoftBorder() {
        val c = primaryTranslucentColors(palette, focused = false, enabled = true)
        // Translucent variant is meant to read as "primary action embedded
        // in a longer section" — accent text on accent-tinted bg. NOT
        // TextPrimary (that's secondary's job).
        assertEquals(palette.Accent, c.textColor)
        // Soft accent at 55% — a hint, not the full ring.
        assertEquals(palette.Accent.copy(alpha = 0.55f), c.borderColor)
    }

    @Test fun translucentPrimaryFocusedShowsFocusRingAndAccentGlowText() {
        val c = primaryTranslucentColors(palette, focused = true, enabled = true)
        assertEquals(palette.FocusRing, c.borderColor)
        // AccentGlow (lighter accent) on focus — clearly brighter than
        // the resting Accent text. THIS is the brightness step the user
        // asked for: rest is darker brand colour, focus is the lit version.
        assertEquals(palette.AccentGlow, c.textColor)
        assertTrue(c.borderWidthIsFocus)
    }

    @Test fun translucentPrimaryDisabledMutesText() {
        val c = primaryTranslucentColors(palette, focused = false, enabled = false)
        assertEquals(palette.TextMuted, c.textColor)
        // Disabled border is alpha-20 accent — barely a hint of
        // affordance, no focus promotion.
        assertEquals(palette.Accent.copy(alpha = 0.20f), c.borderColor)
        assertEquals(false, c.borderWidthIsFocus)
    }

    // ── secondary ─────────────────────────────────────────────────

    @Test fun secondaryIdleUsesPanelBorderAndPrimaryText() {
        val c = secondaryColors(palette, focused = false, enabled = true)
        // Generic hairline at rest — secondary buttons are companions,
        // not the headline. PanelBorder is the design's "thin neutral
        // outline on a translucent surface" token.
        assertEquals(palette.PanelBorder, c.borderColor)
        assertEquals(palette.TextPrimary, c.textColor)
    }

    @Test fun secondaryFocusedShowsFocusRingAndAccentGlowText() {
        val c = secondaryColors(palette, focused = true, enabled = true)
        // Secondary's focus signal is the bg tint shift to accent-low-
        // alpha + this FocusRing border + AccentGlow text. The three
        // signals together (plus the halo + scale on the composable
        // side) are what carries at 3 m on Fire TV. A focus-only border
        // swap proved insufficient in MK.16 testing.
        assertEquals(palette.FocusRing, c.borderColor)
        assertEquals(palette.AccentGlow, c.textColor)
    }

    @Test fun secondaryDisabledUsesBorderSubtleAndMutedText() {
        val c = secondaryColors(palette, focused = false, enabled = false)
        assertEquals(palette.BorderSubtle, c.borderColor)
        assertEquals(palette.TextMuted, c.textColor)
    }

    // ── danger ────────────────────────────────────────────────────

    @Test fun dangerIdleUsesAlphaErrorBorderAndErrorText() {
        val c = dangerColors(palette, focused = false, enabled = true)
        // 40% Error at rest — visible enough to read as "destructive"
        // but not screaming. Full Error is reserved for focus so the
        // user has a clear "yes you're about to delete something"
        // moment when the cursor lands.
        assertEquals(palette.Error.copy(alpha = 0.4f), c.borderColor)
        assertEquals(palette.Error, c.textColor)
    }

    @Test fun dangerFocusedPromotesBorderToFullError() {
        val c = dangerColors(palette, focused = true, enabled = true)
        // Danger's focus signal is Error at full saturation — the "you
        // are about to do something destructive" moment. NOT FocusRing
        // — destructive actions must read distinctly from neutral
        // confirms even at full focus.
        assertEquals(palette.Error, c.borderColor)
        assertTrue(c.borderWidthIsFocus)
    }

    @Test fun dangerDisabledMutesEverything() {
        val c = dangerColors(palette, focused = false, enabled = false)
        assertEquals(palette.Error.copy(alpha = 0.16f), c.borderColor)
        assertEquals(palette.TextMuted, c.textColor)
    }

    // ── parked cursor on a disabled button (MB-395) ───────────────
    // Buttons never leave the focus system when logically disabled, so
    // every variant must render a visible-but-dimmed frame when the
    // cursor sits on a disabled button. Full ring = actionable; dimmed
    // ring = "you are here, but this control is busy/unavailable"; no
    // ring at all = the cursor vanished, which is the original bug.

    @Test fun translucentPrimaryFocusedWhileDisabledShowsDimmedFrame() {
        val c = primaryTranslucentColors(palette, focused = true, enabled = false)
        assertEquals(palette.FocusRing.copy(alpha = DISABLED_FOCUS_FRAME_ALPHA), c.borderColor)
        assertEquals(palette.TextMuted, c.textColor)
        assertTrue(c.borderWidthIsFocus)
    }

    @Test fun secondaryFocusedWhileDisabledShowsDimmedFrame() {
        val c = secondaryColors(palette, focused = true, enabled = false)
        assertEquals(palette.FocusRing.copy(alpha = DISABLED_FOCUS_FRAME_ALPHA), c.borderColor)
        assertEquals(palette.TextMuted, c.textColor)
        assertTrue(c.borderWidthIsFocus)
    }

    @Test fun dangerFocusedWhileDisabledShowsDimmedErrorFrame() {
        // Danger dims its OWN hue, not FocusRing — destructive stays
        // visually distinct from neutral in every state.
        val c = dangerColors(palette, focused = true, enabled = false)
        assertEquals(palette.Error.copy(alpha = DISABLED_FOCUS_FRAME_ALPHA), c.borderColor)
        assertEquals(palette.TextMuted, c.textColor)
        assertTrue(c.borderWidthIsFocus)
    }

    @Test fun dimmedFrameIsPaletteDerivedAcrossThemes() {
        // Same guard as focusRingAlwaysComesFromPaletteNotAHardcodedColor,
        // for the parked-cursor state: the dim frame must follow the
        // theme's FocusRing token, not a pinned colour.
        listOf(FrostedEmerald, MidnightSapphire, WarmAmber).forEach { p ->
            val expected = p.FocusRing.copy(alpha = DISABLED_FOCUS_FRAME_ALPHA)
            assertEquals(expected, primarySolidColors(p, focused = true, enabled = false).borderColor)
            assertEquals(expected, primaryTranslucentColors(p, focused = true, enabled = false).borderColor)
            assertEquals(expected, secondaryColors(p, focused = true, enabled = false).borderColor)
        }
    }

    // ── cross-palette guards ──────────────────────────────────────

    @Test fun focusRingAlwaysComesFromPaletteNotAHardcodedColor() {
        // Catch a future "let me just hardcode a white border so it's
        // visible everywhere" regression. The FocusRing token is what
        // theme-swap (MK.16.2 Sapphire/Amber/Mono) depends on — if we
        // pin it, the theme picker stops working.
        listOf(FrostedEmerald, MidnightSapphire, WarmAmber).forEach { p ->
            assertEquals(p.FocusRing, primarySolidColors(p, focused = true, enabled = true).borderColor)
            assertEquals(p.FocusRing, primaryTranslucentColors(p, focused = true, enabled = true).borderColor)
            assertEquals(p.FocusRing, secondaryColors(p, focused = true, enabled = true).borderColor)
        }
    }

    // ── solid primary brightness step (the MK.UI.BTN-fix2 fix) ───

    @Test fun solidPrimaryRestIsDarkerThanFocus() {
        // The contract that fixes the "Resume button selector isn't
        // visible because rest is already brightest" complaint. Rest
        // must paint the DARKER end of the accent ramp so focus has
        // somewhere brighter to climb. Locked specifically: rest's TOP
        // stop must be `AccentDeep` (the brand mid-tone), and focus's
        // TOP stop must be `Accent` (the bright brand colour). If a
        // future "let's make rest pop more" PR puts Accent back on the
        // resting state, this test trips.
        val rest = primarySolidGradientStops(palette, focused = false, enabled = true)
        val focus = primarySolidGradientStops(palette, focused = true, enabled = true)

        assertEquals(palette.AccentDeep, rest.top)
        assertEquals(palette.AccentMuted, rest.bottom)
        assertEquals(palette.Accent, focus.top)
        assertEquals(palette.AccentDeep, focus.bottom)
        // And the two state gradients must not be identical — if they
        // are, the "off → on" jump disappears entirely.
        assertNotEquals(rest, focus)
    }

    @Test fun solidPrimaryFocusTopIsBrightestTokenAvailable() {
        // Every shipped palette must put its brightest enabled
        // accent token on the focused button's top stop. This is what
        // makes the cursor visible across themes — Emerald lights up
        // `#00E28A`, Sapphire `#4A8CFF`, Amber `#FFB14A`. A future
        // palette that forgets to define a brighter Accent than
        // AccentDeep trips here.
        listOf(FrostedEmerald, MidnightSapphire, WarmAmber).forEach { p ->
            val focusTop = primarySolidGradientStops(p, focused = true, enabled = true).top
            val restTop = primarySolidGradientStops(p, focused = false, enabled = true).top
            assertEquals(p.Accent, focusTop)
            assertEquals(p.AccentDeep, restTop)
            assertNotEquals(focusTop, restTop)
        }
    }

    @Test fun solidPrimaryDisabledStopsAreMutedRegardlessOfFocus() {
        // Disabled buttons ARE focusable since MB-395 (the cursor can
        // park on them), but the FILL must still not promote — only the
        // dimmed frame marks the cursor; a lit gradient would lie to
        // the user that they can act.
        val restDisabled = primarySolidGradientStops(palette, focused = false, enabled = false)
        val focusDisabled = primarySolidGradientStops(palette, focused = true, enabled = false)
        assertEquals(restDisabled, focusDisabled)
        // Top stop is the muted token alpha-blended down — not Accent,
        // not AccentDeep. A regression that lets disabled paint the
        // bright gradient trips here.
        assertEquals(palette.AccentMuted.copy(alpha = 0.6f), restDisabled.top)
    }

    @Test fun translucentPrimaryAndSecondaryAreVisuallyDistinct() {
        // Pre-unification, several screens used "translucent primary"
        // and "secondary" interchangeably — the user saw two buttons
        // labeled "Play" / "Remove" rendered with subtly different
        // chrome and couldn't tell which was the headline action.
        // Lock in that the two are not synonyms.
        val tp = primaryTranslucentColors(palette, focused = false, enabled = true)
        val sec = secondaryColors(palette, focused = false, enabled = true)
        // Idle text differs — Accent (brand) vs TextPrimary (neutral).
        assertNotEquals(tp.textColor, sec.textColor)
        // Idle border differs — soft accent vs neutral panel border.
        assertNotEquals(tp.borderColor, sec.borderColor)
    }
}
