package com.yancotv.shared.content

/**
 * Title cleaner for IPTV content. Mirrors `@yancotv/core` `title-cleaner.ts`.
 *
 * IPTV providers add a lot of noise to titles: quality tags, country prefixes,
 * provider branding, numbering, etc. This module strips that noise to produce
 * a clean title suitable for display and metadata matching.
 */

private val STRIP_PATTERNS: List<Regex> =
    listOf(
        // Bracketed tags FIRST (before quality tags strip inner content):
        // [HD], [MULTI], [4K], (HD), {HD}, etc.
        Regex("""\s*[\[({][^\])}\n]*[\])}]"""),
        // Quality/resolution tags: HD, FHD, UHD, 4K, SD, 720p, 1080p, etc.
        Regex("""\b(?:4K|8K|UHD|FHD|HD|SD|H\.?265|H\.?264|HEVC|HDR(?:10)?|MULTI)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:\d{3,4}[pi])\b""", RegexOption.IGNORE_CASE),
        // Country prefix patterns: "US:", "US |", "UK:" (2-3 letter codes with : or |)
        Regex("""^[A-Z]{2,3}\s*[:|]\s*"""),
        // Country prefix with dash: only 2-letter codes (to avoid matching "CNN -")
        Regex("""^[A-Z]{2}\s+-\s+"""),
        // Country/region with pipe: "| US", "| UK" at end
        Regex("""\s*\|\s*[A-Z]{2,3}$"""),
        // Provider channel numbering: "001.", "CH 001:", etc.
        Regex("""^(?:CH\s*)?\d{1,4}[.:]\s*""", RegexOption.IGNORE_CASE),
        // Trailing provider noise: "- Backup", "| S2", "| Server 3"
        Regex("""\s*[-|]\s*(?:backup|bk|s\d+|server\s*\d+)$""", RegexOption.IGNORE_CASE),
        // Double/triple spaces from removals
        Regex("""\s{2,}"""),
    )

/**
 * Separators left stranded at an edge once the strips above have run.
 *
 * `"4K: V SPORT ᵁᴴᴰ"` loses its `4K` to the quality pattern and trims to
 * `": V SPORT ᵁᴴᴰ"` — the colon was a *separator between* the tag and the
 * name, and with the tag gone it separates nothing. Measured on a real
 * 273,869-item account: **6,903 live channels** stored a clean title
 * beginning with one, and every one of them rendered with a leading `": "`
 * that also ate two characters from an already-truncating label.
 *
 * Only the characters providers actually use as separators, and only at
 * the edges. `.` and `,` are deliberately absent — stripping a trailing
 * dot would turn `M.A.S.H.` into `M.A.S.H`, which is a different kind of
 * wrong from the one being fixed.
 */
private val EDGE_SEPARATORS = Regex("""^[\s:|/\-–—]+|[\s:|/\-–—]+$""")

/** Clean an IPTV title by removing provider noise. */
fun cleanTitle(rawTitle: String): String {
    var title = rawTitle.trim()

    for (pattern in STRIP_PATTERNS) {
        title = pattern.replace(title, " ")
    }

    title = EDGE_SEPARATORS.replace(title, "")

    // MB-377 — a title stripped down to only punctuation/separators (e.g.
    // "(MX) (VIX 01) | (2098-12-31 08:00:01)" reduces to "|") is as useless as
    // an empty one, but the old `isEmpty()` guard let it through, so every such
    // row rendered identically (315 rows on a real catalogue all showed "|").
    // Treat a result with no letter or digit in ANY script as empty and fall
    // back to the raw title, which at least stays distinguishable. `none` is
    // also true for the empty string, so this subsumes the old guard.
    if (title.none { it.isLetterOrDigit() }) return rawTitle.trim()

    return title
}

/**
 * Extract year from a title like "The Matrix (1999)" or "Movie 2023".
 *
 * `currentYear` defaults to a generous upper bound so callers can omit it;
 * pass the real current year when stricter rejection of future years matters.
 */
fun extractYear(title: String, currentYear: Int = 9999): Int? {
    val parenMatch = Regex("""\((\d{4})\)""").find(title)
    if (parenMatch != null) {
        val year = parenMatch.groupValues[1].toInt()
        if (year in 1900..(currentYear + 2)) return year
    }

    val trailingMatch = Regex("""\b((?:19|20)\d{2})$""").find(title)
    if (trailingMatch != null) {
        val year = trailingMatch.groupValues[1].toInt()
        if (year in 1900..(currentYear + 2)) return year
    }

    return null
}

data class SeasonEpisode(val season: Int, val episode: Int)

/** Extract season and episode numbers from a title. */
fun extractSeasonEpisode(title: String): SeasonEpisode? {
    // S01E02, S1E2, s01e02
    val seMatch = Regex("""S(\d{1,2})\s*E(\d{1,3})""", RegexOption.IGNORE_CASE).find(title)
    if (seMatch != null) {
        return SeasonEpisode(seMatch.groupValues[1].toInt(), seMatch.groupValues[2].toInt())
    }

    // Season 1 Episode 2
    val longMatch = Regex("""Season\s+(\d{1,2}).*?Episode\s+(\d{1,3})""", RegexOption.IGNORE_CASE).find(title)
    if (longMatch != null) {
        return SeasonEpisode(longMatch.groupValues[1].toInt(), longMatch.groupValues[2].toInt())
    }

    // E02 or Ep02 (episode only, assume season 1)
    val epOnly = Regex("""\bE(?:p(?:isode)?)?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(title)
    if (epOnly != null) {
        return SeasonEpisode(1, epOnly.groupValues[1].toInt())
    }

    return null
}

/**
 * The episode's own name, with the provider's boilerplate removed.
 *
 * Xtream providers routinely put the whole series identity into every
 * episode title, so a season list reads as a column of near-identical
 * strings with the one distinguishing word at the far right — measured on
 * a real account: `TR - Hakan: Muhafız (2018) (TR) - S01E01 - 1. Bölüm`,
 * where the episode is called "1. Bölüm".
 *
 * [extractShowName] is the mirror of this: it keeps the part before the
 * SxxExx marker, and this keeps the part after.
 *
 * Falls back, in order, to the raw title and then to `Episode N`, so a row
 * is never blank. [seriesTitle] is compared against so an episode titled
 * only with the series name is treated as having no title of its own.
 */
fun cleanEpisodeTitle(rawTitle: String, seriesTitle: String, episodeNumber: Int): String {
    val trimmed = rawTitle.trim()
    val fallback = "Episode $episodeNumber"
    if (trimmed.isEmpty()) return fallback

    // Everything after the SxxExx marker and its separator, when there is
    // anything there. `- 1. Bölüm` -> `1. Bölüm`.
    // Find the marker first, then take whatever follows it — rather than
    // capturing the tail in one pattern. When a title ends *at* the marker
    // (`Show - S01E07`) there is no episode name, and the tail must come
    // back empty so the fallback runs; a single capturing pattern instead
    // fails to match and leaves the whole boilerplate title standing.
    //
    // `(?!\d)` is load-bearing too: without it `S01E07` backtracks to
    // match `S01E0`, leaving the trailing `7` as the "title".
    val marker =
        Regex("""S\d{1,2}\s*E\d{1,3}(?!\d)""", RegexOption.IGNORE_CASE).find(trimmed)

    val candidate =
        (if (marker != null) trimmed.substring(marker.range.last + 1) else trimmed)
            .trim(' ', '-', '–', '—', ':')

    // A title that is only the series name says nothing the page has not
    // already said above the list.
    if (candidate.isEmpty() || candidate.equals(seriesTitle.trim(), ignoreCase = true)) {
        return fallback
    }
    return candidate
}

/** Extract the show name from a series title (strip S01E02 and everything after). */
fun extractShowName(title: String): String {
    var showName = Regex("""\s*S\d{1,2}\s*E\d{1,3}.*""", RegexOption.IGNORE_CASE).replace(title, "")
    showName = Regex("""\s*Season\s+\d+.*""", RegexOption.IGNORE_CASE).replace(showName, "")
    showName = cleanTitle(showName)
    return showName.ifEmpty { title }
}
