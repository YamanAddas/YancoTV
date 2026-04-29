package com.yancotv.shared.content

import com.yancotv.shared.parsers.M3uEntry
import com.yancotv.shared.types.ContentType

/**
 * Kotlin port of `@yancotv/core` `content/classifier.ts`. Behavior parity
 * required — tests mirror `tests/unit/content-classifier.test.ts` and
 * `tests/unit/content-classifier-extended.test.ts`.
 */

// Series group markers — kept broad so non-English providers land correctly.
private val SERIES_GROUP_PATTERNS =
    listOf(
        "series",
        "serie",
        "episode",
        "tv show",
        "tvshow",
        "season",
        "sezon",
        "dizi",
        "serial",
        "sorozat",
        "show",
    )

// Movie/VOD group markers.
private val MOVIE_GROUP_PATTERNS =
    listOf(
        "movie",
        "vod",
        "film",
        "cinema",
        "peliculas",
        "pelicula",
        "filme",
        "kino",
        "filmy",
        "on demand",
        "ondemand",
    )

private fun matchesAny(group: String, patterns: List<String>): Boolean {
    for (p in patterns) {
        if (group.contains(p)) return true
    }
    return false
}

private val SERIES_SE_REGEX = Regex("""S\d{1,2}\s*E\d{1,3}""", RegexOption.IGNORE_CASE)
private val SEASON_REGEX = Regex("""Season\s+\d""", RegexOption.IGNORE_CASE)
private val VIDEO_EXT_REGEX =
    Regex("""\.(mp4|mkv|avi|mov|m4v|webm|flv|wmv)(\?|#|$)""", RegexOption.IGNORE_CASE)

/** Classify an M3U entry into live, movie, or series. */
fun classifyEntry(entry: M3uEntry): ContentType {
    val group = entry.groupTitle.lowercase()
    val title = entry.title
    val url = entry.streamUrl.lowercase()
    val duration = entry.duration

    // --- Series indicators (check first — more specific) ---
    if (SERIES_SE_REGEX.containsMatchIn(title) || SEASON_REGEX.containsMatchIn(title)) {
        return ContentType.SERIES
    }

    if (matchesAny(group, SERIES_GROUP_PATTERNS)) {
        return ContentType.SERIES
    }

    if (url.contains("/series/")) {
        return ContentType.SERIES
    }

    // --- Movie/VOD indicators ---
    if (matchesAny(group, MOVIE_GROUP_PATTERNS)) {
        return ContentType.MOVIE
    }

    if (url.contains("/movie/")) {
        return ContentType.MOVIE
    }

    if (VIDEO_EXT_REGEX.containsMatchIn(url)) {
        if (SERIES_SE_REGEX.containsMatchIn(title)) return ContentType.SERIES
        return ContentType.MOVIE
    }

    if (duration > 0) {
        return ContentType.MOVIE
    }

    return ContentType.LIVE
}

// --- Category normalization ---

private val CATEGORY_NORMALIZATIONS: List<Pair<Regex, String>> =
    listOf(
        // Country prefixes.
        Regex("""^[A-Z]{2,3}\s*[:|]\s*""") to "",
        Regex("""^[A-Z]{2,3}\s*[-–]\s*""") to "",
        // Trailing country suffix.
        Regex("""\s*\|\s*[A-Z]{2,3}$""") to "",
        // Common variations.
        Regex("""\bSport(?:s)?\b""", RegexOption.IGNORE_CASE) to "Sports",
        Regex("""\bEntertainm?ent\b""", RegexOption.IGNORE_CASE) to "Entertainment",
        Regex("""\bDocument(?:ary|aries)\b""", RegexOption.IGNORE_CASE) to "Documentary",
        Regex("""\bChild(?:ren'?s?|s)\b""", RegexOption.IGNORE_CASE) to "Kids",
        Regex("""\bKid(?:'?s)?\b""", RegexOption.IGNORE_CASE) to "Kids",
        Regex("""\bMusic(?:al)?\b""", RegexOption.IGNORE_CASE) to "Music",
        Regex("""\bReligio(?:us|n)\b""", RegexOption.IGNORE_CASE) to "Religious",
        Regex("""\bEduc(?:ation(?:al)?)\b""", RegexOption.IGNORE_CASE) to "Education",
        Regex("""\bCook(?:ing)?\b""", RegexOption.IGNORE_CASE) to "Cooking",
        Regex("""\bTravel\b""", RegexOption.IGNORE_CASE) to "Travel",
        Regex("""\bComedy\b""", RegexOption.IGNORE_CASE) to "Comedy",
        Regex("""\bDrama\b""", RegexOption.IGNORE_CASE) to "Drama",
        Regex("""\bAction\b""", RegexOption.IGNORE_CASE) to "Action",
        Regex("""\bHorror\b""", RegexOption.IGNORE_CASE) to "Horror",
        Regex("""\bSci[- ]?Fi\b""", RegexOption.IGNORE_CASE) to "Sci-Fi",
        Regex("""\bThriller\b""", RegexOption.IGNORE_CASE) to "Thriller",
        Regex("""\bRomance\b""", RegexOption.IGNORE_CASE) to "Romance",
        Regex("""\bWestern\b""", RegexOption.IGNORE_CASE) to "Western",
        Regex("""\bAnimat(?:ion|ed)\b""", RegexOption.IGNORE_CASE) to "Animation",
        Regex("""\bAnime\b""", RegexOption.IGNORE_CASE) to "Anime",
    )

private val MULTI_SPACE_REGEX = Regex("""\s{2,}""")

/** Normalize a category/group name for consistency. */
fun normalizeCategory(category: String): String {
    if (category.isEmpty()) return ""

    var normalized = category.trim()

    for ((pattern, replacement) in CATEGORY_NORMALIZATIONS) {
        normalized = pattern.replace(normalized, replacement)
    }

    normalized = MULTI_SPACE_REGEX.replace(normalized, " ").trim()

    // Title case (handle hyphenated words like Sci-Fi)
    normalized =
        normalized
            .split(' ')
            .joinToString(" ") { word ->
                when {
                    word.length <= 2 && word == word.uppercase() -> word // Keep short acronyms
                    word.contains('-') ->
                        word
                            .split('-')
                            .joinToString("-") { part ->
                                if (part.isEmpty()) {
                                    part
                                } else {
                                    part.substring(0, 1).uppercase() + part.substring(1).lowercase()
                                }
                            }
                    else ->
                        if (word.isEmpty()) {
                            word
                        } else {
                            word.substring(0, 1).uppercase() + word.substring(1).lowercase()
                        }
                }
            }

    return normalized.ifEmpty { category.trim() }
}
