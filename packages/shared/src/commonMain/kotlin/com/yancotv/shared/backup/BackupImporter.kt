package com.yancotv.shared.backup

import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.sources.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/**
 * MK.19.8.2 — Importer counterpart to [BackupExporter]. Merge mode only
 * for v1 (per the active-queue decision); replace mode is deferred.
 *
 * Two-phase import:
 *  1. **Synchronous phase**: validates the file (schema-version guard,
 *     checksum), decrypts credentials with [BackupCipher] when the file
 *     is password-encrypted, re-encrypts via [credentialStore] for local
 *     storage, then upserts every record that doesn't depend on
 *     content_id resolution: sources, favorite_lists, settings,
 *     group_preferences, reminders, recordings, recording_schedules.
 *  2. **Pending phase**: records that hold a content_id FK
 *     (favorites, watch_history, content overrides, channel overrides,
 *     parental locked/hidden) are buffered when the local content table
 *     hasn't been resynced yet. [retryPendingLinks] drains the buffer
 *     against the now-updated content table; MK.19.8.4 wires it to a
 *     SourceSync completion observer.
 *
 * Errors:
 *  - [BackupSchemaTooNewException] if `dbSchemaVersion` exceeds binary's
 *    schema version.
 *  - [BackupChecksumMismatchException] if the recomputed SHA-256 doesn't
 *    match the embedded checksum.
 *  - [BackupDecryptException] wraps password / cipher failures.
 */
class BackupImporter(
    private val db: YancoDb,
    private val credentialStore: CredentialStore,
    private val cipher: BackupCipher = BackupCipher(),
) {
    private val _pending = MutableStateFlow(PendingLinkState.empty())
    val pendingLinks: StateFlow<PendingLinkState> = _pending.asStateFlow()

    /**
     * Apply [file] to the DB in merge mode.
     *
     * @param file parsed backup file (caller handles JSON deserialization)
     * @param password required when [BackupFileV1.encryption] is non-null
     * @param currentSchemaVersion `YancoDb.Schema.version` at import time
     */
    fun import(
        file: BackupFileV1,
        password: String? = null,
        currentSchemaVersion: Int,
    ): RestoreReport {
        require(file.schemaVersion == 1) {
            "unsupported backup schemaVersion ${file.schemaVersion}"
        }
        if (file.dbSchemaVersion > currentSchemaVersion) {
            throw BackupSchemaTooNewException(file.dbSchemaVersion, currentSchemaVersion)
        }
        val recomputed = sha256Hex(BackupCanonicalJson.Compact.encodeToString(file.records).encodeToByteArray())
        if (recomputed != file.checksum) {
            throw BackupChecksumMismatchException(file.checksum, recomputed)
        }

        val key =
            file.encryption?.let { enc ->
                val pw = password ?: throw BackupDecryptException("file is password-encrypted but no password supplied")
                runCatching { cipher.deriveKey(pw, enc.saltHex, enc.iterations) }
                    .getOrElse { throw BackupDecryptException("PBKDF2 key derivation failed: ${it.message}") }
            }

        val restored = mutableMapOf<String, Int>()
        val skipped = mutableMapOf<String, Int>()
        val warnings = mutableListOf<String>()

        // --- Phase 1: synchronous tables ------------------------------------

        restored["sources"] = importSources(file.records.sources, key, warnings, skipped)
        restored["favoriteLists"] = importFavoriteLists(file.records.favoriteLists, skipped)
        restored["settings"] = importSettings(file.records.settings, skipped)
        restored["groupPreferences"] = importGroupPreferences(file.records.groupPreferences, skipped)
        restored["reminders"] = importReminders(file.records.reminders, skipped)
        restored["recordings"] = importRecordings(file.records.recordings, skipped)
        restored["recordingSchedules"] = importRecordingSchedules(file.records.recordingSchedules, skipped)

        // --- Phase 2: content-id-keyed records (re-link or buffer) ----------

        val pendingFavorites = mutableListOf<FavoriteRecord>()
        val pendingHistory = mutableListOf<WatchHistoryRecord>()
        val pendingContentOverrides = mutableListOf<ContentOverrideRecord>()
        val pendingChannelOverrides = mutableListOf<ChannelOverrideRecord>()
        val pendingLocked = mutableListOf<ChannelRef>()
        val pendingHidden = mutableListOf<ChannelRef>()

        restored["favorites"] = importFavorites(file.records.favorites, pendingFavorites, skipped)
        restored["watchHistory"] = importWatchHistory(file.records.watchHistory, pendingHistory, skipped)
        restored["contentOverrides"] = importContentOverrides(file.records.contentOverrides, pendingContentOverrides, skipped)
        restored["channelOverrides"] = importChannelOverrides(file.records.channelOverrides, pendingChannelOverrides, skipped)
        restored["lockedChannels"] = importChannelRefs(file.records.lockedChannels, pendingLocked) { id, ts ->
            db.parentalQueries.lockChannel(id, ts)
        }
        restored["hiddenChannels"] = importChannelRefs(file.records.hiddenChannels, pendingHidden) { id, ts ->
            db.parentalQueries.hideChannel(id, ts)
        }

        _pending.value =
            PendingLinkState(
                favorites = pendingFavorites,
                watchHistory = pendingHistory,
                contentOverrides = pendingContentOverrides,
                channelOverrides = pendingChannelOverrides,
                lockedChannels = pendingLocked,
                hiddenChannels = pendingHidden,
            )

        return RestoreReport(
            restored = restored.toMap(),
            skipped = skipped.toMap(),
            unlinked = _pending.value.counts(),
            warnings = warnings.toList(),
        )
    }

    /**
     * Re-attempt resolution for every buffered record. Called by the
     * source-sync completion observer (MK.19.8.4) after each catalog
     * refresh. Returns a delta report of newly-restored counts.
     */
    fun retryPendingLinks(): RestoreReport {
        val state = _pending.value
        if (state.isEmpty()) return RestoreReport.empty()

        val restored = mutableMapOf<String, Int>()
        val skipped = mutableMapOf<String, Int>()
        val stillPending = PendingLinkState.empty().toMutable()

        restored["favorites"] = importFavorites(state.favorites, stillPending.favorites, skipped)
        restored["watchHistory"] = importWatchHistory(state.watchHistory, stillPending.watchHistory, skipped)
        restored["contentOverrides"] = importContentOverrides(state.contentOverrides, stillPending.contentOverrides, skipped)
        restored["channelOverrides"] = importChannelOverrides(state.channelOverrides, stillPending.channelOverrides, skipped)
        restored["lockedChannels"] = importChannelRefs(state.lockedChannels, stillPending.lockedChannels) { id, ts ->
            db.parentalQueries.lockChannel(id, ts)
        }
        restored["hiddenChannels"] = importChannelRefs(state.hiddenChannels, stillPending.hiddenChannels) { id, ts ->
            db.parentalQueries.hideChannel(id, ts)
        }

        _pending.value = stillPending.toImmutable()

        return RestoreReport(
            restored = restored.toMap(),
            skipped = skipped.toMap(),
            unlinked = _pending.value.counts(),
            warnings = emptyList(),
        )
    }

    // ─── Phase 1 importers ────────────────────────────────────────────────

    private fun importSources(
        records: List<SourceRecord>,
        key: ByteArray?,
        warnings: MutableList<String>,
        skipped: MutableMap<String, Int>,
    ): Int {
        var inserted = 0
        for (r in records) {
            val existing = db.sourcesQueries.selectById(r.id).executeAsOneOrNull()
            if (existing != null) {
                skipped["sources"] = (skipped["sources"] ?: 0) + 1
                continue
            }
            // Decrypt the (possibly password-wrapped) credentials, then
            // re-encrypt with the local Keystore for storage.
            val username = r.username?.let { decryptIfNeeded(it, key, warnings) }
            val password = r.password?.let { decryptIfNeeded(it, key, warnings) }
            val mac = r.macAddress?.let { decryptIfNeeded(it, key, warnings) }

            db.sourcesQueries.insert(
                id = r.id,
                name = r.name,
                type = r.type,
                url = r.url,
                file_path = r.filePath,
                username_encrypted = username?.let { credentialStore.encrypt(it) },
                password_encrypted = password?.let { credentialStore.encrypt(it) },
                mac_address_encrypted = mac?.let { credentialStore.encrypt(it) },
                epg_url = r.epgUrl,
                user_agent = r.userAgent,
                referer = r.referer,
                last_synced = null,
                last_sync_error = null,
                is_active = r.isActive,
                priority = r.priority,
                channel_count = 0,
                auto_sync_interval = r.autoSyncInterval,
                epg_priority = r.epgPriority,
                created_at = r.createdAt,
                updated_at = r.updatedAt,
            )
            inserted++
        }
        return inserted
    }

    private fun decryptIfNeeded(
        value: String,
        key: ByteArray?,
        warnings: MutableList<String>,
    ): String {
        if (key == null) return value // plaintext mode
        return runCatching { cipher.decryptBytes(value, key).decodeToString() }
            .getOrElse {
                warnings.add("credential decrypt failed: ${it.message}; row left empty")
                ""
            }
    }

    private fun importFavoriteLists(
        records: List<FavoriteListRecord>,
        skipped: MutableMap<String, Int>,
    ): Int {
        var inserted = 0
        for (r in records) {
            // 'default' list is seeded by FavoriteLists.sq INSERT OR IGNORE;
            // skip duplicates silently.
            val existing = db.favoriteListsQueries.selectById(r.id).executeAsOneOrNull()
            if (existing != null) {
                skipped["favoriteLists"] = (skipped["favoriteLists"] ?: 0) + 1
                continue
            }
            db.favoriteListsQueries.insert(
                id = r.id,
                name = r.name,
                sort_order = r.sortOrder,
                is_default = r.isDefault,
                created_at = r.createdAt,
                updated_at = r.updatedAt,
            )
            inserted++
        }
        return inserted
    }

    private fun importSettings(
        records: List<SettingsKv>,
        skipped: MutableMap<String, Int>,
    ): Int {
        // Settings is upsert by primary key so "merge mode" overwrites
        // existing keys with backup values. Skip nothing.
        for (r in records) db.settingsQueries.upsert(r.key, r.value)
        return records.size
    }

    private fun importGroupPreferences(
        records: List<GroupPreferenceRecord>,
        skipped: MutableMap<String, Int>,
    ): Int {
        for (r in records) {
            db.groupPreferencesQueries.upsert(
                id = r.id,
                content_type = r.contentType,
                group_key = r.groupKey,
                sort_order = r.sortOrder,
                is_hidden = r.isHidden,
                is_pinned = r.isPinned,
                custom_name = r.customName,
                created_at = r.createdAt,
            )
        }
        return records.size
    }

    private fun importReminders(
        records: List<ReminderRecord>,
        skipped: MutableMap<String, Int>,
    ): Int {
        var inserted = 0
        for (r in records) {
            val existing = db.remindersQueries.selectById(r.id).executeAsOneOrNull()
            if (existing != null) {
                skipped["reminders"] = (skipped["reminders"] ?: 0) + 1
                continue
            }
            db.remindersQueries.insert(
                id = r.id,
                programme_id = r.programmeId,
                channel_tvg_id = r.channelTvgId,
                title = r.title,
                start_time = r.startTime,
                end_time = r.endTime,
                lead_seconds = r.leadSeconds,
                fire_at = r.fireAt,
                fired = r.fired,
                created_at = r.createdAt,
            )
            inserted++
        }
        return inserted
    }

    private fun importRecordings(
        records: List<RecordingRecord>,
        skipped: MutableMap<String, Int>,
    ): Int {
        var inserted = 0
        for (r in records) {
            val existing = db.recordingsQueries.selectById(r.id).executeAsOneOrNull()
            if (existing != null) {
                skipped["recordings"] = (skipped["recordings"] ?: 0) + 1
                continue
            }
            // content_id stored as null on import — re-resolve via stream_url
            // is too lossy for recordings (the file is the canonical artefact).
            // The recordings browser handles null content_id gracefully.
            db.recordingsQueries.insert(
                id = r.id,
                content_id = null,
                title = r.title,
                stream_url = r.streamUrl,
                file_path = r.fileUri,
                status = r.status,
                started_at = r.startedAt,
                ended_at = r.endedAt,
                duration_seconds = r.durationSeconds,
                file_size_bytes = r.fileSizeBytes,
                error = null,
                format = r.format,
            )
            inserted++
        }
        return inserted
    }

    private fun importRecordingSchedules(
        records: List<RecordingScheduleRecord>,
        skipped: MutableMap<String, Int>,
    ): Int {
        var inserted = 0
        for (r in records) {
            val existing = db.recordingSchedulesQueries.selectById(r.id).executeAsOneOrNull()
            if (existing != null) {
                skipped["recordingSchedules"] = (skipped["recordingSchedules"] ?: 0) + 1
                continue
            }
            // Try to resolve content_id immediately if sources have synced;
            // otherwise leave null. The schedule's stream_url is the
            // authoritative trigger; content_id is advisory.
            val contentId =
                r.sourceId?.let { src ->
                    db.contentQueries.findIdBySourceAndStreamUrl(src, r.streamUrl).executeAsOneOrNull()
                }
            db.recordingSchedulesQueries.insert(
                id = r.id,
                content_id = contentId,
                programme_id = null,
                title = r.title,
                stream_url = r.streamUrl,
                scheduled_start = r.scheduledStart,
                scheduled_end = r.scheduledEnd,
                state = r.state,
                recording_id = r.recordingId,
                error = null,
                created_at = r.createdAt,
                updated_at = r.updatedAt,
                series_key = r.seriesKey,
            )
            inserted++
        }
        return inserted
    }

    // ─── Phase 2 importers (re-link or buffer) ────────────────────────────

    private fun importFavorites(
        records: List<FavoriteRecord>,
        bufferIfUnresolved: MutableList<FavoriteRecord>,
        skipped: MutableMap<String, Int>,
    ): Int {
        var inserted = 0
        for (r in records) {
            val cid = db.contentQueries.findIdBySourceAndStreamUrl(r.sourceId, r.streamUrl).executeAsOneOrNull()
            if (cid == null) {
                bufferIfUnresolved.add(r)
                continue
            }
            // Skip if this exact favorite id is already present (merge mode
            // is keyed by primary id, not by content+list — duplicate adds
            // are a separate user action).
            val existing =
                db.favoritesQueries.selectByList(r.listId ?: "default")
                    .executeAsList()
                    .any { it.favorite_id == r.favoriteId }
            if (existing) {
                skipped["favorites"] = (skipped["favorites"] ?: 0) + 1
                continue
            }
            db.favoritesQueries.insert(
                id = r.favoriteId,
                content_id = cid,
                list_id = r.listId ?: "default",
                added_at = r.addedAt,
            )
            inserted++
        }
        return inserted
    }

    private fun importWatchHistory(
        records: List<WatchHistoryRecord>,
        bufferIfUnresolved: MutableList<WatchHistoryRecord>,
        skipped: MutableMap<String, Int>,
    ): Int {
        var inserted = 0
        for (r in records) {
            val cid = db.contentQueries.findIdBySourceAndStreamUrl(r.sourceId, r.streamUrl).executeAsOneOrNull()
            if (cid == null) {
                bufferIfUnresolved.add(r)
                continue
            }
            // episode_id resolution: best-effort; if the episode hasn't
            // been re-fetched yet this is fine (resume-point still keys on
            // content_id at the series-container level).
            val episodeId: String? = null // simplified for v1; episodes refresh independently
            db.watchHistoryQueries.upsert(
                id = r.historyId,
                content_id = cid,
                episode_id = episodeId,
                position_seconds = r.positionSeconds,
                duration_seconds = r.durationSeconds,
                watched_at = r.watchedAt,
            )
            inserted++
        }
        return inserted
    }

    private fun importContentOverrides(
        records: List<ContentOverrideRecord>,
        bufferIfUnresolved: MutableList<ContentOverrideRecord>,
        skipped: MutableMap<String, Int>,
    ): Int {
        var inserted = 0
        for (r in records) {
            val cid = db.contentQueries.findIdBySourceAndStreamUrl(r.sourceId, r.streamUrl).executeAsOneOrNull()
            if (cid == null) {
                bufferIfUnresolved.add(r)
                continue
            }
            db.contentQueries.setOverrides(
                nameOverride = r.nameOverride,
                logoOverride = r.logoOverride,
                id = cid,
            )
            inserted++
        }
        return inserted
    }

    private fun importChannelOverrides(
        records: List<ChannelOverrideRecord>,
        bufferIfUnresolved: MutableList<ChannelOverrideRecord>,
        skipped: MutableMap<String, Int>,
    ): Int {
        var inserted = 0
        for (r in records) {
            val cid = db.contentQueries.findIdBySourceAndStreamUrl(r.sourceId, r.streamUrl).executeAsOneOrNull()
            if (cid == null) {
                bufferIfUnresolved.add(r)
                continue
            }
            db.parentalQueries.upsertOverride(
                content_id = cid,
                custom_name = r.customName,
                custom_logo_url = r.customLogoUrl,
                custom_number = r.customNumber,
                custom_group = r.customGroup,
                updated_at = r.updatedAt,
            )
            inserted++
        }
        return inserted
    }

    private fun importChannelRefs(
        records: List<ChannelRef>,
        bufferIfUnresolved: MutableList<ChannelRef>,
        upsert: (contentId: String, ts: Long) -> Unit,
    ): Int {
        var inserted = 0
        for (r in records) {
            val cid = db.contentQueries.findIdBySourceAndStreamUrl(r.sourceId, r.streamUrl).executeAsOneOrNull()
            if (cid == null) {
                bufferIfUnresolved.add(r)
                continue
            }
            upsert(cid, r.ts)
            inserted++
        }
        return inserted
    }
}

/**
 * Snapshot of records that still need a local content_id resolution.
 * Exposed via [BackupImporter.pendingLinks] for MK.19.8.4 to drain.
 */
data class PendingLinkState(
    val favorites: List<FavoriteRecord>,
    val watchHistory: List<WatchHistoryRecord>,
    val contentOverrides: List<ContentOverrideRecord>,
    val channelOverrides: List<ChannelOverrideRecord>,
    val lockedChannels: List<ChannelRef>,
    val hiddenChannels: List<ChannelRef>,
) {
    fun isEmpty(): Boolean =
        favorites.isEmpty() && watchHistory.isEmpty() && contentOverrides.isEmpty() &&
            channelOverrides.isEmpty() && lockedChannels.isEmpty() && hiddenChannels.isEmpty()

    fun counts(): Map<String, Int> =
        mapOf(
            "favorites" to favorites.size,
            "watchHistory" to watchHistory.size,
            "contentOverrides" to contentOverrides.size,
            "channelOverrides" to channelOverrides.size,
            "lockedChannels" to lockedChannels.size,
            "hiddenChannels" to hiddenChannels.size,
        ).filterValues { it > 0 }

    internal fun toMutable() =
        MutablePendingLinkState(
            favorites.toMutableList(),
            watchHistory.toMutableList(),
            contentOverrides.toMutableList(),
            channelOverrides.toMutableList(),
            lockedChannels.toMutableList(),
            hiddenChannels.toMutableList(),
        )

    companion object {
        fun empty() = PendingLinkState(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

internal data class MutablePendingLinkState(
    val favorites: MutableList<FavoriteRecord>,
    val watchHistory: MutableList<WatchHistoryRecord>,
    val contentOverrides: MutableList<ContentOverrideRecord>,
    val channelOverrides: MutableList<ChannelOverrideRecord>,
    val lockedChannels: MutableList<ChannelRef>,
    val hiddenChannels: MutableList<ChannelRef>,
) {
    fun toImmutable() =
        PendingLinkState(
            favorites.toList(),
            watchHistory.toList(),
            contentOverrides.toList(),
            channelOverrides.toList(),
            lockedChannels.toList(),
            hiddenChannels.toList(),
        )
}

/**
 * Per-table summary returned from [BackupImporter.import] and
 * [BackupImporter.retryPendingLinks]. UI consumes these counts to show
 * the user what landed and what didn't.
 */
data class RestoreReport(
    val restored: Map<String, Int>,
    val skipped: Map<String, Int>,
    val unlinked: Map<String, Int>,
    val warnings: List<String>,
) {
    val totalRestored: Int get() = restored.values.sum()
    val totalSkipped: Int get() = skipped.values.sum()
    val totalUnlinked: Int get() = unlinked.values.sum()

    companion object {
        fun empty() = RestoreReport(emptyMap(), emptyMap(), emptyMap(), emptyList())
    }
}

class BackupSchemaTooNewException(
    val backupVersion: Int,
    val currentVersion: Int,
) : RuntimeException(
        "backup is for db schema $backupVersion but binary is at $currentVersion; upgrade the app first",
    )

class BackupChecksumMismatchException(
    val expected: String,
    val actual: String,
) : RuntimeException("backup checksum mismatch (expected=$expected actual=$actual)")

class BackupDecryptException(message: String) : RuntimeException(message)
