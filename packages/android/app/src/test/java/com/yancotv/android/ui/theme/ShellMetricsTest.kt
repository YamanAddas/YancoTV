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
