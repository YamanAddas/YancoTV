package com.yancotv.shared.content

import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentMetadata
import com.yancotv.shared.types.DETAIL_SCHEMA
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.EpisodeInfo
import com.yancotv.shared.types.Result
import com.yancotv.shared.types.SubtitleTrack
import com.yancotv.shared.types.TmdbType
import com.yancotv.shared.xtream.XtreamClient
import com.yancotv.shared.xtream.XtreamClientOptions
import com.yancotv.shared.xtream.XtreamStreamType
import kotlinx.serialization.json.Json

/**
 * Lazy detail loader for the Movie + Series detail screens. Reads whatever
 * metadata was captured at catalog-sync time, and when something critical
 * is missing (no plot for a movie, no episodes for a series) fetches it
 * from the provider and persists the enriched metadata back to the row.
 *
 * The shell stays on the sync-time metadata until the user actually opens
 * a detail page — synching 20k+ movies with per-item `get_vod_info` calls
 * is a non-starter on TV-tier hardware.
 *
 * Returns a [Result] so callers can distinguish "no creds / provider
 * unreachable" from "we rendered cached data but couldn't freshen it"
 * paths.
 */
class ContentDetailService(
    private val db: YancoDb,
    private val sources: SourceRepository,
    private val http: HttpClient,
    private val logger: Logger,
    private val clock: () -> Long,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * What the detail screen renders. [item] carries the possibly-refreshed
     * [ContentItem.logoUrl] (swapped with a movie's cover_big when the
     * sync-time value was empty). [metadata] is fully populated with
     * whatever we could load. [episodes] is only non-empty for series.
     */
    /**
     * [refreshInBackground] is set when what is being returned is usable but
     * out of date — the caller should render it and re-run [load] with
     * [force] on its own scope so the next open is better. Nothing here does
     * that itself: this class has no scope, and inventing one would make a
     * detail read outlive the screen that asked for it.
     */
    data class Loaded(
        val item: ContentItem,
        val metadata: ContentMetadata,
        val episodes: List<EpisodeInfo>,
        val refreshInBackground: Boolean = false,
    )

    /**
     * Whether a cached blob is worth going back to the provider for.
     *
     * Two reasons. Something the page needs is **missing** — a movie with
     * no plot, a series with no episodes — which is the original rule. Or
     * the blob predates the current extraction generation: the fields this
     * build renders were parsed away when it was written, so they look
     * absent for the same reason a provider that has none does, and no
     * amount of null-checking can tell those apart. See [DETAIL_SCHEMA].
     *
     * Either way there has to be an id to fetch against.
     */
    internal fun needsFetch(type: ContentType, cached: ContentMetadata): Boolean {
        val fetchable = when (type) {
            ContentType.MOVIE -> cached.streamId != null
            ContentType.SERIES -> cached.seriesId != null
            else -> false
        }
        return fetchable && (isMissingEssentials(type, cached) || isStale(type, cached))
    }

    /** Nothing worth rendering is cached — this fetch has to be waited for. */
    private fun isMissingEssentials(type: ContentType, cached: ContentMetadata): Boolean =
        when (type) {
            ContentType.MOVIE -> cached.plot.isNullOrBlank()
            ContentType.SERIES -> cached.episodes.isNullOrEmpty()
            else -> false
        }

    /**
     * Whether an old blob is missing something *this* type now renders.
     *
     * Scoped by type on purpose. Generation 1 added per-episode fields, so
     * a movie blob written by generation 0 is complete — treating it as
     * stale would put a provider round-trip in front of every movie page
     * the viewer has ever opened, once, and get nothing back for it.
     *
     * **When bumping [DETAIL_SCHEMA], add the types the new generation
     * actually affects here.** A generation that reads a new *movie* field
     * has to list MOVIE, or those blobs will stand forever.
     */
    private fun isStale(type: ContentType, cached: ContentMetadata): Boolean {
        if (cached.detailSchema >= DETAIL_SCHEMA) return false
        return when (type) {
            ContentType.SERIES -> true // 1 — still, air date, runtime
            else -> false
        }
    }

    suspend fun load(item: ContentItem, force: Boolean = false): Loaded {
        val cached = item.metadataJson?.let { parseMetadata(it) } ?: ContentMetadata()
        if (!force && !needsFetch(item.type, cached)) {
            return Loaded(item, cached, cached.episodes.orEmpty())
        }
        // **A stale cache is shown, not waited for.**
        //
        // "Stale" here means the blob is complete by the old rules and only
        // predates the current extraction generation — there is something
        // good to render right now. Awaiting the provider for it turns a
        // screen that used to open instantly into one that waits, and when
        // the provider is unreachable `request` retries three times with
        // 1s/3s/8s backoff, so an offline viewer would sit for twelve
        // seconds of sleep plus four timeouts in front of an episode list
        // already on the device — and again on every open, because a failed
        // fetch correctly stamps nothing.
        //
        // So the caller gets the cache immediately and is told to enrich in
        // the background; the *next* open has the richer copy. A cache with
        // nothing to show still blocks, because there is nothing else to do.
        if (!force && isStale(item.type, cached) && !isMissingEssentials(item.type, cached)) {
            return Loaded(item, cached, cached.episodes.orEmpty(), refreshInBackground = true)
        }

        val creds =
            sources.xtreamCredentials(item.sourceId)
                ?: return Loaded(item, cached, cached.episodes.orEmpty())
        val client =
            XtreamClient(
                creds.baseUrl,
                creds.username,
                creds.password,
                XtreamClientOptions(http, logger),
            )

        val enriched =
            when (item.type) {
                ContentType.MOVIE -> loadMovieDetail(item, cached, client)
                ContentType.SERIES -> loadSeriesDetail(item, cached, client)
                else -> return Loaded(item, cached, emptyList())
            }

        // Persist the enrichment so the next open is instant — and so
        // Continue-watching / Favorites surfaces that read metadataJson
        // later get the richer copy too.
        val newLogoUrl =
            when {
                !item.logoUrl.isNullOrBlank() -> item.logoUrl
                !enriched.tmdbPosterUrl.isNullOrBlank() -> enriched.tmdbPosterUrl
                !enriched.backdropUrl.isNullOrBlank() -> enriched.backdropUrl
                else -> null
            }
        runCatching {
            db.contentQueries.updateMetadataAndLogo(
                metadataJson = encodeMetadata(enriched),
                logoUrl = newLogoUrl,
                id = item.id,
            )
        }.onFailure { logger.warn("ContentDetailService.updateMetadataAndLogo: ${it.message}") }

        // Persist series episodes into the `episodes` table so the
        // `watch_history.episode_id` FK is satisfied when the user
        // resumes mid-episode. Without this, PlaybackController's
        // episode resume-point write would FK-violate against
        // `episodes(id)`. Idempotent via INSERT OR REPLACE.
        if (item.type == ContentType.SERIES) {
            persistEpisodes(item.id, enriched.episodes.orEmpty())
        }

        val refreshedItem =
            item.copy(
                logoUrl = newLogoUrl,
                metadataJson = encodeMetadata(enriched),
            )
        return Loaded(refreshedItem, enriched, enriched.episodes.orEmpty())
    }

    /**
     * Idempotent batch upsert of a series's episodes into the `episodes`
     * table. Each row is wrapped individually so a malformed entry doesn't
     * block the rest. Failures are logged, never thrown — episode
     * persistence is best-effort and a missing row only costs the user a
     * resume point on that one episode.
     */
    private fun persistEpisodes(seriesId: String, episodes: List<EpisodeInfo>) {
        if (episodes.isEmpty()) return
        episodes.forEach { ep ->
            runCatching {
                db.episodesQueries.upsert(
                    id = ep.id,
                    content_id = seriesId,
                    season_number = ep.seasonNumber.toLong(),
                    episode_number = ep.episodeNumber.toLong(),
                    title = ep.title,
                    stream_url = ep.streamUrl,
                    // The provider sends `duration_secs` on 99% of episodes
                    // and this column had been hard-wired to null since it
                    // was added, so nothing outside the metadata blob ever
                    // knew how long an episode was.
                    duration = ep.durationSeconds?.toLong(),
                    still_url = ep.stillUrl,
                    air_date = ep.airDate,
                )
            }.onFailure {
                logger.warn("ContentDetailService.persistEpisodes(${ep.id}): ${it.message}")
            }
        }
    }

    private suspend fun loadMovieDetail(item: ContentItem, cached: ContentMetadata, client: XtreamClient): ContentMetadata {
        val streamId = cached.streamId?.toInt() ?: return cached
        return when (val res = client.getVodInfo(streamId)) {
            is Result.Ok -> {
                val v = res.value
                cached.copy(
                    plot = v.plot.ifBlank { cached.plot },
                    cast = v.cast.ifBlank { cached.cast },
                    director = v.director.ifBlank { cached.director },
                    genre = v.genre.ifBlank { cached.genre },
                    releaseDate = v.releaseDate.ifBlank { cached.releaseDate },
                    rating = v.rating.ifBlank { cached.rating },
                    duration = v.duration.ifBlank { cached.duration },
                    backdropUrl = v.backdropUrl.ifBlank { cached.backdropUrl },
                    tagline = v.tagline.ifBlank { cached.tagline },
                    youtubeTrailer = v.youtubeTrailer.ifBlank { cached.youtubeTrailer },
                    subtitles =
                    v.subtitles
                        .map { SubtitleTrack(language = it.language, url = it.url) }
                        .takeIf { it.isNotEmpty() } ?: cached.subtitles,
                    tmdbId = v.tmdbId?.toLong() ?: cached.tmdbId,
                    tmdbType = cached.tmdbType ?: v.tmdbId?.let { TmdbType.MOVIE },
                    detailFetchedAt = nowMs(),
                    // Same rule as the series path: an `Ok` that carried
                    // nothing must not mark the cache as current. Harmless
                    // today because generation 1 does not list MOVIE, and a
                    // trap for generation 2 if it were left unconditional.
                    detailSchema =
                    if (v.plot.isNotBlank() || v.cast.isNotBlank() || v.genre.isNotBlank()) {
                        DETAIL_SCHEMA
                    } else {
                        cached.detailSchema
                    },
                )
            }
            is Result.Err -> {
                logger.warn("ContentDetailService.loadMovieDetail: ${res.error.message}")
                cached
            }
        }
    }

    private suspend fun loadSeriesDetail(item: ContentItem, cached: ContentMetadata, client: XtreamClient): ContentMetadata {
        val seriesId = cached.seriesId?.toInt() ?: return cached
        return when (val res = client.getSeriesInfo(seriesId)) {
            is Result.Ok -> {
                val d = res.value
                // Flatten season → episode map into the ordered list the
                // detail UI uses directly. Season ordering is numeric, so
                // "10" comes after "2" — parse as int before sorting.
                val flat = mutableListOf<EpisodeInfo>()
                d.episodes.entries
                    .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
                    .forEach { (seasonKey, eps) ->
                        val seasonNum = seasonKey.toIntOrNull() ?: 0
                        eps.sortedBy { it.episodeNum }.forEach { e ->
                            flat.add(
                                EpisodeInfo(
                                    id = e.id.ifBlank { "${item.id}-s$seasonNum-e${e.episodeNum}" },
                                    seasonNumber = seasonNum,
                                    episodeNumber = e.episodeNum,
                                    title = e.title.ifBlank { "Episode ${e.episodeNum}" },
                                    streamUrl =
                                    client.buildStreamUrl(
                                        e.id.toIntOrNull() ?: 0,
                                        XtreamStreamType.SERIES,
                                        e.containerExtension,
                                    ),
                                    duration = e.info.duration?.takeIf { it.isNotBlank() },
                                    stillUrl = e.info.stillUrl?.takeIf { it.isNotBlank() },
                                    airDate = e.info.airDate?.takeIf { it.isNotBlank() },
                                    durationSeconds = e.info.durationSecs?.takeIf { it > 0 },
                                ),
                            )
                        }
                    }
                cached.copy(
                    plot = d.info.plot.ifBlank { cached.plot },
                    cast = d.info.cast.ifBlank { cached.cast },
                    director = d.info.director.ifBlank { cached.director },
                    genre = d.info.genre.ifBlank { cached.genre },
                    releaseDate = d.info.releaseDate.ifBlank { cached.releaseDate },
                    rating = d.info.rating.ifBlank { cached.rating },
                    episodes = flat.takeIf { it.isNotEmpty() } ?: cached.episodes,
                    detailFetchedAt = nowMs(),
                    // Only when the response actually carried episodes.
                    //
                    // `Result.Ok` with an empty map is routine — a provider
                    // that sends `"episodes": []` (an array, not an object)
                    // parses to nothing — and the line above then keeps the
                    // *old* generation's episode list. Stamping the current
                    // generation over that would mark data that has none of
                    // the new fields as current, and it would never be
                    // asked for again.
                    detailSchema = if (flat.isNotEmpty()) DETAIL_SCHEMA else cached.detailSchema,
                )
            }
            is Result.Err -> {
                logger.warn("ContentDetailService.loadSeriesDetail: ${res.error.message}")
                cached
            }
        }
    }

    private fun parseMetadata(raw: String): ContentMetadata? = runCatching {
        json.decodeFromString(ContentMetadata.serializer(), raw)
    }.getOrNull()

    private fun encodeMetadata(meta: ContentMetadata): String = json.encodeToString(ContentMetadata.serializer(), meta)

    private fun nowMs(): Long = clock()
}
