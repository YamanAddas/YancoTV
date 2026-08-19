package com.yancotv.android.player

import com.yancotv.shared.playback.Playable
import com.yancotv.shared.types.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MB-346 — [dockTypeLabel] contract.
 *
 * The defect this pins was observed on a Fire TV: playing S1E2 of a series, the
 * dock's type chip read "MOVIE" while the kicker line directly above it read
 * "S1E2 · ... S01E02". One surface, two answers.
 *
 * The coupling under defence is that `PlaybackController.toContentItemView`
 * types a playing episode's synthesized `ContentItem` as `ContentType.MOVIE` on
 * purpose, so `ContentType` ALONE can never distinguish an episode from a movie
 * during playback. The first test is therefore the whole point of the file: if
 * someone later "simplifies" this back to a plain `when (type)`, it goes red.
 *
 * Negative control (run): deleting the `if (episode != null)` guard fails
 * exactly the three episode-bearing cases — `an episode is EPISODE even though
 * its item is typed MOVIE`, `an episode with no season or episode numbers is
 * still not a movie`, and `episode presence wins over the item type` — while the
 * three episode-free cases stay green. 6 tests, 3 failed.
 */
class DockTypeLabelTest {
    private fun ep(season: Int = 1, number: Int = 2) = Playable.Episode(
        id = "ep-1",
        seriesId = "series-1",
        title = "Show — Pilot",
        streamUrl = "http://example.invalid/ep.mkv",
        seasonNumber = season,
        episodeNumber = number,
    )

    @Test
    fun `an episode is EPISODE even though its item is typed MOVIE`() {
        // The regression. toContentItemView types episodes MOVIE deliberately;
        // reading ContentType alone is what shipped "MOVIE" on every episode.
        assertEquals(DockTypeLabel.EPISODE, dockTypeLabel(ContentType.MOVIE, ep()))
    }

    @Test
    fun `a movie is still a movie`() {
        assertEquals(DockTypeLabel.MOVIE, dockTypeLabel(ContentType.MOVIE, episode = null))
    }

    @Test
    fun `an episode with no season or episode numbers is still not a movie`() {
        // Deliberately weaker than episodeKicker, which returns null here
        // because it cannot render "S1E2". Unnumbered still is not a movie, so
        // the chip and the kicker legitimately disagree about this input: the
        // dock shows EPISODE with the brand kicker.
        assertEquals(DockTypeLabel.EPISODE, dockTypeLabel(ContentType.MOVIE, ep(season = 0, number = 0)))
    }

    @Test
    fun `a SERIES-typed item with no episode is labelled EPISODE`() {
        // Not reachable via the synthesized-episode path, but a series container
        // is not Playable at all, so EPISODE remains the honest label.
        assertEquals(DockTypeLabel.EPISODE, dockTypeLabel(ContentType.SERIES, episode = null))
    }

    @Test
    fun `live is live`() {
        // The dock is never shown for LIVE, so this arm exists for exhaustiveness
        // rather than for a screen anyone sees.
        assertEquals(DockTypeLabel.LIVE, dockTypeLabel(ContentType.LIVE, episode = null))
    }

    @Test
    fun `episode presence wins over the item type`() {
        // Pins precedence so the guard cannot be quietly reordered below the
        // when-branch. LIVE plus an episode is an impossible pairing in
        // production; the assertion is about ordering, not about that input.
        assertEquals(DockTypeLabel.EPISODE, dockTypeLabel(ContentType.LIVE, ep()))
    }
}
