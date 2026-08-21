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
 * So the rule is: **pins win, size is the fallback.** A user who has curated
 * gets exactly what they curated. A user who has not gets the biggest categories,
 * which on a 272,419-item catalogue is a far better guess than provider order —
 * that would surface whichever bucket happens to sit first in the playlist, as
 * likely to be a near-empty test category as anything worth watching.
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
    consider(bySize)

    return chosen.map { key ->
        CategoryRail(groupKey = key, displayName = displayNames[key]?.takeIf { it.isNotBlank() } ?: key)
    }
}
