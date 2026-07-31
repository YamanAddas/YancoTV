package com.yancotv.android.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yancotv.android.BuildConfig
import com.yancotv.android.R
import com.yancotv.android.sources.SourceSyncCoordinator
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
import com.yancotv.shared.sources.CredentialStore
import com.yancotv.shared.sources.SyncProgress
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
    private val syncCoordinator: SourceSyncCoordinator,
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
     * Default export path — writes to the system's public Downloads
     * folder (`MediaStore.Downloads` on API 29+, direct file write to
     * `Environment.DIRECTORY_DOWNLOADS` on API ≤28). The file survives
     * uninstall and is visible in any file manager / Files app.
     *
     * Used when the user hasn't explicitly picked a backup folder
     * via the Settings → Backup → "Change folder…" button.
     */
    suspend fun exportToDefault(filename: String, password: String?, label: String?): ExportResult = withContext(Dispatchers.IO) {
        val (file, bytes) = buildBackupBytes(password)
        val (storageUriString, sizeBytes) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToMediaStoreDownloads(filename, bytes)
                    ?: return@withContext ExportResult.Failed(
                        context.getString(R.string.bc_downloads_insert_failed),
                    )
            } else {
                writeToPublicDownloadsLegacy(filename, bytes)
                    ?: return@withContext ExportResult.Failed(
                        context.getString(R.string.bc_no_writable_location),
                    )
            }
        persistMetadata(file, storageUriString, sizeBytes, label)
        ExportResult.Success(file = file, bytesWritten = sizeBytes)
    }

    // MB-294 — annotated rather than guarded again: the sole caller already
    // gates this on `SDK_INT >= Q` (see above), but lint cannot see through
    // the call boundary and flagged `MediaStore.Downloads.EXTERNAL_CONTENT_URI`
    // (API 29) as an unguarded NewApi error. @RequiresApi states the contract
    // for both lint and the next reader. Behaviour is unchanged.
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToMediaStoreDownloads(filename: String, bytes: ByteArray): Pair<String, Long>? {
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YancoTV")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return runCatching {
            context.contentResolver.openOutputStream(uri, "w")?.use {
                it.write(bytes)
                it.flush()
            }
                ?: error("openOutputStream returned null")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            uri.toString() to bytes.size.toLong()
        }.getOrElse {
            // Best-effort cleanup of the half-written row.
            runCatching { context.contentResolver.delete(uri, null, null) }
            Log.e(TAG, "MediaStore.Downloads write failed", it)
            null
        }
    }

    /**
     * API ≤28 export path. Tries the user-visible public Downloads
     * folder first; falls back to app-private external storage when
     * that fails — which is the common case on Fire TV / Fire OS 7
     * (API 28) because `WRITE_EXTERNAL_STORAGE` is declared in the
     * manifest with `maxSdkVersion=28` but never requested at
     * runtime, so the OS denies the write with `SecurityException`.
     *
     * The fallback path `/sdcard/Android/data/com.yancotv.android/files/Download/YancoTV/`
     * needs no permission on any API level, survives reboot, and is
     * still pullable via `adb pull` (which is the recommended
     * off-device transfer for Fire TV anyway — the device has no
     * built-in file manager). It's wiped on app uninstall, so the
     * "Restore" flow should be exercised before uninstalling.
     */
    private fun writeToPublicDownloadsLegacy(filename: String, bytes: ByteArray): Pair<String, Long>? {
        // First attempt: the user-visible public Downloads folder.
        @Suppress("DEPRECATION")
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val publicTarget = File(publicDownloads, "YancoTV").also { runCatching { it.mkdirs() } }
        val publicAttempt = runCatching {
            val file = File(publicTarget, filename)
            FileOutputStream(file).use {
                it.write(bytes)
                it.flush()
            }
            Uri.fromFile(file).toString() to file.length()
        }
        if (publicAttempt.isSuccess) return publicAttempt.getOrThrow()
        Log.w(
            TAG,
            "Public Downloads write failed on API ${Build.VERSION.SDK_INT} (likely " +
                "WRITE_EXTERNAL_STORAGE not granted — declared with maxSdkVersion=28 but " +
                "never requested at runtime). Falling back to app-private external storage.",
            publicAttempt.exceptionOrNull(),
        )

        // Fallback: app-private external storage. Needs no permission.
        // Survives reboot, wiped on uninstall — fine for backup export
        // where the recommended flow is "export then transfer
        // off-device immediately."
        val appPrivateDownloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val privateTarget = File(appPrivateDownloads, "YancoTV").also { runCatching { it.mkdirs() } }
        return runCatching {
            val file = File(privateTarget, filename)
            FileOutputStream(file).use {
                it.write(bytes)
                it.flush()
            }
            Uri.fromFile(file).toString() to file.length()
        }.getOrElse {
            Log.e(TAG, "App-private external storage write also failed", it)
            null
        }
    }

    /**
     * Build a backup and write it as `[filename]` inside [folderUri]
     * (an SAF tree URI from `ACTION_OPEN_DOCUMENT_TREE`). Fire TV's
     * file picker handles `OPEN_DOCUMENT_TREE` cleanly with D-pad —
     * unlike `CREATE_DOCUMENT` which can trap focus on the Save
     * button. We mirror the recording-folder pattern from
     * [com.yancotv.android.recording.RecordingStorageResolver].
     */
    suspend fun export(folderUri: Uri, filename: String, password: String?, label: String?): ExportResult = withContext(Dispatchers.IO) {
        val (file, bytes) = buildBackupBytes(password)

        // Resolve the tree URI to a writable DocumentFile, create
        // (or replace) the named file inside, write bytes.
        val tree =
            DocumentFile.fromTreeUri(context, folderUri)
                ?: return@withContext ExportResult.Failed(context.getString(R.string.bc_cant_open_folder))
        // If a file with the same name already exists, delete it
        // first — SAF createFile silently appends "(1)" suffixes
        // otherwise, which fragments the user's backup folder.
        tree.findFile(filename)?.delete()
        val doc =
            tree.createFile("application/json", filename)
                ?: return@withContext ExportResult.Failed(context.getString(R.string.bc_cant_create_file, filename))

        context.contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: return@withContext ExportResult.Failed(
            context.getString(R.string.bc_cant_open_for_writing),
        )

        persistMetadata(file, doc.uri.toString(), bytes.size.toLong(), label)
        ExportResult.Success(file = file, bytesWritten = bytes.size.toLong())
    }

    /** Build the BackupFileV1 + canonical-pretty-JSON bytes once, share across export paths. */
    private fun buildBackupBytes(password: String?): Pair<BackupFileV1, ByteArray> {
        val exporter = BackupExporter(db, credentialStore)
        val file =
            exporter.export(
                appVersion = BuildConfig.VERSION_NAME,
                dbSchemaVersion = YancoDb.Schema.version.toInt(),
                nowMs = System.currentTimeMillis(),
                password = password,
            )
        val pretty = BackupCanonicalJson.encodePretty(file)
        return file to pretty.encodeToByteArray()
    }

    /** Insert a BackupMetadata row tracking the just-written export. */
    private fun persistMetadata(file: BackupFileV1, fileUri: String, sizeBytes: Long, label: String?) {
        // SHA-256 over the on-disk bytes — distinct from file.checksum
        // (which is over the `records` block only). The on-disk
        // checksum lets a future "verify integrity" feature catch
        // out-of-band edits to the file.
        val onDiskChecksum = sha256Hex(file.let { BackupCanonicalJson.encodePretty(it).encodeToByteArray() })
        val countsJson = BackupCanonicalJson.encodeCompact(file.recordCounts)
        db.backupMetadataQueries.insert(
            id = "bk-${file.createdAt}",
            file_uri = fileUri,
            label = label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.bc_no_label),
            schema_version = file.dbSchemaVersion.toLong(),
            checksum = onDiskChecksum,
            size_bytes = sizeBytes,
            record_counts = countsJson,
            notes = null,
            created_at = file.createdAt,
        )
    }

    /**
     * Read [source] as a [BackupFileV1] and apply via [BackupImporter].
     * The file-existence check for recordings is wired to a real
     * `ContentResolver.openInputStream` probe so MB-217 catches missing
     * files at import time.
     */
    suspend fun import(source: Uri, password: String?): ImportResult = withContext(Dispatchers.IO) {
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

                // v1.1.0 — auto-kick a source sync for every source that
                // landed in this restore. The Restore UI text promises
                // "your sources will resync" but the prior implementation
                // only set up the post-sync-completion observer and
                // depended on the user manually triggering a sync.
                // Real-world reports: users restored, saw "33 pending
                // source resync" in the status line, then nothing
                // happened until they navigated to Sources and tapped
                // Sync. Now we do it for them.
                //
                // SyncCoordinator is single-slot (refuses a concurrent
                // start), so we chain through the sources sequentially
                // — fire the first sync, observe the state machine, fire
                // the next when state returns to null (which happens in
                // SyncCoordinator's `finally` after a sync completes or
                // crashes). All in scope = app-scoped so the chain
                // survives the user navigating away.
                kickRestoreSyncChain()
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
     * Kick a sync for every source currently in the DB, sequentially.
     * Called from [import] after a successful restore that left pending
     * records — so the user doesn't have to manually fire each source
     * sync to get their watch history / favorites / parental controls
     * back. Waits for `syncCoordinator.state` to return to null between
     * sources because the coordinator is single-slot and rejects a
     * concurrent start. Runs in the app-scoped [scope] so it survives
     * UI navigation.
     */
    private fun kickRestoreSyncChain() {
        scope.launch {
            val sources =
                runCatching {
                    db.sourcesQueries.selectAll().executeAsList()
                }.getOrNull().orEmpty()
            for (source in sources) {
                // Single-slot coordinator: wait for any in-flight sync
                // to clear before starting the next.
                while (syncCoordinator.state.value != null) {
                    delay(250)
                }
                Log.i(TAG, "post-restore auto-sync kicking source ${source.id} (${source.name})")
                syncCoordinator.start(source.id, source.name)
            }
        }
    }

    /**
     * User-initiated retry — surfaced as a "Retry pending links" button
     * in the Backup tab. Forces a `retryPendingLinks` pass on the most
     * recent importer without waiting for a sync-coordinator emission.
     * Useful when the auto-retry observer missed an event, or when the
     * user wants to verify "still N pending" status hasn't moved.
     */
    fun retryPendingLinksNow() {
        val importer = pendingImporter ?: return
        scope.launch {
            val report = runCatching { importer.retryPendingLinks() }.getOrNull()
            val stillPending = report?.unlinked?.values?.sum() ?: 0
            _pendingCount.value = stillPending
            Log.i(TAG, "manual retryPendingLinks restored=${report?.totalRestored} pending=$stillPending")
            if (stillPending == 0) {
                pendingImporter = null
                retriesRemaining = 0
            }
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
    data class Success(val report: RestoreReport, val importer: BackupImporter, val file: BackupFileV1) : ImportResult

    data object ChecksumMismatch : ImportResult

    data class SchemaTooNew(val backupVersion: Int, val currentVersion: Int) : ImportResult

    data class DecryptFailed(val message: String) : ImportResult

    data class MalformedJson(val message: String) : ImportResult

    data class IoError(val message: String) : ImportResult

    data class UnexpectedError(val message: String) : ImportResult
}

/** Small accessor — Settings UI calls this when password is needed. */
fun BackupFileV1.isPasswordEncrypted(): Boolean = encryption != null
