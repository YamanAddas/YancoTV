package com.yancotv.shared.db

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Stage 1.5 — proactive sources backup for DB corruption recovery.
 *
 * On every successful DB open, [DatabaseFactory] dumps the live sources
 * table to `filesDir/sources-backup.json` (this class). If the next DB
 * open fails (corrupt SQLite file, schema-mismatch the migrator can't
 * resolve, disk full + truncated DB, etc.), [DatabaseFactory] reads this
 * JSON, deletes the bad DB, creates a fresh one, and re-imports the
 * sources from the backup.
 *
 * **Why proactive instead of on-mutation:** dumping after every
 * `SourceRepository.insert` / `delete` / `update` would be the more
 * conservative design but adds a hot-path dependency between
 * `:shared`'s repository code and Android's filesystem. Dumping at
 * controller-init time covers the common case (user updated → DB
 * corrupts → next launch recovers) at the cost of losing same-session
 * additions. A same-session corruption is rare and the loss is small.
 *
 * **What about credentials:** Xtream / Stalker passwords live in the
 * Android Keystore, keyed by the `Source.id`. The encrypted blobs in
 * the DB are kept on backup as opaque byte arrays — they're meaningless
 * without their Keystore key, but the Keystore is independent of the
 * SQLite file and survives DB deletion. So restoring a backup gives
 * back functioning sources because the encrypted blob restored to the
 * fresh DB matches the still-living Keystore entry.
 */
internal class SourcesBackup(
    private val backupFile: File,
) {
    /**
     * Production constructor: writes to `<filesDir>/sources-backup.json`,
     * the standard location for app-private state on Android.
     */
    constructor(context: Context) : this(File(context.filesDir, BACKUP_FILE_NAME))

    /**
     * Dump every row from the `sources` table to JSON. Called from
     * [DatabaseFactory.create] after a successful open.
     *
     * Failure to write is non-fatal: the next DB open might not have a
     * backup to recover from, but the app launches normally and the
     * user keeps working. We log via Kermit-friendly Logcat.
     */
    fun writeFromDb(db: YancoDb) {
        runCatching {
            val rows =
                db.sourcesQueries
                    .selectAll()
                    .executeAsList()
                    .map { row ->
                        BackedUpSource(
                            id = row.id,
                            name = row.name,
                            type = row.type,
                            url = row.url,
                            filePath = row.file_path,
                            usernameEncrypted = row.username_encrypted,
                            passwordEncrypted = row.password_encrypted,
                            macAddressEncrypted = row.mac_address_encrypted,
                            epgUrl = row.epg_url,
                            userAgent = row.user_agent,
                            referer = row.referer,
                            lastSynced = row.last_synced,
                            lastSyncError = row.last_sync_error,
                            isActive = row.is_active,
                            priority = row.priority,
                            channelCount = row.channel_count,
                            autoSyncInterval = row.auto_sync_interval,
                            epgPriority = row.epg_priority,
                            autoSyncOnStart = row.auto_sync_on_start,
                            createdAt = row.created_at,
                            updatedAt = row.updated_at,
                        )
                    }
            val payload =
                SourcesBackupFile(
                    schemaVersion = YancoDb.Schema.version.toInt(),
                    backupTime = System.currentTimeMillis(),
                    sources = rows,
                )
            // Atomic write — write-temp-then-rename so a crash mid-write
            // doesn't leave a half-written backup that fails to parse.
            val tmp = File(backupFile.parentFile, "${backupFile.name}.tmp")
            tmp.writeText(JSON.encodeToString(SourcesBackupFile.serializer(), payload))
            // File.renameTo overwrites on Android — atomic at the
            // filesystem level for files in the same directory.
            tmp.renameTo(backupFile)
        }.onFailure { t ->
            Log.w(TAG, "Sources backup write failed (non-fatal)", t)
        }
    }

    /**
     * Read the last-good sources backup. Returns null if no backup exists
     * (first launch, or backup file got deleted) or if the file fails to
     * parse (corrupted backup; better to lose sources than to crash on
     * launch).
     */
    fun read(): SourcesBackupFile? =
        runCatching {
            if (!backupFile.exists()) return@runCatching null
            JSON.decodeFromString(SourcesBackupFile.serializer(), backupFile.readText())
        }.getOrNull()

    /**
     * Re-insert the backed-up sources into a freshly-created DB. Called
     * by [DatabaseFactory] only after a corruption recovery has stood up
     * a new (empty) DB. Safe to call with an empty list.
     */
    fun restoreInto(
        db: YancoDb,
        sources: List<BackedUpSource>,
    ) {
        if (sources.isEmpty()) return
        db.sourcesQueries.transaction {
            sources.forEach { src ->
                db.sourcesQueries.insert(
                    id = src.id,
                    name = src.name,
                    type = src.type,
                    url = src.url,
                    file_path = src.filePath,
                    username_encrypted = src.usernameEncrypted,
                    password_encrypted = src.passwordEncrypted,
                    mac_address_encrypted = src.macAddressEncrypted,
                    epg_url = src.epgUrl,
                    user_agent = src.userAgent,
                    referer = src.referer,
                    last_synced = src.lastSynced,
                    last_sync_error = src.lastSyncError,
                    is_active = src.isActive,
                    priority = src.priority,
                    channel_count = src.channelCount,
                    auto_sync_interval = src.autoSyncInterval,
                    epg_priority = src.epgPriority,
                    auto_sync_on_start = src.autoSyncOnStart,
                    created_at = src.createdAt,
                    updated_at = src.updatedAt,
                )
            }
        }
        Log.i(TAG, "Sources backup restored: ${sources.size} source(s)")
    }

    private companion object {
        const val BACKUP_FILE_NAME = "sources-backup.json"
        const val TAG = "YancoSourcesBackup"
        val JSON = Json { prettyPrint = false; encodeDefaults = true }
    }
}

/**
 * One source row, serialized as JSON. Encrypted blobs ride along as raw
 * byte arrays (kotlinx.serialization renders them as JSON number arrays
 * by default). Per the contract above, the bytes are meaningless without
 * the matching Android Keystore entry — but the Keystore survives DB
 * deletion, so a restore round-trips usefully.
 */
@Serializable
internal data class BackedUpSource(
    val id: String,
    val name: String,
    val type: String,
    val url: String?,
    val filePath: String?,
    val usernameEncrypted: ByteArray?,
    val passwordEncrypted: ByteArray?,
    val macAddressEncrypted: ByteArray?,
    val epgUrl: String?,
    val userAgent: String?,
    // Stage 2.3 — added 2026-04-26. Default null so backups produced by
    // older builds (no `referer` column) still deserialise cleanly.
    val referer: String? = null,
    val lastSynced: Long?,
    val lastSyncError: String?,
    val isActive: Boolean,
    val priority: Long,
    val channelCount: Long,
    val autoSyncInterval: Long,
    // Stage 2.4 — added 2026-04-26. Default 0 so backups produced by older
    // builds (no `epg_priority` column) still deserialise cleanly.
    val epgPriority: Long = 0,
    // v9 → v10 — added 2026-04-27. Default false so backups produced by
    // older builds still deserialise cleanly.
    val autoSyncOnStart: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
) {
    // Data classes with ByteArray properties default to reference equality.
    // Override so equals(...) / hashCode() compare by content — useful for
    // tests and any future deduplication / diff logic.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BackedUpSource) return false
        return id == other.id &&
            name == other.name &&
            type == other.type &&
            url == other.url &&
            filePath == other.filePath &&
            usernameEncrypted.contentEqualsOrBothNull(other.usernameEncrypted) &&
            passwordEncrypted.contentEqualsOrBothNull(other.passwordEncrypted) &&
            macAddressEncrypted.contentEqualsOrBothNull(other.macAddressEncrypted) &&
            epgUrl == other.epgUrl &&
            userAgent == other.userAgent &&
            referer == other.referer &&
            lastSynced == other.lastSynced &&
            lastSyncError == other.lastSyncError &&
            isActive == other.isActive &&
            priority == other.priority &&
            channelCount == other.channelCount &&
            autoSyncInterval == other.autoSyncInterval &&
            epgPriority == other.epgPriority &&
            autoSyncOnStart == other.autoSyncOnStart &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int = id.hashCode() * 31 + updatedAt.hashCode()

    private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
        when {
            this == null && other == null -> true
            this == null || other == null -> false
            else -> this.contentEquals(other)
        }
}

/**
 * On-disk envelope for a sources backup. Carries the schema version that
 * produced the dump so a future change that's incompatible can detect the
 * mismatch and reject the restore (better to start fresh than to write
 * stale columns into a different schema).
 */
@Serializable
internal data class SourcesBackupFile(
    val schemaVersion: Int,
    val backupTime: Long,
    val sources: List<BackedUpSource>,
)
