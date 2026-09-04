package com.yancotv.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MK.38.2 — the dock's overflow rule.
 *
 * The bug this guards is not "the row looks cramped". Compose's `Row` measures
 * children past the edge at **zero width**: they are not clipped, they are
 * absent, with no visual sign that anything was dropped. The dock is ordered
 * with the menu last, so the first control a narrow screen deleted was the one
 * that could reach every other control. That is why the pinning assertions
 * below matter more than the arithmetic ones.
 */
class DockFitTest {

    /** Standard dock at the touch floor: hero, transport, divider, secondaries. */
    private val widths = mapOf(
        DockControl.SKIP_BACK to 48f,
        DockControl.PLAY_PAUSE to 48f,
        DockControl.SKIP_FORWARD to 48f,
        DockControl.NEXT to 48f,
        DockControl.DIVIDER to 9f,
        DockControl.SUBTITLES to 48f,
        DockControl.AUDIO to 53.8f,
        DockControl.SPEED to 53.8f,
        DockControl.ASPECT to 48f,
        DockControl.FAVORITE to 48f,
        DockControl.MENU to 48f,
    )

    private fun width(list: List<DockControl>, gap: Float): Float {
        if (list.isEmpty()) return 0f
        return list.fold(0f) { a, c -> a + widths.getValue(c) } + gap * (list.size - 1)
    }

    /** 640dp landscape phone, inside the dock shell's 48dp side padding. */
    private val phone640 = 640f - 96f - 2 * 10f

    @Test
    fun `a wide dock drops nothing`() {
        val order = dockControlOrder(hasNext = true)
        val fit = fitDockControls(order, widths, gapDp = 5f, availableDp = 4000f)
        assertEquals(order, fit.shown)
        assertTrue(fit.overflow.isEmpty())
    }

    @Test
    fun `exactly enough room drops nothing`() {
        val order = dockControlOrder(hasNext = true)
        val fit = fitDockControls(order, widths, gapDp = 5f, availableDp = width(order, 5f))
        assertEquals(order, fit.shown)
    }

    @Test
    fun `one dp short drops the favourite, and only the favourite`() {
        val order = dockControlOrder(hasNext = true)
        val fit = fitDockControls(order, widths, gapDp = 5f, availableDp = width(order, 5f) - 1f)
        assertEquals(listOf(DockControl.FAVORITE), fit.overflow)
        assertTrue(DockControl.FAVORITE !in fit.shown)
    }

    @Test
    fun `it drops in the documented order, least useful first`() {
        val order = dockControlOrder(hasNext = true)
        val seen = mutableListOf<DockControl>()
        var available = width(order, 5f)
        while (available > 0) {
            available -= 20f
            for (c in fitDockControls(order, widths, 5f, available).overflow) {
                if (c !in seen) seen += c
            }
        }
        assertEquals(
            listOf(
                DockControl.FAVORITE,
                DockControl.ASPECT,
                DockControl.SPEED,
                DockControl.AUDIO,
                DockControl.SUBTITLES,
            ),
            seen,
        )
    }

    @Test
    fun `the menu is never dropped, at any width`() {
        // The load-bearing assertion. The three-dot control is what stands in
        // for everything dropped; dropping IT is the failure this file exists
        // for, and it is what the unmeasured Row did first.
        for (available in 0..800 step 7) {
            val fit = fitDockControls(dockControlOrder(hasNext = true), widths, 5f, available.toFloat())
            assertTrue("menu missing at ${available}dp", DockControl.MENU in fit.shown)
            assertTrue("menu leaked into overflow at ${available}dp", DockControl.MENU !in fit.overflow)
        }
    }

    @Test
    fun `transport is never dropped, at any width`() {
        val transport = listOf(DockControl.SKIP_BACK, DockControl.PLAY_PAUSE, DockControl.SKIP_FORWARD)
        for (available in 0..800 step 7) {
            val shown = fitDockControls(dockControlOrder(hasNext = true), widths, 5f, available.toFloat()).shown
            assertTrue("transport incomplete at ${available}dp", shown.containsAll(transport))
        }
    }

    @Test
    fun `the divider goes once it separates nothing`() {
        val order = dockControlOrder(hasNext = true)
        val tiny = fitDockControls(order, widths, 5f, availableDp = 10f)
        assertTrue(DockControl.DIVIDER !in tiny.shown)
        // ...but it stays while there is still a second cluster to separate.
        val oneDropped = fitDockControls(order, widths, 5f, width(order, 5f) - 1f)
        assertTrue(DockControl.DIVIDER in oneDropped.shown)
    }

    @Test
    fun `overflow keeps the row order, not the order things were dropped in`() {
        // The menu renders these; a list reading FAV, FIT, SPEED reads as a
        // ranking of something rather than as the row it came from.
        val order = dockControlOrder(hasNext = true)
        val fit = fitDockControls(order, widths, 5f, availableDp = 260f)
        assertTrue(fit.overflow.size > 1)
        assertEquals(fit.overflow.sortedBy { order.indexOf(it) }, fit.overflow)
    }

    @Test
    fun `nothing appears in both shown and overflow`() {
        for (available in 0..800 step 13) {
            val fit = fitDockControls(dockControlOrder(hasNext = true), widths, 5f, available.toFloat())
            val both = fit.shown.intersect(fit.overflow.toSet())
            assertTrue("$both at ${available}dp", both.isEmpty())
        }
    }

    @Test
    fun `a control missing from the widths map counts as zero rather than throwing`() {
        // The map comes from the composable. A control added to the order and
        // forgotten in the map must not crash a running player.
        val order = dockControlOrder(hasNext = true)
        val fit = fitDockControls(order, widths - DockControl.SPEED, 5f, availableDp = 500f)
        assertTrue(fit.shown.isNotEmpty())
    }

    @Test
    fun `a 640dp phone in landscape overflows, and keeps the menu`() {
        val order = dockControlOrder(hasNext = true)
        assertTrue(
            "the row is supposed to be too wide here — if this fails the premise " +
                "changed and the numbers in the KDoc are stale",
            width(order, 5f) > phone640,
        )
        val fit = fitDockControls(order, widths, 5f, phone640)
        assertTrue(fit.overflow.isNotEmpty())
        assertTrue(DockControl.MENU in fit.shown)
        assertTrue(width(fit.shown, 5f) <= phone640)
    }

    @Test
    fun `Spanish drops at least as much as English at the same width`() {
        // VELOCIDAD is four characters longer than SPEED. The locale is part of
        // the fit, which is why the widths are resolved from stringResource.
        val hex = 48f
        val en = widths +
            (DockControl.SPEED to PlayerChromeMetrics.dockSecondaryWidthDp("SPEED", hex)) +
            (DockControl.ASPECT to PlayerChromeMetrics.dockSecondaryWidthDp("FIT", hex))
        val es = widths +
            (DockControl.SPEED to PlayerChromeMetrics.dockSecondaryWidthDp("VELOCIDAD", hex)) +
            (DockControl.ASPECT to PlayerChromeMetrics.dockSecondaryWidthDp("AJUSTE", hex))
        val order = dockControlOrder(hasNext = true)
        val enFit = fitDockControls(order, en, 5f, phone640)
        val esFit = fitDockControls(order, es, 5f, phone640)
        assertTrue(
            "es=${esFit.overflow} en=${enFit.overflow}",
            esFit.overflow.size >= enFit.overflow.size,
        )
    }

    @Test
    fun `live has no next to drop and still fits the same way`() {
        val live = dockControlOrder(hasNext = true, isLive = true)
        assertTrue(DockControl.NEXT !in live)
        val fit = fitDockControls(live, widths, 5f, phone640)
        assertTrue(DockControl.MENU in fit.shown)
        assertTrue(width(fit.shown, 5f) <= phone640)
    }

    @Test
    fun `the focus order follows what is shown, not what the brief lists`() {
        // A cursor stop for a control that is not on screen is a press that
        // does nothing. dockFocusOrder(shown) is the honest answer; the
        // hasNext overload answers a different question on purpose.
        val order = dockControlOrder(hasNext = true)
        val fit = fitDockControls(order, widths, 5f, phone640)
        val focus = dockFocusOrder(fit.shown)
        assertTrue(DockControl.DIVIDER !in focus)
        for (dropped in fit.overflow) {
            assertTrue("$dropped is focusable but not rendered", dropped !in focus)
        }
        assertEquals(fit.shown.filterNot { it == DockControl.DIVIDER }, focus)
    }
}
