package com.yancotv.shared.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SourceType {
    @SerialName("m3u_url") M3U_URL,
    @SerialName("m3u_file") M3U_FILE,
    @SerialName("xtream") XTREAM,
    @SerialName("stalker") STALKER,
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
    val lastSynced: Long? = null,
    val isActive: Boolean,
    val priority: Int,
    val channelCount: Int,
    val lastSyncError: String? = null,
    val autoSyncInterval: Int,
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
    val autoSyncInterval: Int? = null,
)
