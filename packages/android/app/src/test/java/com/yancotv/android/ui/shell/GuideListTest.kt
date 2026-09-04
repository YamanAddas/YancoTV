package com.yancotv.android.ui.shell

import com.yancotv.shared.types.EpgGuideChannel
import com.yancotv.shared.types.EpgProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The row's three decisions, which are the whole of the portrait guide's logic.
 *
 * All times are **unix seconds**, not milliseconds — `epg_programmes.start_time`
 * / `end_time` are XMLTV epoch seconds and the guide compares against
 * `clock() / 1000`. Getting that wrong empties the guide silently, which is
 * exactly what MB-390 was.
 */
class GuideListTest {

    private fun programme(id: String, start: Long, end: Long) = EpgProgramme(id = id, channelTvgId = "c", title = "P$id", startTime = start, endTime = end)

    private fun channel(vararg p: EpgProgramme) = EpgGuideChannel(id = "ch", tvgId = "c", name = "Channel", programmes = p.toList())

    private val now = 1_000_000L

    @Test
    fun `now is the programme whose window contains the clock`() {
        val c = channel(
            programme("a", now - 7200, now - 3600),
            programme("b", now - 600, now + 600),
            programme("c", now + 600, now + 3600),
        )
        assertEquals("Pb", c.nowProgramme(now)?.title)
    }

    /**
     * The half-open window matters at the seam. A programme that ends exactly
     * now is over; the one starting exactly now is on. Treating both ends as
     * inclusive shows two programmes as current for one second every half hour.
     */
    @Test
    fun `a programme ending exactly now is over`() {
        val c = channel(programme("a", now - 600, now), programme("b", now, now + 600))
        assertEquals("Pb", c.nowProgramme(now)?.title)
    }

    @Test
    fun `a gap in the guide yields no current programme`() {
        val c = channel(programme("a", now - 7200, now - 3600), programme("b", now + 3600, now + 7200))
        assertNull(c.nowProgramme(now))
    }

    @Test
    fun `a channel with no programmes yields nothing rather than throwing`() {
        assertNull(channel().nowProgramme(now))
        assertNull(channel().nextProgramme(now))
    }

    @Test
    fun `next is the earliest programme that has not started`() {
        val c = channel(
            programme("late", now + 7200, now + 10800),
            programme("soon", now + 600, now + 1200),
            programme("past", now - 600, now - 300),
        )
        assertEquals("Psoon", c.nextProgramme(now)?.title)
    }

    @Test
    fun `progress is the fraction elapsed`() {
        val p = programme("a", now - 300, now + 300)
        assertEquals(0.5f, programmeProgress(p, now), 0.001f)
    }

    @Test
    fun `progress is clamped outside the window`() {
        val p = programme("a", now, now + 600)
        assertEquals(0f, programmeProgress(p, now - 100), 0.001f)
        assertEquals(1f, programmeProgress(p, now + 9999), 0.001f)
    }

    /**
     * Providers ship rows nobody validated, including ones where the programme
     * ends when it starts. Without the guard that is a divide by zero on a
     * screen that is only trying to draw a progress bar.
     */
    @Test
    fun `a zero-length programme does not divide by zero`() {
        assertEquals(0f, programmeProgress(programme("a", now, now), now), 0.001f)
        assertEquals(0f, programmeProgress(programme("a", now, now - 60), now), 0.001f)
    }
}
