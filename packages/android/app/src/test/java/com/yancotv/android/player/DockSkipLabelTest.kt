package com.yancotv.android.player

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MK.38.4 — the digits on the -10 / +10 controls follow the UI language.
 *
 * Found by looking at the dock on a phone set to Arabic: the row read `-10` in
 * ASCII while the clock two lines above it read `٨:٠٣` in Arabic-Indic. Both
 * are numbers, both were rendered by the same app, in the same glance.
 */
class DockSkipLabelTest {

    @Test
    fun `Arabic gets Arabic-Indic digits`() {
        assertEquals("١٠", dockSkipLabelDigits(10, Locale.forLanguageTag("ar")))
    }

    @Test
    fun `English Spanish and French are unchanged`() {
        for (tag in listOf("en", "es", "fr")) {
            assertEquals(tag, "10", dockSkipLabelDigits(10, Locale.forLanguageTag(tag)))
        }
    }

    @Test
    fun `the label states the step the buttons actually seek by`() {
        // The point of passing SeekAccelerator.BASE_STEP_SEC rather than a
        // literal: if the step changes, this test moves with it and the label
        // cannot go on claiming 10.
        assertEquals(
            dockSkipLabelDigits(SeekAccelerator.BASE_STEP_SEC, Locale.ENGLISH),
            SeekAccelerator.BASE_STEP_SEC.toString(),
        )
    }

    @Test
    fun `no locale produces an empty or grouped label`() {
        // A thousands separator would break the hexagon's width budget, and an
        // empty string would render a blank control. Neither happens at these
        // magnitudes, and this says so out loud.
        for (tag in listOf("en", "ar", "es", "fr", "de", "hi")) {
            val s = dockSkipLabelDigits(SeekAccelerator.BASE_STEP_SEC, Locale.forLanguageTag(tag))
            assertTrue("$tag produced '$s'", s.isNotBlank() && s.length <= 3)
        }
    }
}
