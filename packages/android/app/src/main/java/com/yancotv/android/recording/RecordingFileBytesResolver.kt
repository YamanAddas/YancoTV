package com.yancotv.android.recording

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.yancotv.shared.recording.RecordingsRepository
import java.io.File

/**
 * MB-219 — Android implementation of [RecordingsRepository]'s
 * `fileBytesIfExists` boot-recovery hook.
 *
 * `RecordingService.handleStop` runs `cancelAndJoin → output.close →
 * output.size → markCompleted` in sequence. Process death anywhere
 * after `cancelAndJoin` leaves the file on disk but the row in
 * `RECORDING`. On the next boot, [RecordingsRepository.sweepOrphans]
 * calls this resolver for each stale row; a non-null positive byte
 * count flips the row to `COMPLETED` instead of `FAILED`, so the user
 * can play a recording that the recorder genuinely finished writing.
 *
 * Resolves the three `file_path` shapes the recording storage backends
 * write:
 *  - `content://` URIs (MediaStore.Downloads, SAF tree documents) →
 *    `ContentResolver` query against `OpenableColumns.SIZE`. Note: a
 *    MediaStore row that's still `IS_PENDING=1` (because the
 *    finalising `output.close()` never ran) will typically still
 *    expose its current size via this column — Android cleans the
 *    pending file later, but during the brief window between
 *    `cancelAndJoin` and `close`, salvaging by size is the right call.
 *  - `file://` URIs → parse to a path and `File.length()`.
 *  - Bare absolute paths (no scheme) → `File.length()` directly.
 *
 * Returns `null` for missing files, paths that fail to resolve, blank
 * input, or any thrown exception (`SecurityException`, dead provider,
 * etc.); the caller treats every `null` as "no recovery possible →
 * mark `FAILED("orphaned_by_app_kill")`".
 */
fun recordingFileBytesResolver(context: Context): (String) -> Long? =
    { fileUri ->
        runCatching {
            when {
                fileUri.isBlank() -> null
                fileUri.startsWith("content://") -> contentResolverFileSize(context, fileUri)
                fileUri.startsWith("file://") -> {
                    val path = Uri.parse(fileUri).path
                    if (path == null) null else filesystemFileSize(File(path))
                }
                else -> filesystemFileSize(File(fileUri))
            }
        }.getOrNull()
    }

private fun contentResolverFileSize(
    context: Context,
    uri: String,
): Long? {
    val parsed = Uri.parse(uri) ?: return null
    return context.contentResolver
        .query(parsed, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIdx < 0 || cursor.isNull(sizeIdx)) {
                null
            } else {
                cursor.getLong(sizeIdx)
            }
        }
}

private fun filesystemFileSize(file: File): Long? = if (file.exists() && file.isFile) file.length() else null
