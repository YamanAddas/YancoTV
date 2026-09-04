package com.yancotv.android.locale

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MK.38.5 — the numeral rule.
 *
 * Found by putting the owner's phone into Arabic: the app showed `بقي ٢ د`
 * and `100%` on the same screens, because `String.format` consults the locale
 * and a Kotlin string template cannot. These tests pin the half that is now
 * locale-aware; nothing here says an identifier should be transliterated, and
 * `ChannelNumberFormat` deliberately still uses `toString()`.
 */
class NumeralsTest {

    private val ar = Locale.forLanguageTag("ar")
    private val en = Locale.ENGLISH

    @Test
    fun `Arabic integers use Arabic-Indic digits`() {
        assertEquals("١٠", Numerals.format(10, ar))
        assertEquals("٠", Numerals.format(0, ar))
    }

    @Test
    fun `English Spanish and French integers are unchanged`() {
        for (tag in listOf("en", "es", "fr")) {
            assertEquals(tag, "10", Numerals.format(10, Locale.forLanguageTag(tag)))
        }
    }

    @Test
    fun `a percentage carries the locale's own percent sign`() {
        // Arabic's sign is ٪ (U+066A), not the ASCII %. Appending "%" by hand —
        // which is what the font-scale readout used to do — gets this wrong in
        // a way no amount of correct digits fixes.
        val arabic = Numerals.percent(100, ar)
        assertTrue(arabic, arabic.contains("١٠٠"))
        assertTrue("expected the Arabic percent sign in $arabic", arabic.contains("٪"))
        assertTrue(Numerals.percent(125, en).startsWith("125"))
    }

    @Test
    fun `percent takes the number a person would say, not a fraction`() {
        // 100 means 100%, not 10 000%. Every call site holds the value this
        // way; converting at each of them is one more place to divide wrong.
        assertTrue(Numerals.percent(100, en).contains("100"))
        assertTrue(Numerals.percent(50, en).contains("50"))
    }

    @Test
    fun `Arabic and English actually differ, so the test is not vacuous`() {
        // If a JVM ever resolved "ar" without its numbering system, every
        // assertion above would pass against Western digits and prove nothing.
        assertNotEquals(Numerals.format(1234, ar), Numerals.format(1234, en))
    }

    @Test
    fun `negatives and large values stay single-token`() {
        // The dock draws these inside a 48dp hexagon. A grouping separator at
        // four digits is expected; a line break or an empty string is not.
        for (tag in listOf("en", "ar", "es", "fr", "de")) {
            val locale = Locale.forLanguageTag(tag)
            for (v in listOf(-30L, 0L, 10L, 90L, 3600L)) {
                val s = Numerals.format(v, locale)
                assertTrue("$tag/$v -> '$s'", s.isNotBlank() && !s.contains("\n"))
            }
        }
    }
}
