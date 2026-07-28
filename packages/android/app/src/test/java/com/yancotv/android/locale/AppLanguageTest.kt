package com.yancotv.android.locale

import java.io.File
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
