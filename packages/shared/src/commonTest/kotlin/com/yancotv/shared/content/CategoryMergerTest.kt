package com.yancotv.shared.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CategoryMergerTest {
    @Test fun mergesSameBaseAcrossLanguagePrefixes() {
        // MB-382 — the James Bond case: English (1) + German (26) categories
        // fold into one "JAMES BOND 007" chip that loads from both.
        val merged = CategoryMerger.merge(listOf("JAMES BOND 007", "DE - JAMES BOND 007"))
        assertEquals(1, merged.size)
        assertEquals("JAMES BOND 007", merged[0].displayName)
        assertEquals(listOf("JAMES BOND 007", "DE - JAMES BOND 007"), merged[0].rawGroupNames)
    }

    @Test fun stripsPrefixEvenForASingleCategory() {
        val merged = CategoryMerger.merge(listOf("EN - NEW RELEASE"))
        assertEquals(1, merged.size)
        assertEquals("NEW RELEASE", merged[0].displayName)
        assertEquals(listOf("EN - NEW RELEASE"), merged[0].rawGroupNames)
    }

    @Test fun preservesProviderOrderByFirstAppearance() {
        val merged = CategoryMerger.merge(
            listOf("Sports", "DE - JAMES BOND 007", "AR - Movies", "JAMES BOND 007"),
        )
        // James Bond takes the slot of its first contributor (index 1), not the
        // English one that appeared later.
        assertEquals(listOf("Sports", "JAMES BOND 007", "Movies"), merged.map { it.displayName })
        assertEquals(listOf("DE - JAMES BOND 007", "JAMES BOND 007"), merged[1].rawGroupNames)
    }

    @Test fun doesNotStripAnUnrecognisedLeadingToken() {
        // "IT" resolves (Italy) but "ZZ" does not; a real title starting with a
        // non-code token must survive intact and not merge with anything.
        val merged = CategoryMerger.merge(listOf("ZZ Top Hits", "IT - Cinema"))
        assertEquals(setOf("ZZ Top Hits", "Cinema"), merged.map { it.displayName }.toSet())
        val zz = merged.first { it.displayName == "ZZ Top Hits" }
        assertEquals(listOf("ZZ Top Hits"), zz.rawGroupNames)
    }

    @Test fun mergeKeyIsCaseInsensitive() {
        val merged = CategoryMerger.merge(listOf("kids", "DE - Kids", "AR - KIDS"))
        assertEquals(1, merged.size)
        assertEquals(3, merged[0].rawGroupNames.size)
        assertTrue(merged[0].displayName.equals("kids", ignoreCase = true))
    }

    @Test fun emptyInputYieldsEmpty() {
        assertEquals(emptyList(), CategoryMerger.merge(emptyList()))
    }
}
