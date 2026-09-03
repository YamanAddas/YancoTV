package com.yancotv.shared.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ContentType {
    @SerialName("live")
    LIVE,

    @SerialName("movie")
    MOVIE,

    @SerialName("series")
    SERIES,
}

@Serializable
enum class SortOption {
    @SerialName("provider")
    PROVIDER,

    @SerialName("name-asc")
    NAME_ASC,

    @SerialName("name-desc")
    NAME_DESC,

    @SerialName("recent")
    RECENT,

    @SerialName("group")
    GROUP,
}

@Serializable
enum class TmdbType {
    @SerialName("movie")
    MOVIE,

    @SerialName("tv")
    TV,
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
    // MK.13.2 — user-applied overrides. Both default to null; UI reads them
    // through `displayTitle` / `displayLogoUrl` so call sites never branch.
    val nameOverride: String? = null,
    val logoOverride: String? = null,
) {
    /**
     * MK.13.2 — what the UI should render for the channel name.
     * Falls back through: user override → cleaned M3U title → raw M3U title.
     * Blank overrides are ignored (same as null) so a user clearing the field
     * doesn't end up with an empty channel row.
     */
    val displayTitle: String
        get() = nameOverride?.takeIf { it.isNotBlank() } ?: cleanTitle?.takeIf { it.isNotBlank() } ?: title

    /**
     * MK.13.2 — what the UI should render for the channel logo.
     * Falls back through: user override → M3U logo. Blank overrides are
     * ignored (same as null) — user clears field → revert to M3U logo, not
     * "no logo at all".
     */
    val displayLogoUrl: String?
        get() = logoOverride?.takeIf { it.isNotBlank() } ?: logoUrl
}

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
data class SubtitleTrack(val language: String, val url: String)

@Serializable
data class EpisodeInfo(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val streamUrl: String,
    val duration: String? = null,
    /** Episode still from the provider — about half of episodes have one. */
    val stillUrl: String? = null,
    /** First-broadcast date as the provider phrases it, usually `YYYY-MM-DD`. */
    val airDate: String? = null,
    /** Runtime in seconds. Present on nearly every episode. */
    val durationSeconds: Int? = null,
)

/**
 * Current generation of the detail-extraction code. Bump this whenever a
 * new field is read out of a provider response, so cached blobs written by
 * the previous generation are refetched instead of standing forever.
 *
 * 1 — MK.iOS.UX4: per-episode still, air date and runtime in seconds.
 */
const val DETAIL_SCHEMA: Int = 1

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
    /**
     * Which generation of the extraction code wrote this blob.
     *
     * A cached detail is only ever refetched when something the UI needs is
     * *missing*, which is right until the app starts needing a field the
     * old code never read. Episode stills and air dates were parsed away
     * until MK.iOS.UX4, so every series already opened held a complete-
     * looking episode list with neither — and no rule that would ever ask
     * the provider again. Bumping [DETAIL_SCHEMA] re-enriches each title
     * once, the next time it is opened.
     */
    val detailSchema: Int = 0,
    val seriesId: Long? = null,
    val streamId: Long? = null,
    val stalkerId: String? = null,
    val duration: String? = null,
    val tvArchive: Int? = null,
    /**
     * The provider's own channel number (Xtream `num`).
     *
     * Was being discarded at sync: measured on a real account, **0 of
     * 53,207 live rows carried one** while the provider returned it for
     * every stream. The guide has been standing in with `content.sort_order`
     * — the playlist position — which is usually but not always the same
     * number.
     */
    val channelNumber: Int? = null,
    val tvArchiveDuration: Int? = null,
    val catchupType: String? = null,
    val catchupSource: String? = null,
    /**
     * Audit catch — `catchup-correction="-1.5"` on EXTINF lines. Offset
     * in HOURS applied to programme start when computing the catch-up
     * URL: providers whose recording archives are off-by-N from the
     * EPG (DST boundary, provider-side TZ misconfig, Eastern-European
     * playlists shipping a reseller offset) ship this so the player
     * plays the right slot. Honoured by TiviMate / IPTVnator / Kodi
     * PVR; YancoTV silently played the wrong slot before this field.
     */
    val catchupCorrection: Double? = null,
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
data class WatchPosition(val positionSeconds: Double, val durationSeconds: Double? = null)

/** Enriched content detail returned by content:getDetail */
@Serializable
data class ContentDetail(val item: ContentItem, val metadata: ContentMetadata, val episodes: List<Episode>, val watchPosition: WatchPosition? = null)

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
    /** Stage 2.2 — which favorite list this entry belongs to. Null for
     *  legacy rows that haven't been migrated yet (shouldn't happen on
     *  shipped builds since the v4 → v5 migration backfills 'default'). */
    val listId: String? = null,
)

/** MK.13.4 — named favorites bucket. `default` ships seeded and cannot
 *  be deleted (UI guards on [isDefault]); user-created lists carry
 *  user-supplied [name] and a [sortOrder] for display ordering. */
@Serializable
data class FavoriteList(val id: String, val name: String, val sortOrder: Int, val isDefault: Boolean, val createdAt: Long, val updatedAt: Long)
