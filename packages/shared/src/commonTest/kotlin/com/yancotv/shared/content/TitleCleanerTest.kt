package com.yancotv.shared.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitleCleanerTest {
    // --- cleanTitle ---

    @Test fun stripsQualityTags() {
        assertEquals("CNN", cleanTitle("CNN HD"))
        assertEquals("ESPN", cleanTitle("ESPN FHD"))
        assertEquals("BBC", cleanTitle("BBC UHD"))
        assertEquals("Channel", cleanTitle("Channel 4K"))
        assertEquals("Movie", cleanTitle("Movie 1080p"))
        assertEquals("Show", cleanTitle("Show 720p"))
    }

    @Test fun stripsBracketedTags() {
        assertEquals("CNN", cleanTitle("CNN [HD]"))
        assertEquals("Movie", cleanTitle("Movie (MULTI)"))
        assertEquals("Channel", cleanTitle("Channel {VIP}"))
        assertEquals("Show", cleanTitle("Show [NEW]"))
    }

    @Test fun stripsCountryPrefixes() {
        assertEquals("CNN", cleanTitle("US: CNN"))
        assertEquals("BBC One", cleanTitle("UK | BBC One"))
        assertEquals("TF1", cleanTitle("FR - TF1"))
    }

    @Test fun stripsTrailingCountrySuffix() {
        assertEquals("Sky Sports", cleanTitle("Sky Sports | UK"))
    }

    @Test fun stripsChannelNumbering() {
        assertEquals("CNN", cleanTitle("001. CNN"))
        assertEquals("Fox News", cleanTitle("CH 042: Fox News"))
        assertEquals("Discovery", cleanTitle("15. Discovery"))
    }

    @Test fun stripsBackupServerSuffixes() {
        assertEquals("CNN", cleanTitle("CNN - Backup"))
        assertEquals("ESPN", cleanTitle("ESPN | S2"))
        assertEquals("Fox", cleanTitle("Fox | Server 3"))
    }

    @Test fun handlesCombinedNoise() {
        assertEquals("CNN", cleanTitle("US: CNN HD [MULTI]"))
    }

    @Test fun returnsOriginalIfCleaningEmptiesTitle() {
        assertEquals("HD", cleanTitle("HD"))
    }

    @Test fun keepsRawWhenCleaningLeavesOnlyPunctuation() {
        // MB-377 — bracket/prefix stripping can reduce a title to a lone
        // separator ("|"), which used to slip past the empty guard and render
        // 300+ channels identically. Must fall back to the raw title instead.
        val raw = "(MX) (VIX 01) | (2098-12-31 08:00:01)"
        val cleaned = cleanTitle(raw)
        assertTrue(cleaned.any { it.isLetterOrDigit() }, "cleaned title must not be punctuation-only, was '$cleaned'")
        assertEquals(raw, cleaned)
    }

    @Test fun trimsWhitespace() {
        assertEquals("CNN", cleanTitle("  CNN  "))
    }

    // --- extractYear ---

    @Test fun extractsYearFromParentheses() {
        assertEquals(1999, extractYear("The Matrix (1999)"))
        assertEquals(2021, extractYear("Dune (2021)"))
    }

    @Test fun extractsTrailingYear() {
        assertEquals(2023, extractYear("Movie Title 2023"))
    }

    @Test fun returnsNullForNoYear() {
        assertNull(extractYear("CNN Live"))
    }

    @Test fun rejectsInvalidYears() {
        assertNull(extractYear("Channel (1800)"))
    }

    // --- extractSeasonEpisode ---

    @Test fun extractsS01E02Format() {
        assertEquals(SeasonEpisode(1, 2), extractSeasonEpisode("Show S01E02"))
        assertEquals(SeasonEpisode(12, 99), extractSeasonEpisode("Show S12E99"))
    }

    @Test fun extractsCaseInsensitive() {
        assertEquals(SeasonEpisode(3, 4), extractSeasonEpisode("show s03e04"))
    }

    @Test fun extractsSeasonEpisodeLongFormat() {
        assertEquals(SeasonEpisode(2, 5), extractSeasonEpisode("Show Season 2 Episode 5"))
    }

    @Test fun extractsEpisodeOnly() {
        assertEquals(SeasonEpisode(1, 5), extractSeasonEpisode("Show E05"))
        assertEquals(SeasonEpisode(1, 12), extractSeasonEpisode("Show Ep12"))
    }

    @Test fun returnsNullForNonSeries() {
        assertNull(extractSeasonEpisode("CNN Live"))
        assertNull(extractSeasonEpisode("The Matrix (1999)"))
    }

    // --- extractShowName ---

    @Test fun stripsS01E02AndEverythingAfter() {
        assertEquals("Breaking Bad", extractShowName("Breaking Bad S01E02 Pilot"))
    }

    @Test fun stripsSeasonXAndEverythingAfter() {
        assertEquals("The Office", extractShowName("The Office Season 3 Episode 1"))
    }

    @Test fun returnsCleanedTitleForNonSeries() {
        assertEquals("CNN Live", extractShowName("CNN Live"))
    }

    // --- Extended edge cases ---

    @Test fun cleansTypicalIptvProviderFormat() {
        assertEquals("CNN", cleanTitle("US: CNN HD [MULTI]"))
    }

    @Test fun cleans001TF1FHD() {
        assertEquals("TF1", cleanTitle("001. TF1 FHD"))
    }

    @Test fun cleansChannelWithLogoBracket() {
        assertEquals("ESPN", cleanTitle("ESPN (Sports)"))
    }

    @Test fun preservesTitlesWithNoNoise() {
        assertEquals("CNN", cleanTitle("CNN"))
        assertEquals("BBC One", cleanTitle("BBC One"))
        assertEquals("The Matrix", cleanTitle("The Matrix"))
    }

    @Test fun handlesMultipleQualityTags() {
        assertEquals("Channel", cleanTitle("Channel HD FHD"))
    }

    @Test fun cleansUKSkySportsHDBackup() {
        assertEquals("Sky Sports", cleanTitle("UK | Sky Sports [HD] - Backup"))
    }

    @Test fun resolutionTag1080p() {
        assertEquals("Movie", cleanTitle("Movie 1080p"))
    }

    @Test fun resolutionTag720p() {
        assertEquals("Show", cleanTitle("Show 720p"))
    }

    @Test fun extractYearInterstellar() {
        assertEquals(2014, extractYear("Interstellar 2014"))
    }

    @Test fun extractYearInception() {
        assertEquals(2010, extractYear("Inception (2010)"))
    }

    @Test fun returnsNullForFutureYearTooFarAhead() {
        assertNull(extractYear("Movie (2099)", currentYear = 2026))
    }

    @Test fun handlesCurrentYear() {
        val thisYear = 2026
        assertEquals(thisYear, extractYear("Movie ($thisYear)", currentYear = thisYear))
    }

    @Test fun handlesNextYear() {
        val thisYear = 2026
        val nextYear = thisYear + 1
        assertEquals(nextYear, extractYear("Movie ($nextYear)", currentYear = thisYear))
    }

    @Test fun doesNotMatch3DigitNumbers() {
        assertNull(extractYear("Channel 123"))
    }

    @Test fun doesNotMatchNumbersInMiddle() {
        assertNull(extractYear("2001 A Space Odyssey"))
    }

    @Test fun extractSeasonEpisodeWithSpace() {
        assertEquals(SeasonEpisode(1, 2), extractSeasonEpisode("Show S01 E02"))
    }

    @Test fun singleDigitSeasonEpisode() {
        assertEquals(SeasonEpisode(1, 2), extractSeasonEpisode("Show S1E2"))
    }

    @Test fun highEpisodeNumbers() {
        assertEquals(SeasonEpisode(1, 120), extractSeasonEpisode("Show S01E120"))
    }

    @Test fun episodeKeyword() {
        assertEquals(SeasonEpisode(1, 5), extractSeasonEpisode("Show Episode 5"))
    }

    @Test fun doesNotMatchEInRegularWords() {
        assertNull(extractSeasonEpisode("THE MATRIX"))
    }

    @Test fun showNameWithS01E02InMiddle() {
        assertEquals("Breaking Bad", extractShowName("Breaking Bad S01E02 Seven Thirty-Seven"))
    }

    @Test fun showNameWithSpecialChars() {
        assertEquals("Grey's Anatomy", extractShowName("Grey's Anatomy S05E12"))
    }

    @Test fun showNameNoSeasonEpisode() {
        assertEquals("Random Movie Title", extractShowName("Random Movie Title"))
    }

    @Test fun showNameEmptyAfterCleaning() {
        assertEquals("S01E01", extractShowName("S01E01"))
    }

    // ───── cleanEpisodeTitle (MK.iOS.5) ─────

    @Test
    fun `cleanEpisodeTitle keeps only what follows the SxxExx marker`() {
        // Verbatim from a real provider account.
        assertEquals(
            "1. Bölüm",
            cleanEpisodeTitle("TR - Hakan: Muhafız (2018) (TR) - S01E01 - 1. Bölüm", "Hakan: Muhafız", 1),
        )
        assertEquals(
            "Winter Is Coming",
            cleanEpisodeTitle("Game of Thrones S01E01 - Winter Is Coming", "Game of Thrones", 1),
        )
        assertEquals("Pilot", cleanEpisodeTitle("Show S2E5: Pilot", "Show", 5))
    }

    @Test
    fun `cleanEpisodeTitle falls back rather than rendering a blank row`() {
        assertEquals("Episode 3", cleanEpisodeTitle("", "Show", 3))
        assertEquals("Episode 3", cleanEpisodeTitle("   ", "Show", 3))
        // Marker present but nothing after it.
        assertEquals("Episode 7", cleanEpisodeTitle("Show - S01E07", "Show", 7))
        // Title is only the series name, which says nothing new.
        assertEquals("Episode 2", cleanEpisodeTitle("Breaking Bad", "Breaking Bad", 2))
        assertEquals("Episode 2", cleanEpisodeTitle("  breaking bad  ", "Breaking Bad", 2))
    }

    @Test
    fun `cleanEpisodeTitle leaves a title with no marker alone`() {
        assertEquals("The Reunion", cleanEpisodeTitle("The Reunion", "Friends", 1))
    }

    // MB-391 — a separator that only existed to divide a stripped tag from
    // the name has nothing left to divide. 6,903 live channels on a real
    // account rendered as ": SKY SPORT…" before this.
    @Test
    fun `cleanTitle drops a separator orphaned by a strip`() {
        assertEquals("V SPORT", cleanTitle("4K: V SPORT UHD"))
        assertEquals("ELEVEN SPORTS 1", cleanTitle("4K: ELEVEN SPORTS 1 UHD"))
        assertEquals("BBC One", cleanTitle("HD | BBC One"))
        assertEquals("Arte", cleanTitle("Arte - HD"))
        assertEquals("Rai Uno", cleanTitle("1080p / Rai Uno"))
    }

    @Test
    fun `cleanTitle keeps punctuation that is part of the name`() {
        // Interior separators are the name's own; only edges are trimmed.
        assertEquals("Sky Sports: Main Event", cleanTitle("Sky Sports: Main Event"))
        assertEquals("Canal+", cleanTitle("Canal+ HD"))
        // A trailing dot is not a separator — `.` is excluded on purpose.
        assertEquals("M.A.S.H.", cleanTitle("M.A.S.H. HD"))
    }

    @Test
    fun `cleanTitle still falls back when only separators remain`() {
        // MB-377 holds: trimming must not turn a punctuation-only title
        // into an empty one.
        assertEquals("|", cleanTitle("|"))
        assertEquals("- HD -", cleanTitle("- HD -"))
    }
}
