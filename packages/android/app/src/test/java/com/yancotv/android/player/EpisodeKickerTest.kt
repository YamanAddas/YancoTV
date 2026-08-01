package com.yancotv.android.player

import com.yancotv.shared.playback.Playable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * MB-340 (plan W2) — [episodeKicker] contract.
 *
 * The parsing here is coupled to two conventions that live elsewhere, and both
 * couplings are what these tests actually defend:
 *  - `Playable.Episode.title` is pre-joined `"<series> — <episode>"` with a
 *    U+2014 em dash (EpisodeInfo.toPlayable);
 *  - the user-facing episode code is UNPADDED `S1E2`
 *    (ContentDetailScreen.resumeButtonLabel).
 *
 * Negative control (run): changing the split literal from `" — "` (em dash) to
 * `" - "` (hyphen) turns `the bare episode title is split off the pre-joined
 * title` red; padding the code to `S%02dE%02d` turns `the episode code is
 * unpadded, matching ContentDetailScreen` red.
 */
class EpisodeKickerTest {
    private fun ep(season: Int, number: Int, title: String) = Playable.Episode(
        id = "ep-1",
        seriesId = "series-1",
        title = title,
        streamUrl = "http://example.invalid/ep.mkv",
        seasonNumber = season,
        episodeNumber = number,
    )

    @Test
    fun `no episode context means no kicker, so movies keep the brand line`() {
        assertNull(episodeKicker(null))
    }

    @Test
    fun `the episode code is unpadded, matching ContentDetailScreen`() {
        // S1E2, never S01E02 — two paddings for the same concept across two
        // screens reads as a bug.
        assertEquals("S1E2", episodeKicker(ep(1, 2, "Show — Pilot"))?.code)
        assertEquals("S10E24", episodeKicker(ep(10, 24, "Show — Finale"))?.code)
    }

    @Test
    fun `the bare episode title is split off the pre-joined title`() {
        // The em dash is the contract with EpisodeInfo.toPlayable.
        val k = episodeKicker(ep(2, 5, "Breaking Bad — Gray Matter"))
        assertEquals("S2E5", k?.code)
        assertEquals("Gray Matter", k?.title)
    }

    @Test
    fun `a title with no separator yields a code and no title`() {
        // Provider gave a flat title; better to show just the code than to
        // render the series name as though it were the episode name.
        val k = episodeKicker(ep(1, 1, "Just A Flat Title"))
        assertEquals("S1E1", k?.code)
        assertNull(k?.title)
    }

    @Test
    fun `an em dash inside the episode name survives the split`() {
        // substringAfter takes the FIRST separator, so everything past it is the
        // episode title even when the title itself contains a dash.
        val k = episodeKicker(ep(1, 3, "Show — Part One — The Arrival"))
        assertEquals("Part One — The Arrival", k?.title)
    }

    @Test
    fun `a title identical to the code is dropped rather than duplicated`() {
        // The autoplay path can synthesise exactly this; "S1E2 · S1E2" looks
        // broken.
        assertNull(episodeKicker(ep(1, 2, "Show — S1E2"))?.title)
        assertNull(episodeKicker(ep(1, 2, "Show — s1e2"))?.title, "match must be case-insensitive")
    }

    @Test
    fun `a blank or whitespace episode title is dropped`() {
        assertNull(episodeKicker(ep(1, 2, "Show — "))?.title)
        assertNull(episodeKicker(ep(1, 2, "Show —    "))?.title)
    }

    @Test
    fun `surrounding whitespace is trimmed from the episode title`() {
        assertEquals("Pilot", episodeKicker(ep(1, 1, "Show —   Pilot  "))?.title)
    }

    @Test
    fun `missing season and episode numbers fall back to the brand kicker`() {
        // ContentRepository fills both with `?: 0` when the provider omits them;
        // "S0E0" is noise, not context.
        assertNull(episodeKicker(ep(0, 0, "Show — Something")))
    }

    @Test
    fun `a partial number still produces a code`() {
        // Specials often carry an episode number with no season, or vice versa.
        // Half the context beats none.
        assertEquals("S0E7", episodeKicker(ep(0, 7, "Show — Special"))?.code)
        assertEquals("S3E0", episodeKicker(ep(3, 0, "Show — Recap"))?.code)
    }
}
