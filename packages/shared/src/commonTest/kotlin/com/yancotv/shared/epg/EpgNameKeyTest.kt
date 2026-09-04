package com.yancotv.shared.epg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MK.39 — the normaliser that decides whether a channel gets a guide.
 *
 * It arrived from the iOS fork in MK.36 with no tests on this side, and
 * MK.39's measurement leaned on it directly: **+3,396 of the owner's 50,342
 * live rows** would gain a guide through these keys. Pinning the behaviour
 * before building on it, because the failure mode is silent and asymmetric —
 * a key that is too loose puts *the wrong programme* on a channel, which the
 * viewer has no way to detect, while a key that is too strict merely leaves
 * the line blank.
 *
 * Every input below is a real title from the owner's catalogue or a real
 * XMLTV name, not an invented one.
 */
class EpgNameKeyTest {

    // ── the whole point: the same channel, decorated differently ──

    @Test
    fun `decorative letterforms fold to plain letters`() {
        // ᵁᴴᴰ is not the letters U H D — separate code points, which every
        // ASCII matcher misses. This is why the playlist and the guide could
        // never see each other.
        assertEquals("skysportsf1", EpgNameKey.keyFor("4K: SKY SPORTS F1 ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
        assertEquals("elevensports1", EpgNameKey.keyFor("4K: ELEVEN SPORTS 1 ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
    }

    @Test
    fun `the same channel at four qualities reduces to one key`() {
        val keys = listOf(
            "SKY SPORTS F1",
            "SKY SPORTS F1 HD",
            "SKY SPORTS F1 FHD",
            "4K: SKY SPORTS F1 ᵁᴴᴰ ³⁸⁴⁰ᴾ",
        ).map { EpgNameKey.keyFor(it) }
        assertEquals(1, keys.toSet().size, "expected one key, got $keys")
        assertEquals("skysportsf1", keys.first())
    }

    @Test
    fun `a country prefix identifies the playlist section, not the channel`() {
        // The guide never carries these.
        assertEquals(EpgNameKey.keyFor("BEIN SPORTS"), EpgNameKey.keyFor("AR| BEIN SPORTS"))
        assertEquals(EpgNameKey.keyFor("ATV HD"), EpgNameKey.keyFor("TR: ATV HD"))
    }

    @Test
    fun `Latin diacritics fold, because the guide spells them properly`() {
        assertEquals(EpgNameKey.keyFor("13eme Rue"), EpgNameKey.keyFor("13ème Rue"))
    }

    @Test
    fun `punctuation and emoji are decoration`() {
        assertEquals(EpgNameKey.keyFor("beIN SPORTS 1"), EpgNameKey.keyFor("beIN-Sports1 ⚽"))
    }

    // ── the half that stops it lying ──

    @Test
    fun `digits are KEPT, so sports channels cannot collapse into each other`() {
        // The single easiest way to put the wrong match on a sports channel
        // is to drop the trailing number.
        assertNotEquals(EpgNameKey.keyFor("beIN SPORTS 1"), EpgNameKey.keyFor("beIN SPORTS 2"))
        assertNotEquals(EpgNameKey.keyFor("EUROSPORT 1"), EpgNameKey.keyFor("EUROSPORT 2"))
    }

    @Test
    fun `a playlist banner is not a channel`() {
        // Measured: 869 of these in the owner's catalogue. They must never
        // claim a key, or one banner would take a guide entry hostage.
        assertNull(EpgNameKey.keyFor("##### 4K ᵁᴴᴰ ³⁸⁴⁰ᴾ #####"))
        assertNull(EpgNameKey.keyFor("####### SS ᴴᴰ #######"))
    }

    @Test
    fun `nothing, blank and pure decoration produce no key`() {
        assertNull(EpgNameKey.keyFor(null))
        assertNull(EpgNameKey.keyFor(""))
        assertNull(EpgNameKey.keyFor("   "))
        assertNull(EpgNameKey.keyFor("4K| ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
    }

    @Test
    fun `a key under four characters is refused, and that is measured, not assumed`() {
        // These are real channels and it still refuses them — TF1, QVC, ATV,
        // STC. MK.39 measured the alternative on the owner's own catalogue:
        // a three-character floor gains 379 rows and puts 497 under keys
        // demonstrably claimed by different tvg_ids. A wrong guide is worse
        // than none, so the floor stays. If this test is ever changed, that
        // measurement has to be re-run first.
        assertNull(EpgNameKey.keyFor("4K: TF1 ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
        assertNull(EpgNameKey.keyFor("AR: STC 4K"))
        assertNull(EpgNameKey.keyFor("TV"))
    }

    @Test
    fun `a name that is only a number is a slot, not an identity`() {
        assertNull(EpgNameKey.keyFor("1234"))
    }

    // ── the index ──

    @Test
    fun `a contested key is decided by programme count, not discarded`() {
        // Measured against a real 756-channel guide: only 133 names appeared
        // exactly once, so discarding every duplicate took a 295-channel
        // playlist from 32 matches to 4.
        val index = EpgNameKey.uniqueIndex(
            channels = listOf("thin.id" to "BBC One", "full.id" to "BBC One"),
            programmeCounts = mapOf("thin.id" to 3, "full.id" to 480),
        )
        assertEquals("full.id", index[EpgNameKey.keyFor("BBC One")])
    }

    @Test
    fun `a tie is broken deterministically, so refreshes do not flip the answer`() {
        val a = EpgNameKey.uniqueIndex(
            listOf("bbb.id" to "BBC One", "aaa.id" to "BBC One"),
            mapOf("bbb.id" to 10, "aaa.id" to 10),
        )
        val b = EpgNameKey.uniqueIndex(
            listOf("aaa.id" to "BBC One", "bbb.id" to "BBC One"),
            mapOf("bbb.id" to 10, "aaa.id" to 10),
        )
        // Both being empty would satisfy the equality and prove nothing.
        assertEquals(1, a.size, "expected one indexed key, got $a")
        assertEquals(a, b, "the index must not depend on the order channels appear in the file")
    }

    @Test
    fun `channels the normaliser refuses never reach the index`() {
        val index = EpgNameKey.uniqueIndex(
            listOf("x.id" to "TV", "y.id" to null, "z.id" to "Eurosport 1"),
        )
        assertEquals(1, index.size, index.toString())
        assertTrue(index.containsValue("z.id"))
    }

    @Test
    fun `an empty guide indexes to nothing rather than throwing`() {
        assertTrue(EpgNameKey.uniqueIndex(emptyList()).isEmpty())
    }
}
