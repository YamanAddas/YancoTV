package com.yancotv.shared.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ContentType {
    @SerialName("live") LIVE,
    @SerialName("movie") MOVIE,
    @SerialName("series") SERIES,
}

@Serializable
enum class SortOption {
    @SerialName("provider") PROVIDER,
    @SerialName("name-asc") NAME_ASC,
    @SerialName("name-desc") NAME_DESC,
    @SerialName("recent") RECENT,
    @SerialName("group") GROUP,
}

@Serializable
enum class TmdbType {
    @SerialName("movie") MOVIE,
    @SerialName("tv") TV,
}

@Serializable
data class ContentItem(
    val id: String,
    val sourceId: String,
    val type: ContentType,
    val title: String,
    val cleanTitle: String? = null,
    val groupName: String? = null,
    val streamUrl: String,
    val logoUrl: String? = null,
    val tvgId: String? = null,
    val metadataJson: String? = null,
    val sortOrder: Int,
    val createdAt: Long,
)

@Serializable
data class Episode(
    val id: String,
    val contentId: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val title: String? = null,
    val streamUrl: String,
    val duration: Double? = null,
)

@Serializable
data class SubtitleTrack(
    val language: String,
    val url: String,
)

@Serializable
data class EpisodeInfo(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val streamUrl: String,
    val duration: String? = null,
)

/** Parsed metadata from the metadata_json column */
@Serializable
data class ContentMetadata(
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val rating: String? = null,
    val description: String? = null,
    val tagline: String? = null,
    val youtubeTrailer: String? = null,
    val backdropUrl: String? = null,
    val subtitles: List<SubtitleTrack>? = null,
    val seriesId: Long? = null,
    val streamId: Long? = null,
    val stalkerId: String? = null,
    val duration: String? = null,
    val tvArchive: Int? = null,
    val tvArchiveDuration: Int? = null,
    val catchupType: String? = null,
    val catchupSource: String? = null,
    val tmdbId: Long? = null,
    val tmdbType: TmdbType? = null,
    val tmdbPosterUrl: String? = null,
    val tmdbBackdropUrl: String? = null,
    val tmdbTagline: String? = null,
    val tmdbEnrichedAt: Long? = null,
    val detailFetchedAt: Long? = null,
    val episodes: List<EpisodeInfo>? = null,
)

@Serializable
data class WatchPosition(
    val positionSeconds: Double,
    val durationSeconds: Double? = null,
)

/** Enriched content detail returned by content:getDetail */
@Serializable
data class ContentDetail(
    val item: ContentItem,
    val metadata: ContentMetadata,
    val episodes: List<Episode>,
    val watchPosition: WatchPosition? = null,
)

/** Watch history entry with joined content data */
@Serializable
data class HistoryEntry(
    val id: String,
    val contentId: String,
    val episodeId: String? = null,
    val positionSeconds: Double,
    val durationSeconds: Double? = null,
    val watchedAt: Long,
    val content: ContentItem,
)

/** Favorite entry with joined content data */
@Serializable
data class FavoriteEntry(
    val favoriteId: String,
    val addedAt: Long,
    val content: ContentItem,
)
