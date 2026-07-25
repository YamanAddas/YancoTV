package com.yancotv.shared.handoff

import com.yancotv.shared.playback.Playable
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MK.26 Track A — the LAN companion-handoff wire contract.
 *
 * A phone (sender) serializes a [HandoffPlayCommand] to JSON and POSTs it to
 * the YancoTV receiver service running on a TV on the same LAN. The receiver
 * rebuilds a playable from the envelope and routes it to the single shared
 * `PlaybackController` — the TV's own ExoPlayer fetches the original stream,
 * so raw TS / AC-3 / HEVC / provider headers / cleartext all play natively
 * with no transcoding and no added lag.
 *
 * The envelope is deliberately lean (NOT a serialized DB row): only the fields
 * the receiver needs to play, resume, and authenticate. This keeps the wire
 * format decoupled from the SQLDelight schema and minimizes what credentialed
 * data crosses the LAN.
 *
 * UNIT NOTE: [resumePositionSeconds] is SECONDS (matching watch_history media
 * offsets); the receiver multiplies by 1000 for ExoPlayer's millisecond seek.
 */
@Serializable
data class HandoffPlayCommand(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Shared secret established at pairing; the receiver rejects mismatches (MK.26.A.4). */
    val pairingToken: String,
    val item: HandoffItem,
    /** SECONDS. 0 = start from the beginning / live edge. */
    val resumePositionSeconds: Long = 0L,
    /** When true, ignore [resumePositionSeconds] and start at 0 ("Watch from start"). */
    val fromStart: Boolean = false,
) {
    companion object {
        /** Bumped when the envelope shape changes incompatibly. */
        const val SCHEMA_VERSION: Int = 1
    }
}

/** Which `PlaybackController` entry point the receiver routes the item to. */
@Serializable
enum class HandoffKind {
    @SerialName("channel")
    CHANNEL,

    @SerialName("movie")
    MOVIE,

    @SerialName("episode")
    EPISODE,
}

/**
 * The minimal description of one playable thing. Mirrors the fields [Playable]
 * exposes, plus the provider auth headers ([userAgent] / [referer]) a gated
 * stream needs — these do NOT travel with a Cast load, which is the whole
 * reason the handoff can play streams a Chromecast can't.
 *
 * Header values are carried on the wire but only wired into playback in
 * MK.26.A.4; A.1 routes the stream URL through the receiver's existing
 * per-source resolution.
 */
@Serializable
data class HandoffItem(
    val id: String,
    val sourceId: String = "",
    val kind: HandoffKind,
    val title: String,
    val streamUrl: String,
    val artworkUrl: String? = null,
    val groupName: String? = null,
    val tvgId: String? = null,
    // EPISODE-only
    val seriesId: String? = null,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    // Provider auth headers (applied receiver-side in MK.26.A.4)
    val userAgent: String? = null,
    val referer: String? = null,
)

/**
 * Rebuild a [ContentItem] for the CHANNEL / MOVIE handoff path
 * (`PlaybackController.play(listOf(item), 0)`). Returns null for a blank URL
 * or for the EPISODE kind (episodes go through [toEpisodePlayable] instead),
 * mirroring [ContentItem.toPlayable]'s blank-URL short-circuit.
 */
fun HandoffItem.toContentItem(): ContentItem? {
    if (streamUrl.isBlank()) return null
    val contentType =
        when (kind) {
            HandoffKind.CHANNEL -> ContentType.LIVE
            HandoffKind.MOVIE -> ContentType.MOVIE
            HandoffKind.EPISODE -> return null
        }
    return ContentItem(
        id = id,
        sourceId = sourceId,
        type = contentType,
        title = title,
        cleanTitle = title,
        groupName = groupName,
        streamUrl = streamUrl,
        logoUrl = artworkUrl,
        tvgId = tvgId,
        metadataJson = null,
        sortOrder = 0,
        createdAt = 0L,
    )
}

/**
 * Rebuild a [Playable.Episode] for the EPISODE handoff path
 * (`PlaybackController.play(episode)`). Returns null for a blank URL, a missing
 * [HandoffItem.seriesId], or any non-EPISODE kind.
 */
fun HandoffItem.toEpisodePlayable(): Playable.Episode? {
    if (kind != HandoffKind.EPISODE) return null
    if (streamUrl.isBlank()) return null
    val series = seriesId ?: return null
    return Playable.Episode(
        id = id,
        seriesId = series,
        title = title,
        streamUrl = streamUrl,
        artworkUrl = artworkUrl,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        sourceId = sourceId,
    )
}

/**
 * Sender-side builder: describe a [Playable] (what's playing on the phone) as a
 * [HandoffItem] for the wire. Reused by the MK.26.A.3 "Play on my TV" picker.
 * [userAgent] / [referer] are the per-source values the sender already resolves
 * locally, forwarded so the receiver can satisfy gated providers (MK.26.A.4).
 */
fun Playable.toHandoffItem(userAgent: String? = null, referer: String? = null): HandoffItem = when (this) {
    is Playable.Channel ->
        HandoffItem(
            id = id,
            sourceId = sourceId,
            kind = HandoffKind.CHANNEL,
            title = title,
            streamUrl = streamUrl,
            artworkUrl = artworkUrl,
            groupName = groupName,
            tvgId = tvgId,
            userAgent = userAgent,
            referer = referer,
        )

    is Playable.Movie ->
        HandoffItem(
            id = id,
            sourceId = sourceId,
            kind = HandoffKind.MOVIE,
            title = title,
            streamUrl = streamUrl,
            artworkUrl = artworkUrl,
            groupName = groupName,
            userAgent = userAgent,
            referer = referer,
        )

    is Playable.Episode ->
        HandoffItem(
            id = id,
            sourceId = sourceId,
            kind = HandoffKind.EPISODE,
            title = title,
            streamUrl = streamUrl,
            artworkUrl = artworkUrl,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            userAgent = userAgent,
            referer = referer,
        )
}
