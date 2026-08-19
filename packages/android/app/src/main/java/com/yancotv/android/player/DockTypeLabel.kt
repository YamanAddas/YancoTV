package com.yancotv.android.player

import com.yancotv.shared.playback.Playable
import com.yancotv.shared.types.ContentType

/**
 * MB-346 — which type chip the VOD dock shows.
 *
 * The dock badged EVERY episode as "MOVIE". Not a typo in the mapping — the
 * mapping is right and its `SERIES -> EPISODE` arm was simply unreachable.
 * [PlaybackController.toContentItemView] synthesizes the `ContentItem` view of a
 * playing episode with `type = ContentType.MOVIE`, deliberately and correctly:
 * an episode IS a VOD file, so resume-point logic behaves and the `type ==
 * ContentType.LIVE` checks scattered across the player read false. Roughly
 * twenty call sites depend on that. The typing is not the bug.
 *
 * The bug is that an internal playback-semantics decision reached a *display*
 * surface, where "is this a VOD file" and "what is the user watching" are
 * different questions. So this resolves the second question from the signal that
 * actually answers it — [PlaybackController.currentEpisode], the stashed
 * `Playable.Episode` — and only falls back to [ContentType] when there is none.
 *
 * That is the same shape as the tested precedent one directory over:
 * `subtitles/buildSubtitleQuery` takes the `Playable.Episode` alongside the item
 * and branches on it first, which is why subtitle search was never affected by
 * this and asks OpenSubtitles for `type=episode` correctly.
 *
 * Pure and Compose-free (mirroring [episodeKicker], which `buildDockData`
 * already calls two lines below the site this fixes) so the rule is unit-
 * testable without a player. Returning an enum rather than a string resource id
 * keeps the test off the Android `R` class.
 */
internal enum class DockTypeLabel {
    MOVIE,
    EPISODE,
    LIVE,
}

/**
 * Resolve the dock's type chip for [type] with [episode] as the override.
 *
 * @param type the CURRENT item's type. For an episode this is `MOVIE` — see the
 *   class KDoc; that is why it cannot be the only input.
 * @param episode the stashed `Playable.Episode`, non-null only while an episode
 *   is playing. Its mere presence is the signal; no field of it is read, so a
 *   provider that omits season/episode numbers still gets the right chip. That
 *   is deliberately a weaker condition than [episodeKicker], which needs a real
 *   season or episode number to render `S1E2` and returns null without one — an
 *   episode with no numbering still is not a movie.
 */
internal fun dockTypeLabel(type: ContentType, episode: Playable.Episode?): DockTypeLabel {
    if (episode != null) return DockTypeLabel.EPISODE
    return when (type) {
        ContentType.MOVIE -> DockTypeLabel.MOVIE
        // Unreachable through the synthesized-episode path above, but a real
        // SERIES-typed item is a container that is not `Playable` at all, so if
        // one ever reaches the dock "EPISODE" is still the honest label.
        ContentType.SERIES -> DockTypeLabel.EPISODE
        ContentType.LIVE -> DockTypeLabel.LIVE
    }
}
