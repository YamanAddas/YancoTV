package com.yancotv.android.recording

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.shared.recording.RecordingFormat
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stage 3.1 / MK.14.2-storage — abstracts "where does this recording's
 * bytes land?" so the [RecordingService] doesn't care whether the user
 * has configured a custom folder via SAF or is using the app-private
 * default.
 *
 * Two implementations:
 *   - [FileBackedOutput] — a regular [File] under
 *     `getExternalFilesDir(MOVIES)`. Fast (direct fd writes), zero
 *     permissions, but invisible to most file managers (lives under
 *     `/Android/data/com.yancotv.android/`).
 *   - [DocumentBackedOutput] — a [DocumentFile] under a user-picked
 *     SAF tree. Visible to file managers; survives uninstall. Slightly
 *     slower because writes go through `ContentResolver`, but
 *     fast enough for ~5–20 Mbps streams.
 *
 * The storagePath stored in `recordings.file_path` is either an
 * absolute filesystem path (FileBackedOutput) or a `content://` URI
 * (DocumentBackedOutput). ExoPlayer accepts both via the existing
 * `PlayerLauncher` path.
 */
sealed interface RecordingOutput {
    /** What goes into `recordings.file_path` for later playback / display. */
    val storagePath: String

    /** Buffered sink the recorder writes into. Caller closes via Sink.close(). */
    fun openSink(): Sink

    /** Best-effort delete; called by the eviction path or user "delete recording". */
    fun delete(): Boolean
}

internal class FileBackedOutput(val file: File) : RecordingOutput {
    override val storagePath: String get() = file.absolutePath

    override fun openSink(): Sink = FileOutputStream(file).asSink().buffered()

    override fun delete(): Boolean = file.delete()
}

internal class DocumentBackedOutput(
    private val context: Context,
    val documentFile: DocumentFile,
) : RecordingOutput {
    override val storagePath: String get() = documentFile.uri.toString()

    override fun openSink(): Sink {
        val stream =
            context.contentResolver.openOutputStream(documentFile.uri, "w")
                ?: error("ContentResolver returned null OutputStream for ${documentFile.uri}")
        return stream.asSink().buffered()
    }

    override fun delete(): Boolean = runCatching { documentFile.delete() }.getOrDefault(false)
}

/**
 * Resolves a [RecordingOutput] for a given record. Reads the user's
 * recording-folder pref from [AppPreferences]:
 *   - If a SAF tree URI is set AND we still hold persistable read+write
 *     permission for it, build a child [DocumentFile] in that tree.
 *   - Otherwise fall back to app-private external storage. Always
 *     succeeds on a device with mounted external storage.
 *
 * Tree-URI permission can lapse: user revokes via system Settings, the
 * containing volume is unmounted (USB drive), or a system update
 * cleans up. We re-validate on every recording start; a stale URI
 * silently falls back to default + clears the pref via [onPermissionLost].
 */
internal class RecordingStorageResolver(
    private val context: Context,
    private val prefs: AppPreferences,
) {
    suspend fun resolve(
        recordId: String,
        title: String,
        format: RecordingFormat,
        onPermissionLost: suspend () -> Unit = {},
    ): RecordingOutput {
        val customRoot = prefs.recordingFlow.value.folderUri
        val ext = extensionFor(format)
        val filename = friendlyFilename(title, recordId) + "." + ext

        if (customRoot != null) {
            val tree =
                runCatching { Uri.parse(customRoot) }.getOrNull()
                    ?.let { uri ->
                        if (hasPersistedPermission(uri)) {
                            DocumentFile.fromTreeUri(context, uri)
                        } else {
                            null
                        }
                    }
            val parent = tree
            if (parent != null) {
                val doc =
                    parent.createFile(MIME_MPEG_TS, filename)
                        ?: parent.createFile("application/octet-stream", filename)
                if (doc != null) return DocumentBackedOutput(context, doc)
            }
            // Permission lost or createFile failed — clear the pref so
            // the user gets the default until they pick again. Logged
            // as a warning so we have telemetry on this dropping.
            onPermissionLost()
        }

        // Default path: app-private external Movies/yanco-recordings/<file>
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

    private fun hasPersistedPermission(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission && it.isWritePermission }

    /**
     * Build a friendly filename for visible folders (SAF). Sanitises
     * characters that file managers / SAF backends sometimes choke on
     * (`/ \ : * ? " < > |`), trims to a sane length, appends a
     * timestamp + a 4-char id suffix for uniqueness.
     *
     * Result: `BeIN Sport HD - 2026-04-26 22-08 - 19dc`.
     * Caller adds the extension.
     */
    private fun friendlyFilename(
        title: String,
        recordId: String,
    ): String {
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

    private fun extensionFor(format: RecordingFormat): String =
        when (format) {
            RecordingFormat.HLS -> "ts"
            RecordingFormat.MPEG_TS -> "ts"
        }

    companion object {
        const val DEFAULT_DIR_NAME = "yanco-recordings"
        const val MIME_MPEG_TS = "video/mp2t"
    }
}
