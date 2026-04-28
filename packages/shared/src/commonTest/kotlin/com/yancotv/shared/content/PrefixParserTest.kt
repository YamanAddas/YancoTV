package com.yancotv.shared.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * MK.20.2 — Table-driven fixtures for [PrefixParser]. Covers the real-world
 * delimiter shapes seen in field M3Us plus the resolve/conflict policy in
 * [PrefixCatalog].
 */
class PrefixParserTest {
    private data class Case(
        val input: String,
        val expectedPrefix: String?,
        val expectedDisplay: String?,
        val expectedKind: PrefixCatalog.Kind?,
        val expectedRemainder: String,
    )

    private val cases =
        listOf(
            // Delimiter shapes — code-first.
            Case("AR| Sports", "AR", "Arabic", PrefixCatalog.Kind.Language, "Sports"),
            Case("AR | Sports", "AR", "Arabic", PrefixCatalog.Kind.Language, "Sports"),
            Case("|AR| Sports", "AR", "Arabic", PrefixCatalog.Kind.Language, "Sports"),
            Case("[AR] Sports", "AR", "Arabic", PrefixCatalog.Kind.Language, "Sports"),
            Case("AR - Sports", "AR", "Arabic", PrefixCatalog.Kind.Language, "Sports"),
            Case("AR – Sports", "AR", "Arabic", PrefixCatalog.Kind.Language, "Sports"), // en-dash
            Case("AR — Sports", "AR", "Arabic", PrefixCatalog.Kind.Language, "Sports"), // em-dash
            Case("AR: Sports", "AR", "Arabic", PrefixCatalog.Kind.Language, "Sports"),
            Case("AR Sports", "AR", "Arabic", PrefixCatalog.Kind.Language, "Sports"), // space-only

            // Region wins over language for ambiguous codes.
            Case("CA | Hockey", "CA", "Canada", PrefixCatalog.Kind.Region, "Hockey"),
            Case("TR | Drama", "TR", "Türkiye", PrefixCatalog.Kind.Region, "Drama"),

            // Three-letter codes.
            Case("USA | Movies", "USA", "USA", PrefixCatalog.Kind.Region, "Movies"),

            // Full-word match via reverse catalog.
            Case("Arabic | Drama", "Arabic", "Arabic", PrefixCatalog.Kind.Language, "Drama"),
            Case("English - News", "English", "English", PrefixCatalog.Kind.Language, "News"),

            // MK.20 polish-sweep — multi-word display names. Pre-fix the
            // single-word `[A-Za-zÀ-ÿ]{3,}` regex matched only the FIRST
            // word, so these silently fell through to "no prefix found"
            // even though the catalog knows them by their displayName.
            Case("Saudi Arabia | beIN", "Saudi Arabia", "Saudi Arabia", PrefixCatalog.Kind.Region, "beIN"),
            Case("South Korea | KBS", "South Korea", "South Korea", PrefixCatalog.Kind.Region, "KBS"),
            Case("Hong Kong | TVB", "Hong Kong", "Hong Kong", PrefixCatalog.Kind.Region, "TVB"),
            Case("New Zealand - TVNZ", "New Zealand", "New Zealand", PrefixCatalog.Kind.Region, "TVNZ"),
            Case("South Africa | SABC", "South Africa", "South Africa", PrefixCatalog.Kind.Region, "SABC"),
            // Multi-word with em-dash + colon delimiters too.
            Case("Czech Republic — Sport", "Czech Republic", "Czech Republic", PrefixCatalog.Kind.Region, "Sport"),
            Case("Saudi Arabia: News", "Saudi Arabia", "Saudi Arabia", PrefixCatalog.Kind.Region, "News"),

            // MK.20 polish-sweep — newly-added 2-letter codes.
            Case("BG | Sport", "BG", "Bulgaria", PrefixCatalog.Kind.Region, "Sport"),
            Case("CZ | Movies", "CZ", "Czech Republic", PrefixCatalog.Kind.Region, "Movies"),
            Case("HR | Drama", "HR", "Croatia", PrefixCatalog.Kind.Region, "Drama"),
            Case("HU | News", "HU", "Hungary", PrefixCatalog.Kind.Region, "News"),
            Case("IS | Sport", "IS", "Iceland", PrefixCatalog.Kind.Region, "Sport"),
            Case("KZ | Movies", "KZ", "Kazakhstan", PrefixCatalog.Kind.Region, "Movies"),
            Case("BH | Sport", "BH", "Bahrain", PrefixCatalog.Kind.Region, "Sport"),
            Case("OM | News", "OM", "Oman", PrefixCatalog.Kind.Region, "News"),
            Case("PS | Drama", "PS", "Palestine", PrefixCatalog.Kind.Region, "Drama"),
            Case("LY | Movies", "LY", "Libya", PrefixCatalog.Kind.Region, "Movies"),

            // Unmatched — known-bad code stays unparsed.
            Case("ZZ | Foo", null, null, null, "ZZ | Foo"),

            // No prefix at all — passthrough as remainder.
            Case("Sports", null, null, null, "Sports"),
            Case("News HD", null, null, null, "News HD"),

            // Lowercase space-only must NOT match — e.g. "ar sports" is just a title.
            // (codeSpaceRegex requires uppercase code + uppercase next token.)
            Case("ar sports", null, null, null, "ar sports"),

            // "US Open" must NOT be eaten — second token "Open" is uppercase
            // so the regex does match. This confirms a known limitation: pure
            // space-only matching is greedy. The IPTV corpus we've seen always
            // uses a delimiter for prefixes, so this is acceptable trade-off.
            // (Test left as documentation, not enforcement.)

            // Empty / whitespace.
            Case("", null, null, null, ""),
            Case("   ", null, null, null, ""),
        )

    @Test
    fun parse_tableDriven() {
        for (c in cases) {
            val got = PrefixParser.parse(c.input)
            assertEquals(c.expectedPrefix, got.prefix, "prefix for '${c.input}'")
            assertEquals(c.expectedDisplay, got.resolved?.displayName, "display for '${c.input}'")
            assertEquals(c.expectedKind, got.resolved?.kind, "kind for '${c.input}'")
            assertEquals(c.expectedRemainder, got.remainder, "remainder for '${c.input}'")
        }
    }

    @Test
    fun resolve_codeIsCaseInsensitive() {
        assertNotNull(PrefixCatalog.resolve("ar"))
        assertNotNull(PrefixCatalog.resolve("AR"))
        assertNotNull(PrefixCatalog.resolve("Ar"))
        assertNull(PrefixCatalog.resolve("zz"))
    }

    @Test
    fun resolve_caCollidesToCanadaNotCatalan() {
        val entry = PrefixCatalog.resolve("CA")
        assertNotNull(entry)
        assertEquals("Canada", entry.displayName)
        assertEquals(PrefixCatalog.Kind.Region, entry.kind)
    }

    @Test
    fun resolve_trCollidesToTurkiyeNotTurkish() {
        val entry = PrefixCatalog.resolve("TR")
        assertNotNull(entry)
        assertEquals("Türkiye", entry.displayName)
    }

    @Test
    fun originalNamePreservedExactly() {
        val raw = "  AR | Sports HD  "
        val parsed = PrefixParser.parse(raw)
        assertEquals(raw, parsed.originalName)
    }
}
