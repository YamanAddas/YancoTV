@file:OptIn(ExperimentalForeignApi::class)

package com.yancotv.shared.sources

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * iOS [FileContentReader] — reads a local playlist the user picked with the
 * document picker.
 *
 * Android receives a `content://` URI from the Storage Access Framework and
 * resolves it through ContentResolver; iOS receives a plain filesystem path
 * from `UIDocumentPickerViewController` (security-scoped access is started
 * and stopped on the Swift side, which owns the picker).
 *
 * [readSource] is deliberately left as the interface default. The Android
 * reader overrides it to stream (MB-230, where a provider dump off a USB
 * stick was as large as a hosted one), but doing that here needs a
 * `kotlinx.io` bridge over `NSInputStream` that has no other caller yet.
 * Local-file sources on iOS land with the document picker; when they do,
 * this is the first thing to revisit for large playlists.
 */
class IosFileContentReader : FileContentReader {
    override suspend fun readText(path: String): String =
        NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
            ?: error("Unable to read file at $path")
}
