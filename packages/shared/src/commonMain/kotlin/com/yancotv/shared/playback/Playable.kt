package com.yancotv.shared.playback

import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.EpisodeInfo

/**
 * A thing the player can actually play.
 *
 * Compile-time barrier against passing a series container (which has no
 * stream_url) into the playback pipeline: the sealed hierarchy forces
 * every caller to disambiguate between a live channel, a VOD movie and a
 * VOD episode, and [ContentItem.toPlayable] returns null for series.
 *
 * Every [Playable] exposes:
 *  - a stable [id] that the resume-point / history store keys by
 *  - a non-blank [streamUrl]
 *  - a human [title] (pre-joined for series episodes)
 *  - an optional [artworkUrl] for thumbnails / lock-screen / Cast
 *  - an [isLive] flag so the player can skip resume-point logic
 *
 * Series are NOT Playable. The only path that puts a [Playable.Episode]
 * into the pipeline is the detail overlay's episode picker.
 */
sealed class Playable {
    abstract val id: String
    abstract val streamUrl: String
    abstract val title: String
    abstract val artworkUrl: String?
    abstract val isLive: Boolean

    /** Live TV channel. Resume-point persistence is a no-op for these. */
    data class Channel(
        override val id: String,
        override val title: String,
        override val streamUrl: String,
        override val artworkUrl: String? = null,
        val tvgId: String? = null,
        val groupName: String? = null,
        val sourceId: String = "",
    ) : Playable() {
        override val isLive: Boolean get() = true
    }

    /** VOD movie. Resume-point persistence uses [id] as the content_id. */
    data class Movie(
        override val id: String,
        override val title: String,
        override val streamUrl: String,
        override val artworkUrl: String? = null,
        val groupName: String? = null,
        val sourceId: String = "",
    ) : Playable() {
        override val isLive: Boolean get() = false
    }

    /**
     * VOD series episode. [id] is the episode's own stable id — never a
     * synthetic "series:ep" concatenation. [seriesId] links back to the
     * series container for future "continue next episode" lookups.
     */
    data class Episode(
        override val id: String,
        val seriesId: String,
        override val title: String,
        override val streamUrl: String,
        override val artworkUrl: String? = null,
        val seasonNumber: Int = 0,
        val episodeNumber: Int = 0,
        val sourceId: String = "",
    ) : Playable() {
        override val isLive: Boolean get() = false
    }
}

/**
 * Convert a [ContentItem] to a [Playable]. Returns null for:
 *  - series containers (they have no playable stream_url on their own)
 *  - blank / missing stream URLs (silently drops malformed rows)
 *
 * Callers that receive null must short-circuit: the player refuses
 * blank-URL loads.
 */
fun ContentItem.toPlayable(): Playable? {
    if (streamUrl.isBlank()) return null
    return when (type) {
        ContentType.LIVE ->
            Playable.Channel(
                id = id,
                title = cleanTitle?.ifBlank { null } ?: title,
                streamUrl = streamUrl,
                artworkUrl = logoUrl?.takeIf { it.isNotBlank() },
                tvgId = tvgId,
                groupName = groupName,
                sourceId = sourceId,
            )
        ContentType.MOVIE ->
            Playable.Movie(
                id = id,
                title = cleanTitle?.ifBlank { null } ?: title,
                streamUrl = streamUrl,
                artworkUrl = logoUrl?.takeIf { it.isNotBlank() },
                groupName = groupName,
                sourceId = sourceId,
            )
        // Series containers are not directly playable; callers must pick
        // an episode via ContentDetailService first. The detail overlay's
        // "Play" button falls back to the first episode when episodes
        // are available.
        ContentType.SERIES -> null
    }
}

/**
 * Convert an [EpisodeInfo] + its parent series [ContentItem] into a
 * [Playable.Episode]. Returns null if the episode's stream URL is blank
 * (occasional Xtream rows arrive without one).
 */
fun EpisodeInfo.toPlayable(series: ContentItem): Playable.Episode? {
    if (streamUrl.isBlank()) return null
    return Playable.Episode(
        id = id,
        seriesId = series.id,
        title = "${series.cleanTitle?.ifBlank { null } ?: series.title} — ${
            title.ifBlank { "S${seasonNumber}E$episodeNumber" }
        }",
        streamUrl = streamUrl,
        artworkUrl = series.logoUrl?.takeIf { it.isNotBlank() },
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        sourceId = series.sourceId,
    )
}
