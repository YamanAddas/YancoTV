package com.yancotv.android.player

import com.yancotv.shared.playback.Playable

/**
 * MB-340 (plan W2 / MK.25.B.2) — the dock's episode context line.
 *
 * The dock's kicker was always the fixed "NOW PLAYING · YANCO.VOD" brand line,
 * so binge-watching a series gave no indication of *which* episode was playing —
 * the one fact you want when you look up mid-episode.
 *
 * Pure and Compose-free (mirroring [com.yancotv.android.player.subtitles.buildSubtitleQuery])
 * so the parsing rules are unit-testable without a player.
 */
internal data class EpisodeKicker(
    /** `S1E2` — unpadded, matching the app's existing convention. */
    val code: String,
    /** Bare episode title, or null when it is absent or redundant. */
    val title: String?,
)

/**
 * Build the kicker parts for [episode], or null when there is no episode context
 * (a movie, or nothing playing) so the caller keeps the existing brand kicker.
 *
 * **Unpadded `S1E2`, not `S01E02`** — deliberately matching
 * `ContentDetailScreen.resumeButtonLabel`, the only other user-facing episode
 * code in the app. Two different paddings for the same concept on two screens
 * would read as a bug.
 */
internal fun episodeKicker(episode: Playable.Episode?): EpisodeKicker? {
    if (episode == null) return null
    // Both default to 0 when the provider omitted them (ContentRepository fills
    // them via `?: 0`), and "S0E0" is noise — fall back to the brand kicker.
    val season = episode.seasonNumber.takeIf { it > 0 }
    val number = episode.episodeNumber.takeIf { it > 0 }
    if (season == null && number == null) return null
    val code = "S${season ?: 0}E${number ?: 0}"

    // `Playable.Episode.title` is PRE-JOINED as "<series> — <episode>" by
    // EpisodeInfo.toPlayable. The separator is U+2014 with spaces on both sides;
    // this split is coupled to that and must change with it. Same coupling, same
    // literal, and the same reciprocal comment as
    // subtitles/SubtitleQueryBuilder.kt, which is the tested precedent.
    val bare = episode.title.substringAfter(" — ", "").trim()

    // Drop a title that adds nothing: blank, or identical to the code itself —
    // the autoplay path can synthesise a title of exactly "S1E2", and rendering
    // "S1E2 · S1E2" looks broken.
    val title = bare.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
    return EpisodeKicker(code = code, title = title)
}
