package com.yancotv.android.locale

import java.util.Locale

/**
 * MK.31.1 — the languages YancoTV ships.
 *
 * [tag] is a BCP-47 language tag and must match the `values-<tag>` resource
 * qualifier exactly, because [AppLanguage.of] round-trips the persisted tag
 * back to an entry.
 *
 * [endonym] is the language's name *in that language* — "العربية", not
 * "Arabic". A language picker is the one screen a user might reach while the
 * app is in a language they can't read, so every option has to be legible to
 * the person who wants it. The English gloss is carried separately in
 * [englishName] for accessibility labels and logs.
 */
enum class AppLanguage(val tag: String, val endonym: String, val englishName: String, val rtl: Boolean = false) {
    /**
     * Follow the device language, falling back to English when the device is
     * set to something YancoTV doesn't ship. Not a locale itself — [locale]
     * is null and the resource system does the resolving.
     */
    System("", "System", "Follow system"),
    English("en", "English", "English"),
    Arabic("ar", "العربية", "Arabic", rtl = true),
    French("fr", "Français", "French"),
    Spanish("es", "Español", "Spanish"),
    ;

    /** Null for [System], which defers to the platform's resolution. */
    val locale: Locale?
        get() = if (tag.isEmpty()) null else Locale.forLanguageTag(tag)

    companion object {
        /**
         * Resolves a persisted tag. Unknown or absent tags fall back to
         * [System] rather than throwing — a downgrade to a build that dropped
         * a language, or a hand-edited pref, must not crash at startup, and
         * this runs on the launch path before anything can report an error.
         */
        fun of(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: System

        /** Languages offered in the picker, in the order they appear. */
        val selectable: List<AppLanguage> get() = entries.toList()
    }
}
