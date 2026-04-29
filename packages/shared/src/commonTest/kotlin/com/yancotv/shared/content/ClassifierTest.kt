package com.yancotv.shared.content

import com.yancotv.shared.parsers.M3uEntry
import com.yancotv.shared.types.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals

private fun makeEntry(
    duration: Double = -1.0,
    title: String = "Test Channel",
    groupTitle: String = "",
    streamUrl: String = "http://example.com/stream",
): M3uEntry = M3uEntry(
    duration = duration,
    title = title,
    groupTitle = groupTitle,
    tvgId = "",
    tvgName = "",
    tvgLogo = "",
    streamUrl = streamUrl,
    rawAttributes = "",
)

class ClassifierTest {
    // --- classifyEntry — base suite (mirrors content-classifier.test.ts) ---

    @Test
    fun classifiesSeriesByS01E02Pattern() {
        assertEquals(ContentType.SERIES, classifyEntry(makeEntry(title = "Breaking Bad S01E02")))
        assertEquals(ContentType.SERIES, classifyEntry(makeEntry(title = "Show s3e12 Episode")))
    }

    @Test
    fun classifiesSeriesBySeasonX() {
        assertEquals(ContentType.SERIES, classifyEntry(makeEntry(title = "The Office Season 2")))
    }

    @Test
    fun classifiesSeriesByGroupName() {
        assertEquals(ContentType.SERIES, classifyEntry(makeEntry(groupTitle = "US | Series")))
        assertEquals(ContentType.SERIES, classifyEntry(makeEntry(groupTitle = "TV Show Drama")))
        assertEquals(ContentType.SERIES, classifyEntry(makeEntry(groupTitle = "Episode List")))
    }

    @Test
    fun classifiesSeriesBySeriesPathInUrl() {
        assertEquals(
            ContentType.SERIES,
            classifyEntry(makeEntry(streamUrl = "http://example.com/series/123/1/1.mp4")),
        )
    }

    @Test
    fun classifiesMoviesByGroupName() {
        assertEquals(ContentType.MOVIE, classifyEntry(makeEntry(groupTitle = "Movies | Action")))
        assertEquals(ContentType.MOVIE, classifyEntry(makeEntry(groupTitle = "VOD")))
        assertEquals(ContentType.MOVIE, classifyEntry(makeEntry(groupTitle = "Film Noir")))
        assertEquals(ContentType.MOVIE, classifyEntry(makeEntry(groupTitle = "Cinema")))
    }

    @Test
    fun classifiesMoviesByMoviePathInUrl() {
        assertEquals(
            ContentType.MOVIE,
            classifyEntry(makeEntry(streamUrl = "http://example.com/movie/123.mp4")),
        )
    }

    @Test
    fun classifiesMoviesByVideoExtension() {
        assertEquals(
            ContentType.MOVIE,
            classifyEntry(makeEntry(streamUrl = "http://example.com/video.mp4")),
        )
        assertEquals(
            ContentType.MOVIE,
            classifyEntry(makeEntry(streamUrl = "http://example.com/video.mkv")),
        )
    }

    @Test
    fun classifiesAsSeriesEvenWithVideoExtensionIfTitleHasS01E02() {
        assertEquals(
            ContentType.SERIES,
            classifyEntry(
                makeEntry(title = "Show S01E02", streamUrl = "http://example.com/video.mp4"),
            ),
        )
    }

    @Test
    fun classifiesMoviesByPositiveDuration() {
        assertEquals(ContentType.MOVIE, classifyEntry(makeEntry(duration = 3600.0)))
    }

    @Test
    fun defaultsToLive() {
        assertEquals(ContentType.LIVE, classifyEntry(makeEntry()))
        assertEquals(ContentType.LIVE, classifyEntry(makeEntry(duration = -1.0)))
    }

    // --- normalizeCategory — base suite ---

    @Test
    fun stripsCountryPrefixes() {
        assertEquals("News", normalizeCategory("US | News"))
        assertEquals("Sports", normalizeCategory("UK: Sports"))
        assertEquals("Entertainment", normalizeCategory("FR - Entertainment"))
    }

    @Test
    fun stripsTrailingCountrySuffix() {
        assertEquals("Sports", normalizeCategory("Sports | US"))
    }

    @Test
    fun normalizesCommonVariations() {
        assertEquals("Sports", normalizeCategory("Sport"))
        assertEquals("Documentary", normalizeCategory("Documentary"))
        assertEquals("Documentary", normalizeCategory("Documentaries"))
        assertEquals("Kids", normalizeCategory("Children's"))
        assertEquals("Kids", normalizeCategory("Kids"))
        assertEquals("Sci-Fi", normalizeCategory("Sci Fi"))
        assertEquals("Animation", normalizeCategory("Animation"))
        assertEquals("Animation", normalizeCategory("Animated"))
    }

    @Test
    fun titleCasesTheResult() {
        assertEquals("News & Politics", normalizeCategory("news & politics"))
    }

    @Test
    fun returnsEmptyStringForEmptyInput() {
        assertEquals("", normalizeCategory(""))
    }

    @Test
    fun preservesShortAcronyms() {
        assertEquals("US Sports", normalizeCategory("US Sports"))
    }

    // --- classifyEntry — extended edge cases ---

    @Test
    fun seriesTitlePatternWinsOverMovieGroup() {
        assertEquals(
            ContentType.SERIES,
            classifyEntry(makeEntry(title = "Show S01E02", groupTitle = "Movies")),
        )
    }

    @Test
    fun seriesGroupWinsOverMovieUrlExtension() {
        assertEquals(
            ContentType.SERIES,
            classifyEntry(
                makeEntry(
                    groupTitle = "Series | Drama",
                    streamUrl = "http://example.com/video.mp4",
                ),
            ),
        )
    }

    @Test
    fun classifiesMovFilesAsMovie() {
        assertEquals(
            ContentType.MOVIE,
            classifyEntry(makeEntry(streamUrl = "http://example.com/video.mov")),
        )
    }

    @Test
    fun classifiesCinemaGroupAsMovie() {
        assertEquals(ContentType.MOVIE, classifyEntry(makeEntry(groupTitle = "Cinema HD")))
    }

    @Test
    fun classifiesTvShowGroupAsSeries() {
        assertEquals(ContentType.SERIES, classifyEntry(makeEntry(groupTitle = "TV Show | Drama")))
    }

    @Test
    fun classifiesMovieUrlPath() {
        assertEquals(
            ContentType.MOVIE,
            classifyEntry(makeEntry(streamUrl = "http://xtream.com/movie/user/pass/123.mp4")),
        )
    }

    @Test
    fun classifiesSeriesUrlPath() {
        assertEquals(
            ContentType.SERIES,
            classifyEntry(makeEntry(streamUrl = "http://xtream.com/series/user/pass/456.mp4")),
        )
    }

    @Test
    fun zeroDurationDefaultsToLive() {
        assertEquals(ContentType.LIVE, classifyEntry(makeEntry(duration = 0.0)))
    }

    @Test
    fun negativeDurationDefaultsToLive() {
        assertEquals(ContentType.LIVE, classifyEntry(makeEntry(duration = -1.0)))
    }

    @Test
    fun positiveDurationWithNoOtherIndicatorIsMovie() {
        assertEquals(ContentType.MOVIE, classifyEntry(makeEntry(duration = 5400.0)))
    }

    // --- normalizeCategory — extended edge cases ---

    @Test
    fun normalizesDocumentariesToDocumentary() {
        assertEquals("Documentary", normalizeCategory("Documentaries"))
    }

    @Test
    fun normalizesChildrenToKids() {
        assertEquals("Kids", normalizeCategory("Children"))
    }

    @Test
    fun normalizesAnimatedToAnimation() {
        assertEquals("Animation", normalizeCategory("Animated"))
    }

    @Test
    fun normalizesMixedCaseInput() {
        assertEquals("Sports", normalizeCategory("SPORTS"))
        assertEquals("Sports", normalizeCategory("sports"))
    }

    @Test
    fun handlesMultipleSpaces() {
        assertEquals("News", normalizeCategory("US  |  News"))
    }

    @Test
    fun preservesComplexCategoryNames() {
        assertEquals("News & Politics", normalizeCategory("News & Politics"))
    }

    @Test
    fun handlesCategoryWithOnlyPrefix() {
        // After stripping "US:", only whitespace remains — returns original trimmed.
        assertEquals("US:", normalizeCategory("US:"))
    }

    @Test
    fun normalizesSciFiWithSpaceToSciFi() {
        assertEquals("Sci-Fi", normalizeCategory("Sci Fi"))
    }

    @Test
    fun normalizesAnimeConsistently() {
        assertEquals("Anime", normalizeCategory("Anime"))
    }

    @Test
    fun normalizesEducationAndEducational() {
        assertEquals("Education", normalizeCategory("Education"))
        assertEquals("Education", normalizeCategory("Educational"))
    }
}
