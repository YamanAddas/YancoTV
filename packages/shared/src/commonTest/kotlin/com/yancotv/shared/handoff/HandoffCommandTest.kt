package com.yancotv.shared.handoff

import com.yancotv.shared.playback.Playable
import com.yancotv.shared.types.ContentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffCommandTest {
    private val json = Json

    @Test
    fun commandRoundTripsThroughJson() {
        val cmd =
            HandoffPlayCommand(
                pairingToken = "tok-123",
                item =
                    HandoffItem(
                        id = "ch:42",
                        sourceId = "src-1",
                        kind = HandoffKind.CHANNEL,
                        title = "BBC One HD",
                        streamUrl = "http://provider/live/42.ts",
                        userAgent = "VLC/3.0.20",
                        referer = "http://provider/",
                    ),
                resumePositionSeconds = 0L,
            )

        val decoded = json.decodeFromString<HandoffPlayCommand>(json.encodeToString(cmd))

        assertEquals(cmd, decoded)
        assertEquals(HandoffPlayCommand.SCHEMA_VERSION, decoded.schemaVersion)
    }

    @Test
    fun kindSerializesToStableWireToken() {
        // The wire token must stay "channel"/"movie"/"episode" — senders and
        // receivers on different app versions depend on it.
        val encoded =
            json.encodeToString(
                HandoffItem(
                    id = "m:1",
                    kind = HandoffKind.MOVIE,
                    title = "Dune",
                    streamUrl = "http://p/movie/1.mp4",
                ),
            )
        assertTrue(encoded.contains("\"movie\""), "expected stable token in: $encoded")
    }

    @Test
    fun channelMapsToLiveContentItem() {
        val item =
            HandoffItem(
                id = "ch:7",
                sourceId = "src-9",
                kind = HandoffKind.CHANNEL,
                title = "Sky Sports",
                streamUrl = "http://p/live/7.ts",
                groupName = "Sports",
                tvgId = "sky.sports.uk",
                artworkUrl = "http://p/logo/7.png",
            )

        val content = item.toContentItem()

        assertEquals(ContentType.LIVE, content?.type)
        assertEquals("ch:7", content?.id)
        assertEquals("src-9", content?.sourceId)
        assertEquals("http://p/live/7.ts", content?.streamUrl)
        assertEquals("http://p/logo/7.png", content?.logoUrl)
        assertEquals("sky.sports.uk", content?.tvgId)
        assertEquals("Sky Sports", content?.title)
    }

    @Test
    fun movieMapsToMovieContentItem() {
        val content =
            HandoffItem(
                id = "m:1",
                kind = HandoffKind.MOVIE,
                title = "Dune",
                streamUrl = "http://p/movie/1.mp4",
            ).toContentItem()

        assertEquals(ContentType.MOVIE, content?.type)
        assertEquals("Dune", content?.title)
    }

    @Test
    fun episodeKindIsNotAContentItem() {
        // Episodes route through play(episode), never play(list).
        val item =
            HandoffItem(
                id = "ep:1",
                kind = HandoffKind.EPISODE,
                title = "Pilot",
                streamUrl = "http://p/series/1.mp4",
                seriesId = "ser:1",
            )
        assertNull(item.toContentItem())
    }

    @Test
    fun blankUrlNeverMapsToAPlayable() {
        val channel =
            HandoffItem(id = "x", kind = HandoffKind.CHANNEL, title = "x", streamUrl = "")
        val episode =
            HandoffItem(
                id = "x",
                kind = HandoffKind.EPISODE,
                title = "x",
                streamUrl = "",
                seriesId = "s",
            )
        assertNull(channel.toContentItem())
        assertNull(episode.toEpisodePlayable())
    }

    @Test
    fun episodeMapsToEpisodePlayableWithSeriesLink() {
        val ep =
            HandoffItem(
                id = "ep:9",
                sourceId = "src-2",
                kind = HandoffKind.EPISODE,
                title = "Show — S02E03",
                streamUrl = "http://p/series/9.mkv",
                seriesId = "ser:42",
                seasonNumber = 2,
                episodeNumber = 3,
            ).toEpisodePlayable()

        assertEquals("ep:9", ep?.id)
        assertEquals("ser:42", ep?.seriesId)
        assertEquals(2, ep?.seasonNumber)
        assertEquals(3, ep?.episodeNumber)
        assertEquals("src-2", ep?.sourceId)
    }

    @Test
    fun episodeWithoutSeriesIdIsRejected() {
        val item =
            HandoffItem(
                id = "ep:1",
                kind = HandoffKind.EPISODE,
                title = "Orphan",
                streamUrl = "http://p/series/1.mp4",
                seriesId = null,
            )
        assertNull(item.toEpisodePlayable())
    }

    @Test
    fun playableRoundTripsToHandoffItemAndBack() {
        val channel =
            Playable.Channel(
                id = "ch:1",
                title = "News 24",
                streamUrl = "http://p/live/1.ts",
                artworkUrl = "http://p/1.png",
                tvgId = "news.24",
                groupName = "News",
                sourceId = "src-1",
            )
        val movie =
            Playable.Movie(
                id = "m:2",
                title = "Arrival",
                streamUrl = "http://p/movie/2.mp4",
                sourceId = "src-1",
            )
        val episode =
            Playable.Episode(
                id = "ep:3",
                seriesId = "ser:3",
                title = "Show — S01E01",
                streamUrl = "http://p/series/3.mp4",
                seasonNumber = 1,
                episodeNumber = 1,
                sourceId = "src-1",
            )

        // Channel + movie survive through HandoffItem -> ContentItem.
        assertEquals(ContentType.LIVE, channel.toHandoffItem().toContentItem()?.type)
        assertEquals("Arrival", movie.toHandoffItem().toContentItem()?.title)

        // Episode survives through HandoffItem -> Playable.Episode, with headers
        // forwarded on the wire (the handoff's edge over Cast).
        val wired = episode.toHandoffItem(userAgent = "VLC/3.0.20", referer = "http://p/")
        assertEquals("VLC/3.0.20", wired.userAgent)
        assertEquals("http://p/", wired.referer)
        val back = wired.toEpisodePlayable()
        assertEquals(episode.id, back?.id)
        assertEquals(episode.seriesId, back?.seriesId)
        assertEquals(episode.seasonNumber, back?.seasonNumber)
    }
}
