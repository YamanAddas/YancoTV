package com.yancotv.android.player.subtitles

import com.yancotv.shared.playback.Playable
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pin the regex set that turns noisy IPTV titles into clean OpenSubtitles
 * queries. The user has already hit two bugs in this code path:
 *   1. Episodes sent the joined "Series — S01E02" string as the query.
 *   2. Movies kept release tags + provider prefixes ("AR-SUBS-" etc.)
 *      that the API can't handle.
 *
 * These tests are the contract — adding a new prefix pattern later
 * shouldn't regress any of them. Table-driven so adding a fresh failing
 * case is one row.
 */
class SubtitleQueryBuilderTest {
    // ───── stripReleaseNoise — provider prefix peeling ─────

    @Test
    fun `strips AR-SUBS- prefix`() {
        assertEquals("Under the Tuscan Sun", stripReleaseNoise("AR-SUBS-Under the Tuscan Sun"))
    }

    @Test
    fun `strips MULTI-SUBS- prefix`() {
        assertEquals("Avatar 2009", stripReleaseNoise("MULTI-SUBS-Avatar 2009"))
    }

    @Test
    fun `strips EN-DUB- prefix`() {
        assertEquals("Inception", stripReleaseNoise("EN-DUB-Inception"))
    }

    @Test
    fun `strips USA-DUB- prefix`() {
        assertEquals("The Dark Knight", stripReleaseNoise("USA-DUB-The Dark Knight"))
    }

    @Test
    fun `strips bare AR- prefix`() {
        assertEquals("The Matrix", stripReleaseNoise("AR-The Matrix"))
    }

    @Test
    fun `strips bare SUBS- prefix without country code`() {
        assertEquals("Inception", stripReleaseNoise("SUBS-Inception"))
    }

    @Test
    fun `strips VIP promo prefix`() {
        assertEquals("Dune", stripReleaseNoise("VIP | Dune"))
    }

    @Test
    fun `peels stacked prefixes - VIP plus AR-SUBS`() {
        assertEquals("Movie 2010", stripReleaseNoise("VIP | AR-SUBS-Movie 2010"))
    }

    @Test
    fun `case insensitive on tag words`() {
        assertEquals("Movie", stripReleaseNoise("ar-subs-Movie"))
    }

    @Test
    fun `does not strip short English words at start`() {
        // "AT" is technically 2 letters but `^[A-Z]{2,4}-` requires uppercase
        // AND a dash. "At-" wouldn't appear in a real title, but checking that
        // mixed-case "At First Sight" stays intact.
        assertEquals("At First Sight", stripReleaseNoise("At First Sight"))
    }

    @Test
    fun `does not strip movie titles starting with one-letter prefix`() {
        // X-Men: "X" is 1 letter, the country-code regex requires 2-4.
        assertEquals("X-Men First Class", stripReleaseNoise("X-Men First Class"))
    }

    @Test
    fun `does not strip movie titles starting with digits`() {
        assertEquals("12 Years a Slave", stripReleaseNoise("12 Years a Slave"))
    }

    // ───── stripReleaseNoise — body release-tag stripping ─────

    @Test
    fun `strips BluRay release tag`() {
        assertEquals("Movie 2010", stripReleaseNoise("Movie 2010 BluRay"))
    }

    @Test
    fun `strips x265 codec tag`() {
        assertEquals("Movie 2010", stripReleaseNoise("Movie 2010 x265"))
    }

    @Test
    fun `strips DDP5_1 audio tag`() {
        assertEquals("Movie 2010", stripReleaseNoise("Movie 2010 DDP5.1"))
    }

    @Test
    fun `strips Atmos tag`() {
        assertEquals("Movie 2010", stripReleaseNoise("Movie 2010 Atmos"))
    }

    @Test
    fun `strips combined release tags`() {
        assertEquals(
            "Movie 2010",
            stripReleaseNoise("Movie 2010 1080p BluRay DDP5.1 x265-RELEASE"),
        )
    }

    @Test
    fun `normalizes dots between words`() {
        assertEquals(
            "Movie Name 2010",
            stripReleaseNoise("Movie.Name.2010.BluRay.x265"),
        )
    }

    @Test
    fun `normalizes underscores between words`() {
        assertEquals(
            "Movie Name 2010",
            stripReleaseNoise("Movie_Name_2010_BluRay"),
        )
    }

    @Test
    fun `passes clean titles through unchanged`() {
        assertEquals("The Matrix", stripReleaseNoise("The Matrix"))
        assertEquals("Inception", stripReleaseNoise("Inception"))
    }

    // ───── appendYearIfMissing ─────

    @Test
    fun `appends year when source has it but query lost it`() {
        assertEquals("Avatar 2009", appendYearIfMissing("Avatar", "Avatar (2009)"))
    }

    @Test
    fun `does not append when query already has a 4-digit year`() {
        assertEquals("Avatar 2009", appendYearIfMissing("Avatar 2009", "Avatar (2009)"))
    }

    @Test
    fun `does not append when source has no year`() {
        assertEquals("The Matrix", appendYearIfMissing("The Matrix", "The Matrix"))
    }

    @Test
    fun `rejects out-of-range years`() {
        // 1800 falls outside 1900..2099, leave query alone.
        assertEquals("Old Movie", appendYearIfMissing("Old Movie", "Old Movie 1800"))
    }

    @Test
    fun `prefers parenthesized year over bare year`() {
        // "Movie 1234 (2009)" — the regex finds the parenthesized 2009 first.
        // Either way appendYearIfMissing skips because query contains 1234.
        // So we test the parenthesized-extraction path with a clean query.
        assertEquals("Movie 2009", appendYearIfMissing("Movie", "Movie (2009) something"))
    }

    // ───── buildSubtitleQuery — episode path ─────

    @Test
    fun `episode path uses series side of joined title`() {
        val episode = makeEpisode(title = "The Office — S02E12", season = 2, episode = 12)
        val item = makeSeriesEpisodeItem(title = "The Office — S02E12")
        val q = assertNotNull(buildSubtitleQuery(item, episode))
        assertEquals("The Office", q.query)
        assertEquals(2, q.season)
        assertEquals(12, q.episode)
        assertEquals("episode", q.type)
    }

    @Test
    fun `episode path strips provider prefix from series name`() {
        val episode = makeEpisode(title = "AR-SUBS-Breaking Bad — S01E01", season = 1, episode = 1)
        val item = makeSeriesEpisodeItem(title = "AR-SUBS-Breaking Bad — S01E01")
        val q = assertNotNull(buildSubtitleQuery(item, episode))
        assertEquals("Breaking Bad", q.query)
    }

    @Test
    fun `episode with zero season number returns null season`() {
        // Some xtream feeds have season=0 for special episodes; the takeIf>0
        // guard turns that into null so OpenSubtitles doesn't filter on a
        // value that doesn't exist in the DB.
        val episode = makeEpisode(title = "Show — Special", season = 0, episode = 1)
        val item = makeSeriesEpisodeItem(title = "Show — Special")
        val q = assertNotNull(buildSubtitleQuery(item, episode))
        assertNull(q.season)
        assertEquals(1, q.episode)
    }

    // ───── buildSubtitleQuery — movie path ─────

    @Test
    fun `movie path strips provider prefix and tags type as movie`() {
        val item =
            makeMovieItem(
                title = "AR-SUBS-Under the Tuscan Sun",
                cleanTitle = "AR-SUBS-Under the Tuscan Sun",
            )
        val q = assertNotNull(buildSubtitleQuery(item, episode = null))
        assertEquals("Under the Tuscan Sun", q.query)
        assertEquals("movie", q.type)
        assertNull(q.season)
        assertNull(q.episode)
    }

    @Test
    fun `movie path appends year when source has it but cleanTitle drops it`() {
        // Hypothetical: cleanTitle removed the year but source title had it.
        val item =
            makeMovieItem(
                title = "Avatar (2009) BluRay",
                cleanTitle = "Avatar BluRay",
            )
        val q = assertNotNull(buildSubtitleQuery(item, episode = null))
        // Year 2009 from raw title appended after strip pulls "BluRay".
        assertEquals("Avatar 2009", q.query)
    }

    @Test
    fun `movie path falls back to title when cleanTitle is blank`() {
        val item =
            makeMovieItem(
                title = "EN-The Matrix",
                cleanTitle = "",
            )
        val q = assertNotNull(buildSubtitleQuery(item, episode = null))
        assertEquals("The Matrix", q.query)
    }

    @Test
    fun `live channel returns query with null type`() {
        val item =
            ContentItem(
                id = "id",
                sourceId = "src",
                type = ContentType.LIVE,
                title = "CNN HD",
                cleanTitle = "CNN",
                groupName = null,
                streamUrl = "http://example.com/stream",
                logoUrl = null,
                tvgId = null,
                metadataJson = null,
                sortOrder = 0,
                createdAt = 0L,
            )
        val q = assertNotNull(buildSubtitleQuery(item, episode = null))
        assertEquals("CNN", q.query)
        assertNull(q.type) // type is "movie" only for MOVIE items
    }

    @Test
    fun `null item returns null bundle`() {
        assertNull(buildSubtitleQuery(item = null, episode = null))
    }

    // ───── helpers ─────

    private fun makeMovieItem(title: String, cleanTitle: String?): ContentItem =
        ContentItem(
            id = "id",
            sourceId = "src",
            type = ContentType.MOVIE,
            title = title,
            cleanTitle = cleanTitle,
            groupName = null,
            streamUrl = "http://example.com/movie.mkv",
            logoUrl = null,
            tvgId = null,
            metadataJson = null,
            sortOrder = 0,
            createdAt = 0L,
        )

    private fun makeSeriesEpisodeItem(title: String): ContentItem =
        ContentItem(
            id = "ep-id",
            sourceId = "src",
            // Episodes synthesize as MOVIE-typed in PlaybackController.
            type = ContentType.MOVIE,
            title = title,
            cleanTitle = title,
            groupName = null,
            streamUrl = "http://example.com/ep.mkv",
            logoUrl = null,
            tvgId = null,
            metadataJson = null,
            sortOrder = 0,
            createdAt = 0L,
        )

    private fun makeEpisode(title: String, season: Int, episode: Int): Playable.Episode =
        Playable.Episode(
            id = "ep-id",
            seriesId = "series-id",
            title = title,
            streamUrl = "http://example.com/ep.mkv",
            seasonNumber = season,
            episodeNumber = episode,
        )
}
