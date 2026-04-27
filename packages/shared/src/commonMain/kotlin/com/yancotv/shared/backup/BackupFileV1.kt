package com.yancotv.shared.backup

import kotlinx.serialization.Serializable

/**
 * MK.19.8 — User-facing backup file format, version 1.
 *
 * Mirrors the Electron backup-service.ts schema so a future desktop ↔
 * Android transfer is plausible without renegotiating the format. This
 * is NOT the same as [com.yancotv.shared.db.SourcesBackup] — that one
 * is a silent corruption-recovery dump to `<filesDir>/sources-backup.json`,
 * Keystore-bound and not portable.
 *
 * Field rules:
 *  - [schemaVersion] is the BACKUP file format version, NOT the DB schema.
 *    Bump only when the JSON shape changes incompatibly.
 *  - [dbSchemaVersion] is the SQLDelight version at export time. Restore
 *    refuses if this exceeds the current binary's `YancoDb.Schema.version`.
 *  - [createdAt] / [appVersion] are advisory metadata for the user-facing
 *    backups list (BackupMetadata Stage 2.5).
 *  - [encryption] non-null means credential blobs in [BackupRecords.sources]
 *    are AES/GCM-encrypted with a key derived from the user's password
 *    (PBKDF2-HMAC-SHA256, salt + iters per [BackupEncryptionInfo]). When
 *    null, credentials are PLAINTEXT — leakage risk; warn at export time.
 *  - [checksum] is SHA-256 hex over the canonical JSON serialization of
 *    [records] (no whitespace, sorted keys via the project's
 *    `BackupCanonicalJson` formatter). Validated by the importer; mismatch
 *    aborts the restore.
 */
@Serializable
data class BackupFileV1(
    val schemaVersion: Int = 1,
    val appVersion: String,
    val dbSchemaVersion: Int,
    val createdAt: Long, // ms since epoch
    val encryption: BackupEncryptionInfo? = null,
    val records: BackupRecords,
    val recordCounts: Map<String, Int>,
    val checksum: String,
)

@Serializable
data class BackupEncryptionInfo(
    val kdf: String = "pbkdf2-sha256",
    val iterations: Int,
    val saltHex: String,
)

/**
 * Per-table record buckets. The table → field mapping mirrors
 * `MK.19.8` investigation §10 (back up vs skip). Cache tables
 * (`content` rows, `epg_programmes`, `episodes`, `subtitle_cache`,
 * `tmdb_cache`) are skipped because they're rebuilt on source sync.
 *
 * Re-link strategy: every record that holds a `content_id` foreign key
 * stores the `(sourceId, streamUrl, …)` tuple instead, so the importer
 * can resolve the local content_id post-resync.
 */
@Serializable
data class BackupRecords(
    val sources: List<SourceRecord>,
    val favoriteLists: List<FavoriteListRecord>,
    val favorites: List<FavoriteRecord>,
    val watchHistory: List<WatchHistoryRecord>,
    val recordingSchedules: List<RecordingScheduleRecord>,
    val recordings: List<RecordingRecord>,
    val contentOverrides: List<ContentOverrideRecord>,
    val channelOverrides: List<ChannelOverrideRecord>,
    val lockedChannels: List<ChannelRef>,
    val hiddenChannels: List<ChannelRef>,
    val groupPreferences: List<GroupPreferenceRecord>,
    val settings: List<SettingsKv>,
    val reminders: List<ReminderRecord>,
)

@Serializable
data class SourceRecord(
    val id: String,
    val name: String,
    val type: String,
    val url: String?,
    val filePath: String?,
    /** Plaintext when [BackupFileV1.encryption] is null; ciphertext (hex) otherwise. */
    val username: String?,
    val password: String?,
    val macAddress: String?,
    val epgUrl: String?,
    val userAgent: String?,
    val referer: String?,
    val isActive: Boolean,
    val priority: Long,
    val epgPriority: Long,
    val autoSyncInterval: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class FavoriteListRecord(
    val id: String,
    val name: String,
    val sortOrder: Long,
    val isDefault: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Re-link tuple: `(sourceId, streamUrl)` resolves to a local `content_id`
 * after source resync. `title` + `tvgId` are advisory hints in case the
 * stream_url drifted between exports (provider URL changes happen).
 */
@Serializable
data class FavoriteRecord(
    val favoriteId: String,
    val sourceId: String,
    val streamUrl: String,
    val title: String,
    val tvgId: String?,
    val listId: String?,
    val addedAt: Long,
)

@Serializable
data class WatchHistoryRecord(
    val historyId: String,
    val sourceId: String,
    val streamUrl: String,
    val title: String,
    val tvgId: String?,
    /** When non-null, identifies an episode within a series content row. */
    val episodeStreamUrl: String?,
    val positionSeconds: Long,
    val durationSeconds: Long?,
    val watchedAt: Long,
)

@Serializable
data class RecordingScheduleRecord(
    val id: String,
    /** Re-link by streamUrl + sourceId via local content lookup. Optional. */
    val sourceId: String?,
    val streamUrl: String,
    val programmeStreamUrl: String?,
    val title: String,
    val scheduledStart: Long,
    val scheduledEnd: Long,
    val state: String,
    val recordingId: String?,
    val seriesKey: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class RecordingRecord(
    val id: String,
    /** Re-link via stream_url; content_id is local-only. */
    val streamUrl: String,
    val title: String,
    val fileUri: String,
    val status: String,
    val format: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val durationSeconds: Long?,
    val fileSizeBytes: Long?,
)

@Serializable
data class ContentOverrideRecord(
    val sourceId: String,
    val streamUrl: String,
    val nameOverride: String?,
    val logoOverride: String?,
)

@Serializable
data class ChannelOverrideRecord(
    val sourceId: String,
    val streamUrl: String,
    val customName: String?,
    val customLogoUrl: String?,
    val customNumber: Long?,
    val customGroup: String?,
    val updatedAt: Long,
)

@Serializable
data class ChannelRef(
    val sourceId: String,
    val streamUrl: String,
    val ts: Long,
)

@Serializable
data class GroupPreferenceRecord(
    val id: String,
    val contentType: String,
    val groupKey: String,
    val sortOrder: Long,
    val isHidden: Boolean,
    val isPinned: Boolean,
    val customName: String?,
    val createdAt: Long,
)

@Serializable
data class SettingsKv(val key: String, val value: String)

@Serializable
data class ReminderRecord(
    val id: String,
    val programmeId: String,
    val channelTvgId: String,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val leadSeconds: Long,
    val fireAt: Long,
    val fired: Boolean,
    val createdAt: Long,
)
