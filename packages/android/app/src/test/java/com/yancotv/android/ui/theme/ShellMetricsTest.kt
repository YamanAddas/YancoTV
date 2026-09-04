package com.yancotv.android.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout *decisions* are what these pin down. The individual sizes are
 * proportions and will be tuned; the rules about which composition a window
 * gets are the part that must not drift, because two screens disagreeing about
 * the shape of the same window is the bug class this object exists to remove.
 */
class ShellMetricsTest {

    private fun metrics(width: Int, height: Int, rail: Int = 0) =
        ShellMetrics(
            windowWidth = width.dp,
            windowHeight = height.dp,
            lane = (width - rail).dp,
        )

    // Real viewports, in dp.
    private val fireTv = metrics(960, 540, rail = 92)
    private val phonePortrait = metrics(411, 869)
    private val phoneLandscape = metrics(869, 411, rail = 92)
    private val tabletPortrait = metrics(800, 1280, rail = 92)
    private val tabletLandscape = metrics(1280, 800, rail = 92)

    // ───── The navigation rule ─────

    @Test
    fun `television keeps the side rail`() {
        assertTrue(fireTv.usesSidebar)
    }

    @Test
    fun `phone in portrait gets the bottom bar`() {
        assertFalse(phonePortrait.usesSidebar)
    }

    /**
     * A mainstream phone in landscape is 869 dp across — genuinely wide, so the
     * width test alone already gives it the rail. Worth pinning because the iOS
     * port needed a second clause here: UIKit still calls an iPhone in landscape
     * "compact" width, so a width-only rule there produced a top bar and a tab
     * bar out of a 411 dp-tall viewport. Android reports real dp and does not
     * have that trap.
     */
    @Test
    fun `mainstream phone in landscape keeps the rail on width alone`() {
        assertTrue(phoneLandscape.usesSidebar)
        assertFalse("869 dp is not narrow", phoneLandscape.narrowViewport)
        assertTrue(phoneLandscape.shortViewport)
    }

    /**
     * This is the case `shortViewport` actually exists for. A small phone
     * sideways is 568x320 — narrow *and* short, so the width test says bottom
     * bar and would spend ~140 dp of a 320 dp-tall window on chrome. Height is
     * what is scarce, so the rail wins.
     */
    @Test
    fun `small phone in landscape keeps the rail because height is what is scarce`() {
        val smallLandscape = metrics(568, 320, rail = 92)
        assertTrue(smallLandscape.narrowViewport)
        assertTrue(smallLandscape.shortViewport)
        assertTrue("height is the scarce axis — rail, not tab bar", smallLandscape.usesSidebar)
    }

    @Test
    fun `tablet keeps the rail in both orientations`() {
        assertTrue(tabletPortrait.usesSidebar)
        assertTrue(tabletLandscape.usesSidebar)
    }

    // ───── The composition rule ─────

    @Test
    fun `television gets the coverflow`() {
        assertTrue(fireTv.usesCoverflow)
    }

    @Test
    fun `phone in portrait gets the grid`() {
        assertFalse(phonePortrait.usesCoverflow)
    }

    @Test
    fun `phone in landscape gets the coverflow`() {
        assertTrue(phoneLandscape.usesCoverflow)
    }

    /**
     * A tablet in portrait is a *tall lane*, which is the same shape problem a
     * phone in portrait has — so it gets the grid too. This is why the rule
     * reads the lane and not the device.
     */
    @Test
    fun `tablet in portrait gets the grid despite being a large screen`() {
        assertFalse(tabletPortrait.usesCoverflow)
        assertTrue(tabletLandscape.usesCoverflow)
    }

    // ───── Sizes scale rather than switch ─────

    /**
     * The bug the whole object exists for: an SE and a Pro Max are both
     * "compact" and used to get byte-identical tiles despite 110 dp of width
     * between them.
     */
    @Test
    fun `a wider phone gets wider tiles`() {
        val se = metrics(320, 568)
        val proMax = metrics(430, 932)
        assertTrue(
            "expected ${proMax.tileWidth} > ${se.tileWidth}",
            proMax.tileWidth > se.tileWidth,
        )
    }

    @Test
    fun `tile width is clamped at both ends`() {
        assertEquals(150.dp, metrics(200, 800).tileWidth)
        assertEquals(ShellDim.posterTile, metrics(4000, 2000).tileWidth)
    }

    /**
     * A landscape phone has ~411 dp of height in total. The portrait hero would
     * be most of the screen on its own, so the short-viewport branch has to give
     * the majority of it back.
     */
    @Test
    fun `hero gives height back when height is scarce`() {
        assertTrue(
            "landscape hero ${phoneLandscape.heroHeight} should be shorter " +
                "than portrait's ${phonePortrait.heroHeight}",
            phoneLandscape.heroHeight < phonePortrait.heroHeight,
        )
        assertTrue(phoneLandscape.heroHeight <= phoneLandscape.windowHeight * 0.5f)
    }

    /**
     * The regression this cap exists for. Sizing the hero from the lane alone
     * gave the Fire TV 380 dp against the 320 the shell has always drawn — a
     * 60 dp jump, 70% of a 540 dp viewport on one card. A wide short window is
     * exactly where width-only reasoning fails.
     */
    @Test
    fun `hero stays close to the television's own number on a wide short window`() {
        val hero = fireTv.heroHeight
        assertTrue(
            "Fire TV hero was $hero; must stay near the shipped 320.dp",
            hero <= 340.dp && hero >= 300.dp,
        )
    }

    @Test
    fun `hero never takes more than three fifths of the fold`() {
        for (m in listOf(fireTv, phonePortrait, phoneLandscape, tabletPortrait, tabletLandscape)) {
            assertTrue(
                "hero ${m.heroHeight} of ${m.windowHeight}",
                m.heroHeight <= m.windowHeight * 0.61f,
            )
        }
    }

    // ───── The browse grid ─────

    /**
     * The grid's column count is derived, never fixed — the bug this replaced
     * was a hard-coded three, which made *tile size* swing with the screen
     * instead of the count doing.
     *
     * The band deliberately excludes the smallest phone: a 320 dp screen hits
     * the two-column floor and lands at 138 dp, which is what the reference
     * build does on a small iPhone. Everything from a normal phone upward sits
     * within a few points.
     */
    @Test
    fun `grid tiles stay about the same size from a normal phone upward`() {
        val screens = listOf(
            metrics(411, 869), metrics(430, 932), metrics(708, 1280), metrics(868, 540),
        )
        for (m in screens) {
            assertTrue(
                "cell ${m.gridCell} on a ${m.lane} lane is outside the comfortable band",
                m.gridCell >= 110.dp && m.gridCell <= 128.dp,
            )
        }
    }

    /**
     * Three columns on a normal phone, not four.
     *
     * A first attempt targeted 88 dp and gave four, which was worse than the
     * size it set out to fix: a channel name had ~84 dp to live in, so almost
     * every one truncated and the rows read as a wall of clipped text. Provider
     * names run long and similar — "3Cat Exclusiu 1/2/3" — so an ellipsis eats
     * exactly the part that tells them apart.
     */
    @Test
    fun `a normal phone gets three columns`() {
        assertEquals(3, metrics(411, 869).gridColumns)
        assertEquals(3, metrics(430, 932).gridColumns)
    }

    @Test
    fun `a wider screen gets more columns, not bigger tiles`() {
        val phone = metrics(411, 869)
        val tablet = metrics(708, 1280)
        assertTrue(phone.gridColumns < tablet.gridColumns)
        assertTrue(
            "tablet cell ${tablet.gridCell} should not balloon past the phone's ${phone.gridCell}",
            tablet.gridCell <= phone.gridCell + 12.dp,
        )
    }

    @Test
    fun `grid never drops below two columns`() {
        assertTrue(metrics(200, 800).gridColumns >= 2)
    }

    // ───── The detail page ─────

    /**
     * The regression this caught. A height-only fraction gave the Fire TV
     * 220 dp against the 330 the detail page has always drawn — a third of the
     * backdrop gone. Nothing read the property yet, which is the only reason it
     * did not ship.
     */
    @Test
    fun `detail backdrop keeps the television's own height`() {
        val h = fireTv.detailHeroHeight
        assertTrue("Fire TV detail backdrop was $h, shipped is 330.dp", h >= 320.dp && h <= 330.dp)
    }

    @Test
    fun `detail backdrop does not swallow a tall window`() {
        assertTrue(
            "portrait backdrop ${phonePortrait.detailHeroHeight} of ${phonePortrait.windowHeight}",
            phonePortrait.detailHeroHeight <= phonePortrait.windowHeight * 0.40f,
        )
    }

    /**
     * The 200 dp poster is 23% of the television's lane and 49% of a phone's.
     * With a gutter either side that left the title column 91 dp, which is what
     * pushed the detail page into the right-hand sliver of the screen.
     */
    @Test
    fun `detail poster leaves the title column room on a phone`() {
        val m = phonePortrait
        val remaining = m.lane - m.pageInset * 2 - m.detailPosterWidth
        assertTrue("title column had $remaining", remaining >= 200.dp)
        assertEquals(ShellDim.detailPosterWidth, fireTv.detailPosterWidth)
    }

    @Test
    fun `page inset never eats a small phone and never floats a large one`() {
        assertEquals(Space.lg, metrics(320, 568).pageInset)
        assertEquals(Space.page, metrics(3000, 2000).pageInset)
    }

    @Test
    fun `category panel never exceeds the television width`() {
        assertEquals(ShellDim.categoriesPanelWidth, fireTv.categoryPanel)
        assertTrue(phoneLandscape.categoryPanel < ShellDim.categoriesPanelWidth)
        assertTrue(phoneLandscape.categoryPanel >= 168.dp)
    }
}
