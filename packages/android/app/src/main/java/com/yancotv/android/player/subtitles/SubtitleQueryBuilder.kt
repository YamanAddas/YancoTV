package com.yancotv.android.player.subtitles

import com.yancotv.shared.playback.Playable
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType

/**
 * Build an OpenSubtitles query bundle from current playback context, with
 * provider-prefix peeling for noisy IPTV titles ("AR-SUBS-Under the Tuscan
 * Sun" → "Under the Tuscan Sun").
 *
 * Lives in its own file (rather than inline in `PlayerOptionsPanels.kt`)
 * with `internal` visibility so unit tests can pin the regex set without
 * pulling Compose / Koin / a fake controller into the test classpath.
 *
 * The regex pipeline runs in two passes — prefix peeling iterates up to
 * 4 times so stacked prefixes ("VIP | AR-SUBS-Movie") all peel; body
 * release-noise (BluRay / x265 / DDP5.1 / etc.) gets stripped after.
 */

internal data class SubtitleQueryBundle(val query: String, val season: Int?, val episode: Int?, val type: String?)

/**
 * For series episodes: `controller.currentItem` is a synthesized MOVIE-typed
 * view whose title is the joined "Series — S01E02" string built in
 * `EpisodeInfo.toPlayable` (Playable.kt). We split that on the first " — "
 * to recover the series name, then pass season/episode/type=episode so
 * OpenSubtitles can disambiguate.
 *
 * For movies: the M3U cleaner only strips quality / country / channel-number
 * noise, leaving release tags like "BluRay", "x265", "DDP5.1", "MULTI" that
 * tank search relevance. We strip those locally before sending, and append
 * a year if one is detectable in the source title and missing from the query.
 */
internal fun buildSubtitleQuery(item: ContentItem?, episode: Playable.Episode?): SubtitleQueryBundle? {
    if (item == null) return null

    if (episode != null) {
        // Series side of "Series — S01E02". Coupled to EpisodeInfo.toPlayable's
        // " — " separator — if that ever changes, update here too.
        val rawSeries = episode.title.substringBefore(" — ", episode.title)
        val cleaned = stripReleaseNoise(rawSeries).ifBlank { rawSeries }
        return SubtitleQueryBundle(
            query = cleaned,
            season = episode.seasonNumber.takeIf { it > 0 },
            episode = episode.episodeNumber.takeIf { it > 0 },
            type = "episode",
        )
    }

    val raw = item.cleanTitle?.takeIf { it.isNotBlank() } ?: item.title
    val cleaned = stripReleaseNoise(raw).ifBlank { raw }
    // Use the ORIGINAL M3U title as the year source — shared `cleanTitle`'s
    // bracket-strip eats "(2009)" before we ever see it, so the year is gone
    // from `cleaned` even when the provider title had it.
    val withYear = appendYearIfMissing(cleaned, item.title)
    val type = if (item.type == ContentType.MOVIE) "movie" else null
    return SubtitleQueryBundle(query = withYear, season = null, episode = null, type = type)
}

// Provider/promo prefixes that appear BEFORE the actual movie name in IPTV
// listings. The shared cleanTitle handles "XX:" / "XX |" / "XX - " forms; these
// patterns cover the dash-no-space + tag-word combos that providers use widely
// ("AR-SUBS-Under the Tuscan Sun", "EN-DUB-Inception", "MULTI-SUBS-Avatar").
// Applied iteratively so stacked prefixes ("VIP | AR-SUBS-Movie") all peel.
internal val SUBTITLE_PREFIX_NOISE = listOf(
    // Country/lang code (2-4 letters) OR MULTI/DUAL, then tag word (SUBS/DUB),
    // separated by space, dash, pipe, or colon.
    // Examples: "AR-SUBS-", "EN | SUBS - ", "MULTI SUBS ", "USA-DUB-".
    Regex(
        """^(?:[A-Z]{2,4}|MULTI|DUAL)[\s\-|:]+(?:SUBS?|DUB(?:BED)?|MULTI(?:SUBS?)?)[\s\-|:]+""",
        RegexOption.IGNORE_CASE,
    ),
    // Bare tag prefix without country code: "SUBS-", "DUB|", "DUBBED: ".
    Regex("""^(?:SUBS?|DUB(?:BED)?|MULTI(?:SUBS?)?)[\s\-|:]+""", RegexOption.IGNORE_CASE),
    // Bare country code with dash, no spaces — the no-space variant the shared
    // cleanTitle's `^[A-Z]{2}\s+-\s+` misses. Uppercase-only to avoid eating
    // real movie titles that start with short English words.
    Regex("""^[A-Z]{2,4}-"""),
    // Provider promo prefixes.
    Regex("""^(?:VIP|PREMIUM|HOT|NEW|EXCLUSIVE|24/?7)[\s\-|:]+""", RegexOption.IGNORE_CASE),
)

internal val SUBTITLE_RELEASE_NOISE = listOf(
    Regex(
        """\b(?:BluRay|BDRip|BRRip|WEB-?DL|WEB-?Rip|HDRip|DVDRip|HDTV|PDTV|CAM|TS|TC|SCR|REMUX|PROPER|REPACK|LIMITED|INTERNAL|EXTENDED|UNRATED|IMAX|OPEN\.?MATTE)\b""",
        RegexOption.IGNORE_CASE,
    ),
    Regex("""\b(?:H\.?265|H\.?264|HEVC|AVC|AAC|AC3|DTS|DDP?5\.1|DD5\.1|FLAC|OPUS|TrueHD|Atmos)\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:x264|x265|xvid|divx|10bit|8bit)\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:5\.1|7\.1|2\.0)\b"""),
    Regex("""\b(?:HDR10\+?|DV|DolbyVision|SDR)\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:MULTI|DUAL|DUBBED|SUB|SUBS|SUBBED|HC|MSUB|ESUB)\b""", RegexOption.IGNORE_CASE),
    // Resolution tags (480p/720p/1080p/2160p/1080i etc.). Shared cleanTitle
    // already strips these for displayed titles, but stripReleaseNoise runs
    // standalone in tests and from the subtitle path so we duplicate it
    // here as a safety net.
    Regex("""\b\d{3,4}[pi]\b""", RegexOption.IGNORE_CASE),
)

internal fun stripReleaseNoise(input: String): String {
    var t = input
    // Peel stacked prefixes first — order matters because body cleanup might
    // otherwise leave "AR- -Under the Tuscan Sun" after stripping SUBS internally.
    repeat(4) {
        val before = t
        for (re in SUBTITLE_PREFIX_NOISE) t = re.replace(t, "")
        if (t == before) return@repeat
    }
    // Replace `_` with space BEFORE body strip — `\b` treats `_` as a word
    // char, so "Movie_BluRay" has no boundary between `_` and `BluRay` and
    // the tag wouldn't strip until separators normalize. Keep `.` for now
    // so audio patterns like DDP5.1 can still match their literal dot.
    t = t.replace('_', ' ')
    for (re in SUBTITLE_RELEASE_NOISE) t = re.replace(t, " ")
    // NOW collapse leftover dots (between words like "Movie.Name") to spaces.
    t = t.replace(Regex("""\.+"""), " ")
    t = t.replace(Regex("""\s{2,}"""), " ").trim()
    // Trailing release-group tag like " -RELEASE" or " -SPARKS" — uppercase-
    // letters-only after a space-dash to avoid eating real titles ending in
    // a hyphenated word ("Spider-Man" has no leading space before the dash).
    t = t.replace(Regex("""\s+-[A-Z][A-Z0-9]+$"""), "").trim()
    t = t.replace(Regex("""^[-–|:\s]+"""), "")
    t = t.replace(Regex("""\s*[-–|:]\s*$"""), "").trim()
    return t
}

internal fun appendYearIfMissing(query: String, source: String): String {
    if (Regex("""\b\d{4}\b""").containsMatchIn(query)) return query
    val match = Regex("""\((\d{4})\)|\b(19|20)\d{2}\b""").find(source) ?: return query
    val year = match.groupValues[1].ifEmpty { match.value }.toIntOrNull() ?: return query
    if (year !in 1900..2099) return query
    return "$query $year"
}
