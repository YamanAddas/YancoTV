package com.yancotv.shared.parental

import com.yancotv.shared.types.ContentItem

/**
 * Keyword-based adult-content detector used by the "Hide adult-tagged
 * content" toggle in Settings → Parental. **Heuristic only** — providers
 * don't label adult rows consistently, so this is a best-effort filter,
 * not a guarantee. A dedicated classifier lands later if needed.
 *
 * Markers scan the group name first (the clearest signal — providers
 * ship "XXX Adults" or "18+ Channels" categories) and fall back to
 * title tokens. Case-insensitive whole-word matching avoids false
 * positives like "Sport 18HD" or "M18 Channel".
 *
 * The filter deliberately runs in-memory over already-loaded pages —
 * SQL-side filtering would need a metadata join that every paginated
 * query would inherit, and the hit list is short enough (usually
 * <100 rows per page) that an O(n × markers) scan is cheap.
 */
object AdultContentFilter {

    // Token list — matches the lowercased, punctuation-stripped
    // input against the whole set. Keep narrow: "adult" alone would
    // eat "Young Adult" family content, so we pair it with a category
    // context check.
    private val STRONG_MARKERS = setOf(
        "xxx", "xxxadult", "adultsonly", "18plus", "adults18",
        "erotic", "erotica", "porn", "pornstar", "hentai", "playboy",
        "hustler", "brazzers", "vivid", "penthouse",
    )

    private val CATEGORY_ONLY_MARKERS = setOf(
        "adult", "adults", "18+", "+18", "21+",
    )

    /** True if the item's category or title text smells like adult content. */
    fun isAdult(item: ContentItem): Boolean {
        val group = normalize(item.groupName)
        if (group.isNotEmpty() && looksAdult(group, includeCategoryOnly = true)) return true
        val title = normalize(item.cleanTitle?.ifBlank { null } ?: item.title)
        return title.isNotEmpty() && looksAdult(title, includeCategoryOnly = false)
    }

    /**
     * Split [text] into lowercase tokens and check each against the marker
     * sets. [includeCategoryOnly] adds the weaker markers that are only
     * safe to match inside a group/category string (to avoid e.g. a
     * channel literally named "Adult Swim" being hidden when the user
     * toggles the filter).
     */
    private fun looksAdult(text: String, includeCategoryOnly: Boolean): Boolean {
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        for (tok in tokens) {
            if (tok in STRONG_MARKERS) return true
            if (includeCategoryOnly && tok in CATEGORY_ONLY_MARKERS) return true
        }
        // Also catch bare "XXX" or "18+" concatenated with other tokens
        // ("XXX-HD", "18+movies") via a substring probe on category text
        // only — title matching stays strict.
        if (includeCategoryOnly) {
            for (marker in listOf("xxx", "18+", "+18")) {
                if (text.contains(marker)) return true
            }
        }
        return false
    }

    private fun normalize(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        // Lowercase, collapse separators to single spaces, drop other
        // punctuation. Preserve "+" so "18+" survives.
        val buf = StringBuilder(s.length)
        var lastWasSpace = true
        for (ch in s.lowercase()) {
            when {
                ch.isLetterOrDigit() || ch == '+' -> {
                    buf.append(ch)
                    lastWasSpace = false
                }
                else -> if (!lastWasSpace) {
                    buf.append(' ')
                    lastWasSpace = true
                }
            }
        }
        return buf.toString().trim()
    }
}
