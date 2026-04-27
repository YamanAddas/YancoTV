package com.yancotv.android.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.yancotv.android.BuildConfig
import com.yancotv.shared.backup.BackupCanonicalJson
import com.yancotv.shared.backup.BackupChecksumMismatchException
import com.yancotv.shared.backup.BackupDecryptException
import com.yancotv.shared.backup.BackupExporter
import com.yancotv.shared.backup.BackupFileV1
import com.yancotv.shared.backup.BackupImporter
import com.yancotv.shared.backup.BackupSchemaTooNewException
import com.yancotv.shared.backup.RestoreReport
import com.yancotv.shared.backup.sha256Hex
import com.yancotv.shared.db.YancoDb
import com.yancotv.android.sources.SourceSyncCoordinator
import com.yancotv.shared.sources.CredentialStore
import com.yancotv.shared.sources.SyncProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * MK.19.8.3 + 19.8.5 — Android-side glue between the SAF picker
 * results and the pure [BackupExporter] / [BackupImporter] engine.
 * Handles:
 *
 *   - Reading / writing the backup JSON via the SAF tree URI
 *   - Persisting a [BackupMetadata] row on every successful export
 *   - Wiring the file-existence check (MB-217) so imported recordings
 *     whose URIs don't resolve land as FAILED
 *
 * Exposed as a Koin singleton so the Settings screen can call it from
 * a coroutine without instantiating the engine itself.
 */
class BackupCoordinator(
    private val context: Context,
    private val db: YancoDb,
    private val credentialStore: CredentialStore,
    syncCoordinator: SourceSyncCoordinator,
) {
    /**
     * MK.19.8.4 — most-recent importer kept around so [retryPendingLinks]
     * can drain its buffer when the next source-sync completes. Cleared
     * after [MAX_RETRY_PASSES] attempts so we don't retry forever.
     */
    private var pendingImporter: com.yancotv.shared.backup.BackupImporter? = null
    private var retriesRemaining = 0

    /** Diagnostic stream for the Settings tab — emits the latest pending count. */
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // MK.19.8.4 — observe sync-coordinator state. When a source
        // finishes (phase = DONE) and an importer has buffered records
        // waiting to relink, drain the buffer. Cap the number of retry
        // passes so a stuck import doesn't churn forever — `MAX_RETRY_PASSES`
        // matches the v1 spec (3 passes, then surface "couldn't link").
        syncCoordinator.state
            .map { it?.progress?.phase }
            .distinctUntilChanged()
            .filter { it == SyncProgress.Phase.DONE }
            .onEach { onSourceSyncComplete() }
            .launchIn(scope)
    }
    /**
     * Build a backup for the current DB and write it to [destination].
     * Returns the [BackupFileV1] (for caller-side reporting); the
     * [BackupMetadata] row tracking this export is persisted as a
     * side-effect.
     */
    suspend fun export(
        destination: Uri,
        password: String?,
        label: String?,
    ): ExportResult =
        withContext(Dispatchers.IO) {
            val exporter = BackupExporter(db, credentialStore)
            val file =
                exporter.export(
                    appVersion = BuildConfig.VERSION_NAME,
                    dbSchemaVersion = YancoDb.Schema.version.toInt(),
                    nowMs = System.currentTimeMillis(),
                    password = password,
                )

            // Pretty-print to disk so the user can eyeball the file in
            // a text editor; checksum is over the compact form (already
            // computed inside exporter.export and stored on the file).
            val pretty = BackupCanonicalJson.encodePretty(file)
            val bytes = pretty.encodeToByteArray()

            context.contentResolver.openOutputStream(destination, "w")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: error("ContentResolver returned null OutputStream for $destination")

            // MK.19.8.5 — record the export in BackupMetadata so the
            // Settings tab can show "your last 3 backups" and the user
            // has a forensics trail. SHA-256 over the on-disk bytes
            // (mirrors what the importer would recompute) — distinct
            // from file.checksum which is over `records` only.
            val onDiskChecksum = sha256Hex(bytes)
            val countsJson = BackupCanonicalJson.encodeCompact(file.recordCounts)
            db.backupMetadataQueries.insert(
                id = "bk-${file.createdAt}",
                file_uri = destination.toString(),
                label = label?.takeIf { it.isNotBlank() } ?: "(no label)",
                schema_version = file.dbSchemaVersion.toLong(),
                checksum = onDiskChecksum,
                size_bytes = bytes.size.toLong(),
                record_counts = countsJson,
                notes = null,
                created_at = file.createdAt,
            )

            ExportResult.Success(file = file, bytesWritten = bytes.size.toLong())
        }

    /**
     * Read [source] as a [BackupFileV1] and apply via [BackupImporter].
     * The file-existence check for recordings is wired to a real
     * `ContentResolver.openInputStream` probe so MB-217 catches missing
     * files at import time.
     */
    suspend fun import(
        source: Uri,
        password: String?,
    ): ImportResult =
        withContext(Dispatchers.IO) {
            val text =
                runCatching {
                    context.contentResolver.openInputStream(source)?.use { it.readBytes().decodeToString() }
                }.getOrElse { return@withContext ImportResult.IoError("could not open $source: ${it.message}") }
                    ?: return@withContext ImportResult.IoError("ContentResolver returned null InputStream for $source")

            val file =
                runCatching { BackupCanonicalJson.decodeBackupFile(text) }
                    .getOrElse { return@withContext ImportResult.MalformedJson(it.message ?: "JSON parse failed") }

            val importer =
                BackupImporter(
                    db = db,
                    credentialStore = credentialStore,
                    recordingFileExists = { uri ->
                        runCatching {
                            context.contentResolver
                                .openInputStream(Uri.parse(uri))
                                ?.use { /* close immediately */ }
                            true
                        }.getOrElse { false }
                    },
                )
            try {
                val report = importer.import(file, password = password, currentSchemaVersion = YancoDb.Schema.version.toInt())
                // MK.19.8.4 — register importer for source-sync-driven
                // retry IF anything actually buffered. No buffer →
                // nothing to retry → don't keep a reference around.
                val unlinked = report.unlinked.values.sum()
                if (unlinked > 0) {
                    pendingImporter = importer
                    retriesRemaining = MAX_RETRY_PASSES
                    _pendingCount.value = unlinked
                } else {
                    pendingImporter = null
                    retriesRemaining = 0
                    _pendingCount.value = 0
                }
                ImportResult.Success(report = report, importer = importer, file = file)
            } catch (e: BackupChecksumMismatchException) {
                Log.w(TAG, "checksum mismatch on $source", e)
                ImportResult.ChecksumMismatch
            } catch (e: BackupSchemaTooNewException) {
                Log.w(TAG, "schema too new", e)
                ImportResult.SchemaTooNew(backupVersion = e.backupVersion, currentVersion = e.currentVersion)
            } catch (e: BackupDecryptException) {
                Log.w(TAG, "decrypt failed", e)
                ImportResult.DecryptFailed(e.message ?: "credential decryption failed")
            } catch (e: Throwable) {
                Log.e(TAG, "unexpected import failure", e)
                ImportResult.UnexpectedError(e.message ?: e::class.simpleName ?: "unknown")
            }
        }

    /**
     * Called by the sync-coordinator subscription when a source
     * completes its catalog sync. Drains the buffered importer (if any)
     * and decrements the retry budget. Public for unit-testing.
     */
    fun onSourceSyncComplete() {
        val importer = pendingImporter ?: return
        if (retriesRemaining <= 0) {
            // Budget exhausted — surface remaining count for the UI to
            // show "couldn't link" but stop trying.
            pendingImporter = null
            return
        }
        retriesRemaining--
        scope.launch {
            val report = runCatching { importer.retryPendingLinks() }.getOrNull()
            val stillPending = report?.unlinked?.values?.sum() ?: 0
            _pendingCount.value = stillPending
            Log.i(
                TAG,
                "retryPendingLinks restored=${report?.totalRestored} pending=$stillPending budget=$retriesRemaining",
            )
            if (stillPending == 0 || retriesRemaining <= 0) {
                pendingImporter = null
            }
        }
    }

    private companion object {
        const val TAG = "YancoBackup"
        const val MAX_RETRY_PASSES = 3
    }
}

sealed interface ExportResult {
    data class Success(val file: BackupFileV1, val bytesWritten: Long) : ExportResult

    data class Failed(val message: String) : ExportResult
}

sealed interface ImportResult {
    data class Success(
        val report: RestoreReport,
        val importer: BackupImporter,
        val file: BackupFileV1,
    ) : ImportResult

    data object ChecksumMismatch : ImportResult

    data class SchemaTooNew(val backupVersion: Int, val currentVersion: Int) : ImportResult

    data class DecryptFailed(val message: String) : ImportResult

    data class MalformedJson(val message: String) : ImportResult

    data class IoError(val message: String) : ImportResult

    data class UnexpectedError(val message: String) : ImportResult
}

/** Small accessor — Settings UI calls this when password is needed. */
fun BackupFileV1.isPasswordEncrypted(): Boolean = encryption != null
