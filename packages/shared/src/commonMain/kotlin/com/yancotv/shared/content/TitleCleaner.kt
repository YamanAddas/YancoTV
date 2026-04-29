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

/** Clean an IPTV title by removing provider noise. */
fun cleanTitle(rawTitle: String): String {
    var title = rawTitle.trim()

    for (pattern in STRIP_PATTERNS) {
        title = pattern.replace(title, " ")
    }

    title = title.trim()

    if (title.isEmpty()) return rawTitle.trim()

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

/** Extract the show name from a series title (strip S01E02 and everything after). */
fun extractShowName(title: String): String {
    var showName = Regex("""\s*S\d{1,2}\s*E\d{1,3}.*""", RegexOption.IGNORE_CASE).replace(title, "")
    showName = Regex("""\s*Season\s+\d+.*""", RegexOption.IGNORE_CASE).replace(showName, "")
    showName = cleanTitle(showName)
    return showName.ifEmpty { title }
}
