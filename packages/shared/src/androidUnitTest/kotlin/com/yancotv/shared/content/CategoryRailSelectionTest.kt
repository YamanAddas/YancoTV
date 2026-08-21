package com.yancotv.shared.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MK.35.2 — [pickCategoryRails] contract.
 *
 * The rule exists because Home has room for three rails and the catalogue has
 * thousands of categories. The tests that matter are the ones asserting the
 * user's existing Settings choices are honoured: pinning, hiding and renaming a
 * category all already worked in Browse, and none of them reached Home. A
 * setting that works in one place and is ignored in another reads as a bug in
 * the setting, not as a missing feature on the home screen.
 */
class CategoryRailSelectionTest {

    @Test
    fun `pinned categories win over bigger ones`() {
        val rails = pickCategoryRails(
            pinned = listOf("Kids"),
            hidden = emptySet(),
            bySize = listOf("Movies", "Series", "Sports"),
        )
        assertEquals("Kids", rails.first().groupKey, "a pin is an instruction, not a hint")
    }

    @Test
    fun `size fills the remaining slots after pins`() {
        val rails = pickCategoryRails(
            pinned = listOf("Kids"),
            hidden = emptySet(),
            bySize = listOf("Movies", "Series", "Sports"),
        )
        assertEquals(listOf("Kids", "Movies", "Series"), rails.map { it.groupKey })
    }

    @Test
    fun `with no pins it falls back to the biggest categories`() {
        val rails = pickCategoryRails(
            pinned = emptyList(),
            hidden = emptySet(),
            bySize = listOf("Movies", "Series", "Sports", "News"),
        )
        assertEquals(listOf("Movies", "Series", "Sports"), rails.map { it.groupKey })
    }

    @Test
    fun `a hidden category never appears, even if it is pinned`() {
        // Contradictory settings are possible — pin something, hide it later.
        // Hiding is the more recent and more emphatic instruction, and a hidden
        // category resurfacing on Home makes the user believe hide is broken.
        val rails = pickCategoryRails(
            pinned = listOf("Adult"),
            hidden = setOf("Adult"),
            bySize = listOf("Movies"),
        )
        assertTrue(rails.none { it.groupKey == "Adult" })
        assertEquals(listOf("Movies"), rails.map { it.groupKey })
    }

    @Test
    fun `a hidden category is excluded from the size fallback too`() {
        val rails = pickCategoryRails(
            pinned = emptyList(),
            hidden = setOf("Adult"),
            bySize = listOf("Adult", "Movies", "Series"),
        )
        assertEquals(listOf("Movies", "Series"), rails.map { it.groupKey })
    }

    @Test
    fun `the same category pinned and large does not produce two rails`() {
        val rails = pickCategoryRails(
            pinned = listOf("Movies"),
            hidden = emptySet(),
            bySize = listOf("Movies", "Series"),
        )
        assertEquals(listOf("Movies", "Series"), rails.map { it.groupKey })
    }

    @Test
    fun `de-duplication ignores case, because providers do not`() {
        // Real catalogues carry the same category under different casing across
        // playlists. Without this the user gets the same rail twice.
        val rails = pickCategoryRails(
            pinned = listOf("Turkish Series"),
            hidden = emptySet(),
            bySize = listOf("TURKISH SERIES", "Movies"),
        )
        assertEquals(listOf("Turkish Series", "Movies"), rails.map { it.groupKey })
    }

    @Test
    fun `hiding also ignores case`() {
        val rails = pickCategoryRails(
            pinned = emptyList(),
            hidden = setOf("adult"),
            bySize = listOf("ADULT", "Movies"),
        )
        assertEquals(listOf("Movies"), rails.map { it.groupKey })
    }

    @Test
    fun `a renamed category shows the user's name, not the provider's`() {
        val rails = pickCategoryRails(
            pinned = listOf("TR|DIZI|4K"),
            hidden = emptySet(),
            bySize = emptyList(),
            displayNames = mapOf("TR|DIZI|4K" to "Turkish Drama"),
        )
        assertEquals("Turkish Drama", rails.single().displayName)
        assertEquals("TR|DIZI|4K", rails.single().groupKey, "the key must stay the provider's, for querying")
    }

    @Test
    fun `blank and whitespace-only categories are dropped`() {
        // Providers ship these. A rail with no title is worse than one fewer rail.
        val rails = pickCategoryRails(
            pinned = listOf("   "),
            hidden = emptySet(),
            bySize = listOf("", "Movies"),
        )
        assertEquals(listOf("Movies"), rails.map { it.groupKey })
    }

    @Test
    fun `a zero limit yields nothing rather than everything`() {
        assertTrue(pickCategoryRails(listOf("Kids"), emptySet(), listOf("Movies"), limit = 0).isEmpty())
    }
}
