package com.yancotv.shared.sources

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Source
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * Resolves a `content://` URI (Storage Access Framework) or `file://` URI to
 * its UTF-8 text. Android sources store paths as opaque URI strings so the
 * user's folder permission survives process death — a raw filesystem path
 * would be unreadable the next time we launch.
 */
class AndroidFileContentReader(private val context: Context) : FileContentReader {
    override suspend fun readText(path: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(path)
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().decodeToString()
        } ?: error("Could not open $path")
    }

    /**
     * MB-230 — real streaming for the local-file M3U path. `readText` above
     * holds the whole playlist as bytes AND as a UTF-16 String at the same
     * time; on a provider-sized dump that alone can outweigh a Fire TV
     * Stick's 384 MB heap budget. Reading through the ContentResolver stream
     * keeps the parser's working set to one line.
     */
    override suspend fun <T> readSource(path: String, block: suspend (Source) -> T): T = withContext(Dispatchers.IO) {
        val uri = Uri.parse(path)
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open $path")
        input.use { stream ->
            stream.asSource().buffered().use { source -> block(source) }
        }
    }
}
