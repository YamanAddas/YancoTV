package com.yancotv.shared.handoff

import com.yancotv.shared.types.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HandoffResolverTest {
    private fun channelCmd(
        token: String = "tok",
        url: String = "http://p/live/1.ts",
        position: Long = 0L,
        fromStart: Boolean = false,
        schema: Int = HandoffPlayCommand.SCHEMA_VERSION,
    ) = HandoffPlayCommand(
        schemaVersion = schema,
        pairingToken = token,
        item =
            HandoffItem(
                id = "ch:1",
                kind = HandoffKind.CHANNEL,
                title = "News",
                streamUrl = url,
            ),
        resumePositionSeconds = position,
        fromStart = fromStart,
    )

    @Test
    fun tokenMismatchIsUnauthorized() {
        val outcome = resolveHandoffCommand(channelCmd(token = "wrong"), expectedToken = "right")
        assertEquals(HandoffOutcome.Rejected(HandoffReject.UNAUTHORIZED), outcome)
    }

    @Test
    fun nullExpectedTokenSkipsTheCheck() {
        val outcome = resolveHandoffCommand(channelCmd(token = "anything"), expectedToken = null)
        assertIs<HandoffOutcome.PlayContent>(outcome)
    }

    @Test
    fun matchingTokenResolves() {
        val outcome = resolveHandoffCommand(channelCmd(token = "secret"), expectedToken = "secret")
        assertIs<HandoffOutcome.PlayContent>(outcome)
    }

    @Test
    fun authorizationIsCheckedBeforeItemValidity() {
        // A bad token must win even when the item is also invalid — never leak
        // item-shape feedback to an unauthorized caller.
        val cmd = channelCmd(token = "wrong", url = "")
        assertEquals(
            HandoffOutcome.Rejected(HandoffReject.UNAUTHORIZED),
            resolveHandoffCommand(cmd, expectedToken = "right"),
        )
    }

    @Test
    fun newerSchemaIsRejected() {
        val cmd = channelCmd(schema = HandoffPlayCommand.SCHEMA_VERSION + 1)
        assertEquals(
            HandoffOutcome.Rejected(HandoffReject.UNSUPPORTED_SCHEMA),
            resolveHandoffCommand(cmd, expectedToken = null),
        )
    }

    @Test
    fun channelResolvesToLiveContent() {
        val outcome = resolveHandoffCommand(channelCmd(), expectedToken = null)
        val play = assertIs<HandoffOutcome.PlayContent>(outcome)
        assertEquals(ContentType.LIVE, play.item.type)
    }

    @Test
    fun movieResolvesToMovieContent() {
        val cmd =
            HandoffPlayCommand(
                pairingToken = "tok",
                item =
                    HandoffItem(
                        id = "m:1",
                        kind = HandoffKind.MOVIE,
                        title = "Dune",
                        streamUrl = "http://p/movie/1.mp4",
                    ),
            )
        val play = assertIs<HandoffOutcome.PlayContent>(resolveHandoffCommand(cmd, null))
        assertEquals(ContentType.MOVIE, play.item.type)
    }

    @Test
    fun episodeResolvesToPlayEpisode() {
        val cmd =
            HandoffPlayCommand(
                pairingToken = "tok",
                item =
                    HandoffItem(
                        id = "ep:1",
                        kind = HandoffKind.EPISODE,
                        title = "Pilot",
                        streamUrl = "http://p/series/1.mp4",
                        seriesId = "ser:1",
                        seasonNumber = 1,
                        episodeNumber = 1,
                    ),
            )
        val play = assertIs<HandoffOutcome.PlayEpisode>(resolveHandoffCommand(cmd, null))
        assertEquals("ser:1", play.episode.seriesId)
    }

    @Test
    fun blankUrlIsInvalidItem() {
        val outcome = resolveHandoffCommand(channelCmd(url = ""), expectedToken = null)
        assertEquals(HandoffOutcome.Rejected(HandoffReject.INVALID_ITEM), outcome)
    }

    @Test
    fun episodeWithoutSeriesIdIsInvalidItem() {
        val cmd =
            HandoffPlayCommand(
                pairingToken = "tok",
                item =
                    HandoffItem(
                        id = "ep:1",
                        kind = HandoffKind.EPISODE,
                        title = "Orphan",
                        streamUrl = "http://p/series/1.mp4",
                        seriesId = null,
                    ),
            )
        assertEquals(
            HandoffOutcome.Rejected(HandoffReject.INVALID_ITEM),
            resolveHandoffCommand(cmd, null),
        )
    }

    @Test
    fun negativePositionIsCoercedToZero() {
        val outcome = resolveHandoffCommand(channelCmd(position = -500L), expectedToken = null)
        val play = assertIs<HandoffOutcome.PlayContent>(outcome)
        assertEquals(0L, play.resumePositionSeconds)
    }

    @Test
    fun positionAndFromStartArePassedThrough() {
        val outcome =
            resolveHandoffCommand(
                channelCmd(position = 742L, fromStart = true),
                expectedToken = null,
            )
        val play = assertIs<HandoffOutcome.PlayContent>(outcome)
        assertEquals(742L, play.resumePositionSeconds)
        assertEquals(true, play.fromStart)
    }

    @Test
    fun forwardedHeadersFlowIntoTheOutcome() {
        val cmd =
            HandoffPlayCommand(
                pairingToken = "tok",
                item =
                    HandoffItem(
                        id = "ch:1",
                        kind = HandoffKind.CHANNEL,
                        title = "News",
                        streamUrl = "http://p/1.ts",
                        userAgent = "VLC/3.0.20",
                        referer = "http://p/",
                    ),
            )
        val play = assertIs<HandoffOutcome.PlayContent>(resolveHandoffCommand(cmd, null))
        assertEquals("VLC/3.0.20", play.userAgent)
        assertEquals("http://p/", play.referer)
    }

    @Test
    fun blankHeadersBecomeNull() {
        val cmd =
            HandoffPlayCommand(
                pairingToken = "tok",
                item =
                    HandoffItem(
                        id = "ch:1",
                        kind = HandoffKind.CHANNEL,
                        title = "News",
                        streamUrl = "http://p/1.ts",
                        userAgent = "",
                        referer = "   ",
                    ),
            )
        val play = assertIs<HandoffOutcome.PlayContent>(resolveHandoffCommand(cmd, null))
        assertEquals(null, play.userAgent)
        assertEquals(null, play.referer)
    }
}
