package com.yancotv.android.locale

import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MK.31.1 — [AppLanguage] contract, plus a consistency guard over the three
 * places a language has to be declared.
 *
 * Adding a language means touching the enum, `res/xml/locales_config.xml` and
 * a `res/values-<tag>/` directory. Miss one and the failure is quiet and
 * confusing: the picker offers a language that silently renders in English, or
 * the system per-app-language screen disagrees with the in-app choice. These
 * tests make that a build failure instead.
 */
class AppLanguageTest {
    // Gradle runs Android unit tests with the module directory as CWD.
    private val res = File("src/main/res")

    @Test
    fun `every tag round-trips through of`() {
        for (language in AppLanguage.entries) {
            assertEquals(language, AppLanguage.of(language.tag), "tag '${language.tag}' must round-trip")
        }
    }

    @Test
    fun `unknown and absent tags fall back to system rather than throwing`() {
        // This runs on the launch path before anything can report an error, so
        // a downgrade that dropped a language, or a hand-edited pref, must not
        // take the app down.
        assertEquals(AppLanguage.System, AppLanguage.of(null))
        assertEquals(AppLanguage.System, AppLanguage.of("de"))
        assertEquals(AppLanguage.System, AppLanguage.of("not-a-tag"))
        assertEquals(AppLanguage.System, AppLanguage.of("EN"), "tags are case-sensitive by resource-qualifier rules")
    }

    @Test
    fun `system carries no locale and every real language does`() {
        assertTrue(AppLanguage.System.locale == null, "System must defer to platform resolution")
        for (language in AppLanguage.entries - AppLanguage.System) {
            assertTrue(language.locale != null, "$language must resolve to a Locale")
            assertEquals(language.tag, language.locale?.toLanguageTag(), "$language tag must match its Locale")
        }
    }

    @Test
    fun `arabic is the only rtl language declared`() {
        val rtl = AppLanguage.entries.filter { it.rtl }
        assertEquals(listOf(AppLanguage.Arabic), rtl, "expected only Arabic to be RTL, got $rtl")
        assertTrue(LocaleController.isRtl(AppLanguage.Arabic))
        assertTrue(!LocaleController.isRtl(AppLanguage.English))
    }

    @Test
    fun `every language has a values directory except english and system`() {
        // English lives in the default `values/`, which is also the fallback
        // for a device language we don't ship. System isn't a locale at all.
        assertTrue(res.isDirectory, "resource dir not found at ${res.absolutePath} — fix this test's CWD assumption")
        for (language in AppLanguage.entries - AppLanguage.System - AppLanguage.English) {
            val dir = File(res, "values-${language.tag}")
            assertTrue(dir.isDirectory, "${language.englishName} has no ${dir.name}/ — the picker would offer it and render English")
            assertTrue(
                File(dir, "strings.xml").isFile,
                "${dir.name}/strings.xml missing",
            )
        }
    }

    @Test
    fun `accessibleName is the language name in the asking locale, not the endonym`() {
        // MK.31.26 — the point of the property. The visible label is an endonym,
        // which a TTS voice set to the UI language cannot pronounce; the spoken
        // label has to be in the UI language instead.
        assertEquals("Arabic", AppLanguage.Arabic.accessibleName(Locale.ENGLISH))
        assertEquals("French", AppLanguage.French.accessibleName(Locale.ENGLISH))

        // Same language asked in French and Spanish must give something DIFFERENT
        // from the English name — that difference is the whole locale-awareness
        // claim. Deliberately not pinning "arabe" / "árabe": those spellings come
        // from CLDR via the platform, so asserting them would make this test fail
        // on a JDK or ICU bump for a reason that has nothing to do with our code.
        val inEnglish = AppLanguage.Arabic.accessibleName(Locale.ENGLISH)
        for (asking in listOf(Locale.FRENCH, Locale.forLanguageTag("es"))) {
            val spoken = AppLanguage.Arabic.accessibleName(asking)
            assertTrue(
                spoken != null && spoken != inEnglish,
                "Arabic asked in $asking gave '$spoken', same as English — not locale-aware",
            )
        }

        // Every shipped language must have a non-blank spoken name, and it must
        // not be the endonym — that is the failure this whole property prevents.
        // English is exempt from the second half: its endonym IS its English name.
        for (language in AppLanguage.entries - AppLanguage.System) {
            val spoken = AppLanguage.of(language.tag).accessibleName(Locale.ENGLISH)
            assertTrue(spoken != null && spoken.isNotBlank(), "${language.tag}: no spoken name")
            if (language != AppLanguage.English) {
                assertTrue(
                    spoken != language.endonym,
                    "${language.tag}: spoken name is still the endonym ($spoken) — TalkBack cannot pronounce it",
                )
            }
        }
    }

    @Test
    fun `accessibleName is null for system and never leaks a bare tag`() {
        // System is not a language — the picker substitutes its own localized
        // "System" label, so null is the contract rather than a placeholder.
        assertEquals(null, AppLanguage.System.accessibleName(Locale.ENGLISH))

        // The guard that matters on a device with thin ICU data: when
        // getDisplayLanguage has no entry it echoes the tag ("ar"), which
        // TalkBack would spell out letter by letter. Asking in a locale the
        // platform is unlikely to have names for exercises that path; whatever
        // comes back must never be the bare two-letter tag.
        val obscure = Locale.forLanguageTag("cy-GB")
        for (language in AppLanguage.entries - AppLanguage.System) {
            val spoken = AppLanguage.of(language.tag).accessibleName(obscure)
            assertTrue(
                spoken != null && !spoken.equals(language.tag, ignoreCase = true),
                "${language.tag} asked in $obscure returned the bare tag: $spoken",
            )
        }
    }

    @Test
    fun `locales_config lists exactly the shipped languages`() {
        val config = File(res, "xml/locales_config.xml")
        assertTrue(config.isFile, "locales_config.xml not found at ${config.absolutePath}")
        val declared =
            Regex("""android:name="([^"]+)"""")
                .findAll(config.readText())
                .map { it.groupValues[1] }
                .toSet()
        val expected = (AppLanguage.entries - AppLanguage.System).map { it.tag }.toSet()
        assertEquals(
            expected,
            declared,
            "locales_config.xml and AppLanguage disagree — on API 33+ the system per-app-language screen would not match the in-app picker",
        )
    }

    @Test
    fun `translated string files declare the same keys as the default`() {
        // A key present in values/ but missing from values-ar/ silently renders
        // in English; a key present ONLY in a translation is dead weight that
        // reads as a missing feature.
        //
        // `translatable="false"` keys are excluded from BOTH directions. Those
        // are numerals ("001"), aspect notation ("16:9") and third-party
        // product names ("MX Player") — copying them into every locale is
        // noise that hides real gaps, and aapt warns if a translation supplies
        // one anyway.
        val default = translatableKeys(File(res, "values/strings.xml"))
        val untranslatable = allKeys(File(res, "values/strings.xml")) - default
        assertTrue(default.isNotEmpty(), "default strings.xml parsed as empty — check the regex")
        assertTrue(
            untranslatable.isNotEmpty(),
            "expected some translatable=\"false\" entries; if the regex broke, this test would pass vacuously",
        )
        for (language in AppLanguage.entries - AppLanguage.System - AppLanguage.English) {
            val file = File(res, "values-${language.tag}/strings.xml")
            val keys = allKeys(file)
            // app_name is deliberately untranslated — it's a brand mark.
            val missing = default - keys - setOf("app_name")
            val extra = keys - default
            assertTrue(missing.isEmpty(), "values-${language.tag}/strings.xml is missing keys: $missing")
            assertTrue(extra.isEmpty(), "values-${language.tag}/strings.xml has keys not in the default: $extra")
            val shouldNotBeThere = keys intersect untranslatable
            assertTrue(
                shouldNotBeThere.isEmpty(),
                "values-${language.tag}/strings.xml translates keys marked translatable=\"false\": $shouldNotBeThere",
            )
        }
    }

    /** Every `<string name="…">` in [file]. */
    private fun allKeys(file: File): Set<String> = Regex("""<string name="([^"]+)"""")
        .findAll(file.readText())
        .map { it.groupValues[1] }
        .toSet()

    /** Keys in [file] that are NOT marked `translatable="false"`. */
    private fun translatableKeys(file: File): Set<String> = Regex("""<string name="([^"]+)"([^>]*)>""")
        .findAll(file.readText())
        .filterNot { it.groupValues[2].contains("translatable=\"false\"") }
        .map { it.groupValues[1] }
        .toSet()
}
