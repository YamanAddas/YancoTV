package com.yancotv.android.ui.focus

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MK.31.2 — logical-direction contract.
 *
 * These look trivial, and that is the point: the whole RTL navigation fix rests
 * on this one mapping, so an inverted `if` here would silently break every
 * pane-escape handler in the app on Arabic while leaving LTR perfect. The
 * LTR row of each test also pins that this change is a **no-op** for existing
 * users, which is the property that matters most before device verification.
 */
class DirectionalNavTest {
    private val ltr = LayoutDirection.Ltr
    private val rtl = LayoutDirection.Rtl

    @Test
    fun `ltr behaviour is unchanged from the hardcoded physical keys`() {
        // Every site this replaced used Left to back out and Right to go
        // deeper. LTR must still resolve exactly that.
        assertEquals(Key.DirectionLeft, startwardKey(ltr))
        assertEquals(Key.DirectionRight, endwardKey(ltr))
        assertEquals(FocusDirection.Left, startwardFocus(ltr))
        assertEquals(FocusDirection.Right, endwardFocus(ltr))
    }

    @Test
    fun `rtl inverts both keys and focus directions`() {
        assertEquals(Key.DirectionRight, startwardKey(rtl))
        assertEquals(Key.DirectionLeft, endwardKey(rtl))
        assertEquals(FocusDirection.Right, startwardFocus(rtl))
        assertEquals(FocusDirection.Left, endwardFocus(rtl))
    }

    @Test
    fun `startward and endward are never the same key`() {
        for (dir in listOf(ltr, rtl)) {
            assertTrue(startwardKey(dir) != endwardKey(dir), "collapsed to one key in $dir")
            assertTrue(startwardFocus(dir) != endwardFocus(dir), "collapsed to one direction in $dir")
        }
    }

    @Test
    fun `isStartward and isEndward agree with the resolvers`() {
        for (dir in listOf(ltr, rtl)) {
            assertTrue(isStartward(startwardFocus(dir), dir))
            assertFalse(isStartward(endwardFocus(dir), dir))
            assertTrue(isEndward(endwardFocus(dir), dir))
            assertFalse(isEndward(startwardFocus(dir), dir))
        }
    }

    @Test
    fun `vertical directions are never mistaken for horizontal ones`() {
        // The `exit` lambdas these feed receive Up/Down too. Treating a DOWN
        // press as "back out to the sidebar" would hijack vertical traversal.
        for (dir in listOf(ltr, rtl)) {
            for (vertical in listOf(FocusDirection.Up, FocusDirection.Down)) {
                assertFalse(isStartward(vertical, dir), "$vertical read as startward in $dir")
                assertFalse(isEndward(vertical, dir), "$vertical read as endward in $dir")
            }
            // Next/Previous (tab-order traversal) must not read as horizontal
            // either — a phone keyboard TAB would otherwise exit the pane.
            for (linear in listOf(FocusDirection.Next, FocusDirection.Previous)) {
                assertFalse(isStartward(linear, dir), "$linear read as startward in $dir")
                assertFalse(isEndward(linear, dir), "$linear read as endward in $dir")
            }
        }
    }

    @Test
    fun `the two layout directions are exact mirrors of each other`() {
        assertEquals(startwardKey(ltr), endwardKey(rtl))
        assertEquals(endwardKey(ltr), startwardKey(rtl))
        assertEquals(startwardFocus(ltr), endwardFocus(rtl))
        assertEquals(endwardFocus(ltr), startwardFocus(rtl))
    }
}
