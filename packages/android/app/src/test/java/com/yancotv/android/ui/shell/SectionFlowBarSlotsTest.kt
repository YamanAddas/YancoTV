package com.yancotv.android.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MB-416 — a tab activates the tab the viewer pressed, in either direction.
 *
 * The property is not "the arithmetic is right". It is that **the slot at a
 * visual position is the one drawn there**, which is what a person testing this
 * by hand actually checks, and which the shipped code got backwards for every
 * RTL language.
 */
class SectionFlowBarSlotsTest {

    // The bar as measured on the owner's Pixel XL: six slots of 226 px.
    private val slotPx = 226f
    private val slots = 6
    private val last = slots - 1

    /** Centre of the nth slot counting from the physical left edge. */
    private fun visualCentre(n: Int) = (n + 0.5f) * slotPx

    @Test
    fun `left to right, LTR reads the slots in order`() {
        for (n in 0 until slots) {
            assertEquals(n, flowBarSlotAt(visualCentre(n), slotPx, slots, rtl = false))
        }
    }

    @Test
    fun `left to right, RTL reads them reversed - which is what the Row draws`() {
        for (n in 0 until slots) {
            assertEquals(last - n, flowBarSlotAt(visualCentre(n), slotPx, slots, rtl = true))
        }
    }

    @Test
    fun `the two device observations that opened this bug`() {
        // Arabic. "المزيد" is drawn at the LEFT end; tapping it used to go Home.
        assertEquals(
            "the left end in RTL is the More slot",
            last,
            flowBarSlotAt(155f - 42f, slotPx, slots, rtl = true),
        )
        // "الرئيسية" is drawn at the RIGHT end; tapping it used to open More.
        assertEquals(
            "the right end in RTL is Home",
            0,
            flowBarSlotAt(1285f - 42f, slotPx, slots, rtl = true),
        )
    }

    @Test
    fun `every position lands inside the bar`() {
        for (rtl in listOf(false, true)) {
            var x = -50f
            while (x < slotPx * slots + 50f) {
                val slot = flowBarSlotAt(x, slotPx, slots, rtl)
                assertTrue("x=$x rtl=$rtl -> $slot", slot in 0..last)
                x += 7f
            }
        }
    }

    @Test
    fun `a zero slot width does not divide`() {
        // First frame, before measurement. Returning 0 beats a crash or an
        // index built from Infinity.
        assertEquals(0, flowBarSlotAt(100f, 0f, slots, rtl = false))
        assertEquals(0f, flowBarDragTarget(100f, 0f, slots, rtl = false), 0.0001f)
    }

    @Test
    fun `dragging tracks the finger under both directions`() {
        // The indicator should sit centred on the finger, so a finger at the
        // centre of the slot drawn for index n yields exactly n.
        for (n in 0 until slots) {
            assertEquals(
                "LTR slot $n",
                n.toFloat(),
                flowBarDragTarget(visualCentre(n), slotPx, slots, rtl = false),
                0.0001f,
            )
            assertEquals(
                "RTL slot $n",
                (last - n).toFloat(),
                flowBarDragTarget(visualCentre(n), slotPx, slots, rtl = true),
                0.0001f,
            )
        }
    }

    @Test
    fun `drag and tap agree about which slot a position belongs to`() {
        // They are separate expressions, and the drag commits by rounding its
        // own value. If they ever disagree, releasing a drag lands somewhere
        // other than where it looked.
        for (rtl in listOf(false, true)) {
            for (n in 0 until slots) {
                val x = visualCentre(n)
                val tapped = flowBarSlotAt(x, slotPx, slots, rtl)
                val dragged = Math.round(
                    flowBarDragTarget(x, slotPx, slots, rtl).coerceIn(0f, last.toFloat()),
                )
                assertEquals("rtl=$rtl slot $n", tapped, dragged)
            }
        }
    }
}
