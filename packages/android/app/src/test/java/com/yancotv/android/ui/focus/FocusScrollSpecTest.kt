package com.yancotv.android.ui.focus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MK.30.1 (MB-307) — contract for [focusScrollDistance].
 *
 * The bug these tests exist to prevent: D-pad DOWN through a Settings tab
 * then back UP left the section header clipped above the first row, with no
 * focusable above it to scroll further. The old spec used a 32dp margin,
 * which is smaller than a section header (~80dp), so it declared itself
 * satisfied while the header was still off-screen.
 *
 * Coordinates are pixels. A "viewport" of 400 and rows of 100 stand in for a
 * TV settings pane at density 1 — the math is density-independent because
 * the composable converts dp to px before calling in.
 */
class FocusScrollSpecTest {
    private val head = 96f
    private val foot = 32f
    private val viewport = 400f

    private fun distance(offset: Float, size: Float = 100f, containerSize: Float = viewport) = focusScrollDistance(
        offset = offset,
        size = size,
        containerSize = containerSize,
        headroomPx = head,
        footroomPx = foot,
    )

    /** Applying the returned delta shifts the element by that much. */
    private fun settle(offset: Float, size: Float = 100f, containerSize: Float = viewport): Float {
        val d = distance(offset, size, containerSize)
        return offset - d
    }

    // ---------------------------------------------------------------- the bug

    @Test
    fun `first row flush with the viewport top scrolls back to reveal its header`() {
        // The exact reported state: bring-into-view has parked the first row
        // at the very top of the viewport, so the section header above it is
        // clipped. The spec must ask to scroll *backward*.
        val d = distance(offset = 0f)
        assertTrue(d < 0f, "expected a backward scroll to reveal the header, got $d")
        assertEquals(-96f, d)
    }

    @Test
    fun `a row inside the headroom band still asks for the full headroom`() {
        // The old 32dp spec stopped here and called it done. 32px of headroom
        // does not clear an 80px section header.
        assertEquals(-64f, distance(offset = 32f))
        assertEquals(96f, settle(offset = 32f))
    }

    @Test
    fun `a row clipped above the viewport is pulled fully into view plus headroom`() {
        assertEquals(-126f, distance(offset = -30f))
        assertEquals(96f, settle(offset = -30f))
    }

    // ------------------------------------------------------------- stability

    @Test
    fun `every settled position is a fixed point`() {
        // No oscillation: one application of the delta must always land
        // somewhere the spec then leaves alone. This is the property that
        // stops a focus event from ping-ponging the scroll container.
        val sizes = listOf(10f, 100f, 250f, 360f, 399f)
        val offsets = (-200..600 step 7).map { it.toFloat() }
        for (size in sizes) {
            for (offset in offsets) {
                val settled = settle(offset, size)
                assertEquals(
                    0f,
                    distance(settled, size),
                    "not a fixed point: size=$size offset=$offset settled=$settled",
                )
            }
        }
    }

    @Test
    fun `a fully visible row with both margins clear is left alone`() {
        assertEquals(0f, distance(offset = 150f))
        assertEquals(0f, distance(offset = 96f))
        assertEquals(0f, distance(offset = 268f)) // trailing edge exactly on the footroom boundary
    }

    // ---------------------------------------------------------------- forward

    @Test
    fun `a row clipped below the viewport scrolls forward and keeps footroom`() {
        val d = distance(offset = 350f)
        assertEquals(82f, d)
        assertEquals(268f, settle(offset = 350f))
    }

    @Test
    fun `a row inside the footroom band scrolls forward to clear it`() {
        assertEquals(2f, distance(offset = 270f))
    }

    // ------------------------------------------------------- tall focusables

    @Test
    fun `a row too tall for both margins keeps headroom and shrinks footroom`() {
        // slack = 400 - 360 = 40, so headroom clamps to 40 and footroom to 0.
        // Defect 2 in the old spec: this case failed both branches and
        // returned 0, leaving the row clipped.
        val size = 360f
        assertEquals(40f, settle(offset = 60f, size = size))
        val settled = settle(offset = 60f, size = size)
        assertTrue(settled >= 0f, "leading edge clipped: $settled")
        assertTrue(settled + size <= viewport, "trailing edge clipped: ${settled + size}")
    }

    @Test
    fun `a focusable taller than the viewport is still brought into view`() {
        // Defect 1 in the old spec: `size >= containerSize` returned 0
        // unconditionally, so a focusable taller than the pane never
        // scrolled into view at all.
        val size = 500f
        assertEquals(120f, distance(offset = 120f, size = size))
        assertEquals(0f, settle(offset = 120f, size = size))
    }

    @Test
    fun `a focusable already spanning the viewport is left alone`() {
        // Preserves the About-tab flicker fix: the whole dpadVerticalScroll
        // Column is the focusable, it always spans the pane, and returning a
        // delta on every focus event fed back into animateScrollBy.
        assertEquals(0f, distance(offset = 0f, size = 500f))
        assertEquals(0f, distance(offset = -80f, size = 500f))
    }

    @Test
    fun `a tall focusable clipped at the bottom anchors its trailing edge`() {
        assertEquals(-100f, distance(offset = -200f, size = 500f))
        assertEquals(-100f, settle(offset = -200f, size = 500f))
    }

    // --------------------------------------------------------------- degenerate

    @Test
    fun `sub-pixel corrections are suppressed`() {
        // Float residue from a previous scroll must not re-trigger one.
        assertEquals(0f, distance(offset = 95.7f))
        assertEquals(0f, distance(offset = 96.3f))
    }

    @Test
    fun `a zero-height viewport asks for nothing`() {
        // Happens for one frame during layout, before the pane is measured.
        assertEquals(0f, distance(offset = 0f, containerSize = 0f))
        assertEquals(0f, distance(offset = 50f, containerSize = 0f))
    }

    @Test
    fun `a zero-height focusable is positioned at the headroom`() {
        // FocusableSpacer is a 0-dp focus trap; it must not wedge the spec.
        assertEquals(96f, settle(offset = 10f, size = 0f))
        assertEquals(0f, distance(offset = 96f, size = 0f))
    }
}
