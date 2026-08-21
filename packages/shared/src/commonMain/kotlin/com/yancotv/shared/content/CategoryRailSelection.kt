package com.yancotv.shared.content

/**
 * MK.35.2 — which categories earn a rail on Home.
 *
 * Home has room for three or four rails, and the catalogue can hold thousands of
 * categories. Something has to choose, and the choice should not be an
 * invention: the user has already expressed preferences — pinned categories,
 * hidden categories, renamed categories — through Settings, and until now none
 * of that reached Home. Pinning a category and then not seeing it on the home
 * screen is the app ignoring an instruction it already accepted.
 *
 * So the rule is a three-tier fallback: **pins, then what you watch, then size.**
 *
 * The middle tier exists because size alone gets it badly wrong. Measured on a
 * real 272,419-item catalogue, the biggest categories were "DE - FILME
 * 1940/2024", "EN - NEW RELEASE" and "IR - PERSIAN SUB/DUB" — German, English
 * and Persian — for a user whose entire watch history is Turkish and Arabic.
 * Size measures what the PROVIDER stocks, not what the viewer wants, and on a
 * catalogue that dumps every language into one playlist those are barely
 * related.
 *
 * Categories drawn from the user's own history are a real signal and cost
 * nothing to collect. Deliberately NOT a hardcoded language list: Home already
 * has one of those for its Recently Added rail, pinned to EN/AR, and it would
 * have to be edited by hand for every user who is not this one.
 *
 * Size remains the last resort, for a fresh install where nothing else is
 * known. It is still better than provider order, which would surface whichever
 * bucket happens to sit first in the playlist.
 *
 * Pure and Compose-free so the rule is testable without a database or a screen.
 */
data class CategoryRail(val groupKey: String, val displayName: String)

/**
 * Pick up to [limit] categories to render as Home rails.
 *
 * @param pinned group keys the user pinned, in their own order. Highest priority.
 * @param hidden group keys the user hid. Removed from BOTH sources — hiding a
 *   category in Settings must hide it everywhere, and a hidden category that
 *   reappears on the home screen is worse than one that was never hidden,
 *   because the user believes the setting is broken.
 * @param bySize group keys ordered largest-first, the fallback.
 * @param displayNames custom names the user set, keyed by group key. Renaming a
 *   category and then seeing its original name on Home is the same class of
 *   broken promise as the hide case.
 */
fun pickCategoryRails(
    pinned: List<String>,
    hidden: Set<String>,
    bySize: List<String>,
    /**
     * Categories the user has actually watched from, most recent first. Ranked
     * above [bySize] because it reflects the viewer rather than the provider's
     * stock levels.
     */
    watched: List<String> = emptyList(),
    displayNames: Map<String, String> = emptyMap(),
    limit: Int = 3,
): List<CategoryRail> {
    if (limit <= 0) return emptyList()
    val seen = mutableSetOf<String>()
    val chosen = mutableListOf<String>()

    fun consider(keys: List<String>) {
        for (key in keys) {
            if (chosen.size >= limit) return
            val trimmed = key.trim()
            if (trimmed.isEmpty()) continue
            // Case-insensitive: providers are inconsistent about capitalising
            // the same category, and a pinned "Turkish Series" must not produce
            // a second rail when the size fallback offers "TURKISH SERIES".
            val dedupeKey = trimmed.lowercase()
            if (dedupeKey in hidden.map { it.trim().lowercase() }) continue
            if (!seen.add(dedupeKey)) continue
            chosen += trimmed
        }
    }

    consider(pinned)
    consider(watched)
    consider(bySize)

    return chosen.map { key ->
        CategoryRail(groupKey = key, displayName = displayNames[key]?.takeIf { it.isNotBlank() } ?: key)
    }
}
