package com.yancotv.shared.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The decorative letterforms are taken verbatim from a real provider's
 * channel list — that is where the bug was found and what it has to fix.
 */
class UnicodeStyleFoldTest {

    @Test
    fun `folds superscript letters and digits back to ASCII`() {
        assertEquals("UHD 3840P", UnicodeStyleFold.fold("ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
        assertEquals(": V SPORT UHD 3840P", UnicodeStyleFold.fold(": V SPORT ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
        assertEquals("US| PRIME RAW 60fps", UnicodeStyleFold.fold("US| PRIME ᴿᴬᵂ ⁶⁰ᶠᵖˢ"))
    }

    @Test
    fun `folds fullwidth forms`() {
        assertEquals("HD 4K", UnicodeStyleFold.fold("ＨＤ ４Ｋ"))
    }

    @Test
    fun `leaves plain text untouched and does not copy it`() {
        val plain = "BBC News HD"
        assertSame(plain, UnicodeStyleFold.fold(plain))
        assertEquals("", UnicodeStyleFold.fold(""))
    }

    @Test
    fun `leaves decoration that is not a letterform alone`() {
        // Separators, box drawing and emoji are what a viewer is reading
        // the list by; folding them would change how titles look rather
        // than what they match as.
        assertEquals("##### UHD #####", UnicodeStyleFold.fold("##### ᵁᴴᴰ #####"))
        assertEquals("beIN | Sports ⚽", UnicodeStyleFold.fold("beIN | Sports ⚽"))
    }

    @Test
    fun `quality badges now parse from a styled title`() {
        // The measured failure: 0 badges across 1,300 live channels.
        assertEquals(listOf(QualityBadge.UHD_4K), QualityBadge.parse("ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
        assertEquals(listOf(QualityBadge.UHD_4K), QualityBadge.parse(": V SPORT ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
        // And an ASCII title keeps working exactly as before.
        assertEquals(listOf(QualityBadge.UHD_4K, QualityBadge.HDR), QualityBadge.parse("Some Movie 2019 4K HDR"))
    }
}
