package com.yancotv.shared.sources

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.writeString

/**
 * Reads bytes-as-text for a platform-specific path reference.
 *
 * Android passes a `content://` URI (from Storage Access Framework) here;
 * the androidMain implementation resolves it via ContentResolver. iOS will
 * pass a file URL which iosMain reads through NSFileManager. Tests wire in
 * an in-memory fake.
 *
 * Lives in the repository boundary (not `http/`) because it's strictly
 * local I/O — keeping the HttpClient contract focused on network.
 */
interface FileContentReader {
    suspend fun readText(path: String): String

    /**
     * Stream the file's bytes to [block] without materialising them.
     *
     * MB-230: a local M3U can be just as large as a hosted one (the user
     * picks the same provider dump off a USB stick), and [readText] holds the
     * entire playlist as a UTF-16 String before the parser even starts. The
     * default implementation preserves that old behaviour so test fakes and
     * the iOS reader keep compiling unchanged; platform readers that can do
     * real streaming — see `AndroidFileContentReader` — override it.
     *
     * Mirrors the shape of [com.yancotv.shared.http.HttpClient.getSource] so
     * both M3U paths read the same way at the call site.
     */
    suspend fun <T> readSource(path: String, block: suspend (Source) -> T): T {
        val text = readText(path)
        return Buffer().apply { writeString(text) }.use { block(it) }
    }
}
