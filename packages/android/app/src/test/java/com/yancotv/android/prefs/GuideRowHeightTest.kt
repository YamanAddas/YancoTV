package com.yancotv.android.prefs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MK.15 — guide row-height preference. */
class GuideRowHeightTest {
    @Test fun unsetFallsBackToStandard() {
        assertEquals(GuideRowHeight.STANDARD, GuideRowHeight.fromKey(null))
    }

    @Test fun unknownKeyFallsBackRatherThanThrowing() {
        // a value written by a newer build, or a corrupted pref, must not
        // crash the guide on launch
        assertEquals(GuideRowHeight.STANDARD, GuideRowHeight.fromKey("gigantic"))
        assertEquals(GuideRowHeight.STANDARD, GuideRowHeight.fromKey(""))
    }

    @Test fun everyKeyRoundTrips() {
        GuideRowHeight.values().forEach {
            assertEquals(it, GuideRowHeight.fromKey(it.key), "key '${it.key}' must round-trip")
        }
    }

    @Test fun standardIsUnchangedFromThePreSettingLayout() {
        // shipping this must be a visual no-op until the user chooses
        // otherwise — 56dp is what the guide hardcoded before MK.15
        assertEquals(56, GuideRowHeight.STANDARD.heightDp)
    }

    @Test fun theOptionsAreOrderedAndDistinct() {
        val heights = GuideRowHeight.values().map { it.heightDp }
        assertEquals(heights.sorted(), heights, "declaration order must go compact -> comfortable")
        assertEquals(heights.distinct().size, heights.size, "two options that look identical are a bug")
        assertTrue(heights.all { it > 0 }, "a zero-height row would make the guide invisible")
    }
}
