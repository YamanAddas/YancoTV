package com.yancotv.shared.sources

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves a `content://` URI (Storage Access Framework) or `file://` URI to
 * its UTF-8 text. Android sources store paths as opaque URI strings so the
 * user's folder permission survives process death — a raw filesystem path
 * would be unreadable the next time we launch.
 */
class AndroidFileContentReader(
    private val context: Context,
) : FileContentReader {
    override suspend fun readText(path: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(path)
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().decodeToString()
        } ?: error("Could not open $path")
    }
}
