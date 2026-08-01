package com.yancotv.android.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MB-339 — guards the `<plurals>` resources introduced in MK.31.
 *
 * The bug this exists to prevent recurring: the app shipped 24 counted strings
 * as flat `<string>` entries, so Arabic rendered the singular for every
 * quantity. Arabic has six CLDR categories and needs the dual for 2 and the
 * broken plural for 3-10 — between them the commonest range in this UI.
 *
 * Reading the XML directly rather than going through Robolectric is deliberate.
 * The failure mode is a *missing or malformed resource*, and the resource files
 * are the artefact under test. Robolectric would add a dependency and an
 * `AndroidManifest` round-trip to check the same four files, and it resolves
 * only one locale per test run.
 *
 * **The task must declare `src/main/res` as an input** or this whole class is
 * theatre — see the `tasks.withType<Test>` block in `app/build.gradle.kts`. The
 * first negative-control of this test reported GREEN against a deliberately
 * broken Arabic plural because Gradle had no reason to re-run it.
 *
 * Two of the checks below exist because lint caught a bug an earlier version of
 * this test did not: French `one` covers **0 and 1**, so a French `one` item
 * with a hardcoded "1" renders "1 ligne restaurée" for a count of zero.
 */
class PluralResourceParityTest {
    private val locales = listOf("values", "values-ar", "values-fr", "values-es")

    /**
     * The categories each language's CLDR rules can actually select. Declaring
     * an item outside this set is dead text — Android never picks it — which is
     * how "no items yet" silently became "0 items" during this very slice.
     *
     * Note `many` for fr/es: modern CLDR gives both a `many` category for exact
     * multiples of a million ("1 000 000 **de** chaînes"). It is easy to miss
     * because no sample count under a million exercises it.
     */
    private val cldrCategories = mapOf(
        "values" to setOf("one", "other"),
        "values-fr" to setOf("one", "many", "other"),
        "values-es" to setOf("one", "many", "other"),
        "values-ar" to setOf("zero", "one", "two", "few", "many", "other"),
    )

    /**
     * Locales whose `one` matches more than a single number. In these, a `one`
     * item without a format argument is a real bug — this is lint's
     * `ImpliedQuantity`, asserted here so it fails in the unit suite too.
     */
    private val oneIsAmbiguous = setOf("values-fr")

    /** Quantities that separate the four rule sets — including the million. */
    private val samples = listOf(0, 1, 2, 3, 7, 11, 25, 100, 101, 102, 111, 1_000_000)

    // ───── CLDR plural rules for these four languages ─────

    private fun category(locale: String, n: Int): String = when (locale) {
        "values" -> if (n == 1) "one" else "other"
        "values-es" -> if (n == 1) {
            "one"
        } else if (n != 0 && n % 1_000_000 == 0) {
            "many"
        } else {
            "other"
        }
        // French takes the singular for zero as well as one.
        "values-fr" ->
            if (n == 0 || n == 1) {
                "one"
            } else if (n % 1_000_000 == 0) {
                "many"
            } else {
                "other"
            }
        "values-ar" -> when {
            n == 0 -> "zero"
            n == 1 -> "one"
            n == 2 -> "two"
            n % 100 in 3..10 -> "few"
            n % 100 in 11..99 -> "many"
            else -> "other"
        }
        else -> error("unhandled locale $locale")
    }

    /**
     * What `getQuantityString` actually returns: the rule's category if the
     * resource declares it, otherwise `other`. Modelling the fallback rather
     * than asserting against it is the point — an absent fr `many` is a
     * documented, acceptable degradation; an absent `other` is a crash.
     */
    private fun resolve(locale: String, p: Plural, n: Int): Pair<String, String?> {
        val want = category(locale, n)
        return if (p.items.containsKey(want)) want to p.items[want] else "other*" to p.items["other"]
    }

    // ───── resource parsing ─────

    private val resDir: File by lazy {
        // Gradle runs unit tests with the module dir as CWD, but that is not
        // contractual, so walk up until the res dir appears rather than
        // hard-coding a relative depth.
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/res")
            if (candidate.isDirectory) return@lazy candidate
            val self = File(dir, "src/main/res")
            if (self.isDirectory) return@lazy self
            dir = dir.parentFile
        }
        error("could not locate app/src/main/res from ${System.getProperty("user.dir")}")
    }

    data class Plural(val name: String, val items: Map<String, String>)

    private fun parse(locale: String): Map<String, Plural> {
        val xml = File(resDir, "$locale/strings.xml").readText()
        val out = LinkedHashMap<String, Plural>()
        Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .forEach { m ->
                val items = Regex("""<item quantity="([^"]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(m.groupValues[2])
                    .associate { it.groupValues[1] to it.groupValues[2] }
                out[m.groupValues[1]] = Plural(m.groupValues[1], items)
            }
        return out
    }

    private val parsed: Map<String, Map<String, Plural>> by lazy { locales.associateWith { parse(it) } }

    /** Highest positional format index used, e.g. `%2$d` -> 2. 0 when none. */
    private fun maxArgIndex(text: String): Int = Regex("""%(\d+)\$""").findAll(text).map { it.groupValues[1].toInt() }.maxOrNull() ?: 0

    // ───── the assertions ─────

    @Test
    fun `there is something to test`() {
        // A regex that silently matched nothing would make every other
        // assertion in this class vacuously true.
        assertTrue(
            "no <plurals> parsed from values/strings.xml — the parser or the resource moved",
            parsed.getValue("values").size >= 20,
        )
    }

    @Test
    fun `every plural exists in every locale`() {
        val base = parsed.getValue("values").keys
        locales.filter { it != "values" }.forEach { loc ->
            assertEquals("$loc is missing plurals present in values/", emptySet<String>(), base - parsed.getValue(loc).keys)
            assertEquals("$loc declares plurals absent from values/", emptySet<String>(), parsed.getValue(loc).keys - base)
        }
    }

    @Test
    fun `Arabic declares all six categories`() {
        // The motivating bug. Anything less and a count in the 2-10 range —
        // the commonest in this UI — falls back to `other`, which is the
        // singular, which is what was wrong in the first place.
        val required = cldrCategories.getValue("values-ar")
        val failures = parsed.getValue("values-ar").values
            .mapNotNull { p -> (required - p.items.keys).takeIf { it.isNotEmpty() }?.let { "${p.name}: missing ${it.sorted()}" } }
        assertEquals("Arabic plural categories missing", emptyList<String>(), failures)
    }

    @Test
    fun `every locale declares other`() {
        // `other` is the fallback every rule set can land on. Without it,
        // getQuantityString has nothing to return.
        val failures = locales.flatMap { loc ->
            parsed.getValue(loc).values.filterNot { it.items.containsKey("other") }.map { "$loc/${it.name}" }
        }
        assertEquals("plural with no `other` item", emptyList<String>(), failures)
    }

    @Test
    fun `no locale declares a category its language never selects`() {
        val failures = locales.flatMap { loc ->
            val allowed = cldrCategories.getValue(loc)
            parsed.getValue(loc).values.mapNotNull { p ->
                (p.items.keys - allowed).takeIf { it.isNotEmpty() }?.let { "$loc/${p.name}: unreachable ${it.sorted()}" }
            }
        }
        assertEquals("dead plural categories — Android never selects these", emptyList<String>(), failures)
    }

    @Test
    fun `an ambiguous one carries a format argument`() {
        // lint ImpliedQuantity, asserted here too. French `one` covers 0 and 1,
        // so "1 ligne restaurée" is what a zero count would render.
        val failures = oneIsAmbiguous.flatMap { loc ->
            parsed.getValue(loc).values.mapNotNull { p ->
                p.items["one"]?.takeIf { !it.contains("%") }?.let { "$loc/${p.name}[one] has no format argument: \"${it.trim()}\"" }
            }
        }
        assertEquals("`one` matches more than one number in this locale", emptyList<String>(), failures)
    }

    @Test
    fun `no translation references a format argument the English source does not`() {
        // This is the crash class. getQuantityString passes a fixed argument
        // list from the call site; a translation reaching past it throws
        // MissingFormatArgumentException in that locale only.
        val base = parsed.getValue("values")
        val failures = locales.filter { it != "values" }.flatMap { loc ->
            parsed.getValue(loc).values.flatMap { p ->
                val budget = base[p.name]?.items?.values?.maxOfOrNull { maxArgIndex(it) } ?: 0
                p.items.filterValues { maxArgIndex(it) > budget }
                    .map { (q, _) -> "$loc/${p.name}[$q] reaches past the $budget argument(s) the call site supplies" }
            }
        }
        assertEquals("format-argument overrun — crashes at render time in one locale", emptyList<String>(), failures)
    }

    @Test
    fun `resolution never yields nothing for real quantities`() {
        val failures = locales.flatMap { loc ->
            parsed.getValue(loc).values.flatMap { p ->
                samples.filter { resolve(loc, p, it).second == null }.map { "$loc/${p.name}: n=$it resolves to nothing" }
            }
        }
        assertEquals("unresolvable quantity", emptyList<String>(), failures)
    }

    @Test
    fun `writes the resolved matrix for review`() {
        val out = File("build/reports/plurals/plural-matrix.txt")
        out.parentFile.mkdirs()
        val review = listOf(0, 1, 2, 3, 11, 100, 1_000_000)
        out.writeText(
            buildString {
                appendLine("MB-339 — resolved plural forms. Generated by PluralResourceParityTest.")
                appendLine("Format arguments are left as %n\$ placeholders; only category selection is resolved.")
                appendLine("A category marked * means the rule asked for one the locale does not declare,")
                appendLine("so Android falls back to `other` — acceptable, but visible here.")
                appendLine()
                parsed.getValue("values").keys.forEach { name ->
                    appendLine(name)
                    locales.forEach { loc ->
                        val p = parsed.getValue(loc).getValue(name)
                        val tag = loc.removePrefix("values").removePrefix("-").ifEmpty { "en" }
                        review.forEach { n ->
                            val (cat, text) = resolve(loc, p, n)
                            appendLine("   %-3s n=%-8d %-7s %s".format(tag, n, cat, text?.trim() ?: "<<MISSING>>"))
                        }
                    }
                    appendLine()
                }
            },
        )
        assertTrue("matrix artifact was not written", out.length() > 1_000)
    }
}
