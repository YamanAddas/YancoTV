package com.yancotv.android.player

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MK.34.1 — [nowPlayingFrom] contract.
 *
 * The headline case is the one from the design brief, captured verbatim off a
 * Fire TV: the provider string names the show twice, the country twice and the
 * episode twice, and the old dock rendered all of it at 34sp.
 *
 * Negative controls, both run, and both corrected a wrong first guess:
 *
 *  - Deleting the repeated-title filter in `episodeName` fails 2 tests — `a
 *    title repeated as the LAST token…` and `de-duplication survives a Turkish
 *    default locale`. It does NOT fail the brief's own case, because there the
 *    repeat sits in the middle and `episodeName` takes the last candidate. The
 *    first version of this file claimed otherwise and was wrong; the
 *    last-token test exists because of that.
 *  - Deleting the `Locale.ROOT` argument fails NOTHING. Kotlin's `lowercase()`
 *    is already locale-independent — that is exactly how it differs from
 *    Java's `toLowerCase()`. The argument is kept as documentation, and the
 *    Turkish test guards the rewrite that would actually break it.
 */
class NowPlayingMetadataTest {
    private val defaultLocale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `the brief's regression case splits into a clean title and one metadata line`() {
        val raw = "Tozkoparan İskender — TR - Tozkoparan İskender (2021) (TR) - S01 E03 - 3. Bölüm"
        val np = nowPlayingFrom(raw)
        assertEquals("Tozkoparan İskender", np.title)
        // No channel segment: see the KDoc — "TRT 1" is burned into the video,
        // not a field this app holds, so it is omitted rather than invented.
        assertEquals("2021 · S01 E03 · 3. Bölüm", np.metadataLine)
    }

    @Test
    fun `the unspaced S01E03 form the provider actually ships parses identically`() {
        // The brief writes "S01 E03"; the real string on the device has no
        // space. Both must land on the same output or the fix only works in
        // the document it was specified in.
        val raw = "Tozkoparan İskender — TR - Tozkoparan İskender (2021) (TR) - S01E03 - 3. Bölüm"
        val np = nowPlayingFrom(raw)
        assertEquals("Tozkoparan İskender", np.title)
        assertEquals("2021 · S01 E03 · 3. Bölüm", np.metadataLine)
    }

    @Test
    fun `the series name is not repeated in the metadata line`() {
        val raw = "Tozkoparan İskender — TR - Tozkoparan İskender (2021) (TR) - S01E03 - 3. Bölüm"
        val np = nowPlayingFrom(raw)
        assertTrue(
            np.segments.none { it.contains("Tozkoparan", ignoreCase = true) },
            "series name leaked into the metadata segments: ${np.segments}",
        )
    }

    @Test
    fun `structured season and episode beat whatever the string says`() {
        // Playable.Episode got these as integers from the Xtream API. A parser
        // that ignores fields it already has is a parser inventing bugs.
        val raw = "Show — S01E03 - Pilot"
        val np = nowPlayingFrom(raw, season = 2, episode = 7)
        assertTrue("S02 E07" in np.segments, "expected structured S02 E07, got ${np.segments}")
        assertTrue("S01 E03" !in np.segments)
    }

    @Test
    fun `a title repeated as the LAST token is not echoed back as the episode name`() {
        // This is what actually pins the de-duplication filter. In the brief's
        // string the repeated title sits in the MIDDLE, and since episodeName
        // takes the last surviving candidate, dropping the filter changes
        // nothing there — the guard only earns its place when the repeat is
        // last, which is a shape providers do ship.
        val np = nowPlayingFrom("Tozkoparan İskender — S01E03 - Tozkoparan İskender")
        assertEquals("Tozkoparan İskender", np.title)
        assertEquals("S01 E03", np.metadataLine)
    }

    @Test
    fun `de-duplication survives a Turkish default locale`() {
        Locale.setDefault(Locale("tr", "TR"))
        // The trap needs an ASCII capital I and a case DIFFERENCE. tr-TR maps
        // uppercase I to dotless ı, so a bare lowercase() turns "DIRILIS" into
        // "dırılıs" and "Dirilis" into "dirilis" — they stop matching and the
        // title comes back as the episode name. Comparing two identical strings
        // would not catch this: a broken locale mangles both the same way.
        val np = nowPlayingFrom("KURULUS DIRILIS — S01E03 - Kurulus Dirilis")
        assertEquals("KURULUS DIRILIS", np.title)
        assertEquals("S01 E03", np.metadataLine)
    }

    @Test
    fun `arabic titles and episode names are preserved intact`() {
        val raw = "مسلسل الاختيار — AR - مسلسل الاختيار (2020) - S02E05 - الحلقة 5"
        val np = nowPlayingFrom(raw)
        assertEquals("مسلسل الاختيار", np.title)
        assertEquals("2020 · S02 E05 · الحلقة 5", np.metadataLine)
    }

    @Test
    fun `a movie keeps its name and its year and gains nothing else`() {
        val np = nowPlayingFrom("Big Buck Bunny (2008)")
        assertEquals("Big Buck Bunny", np.title)
        assertEquals("2008", np.metadataLine)
    }

    @Test
    fun `a leading provider country prefix is stripped from the title`() {
        val np = nowPlayingFrom("TR - Kurulus Osman (2019)")
        assertEquals("Kurulus Osman", np.title)
        assertEquals("2019", np.metadataLine)
    }

    @Test
    fun `a channel is rendered first when the caller genuinely knows one`() {
        // LIVE, where the channel IS the item. Never a category.
        val np = nowPlayingFrom("Match of the Day", channel = "BBC One")
        assertEquals("BBC One", np.metadataLine)
    }

    @Test
    fun `an unparseable title is returned untouched rather than mangled`() {
        val np = nowPlayingFrom("منوعات")
        assertEquals("منوعات", np.title)
        assertEquals("", np.metadataLine)
    }

    @Test
    fun `blank input falls back to the input instead of rendering an em dash`() {
        assertEquals("   ", nowPlayingFrom("   ").title)
        assertTrue(nowPlayingFrom("   ").segments.isEmpty())
    }

    @Test
    fun `season zero or episode zero is treated as absent, matching episodeKicker`() {
        val np = nowPlayingFrom("Show — Pilot", season = 0, episode = 0)
        assertTrue(np.segments.none { it.startsWith("S") }, "got ${np.segments}")
    }
}
