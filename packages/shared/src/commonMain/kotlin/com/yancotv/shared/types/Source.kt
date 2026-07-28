package com.yancotv.shared.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SourceType {
    @SerialName("m3u_url")
    M3U_URL,

    @SerialName("m3u_file")
    M3U_FILE,

    @SerialName("xtream")
    XTREAM,

    @SerialName("stalker")
    STALKER,
}

@Serializable
data class Source(
    val id: String,
    val name: String,
    val type: SourceType,
    val url: String? = null,
    val filePath: String? = null,
    val epgUrl: String? = null,
    /** Optional per-source HTTP User-Agent. Applied to mpv playback. */
    val userAgent: String? = null,
    /** Optional per-source HTTP Referer. Required by some providers
     *  (TikiLive / OkLivetv-class hosts) that gate playback on the
     *  Referer header. NULL = no header sent. */
    val referer: String? = null,
    val lastSynced: Long? = null,
    val isActive: Boolean,
    val priority: Int,
    val channelCount: Int,
    val lastSyncError: String? = null,
    val autoSyncInterval: Int,
    /** Stage 2.4 — multi-EPG merge priority. Higher wins when two sources
     *  cover the same tvg_id. Default 0 = "no preference, last writer
     *  wins" which matches single-EPG installs. */
    val epgPriority: Int = 0,
    /** v9 → v10 — when true, the Android shell triggers a background
     *  refresh for this source on every MainActivity creation. Off by
     *  default; the user opts in per-source from the Sources detail UI. */
    val autoSyncOnStart: Boolean = false,
    /** v11 → v12 (MK.30.3) — when the provider account behind this source
     *  stops working, in ms since epoch. NULL means "no expiry to show":
     *  either the source type carries no account metadata (m3u_url /
     *  m3u_file), or it hasn't synced since the column was added, or the
     *  provider reports the account as non-expiring. Captured from the
     *  Xtream handshake, which reports Unix *seconds* — converted on the
     *  way in. */
    val expiresAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class AddSourceInput(
    val name: String,
    val type: SourceType,
    val url: String? = null,
    val filePath: String? = null,
    val username: String? = null,
    val password: String? = null,
    val macAddress: String? = null,
    val epgUrl: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
)

@Serializable
data class UpdateSourceInput(
    val id: String,
    val name: String? = null,
    val url: String? = null,
    val username: String? = null,
    val password: String? = null,
    val macAddress: String? = null,
    val epgUrl: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val autoSyncInterval: Int? = null,
    val autoSyncOnStart: Boolean? = null,
)
