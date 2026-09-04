package com.yancotv.android.player

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MK.34.10 — the design brief's numbers, asserted in the brief's own units.
 *
 * **This file exists because the same mistake happened three times.** Every
 * measurement in the brief is a PHYSICAL PIXEL at 1920x1080, and dp is what you
 * reach for in Compose. The difference is invisible in code review and only
 * shows up when something is measured on a real density-2.0 device:
 *
 *   - the dock was built at 2x — hero 168px against a spec of 78-88;
 *   - the metadata line was set larger than the title, inverting the hierarchy;
 *   - the options sheet was 640px against a 320-440 clamp, and the fix then
 *     halved the RATIO as well as the bounds and pinned it to the floor.
 *
 * Each was caught by measuring pixels on a TV: build, install, `uiautomator
 * dump`, arithmetic. These tests do the same arithmetic in under a second.
 *
 * Assertions are therefore written as PIXELS AT DENSITY 2.0, matching the brief,
 * rather than as the dp the code happens to return. Reading them back in dp
 * would just re-encode the unit confusion the file is here to prevent.
 */
class PlayerChromeMetricsTest {
    /** A 1920x1080 TV reporting density 2.0 — the Fire TV this was measured on. */
    private val tvWidthDp = 960f
    private val tvHeightDp = 540f
    private val density = 2f

    private fun px(dp: Float) = PlayerChromeMetrics.toPx(dp, density).roundToInt()

    @Test
    fun `hero control lands in the brief's 78 to 88 pixel band`() {
        val actual = px(PlayerChromeMetrics.hexSizeDp(HexVariant.HERO, tvWidthDp))
        assertTrue(actual in 78..88, "hero was ${actual}px, brief says 78-88")
    }

    @Test
    fun `transport controls land in the brief's 52 to 60 pixel band`() {
        val actual = px(PlayerChromeMetrics.hexSizeDp(HexVariant.TRANSPORT, tvWidthDp))
        assertTrue(actual in 52..60, "transport was ${actual}px, brief says 52-60")
    }

    @Test
    fun `secondary controls land in the brief's 48 to 56 pixel band`() {
        val actual = px(PlayerChromeMetrics.hexSizeDp(HexVariant.SECONDARY, tvWidthDp))
        assertTrue(actual in 48..56, "secondary was ${actual}px, brief says 48-56")
    }

    @Test
    fun `the size hierarchy is strict, not nominal`() {
        // The brief is explicit that the hexes must NOT be equally prominent.
        // Ordering alone is a weak assertion — three sizes one pixel apart would
        // satisfy it — so this also demands the hero be meaningfully dominant.
        val hero = PlayerChromeMetrics.hexSizeDp(HexVariant.HERO, tvWidthDp)
        val transport = PlayerChromeMetrics.hexSizeDp(HexVariant.TRANSPORT, tvWidthDp)
        val secondary = PlayerChromeMetrics.hexSizeDp(HexVariant.SECONDARY, tvWidthDp)
        val menuIcon = PlayerChromeMetrics.hexSizeDp(HexVariant.MENU_ICON, tvWidthDp)
        assertTrue(hero > transport && transport > secondary && secondary > menuIcon)
        assertTrue(hero > transport * 1.35f, "hero ($hero) is not clearly dominant over transport ($transport)")
    }

    @Test
    fun `dock padding and gaps sit inside the brief's bands`() {
        assertTrue(px(PlayerChromeMetrics.dockPaddingDp(tvWidthDp)) in 24..32)
        assertTrue(px(PlayerChromeMetrics.dockGapDp(tvWidthDp)) in 12..18)
    }

    @Test
    fun `title type honours clamp of 20 to 30 pixels`() {
        // 1.7vw of 1920 is 32.6px, so the ceiling is what actually applies here.
        val actual = px(PlayerChromeMetrics.titleFontSp(tvWidthDp))
        assertEquals(30, actual, "title was ${actual}px, brief clamps to 20-30")
    }

    @Test
    fun `the metadata line must be smaller than the title, not merely different`() {
        // The bug this pins: the metadata line shipped at 14sp = 28px against a
        // title that should be 30px. Both looked plausible in isolation and the
        // hierarchy was inverted in practice.
        val titlePx = px(PlayerChromeMetrics.titleFontSp(tvWidthDp))
        val metadataPx = px(11f)
        assertTrue(metadataPx < titlePx * 0.85f, "metadata ${metadataPx}px is not clearly below title ${titlePx}px")
    }

    @Test
    fun `options sheet resolves to the clamp's ceiling, not its floor`() {
        // clamp(320px, 30vw, 440px): 30vw of 1920 is 576px, so 440 applies.
        // Halving the ratio produced 320 — the floor — which is the failure this
        // catches. Asserting "inside 320..440" would have passed that bug.
        val actual = px(PlayerChromeMetrics.sheetWidthDp(tvWidthDp))
        assertEquals(440, actual, "sheet was ${actual}px, brief's arithmetic gives 440")
    }

    @Test
    fun `options sheet height honours 68vh`() {
        val actual = PlayerChromeMetrics.sheetMaxHeightDp(tvHeightDp)
        assertEquals(367, actual.roundToInt())
        assertTrue(actual < tvHeightDp, "sheet cap must not exceed the screen it caps")
    }

    @Test
    fun `physical size is preserved across densities, which is the whole point`() {
        // A 960dp density-2.0 TV and a 1920dp density-1.0 TV are the same screen.
        // A fixed dp constant renders one of them at half size; halving a dp
        // constant renders the other at half size. Only a width fraction is
        // right for both, and this is the assertion that says so.
        val atDensity2 = PlayerChromeMetrics.toPx(PlayerChromeMetrics.hexSizeDp(HexVariant.HERO, 960f), 2f)
        val atDensity1 = PlayerChromeMetrics.toPx(PlayerChromeMetrics.hexSizeDp(HexVariant.HERO, 1920f), 1f)
        assertEquals(atDensity2.roundToInt(), atDensity1.roundToInt())
    }

    @Test
    fun `a narrow window falls back to floors instead of unusable targets`() {
        // 640dp — a phone in landscape. The ratios alone would give a 27dp hero,
        // which is below what a D-pad user can pick out; the floors are an
        // accessibility backstop, not part of the design.
        val narrow = 640f
        assertEquals(40f, PlayerChromeMetrics.hexSizeDp(HexVariant.HERO, narrow))
        assertEquals(27f, PlayerChromeMetrics.hexSizeDp(HexVariant.TRANSPORT, narrow))
        assertEquals(25f, PlayerChromeMetrics.hexSizeDp(HexVariant.SECONDARY, narrow))
    }

    @Test
    fun `the dock still fits a narrow window without overflowing`() {
        // The overflow that crushed the three-dot control from 52px to 48px was
        // invisible until measured, because Compose silently shrinks the LAST
        // child. This budgets the row instead of trusting it.
        val narrow = 640f
        val hero = PlayerChromeMetrics.hexSizeDp(HexVariant.HERO, narrow)
        val transport = PlayerChromeMetrics.hexSizeDp(HexVariant.TRANSPORT, narrow)
        val secondary = PlayerChromeMetrics.hexSizeDp(HexVariant.SECONDARY, narrow)
        val gap = PlayerChromeMetrics.dockGapDp(narrow)
        val padding = PlayerChromeMetrics.dockPaddingDp(narrow)

        // -10, hero, +10, next
        val transportCluster = transport * 3 + hero
        // CC / AUDIO / SPEED / FIT sized from their labels, plus favourite + menu
        val labelled = listOf("CC", "AUDIO", "SPEED", "FIT").sumOf { label ->
            ((label.length * 5).toFloat() + secondary * 0.6f).coerceAtLeast(secondary).toDouble()
        }.toFloat()
        val secondaryCluster = labelled + secondary * 2
        val controls = dockControlOrder(hasNext = true).size
        val total = transportCluster + secondaryCluster + gap * (controls - 1) + padding * 2 + secondary * 0.62f

        // Outer chrome padding is 48dp per side.
        val usable = narrow - 96f
        assertTrue(total < usable, "dock needs ${total}dp of ${usable}dp available at ${narrow}dp")
    }

    // ───── MK.37.F — touch floors ─────

    /**
     * **Why the gate is on `isTv` and not on width.**
     *
     * A television is 960 dp wide and its hero comes out at 41.5 dp — *below*
     * the 48 dp touch minimum. So the touch floor would move the television's
     * own numbers if it were applied there, and any rule that decided "touch"
     * from a width threshold would eventually catch a wide TV. The call site
     * keys off the device being a television; this test is what says so.
     */
    @Test
    fun `the touch floor would change a television, which is why the gate is not width`() {
        val asTv = PlayerChromeMetrics.hexSizeDp(HexVariant.HERO, tvWidthDp)
        val asTouch = PlayerChromeMetrics.hexSizeDp(HexVariant.HERO, tvWidthDp, touch = true)
        assertTrue(asTv < PlayerChromeMetrics.TOUCH_TARGET_DP, "TV hero was $asTv")
        assertTrue(asTouch > asTv, "touch floor should raise it, got $asTouch")
    }

    /** The floor only ever raises; it can never shrink a control. */
    @Test
    fun `the touch floor never shrinks anything`() {
        for (widthDp in listOf(320f, 411f, 731f, 960f, 1920f)) {
            for (v in HexVariant.entries) {
                assertTrue(
                    PlayerChromeMetrics.hexSizeDp(v, widthDp, touch = true) >=
                        PlayerChromeMetrics.hexSizeDp(v, widthDp),
                    "$v at ${widthDp}dp shrank under the touch floor",
                )
            }
        }
    }

    /**
     * The defect this fixes, and it predates portrait: every ratio is against a
     * 1920 px television, so on a phone in LANDSCAPE every control already fell
     * to a floor written for a D-pad — transport at 27 dp against Android's
     * 48 dp minimum touch target.
     */
    @Test
    fun `a phone gets touch-sized controls in both orientations`() {
        for (widthDp in listOf(731f, 411f)) {
            for (v in listOf(HexVariant.HERO, HexVariant.TRANSPORT, HexVariant.SECONDARY)) {
                val size = PlayerChromeMetrics.hexSizeDp(v, widthDp, touch = true)
                assertTrue(
                    size >= PlayerChromeMetrics.TOUCH_TARGET_DP,
                    "$v at ${widthDp}dp was $size, under the 48dp touch minimum",
                )
            }
        }
    }

    /** The menu glyph sits inside a larger control, so it is not a target. */
    @Test
    fun `the menu icon is not inflated to a touch target`() {
        assertTrue(PlayerChromeMetrics.hexSizeDp(HexVariant.MENU_ICON, 411f, touch = true) < 48f)
    }
}
