package com.yancotv.android.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MK.31.2 — [sliderValueForX] contract.
 *
 * The settings slider draws its fill from `Alignment.CenterStart`, which Compose
 * mirrors under RTL, so the track's minimum follows the layout start. The touch
 * mapping used to be a raw `x / width`, i.e. physical — so in Arabic, tapping
 * the visually-empty end of the bar jumped to the minimum. The LTR cases below
 * also pin that this change is a no-op for existing users.
 */
class SliderValueForXTest {
    private val range = 0..100
    private val width = 200f

    private fun ltr(x: Float, step: Int = 1) = sliderValueForX(x, width, range, step, rtl = false)

    private fun rtl(x: Float, step: Int = 1) = sliderValueForX(x, width, range, step, rtl = true)

    @Test
    fun `ltr maps left to minimum and right to maximum`() {
        assertEquals(0, ltr(0f))
        assertEquals(50, ltr(100f))
        assertEquals(100, ltr(200f))
    }

    @Test
    fun `rtl maps right to minimum and left to maximum`() {
        assertEquals(0, rtl(200f))
        assertEquals(50, rtl(100f))
        assertEquals(100, rtl(0f))
    }

    @Test
    fun `the midpoint is the same value in both directions`() {
        assertEquals(ltr(100f), rtl(100f))
    }

    @Test
    fun `taps outside the track clamp instead of overshooting`() {
        // detectHorizontalDragGestures reports positions past the bounds once
        // the pointer leaves the composable.
        assertEquals(0, ltr(-40f))
        assertEquals(100, ltr(9999f))
        assertEquals(100, rtl(-40f))
        assertEquals(0, rtl(9999f))
    }

    @Test
    fun `values snap to the step in both directions`() {
        // A 5-step slider must never emit 47.
        val stepped = (0..200 step 7).map { ltr(it.toFloat(), step = 5) }
        assertEquals(emptyList(), stepped.filter { it % 5 != 0 }, "LTR emitted off-step values")
        val steppedRtl = (0..200 step 7).map { rtl(it.toFloat(), step = 5) }
        assertEquals(emptyList(), steppedRtl.filter { it % 5 != 0 }, "RTL emitted off-step values")
    }

    @Test
    fun `every result is inside the range`() {
        for (x in -100..300 step 3) {
            for (isRtl in listOf(false, true)) {
                val v = sliderValueForX(x.toFloat(), width, range, 5, isRtl)
                assertEquals(true, v in range, "x=$x rtl=$isRtl produced $v outside $range")
            }
        }
    }

    @Test
    fun `a non-positive width returns the minimum instead of dividing by zero`() {
        // BoxWithConstraints can report 0 for a frame during layout.
        assertEquals(range.first, sliderValueForX(50f, 0f, range, 1, rtl = false))
        assertEquals(range.first, sliderValueForX(50f, -1f, range, 1, rtl = true))
    }

    @Test
    fun `a non-zero-based range still maps end to end`() {
        // Buffer-size sliders use ranges like 5..60.
        val r = 5..60
        assertEquals(5, sliderValueForX(0f, width, r, 5, rtl = false))
        assertEquals(60, sliderValueForX(width, width, r, 5, rtl = false))
        assertEquals(60, sliderValueForX(0f, width, r, 5, rtl = true))
        assertEquals(5, sliderValueForX(width, width, r, 5, rtl = true))
    }
}
