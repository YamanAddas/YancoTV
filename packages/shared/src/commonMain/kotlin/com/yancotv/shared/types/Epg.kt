package com.yancotv.shared.types

import kotlinx.serialization.Serializable

/** EPG programme as stored in the database */
@Serializable
data class EpgProgramme(
    val id: String,
    val channelTvgId: String,
    val title: String,
    val description: String? = null,
    /** Unix seconds */
    val startTime: Long,
    /** Unix seconds */
    val endTime: Long,
    val category: String? = null,
    val iconUrl: String? = null,
)

/** Now + Next pair for a single channel */
@Serializable
data class NowNext(val channelTvgId: String, val now: EpgProgramme? = null, val next: EpgProgramme? = null)

/** Map of tvgId -> NowNext for bulk queries */
typealias NowNextMap = Map<String, NowNext>

@Serializable
data class EpgGuideChannel(
    val tvgId: String,
    /** Channel display name (joined from content table) */
    val name: String,
    val logoUrl: String? = null,
    /** Stream URL for direct playback — avoids a second getLive() call from the Guide page */
    val streamUrl: String? = null,
    val programmes: List<EpgProgramme>,
    /** MK.16.5 — channel number rendered in the guide. Currently
     *  populated from `content.sort_order`, which today is the
     *  sequential playlist position (not `tvg-chno`). NULL when the
     *  source didn't provide one. Storing the broadcast-style channel
     *  number from `tvg-chno` separately needs a schema migration —
     *  tracked as a follow-up. */
    val channelNumber: Int? = null,
)

/** EPG guide slice — programmes for a time range, grouped by channel */
@Serializable
data class EpgGuideData(val channels: List<EpgGuideChannel>, val startTime: Long, val endTime: Long)

/** Status returned after an EPG refresh */
@Serializable
data class EpgRefreshResult(val ok: Boolean, val programmeCount: Int? = null, val channelCount: Int? = null, val error: String? = null)

/** EPG settings stored in the settings table */
@Serializable
data class EpgSettings(
    val globalEpgUrl: String? = null,
    /** default 12 */
    val refreshIntervalHours: Int,
    /** Unix ms */
    val lastRefreshedAt: Long? = null,
)
