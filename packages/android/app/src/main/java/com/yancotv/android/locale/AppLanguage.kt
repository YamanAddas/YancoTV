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
 * the person who wants it. [accessibleName] carries the spoken form for
 * TalkBack, and [englishName] is its deterministic fallback.
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

    /**
     * MK.31.26 — the spoken name for TalkBack, in [inLocale].
     *
     * The visible label is deliberately the [endonym], which a TTS voice set to
     * the UI language cannot pronounce: an English or French engine handed
     * "العربية" either says nothing or mangles it. So the accessibility label
     * carries the language's name *as spoken in the current UI language* —
     * "Arabic" in English, "arabe" in French, "árabe" in Spanish.
     *
     * Delegates to [Locale.getDisplayLanguage] rather than a 4×4 resource table
     * because the platform already ships those names for every locale pair, and
     * a hand-maintained table would be four more strings to get wrong per
     * language added.
     *
     * Falls back to [englishName] when ICU has nothing useful — a missing entry
     * makes `getDisplayLanguage` echo the bare tag ("ar"), which TalkBack would
     * spell out letter by letter. Guarding on that is cheap and the reason
     * [englishName] still exists.
     *
     * Null for [System], which is not a language; callers substitute the
     * localized "System" label they already render.
     */
    fun accessibleName(inLocale: Locale): String? = locale?.let { own ->
        own.getDisplayLanguage(inLocale)
            .takeIf { it.isNotBlank() && !it.equals(own.language, ignoreCase = true) }
            ?: englishName
    }

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
