package com.yancotv.android.recording

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.RecordingStorageMode
import com.yancotv.shared.recording.RecordingFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.buffered

/**
 * Stage 3.1 / MK.14.2-storage (audit-revised) — abstracts "where does
 * this recording's bytes land?" so [RecordingService] doesn't care which
 * destination strategy the user picked.
 *
 * Three implementations correspond to [RecordingStorageMode]:
 *
 *   - [FileBackedOutput] — a regular [File].
 *     • For [RecordingStorageMode.APP_PRIVATE]: under
 *       `getExternalFilesDir(MOVIES)/yanco-recordings/`. Wiped on
 *       uninstall (Android contract for app-specific external dirs).
 *     • For [RecordingStorageMode.PUBLIC_MEDIA_STORE] on API ≤28: under
 *       `/storage/emulated/0/Movies/YancoTV/`, requires
 *       `WRITE_EXTERNAL_STORAGE`. Survives uninstall.
 *   - [DocumentBackedOutput] — a [DocumentFile] under a SAF tree URI.
 *     For [RecordingStorageMode.CUSTOM_SAF]. Survives uninstall (file
 *     side); YancoTV-side metadata (recordings table rows + the
 *     persisted URI grant itself) does NOT carry across reinstall.
 *   - [MediaStoreRecordingOutput] — a row inserted into
 *     `MediaStore.Video.Media` with `RELATIVE_PATH = Movies/YancoTV`.
 *     For [RecordingStorageMode.PUBLIC_MEDIA_STORE] on API 29+. Zero
 *     permissions required under scoped storage; file survives uninstall
 *     but loses YancoTV ownership attribution at that point.
 *
 * The `storagePath` stored in `recordings.file_path` is either an
 * absolute filesystem path (FileBackedOutput) or a `content://` URI
 * (the other two). ExoPlayer accepts both via `DefaultDataSource.Factory`'s
 * scheme routing.
 */
/**
 * MB-218 — extends [AutoCloseable] so post-write finalisation runs via
 * `output.use { … }` or an explicit `close()`. The MediaStore backend's
 * `IS_PENDING=0` flip lives in [close]; File / SAF backends use the
 * default no-op. Pre-MB-218 the call site had to remember a separate
 * `onFinalize()` invocation; forgetting left MediaStore files invisible
 * in Gallery.
 */
sealed interface RecordingOutput : AutoCloseable {
    /** What goes into `recordings.file_path` for later playback / display. */
    val storagePath: String

    /** Buffered sink the recorder writes into. Caller closes via Sink.close(). */
    fun openSink(): Sink

    /**
     * Buffered [OutputStream] handle. Used by MK.14.8's
     * [RecordingDataSink] (which works in `byte[]` chunks, not the
     * kotlinx-io [Sink] surface). Caller takes ownership and closes the
     * stream when the recording ends. Backed by the same underlying
     * file or SAF descriptor as [openSink] — calling both on the same
     * [RecordingOutput] is undefined and not done in production.
     */
    fun openOutputStream(): OutputStream

    /** On-disk byte count after the sink is closed. Used by [RecordingService.handleStop]
     *  to decide between markCompleted (>0) and silent delete (0). 0 on error. */
    fun size(): Long

    /** Best-effort delete; called by the eviction path or user "delete recording". */
    fun delete(): Boolean

    /**
     * Called AFTER the sink/stream has been closed and bytes are flushed
     * to disk. Default no-op for File / SAF backends. The MediaStore
     * backend overrides to flip `IS_PENDING = 0` so the file becomes
     * visible in Gallery / Photos / file manager apps.
     */
    override fun close() {}
}

internal class FileBackedOutput(val file: File) : RecordingOutput {
    override val storagePath: String get() = file.absolutePath

    override fun openSink(): Sink = FileOutputStream(file).asSink().buffered()

    override fun openOutputStream(): OutputStream = BufferedOutputStream(FileOutputStream(file))

    override fun size(): Long = runCatching { file.length() }.getOrDefault(0L)

    override fun delete(): Boolean = file.delete()
}

internal class DocumentBackedOutput(private val context: Context, val documentFile: DocumentFile) : RecordingOutput {
    override val storagePath: String get() = documentFile.uri.toString()

    override fun openSink(): Sink {
        val stream =
            context.contentResolver.openOutputStream(documentFile.uri, "w")
                ?: error("ContentResolver returned null OutputStream for ${documentFile.uri}")
        return stream.asSink().buffered()
    }

    override fun openOutputStream(): OutputStream {
        val stream =
            context.contentResolver.openOutputStream(documentFile.uri, "w")
                ?: error("ContentResolver returned null OutputStream for ${documentFile.uri}")
        return BufferedOutputStream(stream)
    }

    override fun size(): Long = runCatching { documentFile.length() }.getOrDefault(0L)

    override fun delete(): Boolean = runCatching { documentFile.delete() }.getOrDefault(false)
}

/**
 * MK.14.X audit revision — MediaStore-backed output for API 29+.
 *
 * Rows inserted into `MediaStore.Video.Media` with `RELATIVE_PATH =
 * Movies/YancoTV` and `IS_PENDING = 1` so the partial file isn't
 * exposed to other apps mid-write. [close] flips `IS_PENDING = 0`
 * once the recorder has closed the stream.
 *
 * Note on uninstall: the underlying file remains in `Movies/YancoTV/`
 * (Android's media contract) but loses YancoTV's authorship attribution.
 * On reinstall we'd see "owner unknown" rows; reading them back may
 * require `READ_MEDIA_VIDEO` on API 33+. Out of scope for the v1.0 fix.
 */
internal class MediaStoreRecordingOutput(private val context: Context, val uri: Uri, private val markPendingOnFinalize: Boolean) : RecordingOutput {
    override val storagePath: String get() = uri.toString()

    override fun openSink(): Sink {
        val stream =
            context.contentResolver.openOutputStream(uri, "w")
                ?: error("ContentResolver returned null OutputStream for $uri")
        return stream.asSink().buffered()
    }

    override fun openOutputStream(): OutputStream {
        val stream =
            context.contentResolver.openOutputStream(uri, "w")
                ?: error("ContentResolver returned null OutputStream for $uri")
        return BufferedOutputStream(stream)
    }

    override fun size(): Long = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(0L) else 0L
            } ?: 0L
    }.getOrDefault(0L)

    override fun delete(): Boolean = runCatching {
        context.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)

    override fun close() {
        // IS_PENDING is API 29+. On API ≤28 this path doesn't exist (we
        // use FileBackedOutput on legacy), so the guard is defensive.
        if (!markPendingOnFinalize) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val values =
                ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
            context.contentResolver.update(uri, values, null, null)
        }
    }
}

/**
 * Resolves a [RecordingOutput] for a given record by branching on
 * [com.yancotv.android.prefs.RecordingPrefs.storageMode]:
 *
 *   - [RecordingStorageMode.PUBLIC_MEDIA_STORE] — MediaStore on API
 *     29+ (zero permission), direct File on API ≤28 (requires
 *     `WRITE_EXTERNAL_STORAGE`). Throws a clear error if the
 *     permission is missing on legacy.
 *   - [RecordingStorageMode.APP_PRIVATE] — `getExternalFilesDir(MOVIES)`.
 *     Always works.
 *   - [RecordingStorageMode.CUSTOM_SAF] — SAF tree URI. If the
 *     persisted permission has lapsed (user revoked, volume
 *     unmounted), [onPermissionLost] fires and we fall back to
 *     [RecordingStorageMode.APP_PRIVATE].
 *
 * Resolution failures throw — [RecordingService.resolveOutputOrFail]
 * catches and marks the row failed. The caller then owns the user-facing
 * error surface.
 */
internal class RecordingStorageResolver(private val context: Context, private val prefs: AppPreferences) {
    suspend fun resolve(recordId: String, title: String, format: RecordingFormat, onPermissionLost: suspend () -> Unit = {}): RecordingOutput {
        val recording = prefs.recordingFlow.value
        val ext = extensionFor(format)
        val friendlyName = friendlyFilename(title, recordId) + "." + ext

        return when (recording.storageMode) {
            RecordingStorageMode.PUBLIC_MEDIA_STORE -> resolvePublic(recordId, ext, friendlyName)
            RecordingStorageMode.APP_PRIVATE -> resolveAppPrivate(recordId, ext)
            RecordingStorageMode.CUSTOM_SAF ->
                resolveCustomSaf(
                    customRoot = recording.folderUri,
                    filename = friendlyName,
                    onPermissionLost = onPermissionLost,
                    fallbackRecordId = recordId,
                    fallbackExt = ext,
                )
        }
    }

    /**
     * Resolve the Public mode destination, falling back to app-private if
     * the active strategy can't allocate a file:
     *   - API ≤28 needs `WRITE_EXTERNAL_STORAGE` for the legacy direct-file
     *     path. If the user hasn't granted it yet, fall back silently —
     *     recordings keep working in app-private until they grant from
     *     Settings → Recordings → Public folder. That's the audit-driven
     *     "never break recording on the broken-picker bug" guarantee.
     *   - API 29+ MediaStore insert can fail for other reasons (provider
     *     quota, no writable volume, OEM differences). Same fallback —
     *     better to record in a less-discoverable place than to record
     *     nothing.
     *
     * Without this fallback the resolver throws before
     * `RecordingService.handleStartLiveTee` has inserted the row, so the
     * failure is invisible to the user — no row in Recordings list AND
     * `activeJobs` stays empty so the player options sheet shows
     * "Record" instead of "Stop recording". Verified on Fire TV API 28
     * post-MK.14.X migration.
     */
    private fun resolvePublic(recordId: String, ext: String, filename: String): RecordingOutput = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolvePublicMediaStore(filename)
        } else if (hasWriteExternalStoragePermission()) {
            resolvePublicLegacy(filename)
        } else {
            // Throw into the fallback path below — same handling as
            // a MediaStore.insert returning null on API 29+.
            error(
                "WRITE_EXTERNAL_STORAGE not granted on API ${Build.VERSION.SDK_INT}; " +
                    "Public mode falls back to app-private until the user grants from Settings.",
            )
        }
    }.getOrElse { t ->
        Log.w(
            TAG,
            "Public mode allocation failed (${t.message}); falling back to app-private. " +
                "Recording will save inside YancoTV's app data dir instead of Movies/YancoTV/.",
        )
        resolveAppPrivate(recordId, ext)
    }

    /**
     * API 29+: scoped-storage MediaStore insert. No permission required.
     * Sets `IS_PENDING = 1` so partial files aren't exposed mid-write —
     * [MediaStoreRecordingOutput.close] flips it to 0 on stop.
     */
    private fun resolvePublicMediaStore(filename: String): RecordingOutput {
        val values =
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.MIME_TYPE, MIME_MPEG_TS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/$PUBLIC_DIR_NAME",
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        val uri =
            context.contentResolver.insert(collection, values)
                ?: error(
                    "MediaStore.insert returned null for $filename — provider rejected the " +
                        "insert. Mode public_media_store on API ${Build.VERSION.SDK_INT}.",
                )
        return MediaStoreRecordingOutput(context, uri, markPendingOnFinalize = true)
    }

    /**
     * API ≤28: direct File writes to `/storage/emulated/0/Movies/YancoTV/`.
     * Requires `WRITE_EXTERNAL_STORAGE` (declared with
     * `maxSdkVersion="28"` in the manifest). Throws if the permission
     * isn't granted — caller path will markFailed and the UI explains
     * the user has to grant it before Public mode works on Fire TV.
     */
    private fun resolvePublicLegacy(filename: String): RecordingOutput {
        if (!hasWriteExternalStoragePermission()) {
            error(
                "WRITE_EXTERNAL_STORAGE not granted — required for public Movies/$PUBLIC_DIR_NAME on " +
                    "API ${Build.VERSION.SDK_INT}. Switch to App-private mode or grant the permission.",
            )
        }
        @Suppress("DEPRECATION")
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val publicDir = File(moviesDir, PUBLIC_DIR_NAME)
        if (!publicDir.exists() && !publicDir.mkdirs()) {
            error("Failed to create public Movies directory: ${publicDir.absolutePath}")
        }
        return FileBackedOutput(File(publicDir, filename))
    }

    private fun resolveAppPrivate(recordId: String, ext: String): RecordingOutput {
        val baseDir =
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: error("No external Movies dir available — device storage unmounted?")
        val recordingsDir = File(baseDir, DEFAULT_DIR_NAME)
        if (!recordingsDir.exists() && !recordingsDir.mkdirs()) {
            error("Failed to create recordings directory: ${recordingsDir.absolutePath}")
        }
        // For app-private paths use the slug-style id-based filename —
        // it's not user-visible; readability doesn't matter and the
        // shorter name keeps `adb shell ls` output clean.
        val slug = recordId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return FileBackedOutput(File(recordingsDir, "$slug.$ext"))
    }

    private suspend fun resolveCustomSaf(
        customRoot: String?,
        filename: String,
        onPermissionLost: suspend () -> Unit,
        fallbackRecordId: String,
        fallbackExt: String,
    ): RecordingOutput {
        if (customRoot == null) {
            // CUSTOM_SAF mode but no URI persisted yet. Fall back to
            // app-private rather than failing — the Settings UI prompts
            // the user to pick a folder; until then, recordings still
            // work just somewhere unobtrusive.
            return resolveAppPrivate(fallbackRecordId, fallbackExt)
        }
        val tree =
            runCatching { Uri.parse(customRoot) }.getOrNull()
                ?.let { uri ->
                    if (hasPersistedPermission(uri)) {
                        DocumentFile.fromTreeUri(context, uri)
                    } else {
                        null
                    }
                }
        if (tree != null) {
            val doc =
                tree.createFile(MIME_MPEG_TS, filename)
                    ?: tree.createFile("application/octet-stream", filename)
            if (doc != null) return DocumentBackedOutput(context, doc)
        }
        // Permission lost or createFile failed — clear the pref and
        // fall back to app-private. The UI will reflect the clearedmode
        // on next render via its prefs flow.
        onPermissionLost()
        return resolveAppPrivate(fallbackRecordId, fallbackExt)
    }

    private fun hasWriteExternalStoragePermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasPersistedPermission(uri: Uri): Boolean = context.contentResolver.persistedUriPermissions
        .any { it.uri == uri && it.isReadPermission && it.isWritePermission }

    /**
     * Build a friendly filename for visible folders (SAF / MediaStore /
     * legacy public). Sanitises characters that file managers / SAF
     * backends sometimes choke on (`/ \ : * ? " < > |`), trims to a sane
     * length, appends a timestamp + a 4-char id suffix for uniqueness.
     *
     * Result: `BeIN Sport HD - 2026-04-26 22-08 - 19dc`.
     * Caller adds the extension.
     */
    private fun friendlyFilename(title: String, recordId: String): String {
        val sanitized =
            title
                .replace(Regex("""[/\\:*?"<>|\n\r\t]+"""), " ")
                .trim()
                .take(80)
                .ifBlank { "Recording" }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date())
        val idSuffix =
            recordId
                .reversed()
                .takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
                .reversed()
                .takeLast(4)
        return "$sanitized - $timestamp - $idSuffix"
    }

    private fun extensionFor(format: RecordingFormat): String = when (format) {
        RecordingFormat.HLS -> "ts"
        RecordingFormat.MPEG_TS -> "ts"
    }

    companion object {
        const val DEFAULT_DIR_NAME = "yanco-recordings"
        const val PUBLIC_DIR_NAME = "YancoTV"
        const val MIME_MPEG_TS = "video/mp2t"
        private const val TAG = "YancoStorageResolver"
    }
}
