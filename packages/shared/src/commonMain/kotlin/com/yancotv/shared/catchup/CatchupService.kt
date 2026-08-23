package com.yancotv.shared.catchup

import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentMetadata
import com.yancotv.shared.types.EpgProgramme
import com.yancotv.shared.types.SourceType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Resolves a past EPG programme into a playable stream URL.
 *
 * Flow:
 *  1. Look up the backing [ContentItem] for the programme's `channelTvgId`.
 *  2. Read catchup hints from `metadataJson` (populated at sync time for
 *     providers that advertise catch-up support).
 *  3. Dispatch on source type: Xtream sources use the timeshift path,
 *     M3U/Stalker sources go through the template-substitution builder.
 *  4. Return a [ContentItem] copy whose `streamUrl` is rewritten — the
 *     player happily consumes it like any live stream.
 *
 * Returns null when catchup isn't supported for the channel / programme
 * (future programme, archive window expired, unsupported source type,
 * missing credentials, no channel match for the tvg_id).
 *
 * Mirrors desktop `catchup-service.ts` behavior so the catch-up UX is
 * consistent across platforms — same provider compatibility rules, same
 * URL shapes.
 */
class CatchupService(private val contentRepo: ContentRepository, private val sourceRepo: SourceRepository, private val clock: () -> Long) {
    /** Lightweight reason codes so the caller can show a specific error toast. */
    enum class UnavailableReason {
        FUTURE_PROGRAMME,
        NO_MATCHING_CHANNEL,
        OUTSIDE_ARCHIVE_WINDOW,
        UNSUPPORTED_SOURCE,
        MISSING_CREDENTIALS,
        MISSING_METADATA,
    }

    sealed class Resolution {
        data class Playable(val item: ContentItem) : Resolution()

        data class Unavailable(val reason: UnavailableReason) : Resolution()
    }

    /**
     * Build a playable item for [programme]. [channelTvgId] is passed in
     * rather than pulled off [programme] so callers that already looked up
     * the programme can reuse the id string without a second field access.
     */
    fun resolve(
        programme: EpgProgramme,
        channelTvgId: String = programme.channelTvgId,
        channelStreamUrl: String? = null,
    ): Resolution {
        val nowSec = clock() / 1000L
        if (programme.startTime > nowSec) {
            return Resolution.Unavailable(UnavailableReason.FUTURE_PROGRAMME)
        }
        // MB-380 — prefer the EXACT channel the user opened (its stream_url from
        // the guide row) over a tvg_id re-lookup. findLiveByTvgId returns an
        // arbitrary highest-priority row, and when many channels share a junk
        // tvg_id — or simply when its priority order differs from the guide's
        // MIN(sort_order) winner — that resolves a DIFFERENT channel than the
        // one on screen, so catch-up replayed the wrong stream. Fall back to the
        // tvg_id pick only when the url isn't matched (or wasn't supplied).
        val channel =
            channelStreamUrl
                ?.let { url -> contentRepo.findIdByStreamUrl(url)?.let { id -> contentRepo.findById(id) } }
                ?: contentRepo.findLiveByTvgId(channelTvgId)
                ?: return Resolution.Unavailable(UnavailableReason.NO_MATCHING_CHANNEL)
        val metadata = channel.metadataJson?.let { parseMetadata(it) }

        // Archive window check: Xtream's tvArchiveDuration is in days. If the
        // provider advertises one and the programme start falls outside it,
        // bail before we build an URL the provider will 404 on.
        val archiveDays = metadata?.tvArchiveDuration
        if (archiveDays != null && archiveDays > 0) {
            val cutoff = nowSec - archiveDays * 86_400L
            if (programme.startTime < cutoff) {
                return Resolution.Unavailable(UnavailableReason.OUTSIDE_ARCHIVE_WINDOW)
            }
        }

        val source =
            sourceRepo.getById(channel.sourceId)
                ?: return Resolution.Unavailable(UnavailableReason.UNSUPPORTED_SOURCE)

        return when (source.type) {
            SourceType.XTREAM -> resolveXtream(channel, programme)
            SourceType.M3U_URL,
            SourceType.M3U_FILE,
            -> resolveM3u(channel, programme, metadata, nowSec)
            SourceType.STALKER -> Resolution.Unavailable(UnavailableReason.UNSUPPORTED_SOURCE)
        }
    }

    private fun resolveXtream(channel: ContentItem, programme: EpgProgramme): Resolution {
        val creds =
            sourceRepo.xtreamCredentials(channel.sourceId)
                ?: return Resolution.Unavailable(UnavailableReason.MISSING_CREDENTIALS)
        val duration = (programme.endTime - programme.startTime).coerceAtLeast(0L)
        val url =
            buildXtreamTimeshiftUrl(
                baseUrl = creds.baseUrl,
                username = creds.username,
                password = creds.password,
                originalStreamUrl = channel.streamUrl,
                programmeStart = programme.startTime,
                programmeDuration = duration,
            )
        return Resolution.Playable(channel.withCatchupUrl(url, programme))
    }

    private fun resolveM3u(channel: ContentItem, programme: EpgProgramme, metadata: ContentMetadata?, nowSec: Long): Resolution {
        val mdMap = HashMap<String, Any?>(4)
        metadata?.catchupSource?.let { mdMap["catchupSource"] = it }
        metadata?.catchupType?.let { mdMap["catchupType"] = it }
        // catchup-correction (hours) — UrlBuilder applies the offset to
        // programmeStart before computing the civil-time URL. Optional;
        // null is treated as 0. Audit catch.
        metadata?.catchupCorrection?.let { mdMap["catchupCorrection"] = it }
        if (mdMap.isEmpty()) {
            return Resolution.Unavailable(UnavailableReason.MISSING_METADATA)
        }
        val duration = (programme.endTime - programme.startTime).coerceAtLeast(0L)
        val url =
            buildM3uCatchupUrl(
                originalUrl = channel.streamUrl,
                metadata = mdMap,
                programmeStart = programme.startTime,
                programmeDuration = duration,
                nowSecs = nowSec,
            ) ?: return Resolution.Unavailable(UnavailableReason.MISSING_METADATA)
        return Resolution.Playable(channel.withCatchupUrl(url, programme))
    }

    private fun parseMetadata(json: String): ContentMetadata? = runCatching { METADATA_JSON.decodeFromString(ContentMetadata.serializer(), json) }
        .getOrElse { err ->
            if (err is SerializationException) null else throw err
        }

    /**
     * Return a copy of [this] with [url] swapped in and an id suffix that
     * encodes the programme. Needed so two back-to-back catchup plays on the
     * same channel don't collide with each other in history/resume lookups.
     */
    private fun ContentItem.withCatchupUrl(url: String, programme: EpgProgramme): ContentItem = copy(
        id = "catchup:$id:${programme.startTime}",
        streamUrl = url,
        cleanTitle = programme.title,
    )

    private companion object {
        private val METADATA_JSON = Json { ignoreUnknownKeys = true }
    }
}
