package com.yancotv.shared.epg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class EpgNameKeyTest {

    /**
     * The case this exists for: the provider's decorated title and the
     * guide's plain display-name are the same channel.
     */
    @Test
    fun `a decorated provider title matches the guide's plain name`() {
        assertEquals(
            EpgNameKey.keyFor("TNT Sports"),
            EpgNameKey.keyFor("TNT SPORTS ᵁᴴᴰ ³⁸⁴⁰ᴾ"),
        )
        assertEquals(
            EpgNameKey.keyFor("Sky Sports Darts"),
            EpgNameKey.keyFor("SKY SPORTS DARTS ᵁᴴᴰ ³⁸⁴⁰ᴾ"),
        )
        assertEquals(
            EpgNameKey.keyFor("beIN Sports 1"),
            EpgNameKey.keyFor("AR| BEIN SPORTS 1 ⁸ᴷ & ᴴᴰ ⚽"),
        )
    }

    /** The same channel listed once per quality is one channel. */
    @Test
    fun `quality variants of one channel share a key`() {
        val hd = EpgNameKey.keyFor("MBC 1 HD")
        assertEquals(hd, EpgNameKey.keyFor("MBC 1 FHD"))
        assertEquals(hd, EpgNameKey.keyFor("MBC 1 4K"))
        assertEquals(hd, EpgNameKey.keyFor("MBC 1 1080p"))
    }

    /**
     * Digits are load-bearing. Dropping trailing numbers is the fastest way
     * to put the wrong match on a sports channel.
     */
    @Test
    fun `channel numbers are kept apart`() {
        assertNotEquals(
            EpgNameKey.keyFor("beIN SPORTS 1"),
            EpgNameKey.keyFor("beIN SPORTS 2"),
        )
        assertNotEquals(
            EpgNameKey.keyFor("Sky Sport 5"),
            EpgNameKey.keyFor("Sky Sport 6"),
        )
    }

    @Test
    fun `a country or playlist prefix is not part of the name`() {
        assertEquals(EpgNameKey.keyFor("Canal Cocina"), EpgNameKey.keyFor("ES| CANAL COCINA"))
        assertEquals(EpgNameKey.keyFor("Yerli Filmler"), EpgNameKey.keyFor("TR - YERLI FILMLER"))
        assertEquals(EpgNameKey.keyFor("CNN"), EpgNameKey.keyFor("US: CNN"))
    }

    /**
     * Names that reduce to nothing meaningful must refuse to match rather
     * than collide with each other.
     */
    @Test
    fun `names that carry no identity return null`() {
        assertNull(EpgNameKey.keyFor("##### ᵁᴴᴰ ³⁸⁴⁰ᴾ #####"))
        assertNull(EpgNameKey.keyFor("4K| ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
        assertNull(EpgNameKey.keyFor("TV"))
        assertNull(EpgNameKey.keyFor("  "))
        assertNull(EpgNameKey.keyFor(null))
        // A bare number is a slot, not a channel.
        assertNull(EpgNameKey.keyFor("101"))
    }

    /**
     * Duplicated display-names are the normal case in real XMLTV — an HD
     * and an SD feed, or a regional variant. Measured on a public guide:
     * of 756 channels only 133 names appeared exactly once. Dropping every
     * contested key cost 32 possible matches down to 4, so the fullest
     * listing wins instead.
     */
    @Test
    fun `a contested name goes to the channel with the most listing`() {
        val index = EpgNameKey.uniqueIndex(
            listOf(
                "13emeRue.mu" to "13eme Rue",
                "13emerue.fr" to "13ème Rue",
                "TNTSports.uk" to "TNT Sports",
            ),
            programmeCounts = mapOf("13emeRue.mu" to 12, "13emerue.fr" to 340),
        )
        assertEquals("13emerue.fr", index[EpgNameKey.keyFor("13eme Rue")!!])
        assertEquals("TNTSports.uk", index[EpgNameKey.keyFor("TNT SPORTS ᵁᴴᴰ")!!])
    }

    /** Ties break on the id, so a refresh does not reshuffle the index. */
    @Test
    fun `a tie is broken deterministically`() {
        val channels = listOf("zzz.uk" to "Some Channel", "aaa.uk" to "Some Channel")
        val first = EpgNameKey.uniqueIndex(channels)
        val second = EpgNameKey.uniqueIndex(channels.reversed())
        assertEquals("aaa.uk", first[EpgNameKey.keyFor("Some Channel")!!])
        assertEquals(first, second)
    }

    /** The same channel appearing twice is not ambiguity. */
    @Test
    fun `a repeated channel keeps its key`() {
        val index = EpgNameKey.uniqueIndex(
            listOf("TNTSports.uk" to "TNT Sports", "TNTSports.uk" to "TNT Sports"),
        )
        assertEquals("TNTSports.uk", index[EpgNameKey.keyFor("TNT Sports")!!])
    }

    @Test
    fun `arabic names normalise without being emptied`() {
        val key = EpgNameKey.keyFor("قناة الجزيرة")
        assertEquals(key, EpgNameKey.keyFor("قناة الجزيرة ᴴᴰ"))
        assertNotEquals(null, key)
    }
}
