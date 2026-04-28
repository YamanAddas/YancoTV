package com.yancotv.shared.backup

import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.sources.CredentialStore
import kotlinx.serialization.encodeToString

/**
 * MK.19.8.1 — Pure exporter. Reads every user-curated table out of
 * [YancoDb] and assembles a [BackupFileV1] payload.
 *
 * Credential handling:
 *  - All three encrypted columns on [com.yancotv.shared.db.Sources] are
 *    decrypted via [credentialStore] (Keystore-bound on Android).
 *  - If [password] is null, the decrypted strings land in [SourceRecord]
 *    as PLAINTEXT — quick personal backup mode.
 *  - If [password] is non-null, each plaintext is re-encrypted with a
 *    PBKDF2-derived AES/GCM key via [BackupCipher]; the salt and
 *    iteration count land in [BackupFileV1.encryption] so the importer
 *    can re-derive the same key.
 *
 * Re-link tuples: every row that holds a `content_id` foreign key is
 * exported with `(sourceId, streamUrl, …)` instead so the importer can
 * resolve the local content_id post-resync. Per-row lookup via
 * `contentQueries.selectById` is O(N) but acceptable — favorites /
 * history / schedules are typically <1k rows.
 *
 * Fields explicitly skipped per the MK.19.8 inventory:
 *  - `content` rows themselves (cache; only override columns ride along)
 *  - `epg_programmes`, `episodes`, `subtitle_cache`, `tmdb_cache` (cache)
 *  - `downloads` (ephemeral, v1 deferred)
 *  - PIN hash (lives in Keystore, not DB)
 */
class BackupExporter(
    private val db: YancoDb,
    private val credentialStore: CredentialStore,
    private val cipher: BackupCipher = BackupCipher(),
) {
    /**
     * Build a [BackupFileV1] from current DB state.
     *
     * @param appVersion advisory metadata (e.g. `BuildConfig.VERSION_NAME`)
     * @param dbSchemaVersion `YancoDb.Schema.version` at call time
     * @param nowMs `Clock`-injected timestamp for `createdAt`
     * @param password non-null enables AES/GCM credential encryption
     */
    fun export(
        appVersion: String,
        dbSchemaVersion: Int,
        nowMs: Long,
        password: String? = null,
    ): BackupFileV1 {
        val encryption = password?.let {
            BackupEncryptionInfo(
                kdf = "pbkdf2-sha256",
                iterations = BACKUP_PBKDF2_ITERATIONS,
                saltHex = cipher.randomSaltHex(),
            )
        }
        val key = encryption?.let { cipher.deriveKey(password!!, it.saltHex, it.iterations) }

        val records =
            BackupRecords(
                sources = exportSources(key),
                favoriteLists = exportFavoriteLists(),
                favorites = exportFavorites(),
                watchHistory = exportWatchHistory(),
                recordingSchedules = exportRecordingSchedules(),
                recordings = exportRecordings(),
                contentOverrides = exportContentOverrides(),
                channelOverrides = exportChannelOverrides(),
                lockedChannels = exportLockedChannels(),
                hiddenChannels = exportHiddenChannels(),
                groupPreferences = exportGroupPreferences(),
                settings = exportSettings(),
                reminders = exportReminders(),
            )

        val recordCounts =
            mapOf(
                "sources" to records.sources.size,
                "favoriteLists" to records.favoriteLists.size,
                "favorites" to records.favorites.size,
                "watchHistory" to records.watchHistory.size,
                "recordingSchedules" to records.recordingSchedules.size,
                "recordings" to records.recordings.size,
                "contentOverrides" to records.contentOverrides.size,
                "channelOverrides" to records.channelOverrides.size,
                "lockedChannels" to records.lockedChannels.size,
                "hiddenChannels" to records.hiddenChannels.size,
                "groupPreferences" to records.groupPreferences.size,
                "settings" to records.settings.size,
                "reminders" to records.reminders.size,
            )

        // Stable checksum: serialize records compact (no whitespace),
        // SHA-256 the UTF-8 bytes. Importer recomputes via the same path.
        val recordsJson = BackupCanonicalJson.Compact.encodeToString(records)
        val checksum = sha256Hex(recordsJson.encodeToByteArray())

        return BackupFileV1(
            schemaVersion = 1,
            appVersion = appVersion,
            dbSchemaVersion = dbSchemaVersion,
            createdAt = nowMs,
            encryption = encryption,
            records = records,
            recordCounts = recordCounts,
            checksum = checksum,
        )
    }

    private fun exportSources(key: ByteArray?): List<SourceRecord> =
        db.sourcesQueries.selectAll().executeAsList().map { row ->
            // Decrypt Keystore-bound blobs first; the resulting plaintext
            // either lands as-is (no password) or is re-encrypted under
            // the user's password-derived key.
            val username = row.username_encrypted?.let { credentialStore.decrypt(it) }
            val password = row.password_encrypted?.let { credentialStore.decrypt(it) }
            val mac = row.mac_address_encrypted?.let { credentialStore.decrypt(it) }
            SourceRecord(
                id = row.id,
                name = row.name,
                type = row.type,
                url = row.url,
                filePath = row.file_path,
                username = username?.let { wrap(it, key) },
                password = password?.let { wrap(it, key) },
                macAddress = mac?.let { wrap(it, key) },
                epgUrl = row.epg_url,
                userAgent = row.user_agent,
                referer = row.referer,
                isActive = row.is_active,
                priority = row.priority,
                epgPriority = row.epg_priority,
                autoSyncInterval = row.auto_sync_interval,
                autoSyncOnStart = row.auto_sync_on_start,
                createdAt = row.created_at,
                updatedAt = row.updated_at,
            )
        }

    /** When [key] is null the value passes through plaintext; otherwise hex-encrypted. */
    private fun wrap(plaintext: String, key: ByteArray?): String =
        if (key == null) plaintext else cipher.encryptHex(plaintext.encodeToByteArray(), key)

    private fun exportFavoriteLists(): List<FavoriteListRecord> =
        db.favoriteListsQueries.selectAll().executeAsList().map {
            FavoriteListRecord(
                id = it.id,
                name = it.name,
                sortOrder = it.sort_order,
                isDefault = it.is_default,
                createdAt = it.created_at,
                updatedAt = it.updated_at,
            )
        }

    private fun exportFavorites(): List<FavoriteRecord> =
        db.favoritesQueries.selectAll().executeAsList().mapNotNull { row ->
            // selectAll JOINs content already; row exposes content's
            // source_id, stream_url, title, tvg_id directly.
            FavoriteRecord(
                favoriteId = row.favorite_id,
                sourceId = row.source_id,
                streamUrl = row.stream_url,
                title = row.title,
                tvgId = row.tvg_id,
                listId = row.list_id,
                addedAt = row.added_at,
            )
        }

    private fun exportWatchHistory(): List<WatchHistoryRecord> {
        val rows = db.watchHistoryQueries.selectRecent(Long.MAX_VALUE).executeAsList()
        return rows.mapNotNull { row ->
            val content = db.contentQueries.selectById(row.content_id).executeAsOneOrNull() ?: return@mapNotNull null
            WatchHistoryRecord(
                historyId = row.id,
                sourceId = content.source_id,
                streamUrl = content.stream_url,
                title = content.title,
                tvgId = content.tvg_id,
                episodeStreamUrl = row.episode_id?.let { eid ->
                    db.episodesQueries.selectById(eid).executeAsOneOrNull()?.stream_url
                },
                positionSeconds = row.position_seconds,
                durationSeconds = row.duration_seconds,
                watchedAt = row.watched_at,
            )
        }
    }

    private fun exportRecordingSchedules(): List<RecordingScheduleRecord> =
        db.recordingSchedulesQueries.selectAll().executeAsList().map { row ->
            val content = row.content_id?.let { db.contentQueries.selectById(it).executeAsOneOrNull() }
            RecordingScheduleRecord(
                id = row.id,
                sourceId = content?.source_id,
                streamUrl = row.stream_url,
                programmeStreamUrl = null, // programme_id is local-only; importer re-resolves via title + start
                title = row.title,
                scheduledStart = row.scheduled_start,
                scheduledEnd = row.scheduled_end,
                state = row.state,
                recordingId = row.recording_id,
                seriesKey = row.series_key,
                createdAt = row.created_at,
                updatedAt = row.updated_at,
            )
        }

    private fun exportRecordings(): List<RecordingRecord> =
        db.recordingsQueries.selectAll().executeAsList().map {
            RecordingRecord(
                id = it.id,
                streamUrl = it.stream_url,
                title = it.title,
                fileUri = it.file_path,
                status = it.status,
                format = it.format,
                startedAt = it.started_at,
                endedAt = it.ended_at,
                durationSeconds = it.duration_seconds,
                fileSizeBytes = it.file_size_bytes,
            )
        }

    private fun exportContentOverrides(): List<ContentOverrideRecord> =
        db.contentQueries.selectOverridesForBackup().executeAsList().map {
            ContentOverrideRecord(
                sourceId = it.source_id,
                streamUrl = it.stream_url,
                nameOverride = it.name_override,
                logoOverride = it.logo_override,
            )
        }

    private fun exportChannelOverrides(): List<ChannelOverrideRecord> =
        db.parentalQueries.selectAllOverrides().executeAsList().mapNotNull { row ->
            val content = db.contentQueries.selectById(row.content_id).executeAsOneOrNull() ?: return@mapNotNull null
            ChannelOverrideRecord(
                sourceId = content.source_id,
                streamUrl = content.stream_url,
                customName = row.custom_name,
                customLogoUrl = row.custom_logo_url,
                customNumber = row.custom_number,
                customGroup = row.custom_group,
                updatedAt = row.updated_at,
            )
        }

    private fun exportLockedChannels(): List<ChannelRef> =
        db.parentalQueries.selectLocked().executeAsList().mapNotNull { id ->
            val c = db.contentQueries.selectById(id).executeAsOneOrNull() ?: return@mapNotNull null
            ChannelRef(sourceId = c.source_id, streamUrl = c.stream_url, ts = 0L)
        }

    private fun exportHiddenChannels(): List<ChannelRef> =
        db.parentalQueries.selectHidden().executeAsList().mapNotNull { id ->
            val c = db.contentQueries.selectById(id).executeAsOneOrNull() ?: return@mapNotNull null
            ChannelRef(sourceId = c.source_id, streamUrl = c.stream_url, ts = 0L)
        }

    private fun exportGroupPreferences(): List<GroupPreferenceRecord> =
        listOf("live", "movie", "series").flatMap { type ->
            db.groupPreferencesQueries.selectByType(type).executeAsList().map { row ->
                GroupPreferenceRecord(
                    id = row.id,
                    contentType = row.content_type,
                    groupKey = row.group_key,
                    sortOrder = row.sort_order,
                    isHidden = row.is_hidden,
                    isPinned = row.is_pinned,
                    customName = row.custom_name,
                    createdAt = row.created_at,
                )
            }
        }

    private fun exportSettings(): List<SettingsKv> =
        db.settingsQueries.selectAll().executeAsList().map { SettingsKv(it.key, it.value_) }

    private fun exportReminders(): List<ReminderRecord> =
        db.remindersQueries.selectAll().executeAsList().map {
            ReminderRecord(
                id = it.id,
                programmeId = it.programme_id,
                channelTvgId = it.channel_tvg_id,
                title = it.title,
                startTime = it.start_time,
                endTime = it.end_time,
                leadSeconds = it.lead_seconds,
                fireAt = it.fire_at,
                fired = it.fired,
                createdAt = it.created_at,
            )
        }
}
