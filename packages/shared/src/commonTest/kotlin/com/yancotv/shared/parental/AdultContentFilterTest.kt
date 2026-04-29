package com.yancotv.shared.parental

import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AdultContentFilter] — heuristic, so the tests document the intentional
 * strictness (false negatives over false positives). Titles like
 * "Adult Swim" must NOT be classified as adult; category strings like
 * "XXX Adults Only" MUST be.
 */
class AdultContentFilterTest {
    @Test fun flagsStrongMarkerInCategory() {
        assertTrue(AdultContentFilter.isAdult(item(group = "XXX Adults Only")))
        assertTrue(AdultContentFilter.isAdult(item(group = "ADULTS ONLY")))
        assertTrue(AdultContentFilter.isAdult(item(group = "18+ Channels")))
        assertTrue(AdultContentFilter.isAdult(item(group = "+18 Movies")))
    }

    @Test fun flagsStrongMarkerInTitle() {
        assertTrue(AdultContentFilter.isAdult(item(title = "Playboy TV")))
        assertTrue(AdultContentFilter.isAdult(item(title = "Brazzers HD")))
        assertTrue(AdultContentFilter.isAdult(item(title = "Penthouse Gold")))
    }

    @Test fun doesNotFlagFamilyFriendlyAdultSwim() {
        // "Adult Swim" is the Cartoon Network brand; isolated "adult"
        // must NOT match in a title.
        assertFalse(AdultContentFilter.isAdult(item(title = "Adult Swim")))
        assertFalse(AdultContentFilter.isAdult(item(title = "Young Adult Cinema")))
    }

    @Test fun doesNotFlagSportsChannelsWith18HD() {
        // Substring matches on "18" must not flag "Sport18HD" or similar.
        assertFalse(AdultContentFilter.isAdult(item(title = "Sport 18HD")))
        assertFalse(AdultContentFilter.isAdult(item(title = "M18 Channel")))
    }

    @Test fun flagsHyphenatedOrRunTogetherXxx() {
        assertTrue(AdultContentFilter.isAdult(item(group = "XXX-HD")))
        assertTrue(AdultContentFilter.isAdult(item(group = "XXXadult")))
    }

    @Test fun emptyGroupAndTitleFallsThrough() {
        assertFalse(AdultContentFilter.isAdult(item(group = null, title = "")))
    }

    @Test fun cleanTitleTakesPrecedenceOverTitle() {
        // Provider title has an adult marker but the cleaned variant
        // (post title-cleaner) doesn't — we normalize on clean_title when
        // present, so should NOT flag.
        assertFalse(
            AdultContentFilter.isAdult(
                ContentItem(
                    id = "c1",
                    sourceId = "s1",
                    type = ContentType.LIVE,
                    title = "[XXX] Premium",
                    cleanTitle = "Premium Documentary",
                    groupName = "Education",
                    streamUrl = "http://x",
                    sortOrder = 0,
                    createdAt = 0L,
                ),
            ),
        )
    }

    @Test fun groupTokenPrecedenceWhenBothSet() {
        // Clean title is safe, but the category is an adult bucket — flag.
        assertTrue(
            AdultContentFilter.isAdult(
                ContentItem(
                    id = "c1",
                    sourceId = "s1",
                    type = ContentType.LIVE,
                    title = "Late Night",
                    cleanTitle = "Late Night",
                    groupName = "18+ Late",
                    streamUrl = "http://x",
                    sortOrder = 0,
                    createdAt = 0L,
                ),
            ),
        )
    }

    private fun item(title: String = "Channel", group: String? = null) = ContentItem(
        id = "c1",
        sourceId = "s1",
        type = ContentType.LIVE,
        title = title,
        cleanTitle = title,
        groupName = group,
        streamUrl = "http://stream",
        sortOrder = 0,
        createdAt = 0L,
    )
}
