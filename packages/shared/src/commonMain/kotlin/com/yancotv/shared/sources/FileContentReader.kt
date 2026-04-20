package com.yancotv.shared.sources

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
}
