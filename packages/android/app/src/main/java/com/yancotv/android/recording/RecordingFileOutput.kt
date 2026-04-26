package com.yancotv.android.recording

import android.content.Context
import android.os.Environment
import com.yancotv.shared.recording.RecordingFormat
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.io.File
import java.io.FileOutputStream

/**
 * Creates the on-disk output for a single recording.
 *
 * v1.0 uses **app-private external storage** (`getExternalFilesDir`)
 * because:
 *   - works on every API level we support (minSdk = 24); no
 *     `WRITE_EXTERNAL_STORAGE` permission needed.
 *   - no MediaStore round-trip to insert + open an output stream.
 *   - files survive uninstalls only if the user explicitly exports —
 *     acceptable for v1.0 (the recordings UI surfaces a clear
 *     warning about this).
 *
 * MediaStore writes with `RELATIVE_PATH = Movies/YancoTV` are a
 * Stage 5 polish (would let recordings appear in the system Files
 * app on Android 10+ and survive uninstall on Android 11+).
 */
internal object RecordingFileOutput {
    private const val DIR_NAME = "yanco-recordings"

    /**
     * Build the target [File] for the given record. Caller is
     * responsible for creating the parent directory (this helper
     * does it on first use). Throws if external storage isn't
     * available — extremely rare on real devices.
     */
    fun fileFor(
        context: Context,
        recordId: String,
        format: RecordingFormat,
    ): File {
        val baseDir =
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: error("No external Movies dir available — device storage unmounted?")
        val recordingsDir = File(baseDir, DIR_NAME)
        if (!recordingsDir.exists() && !recordingsDir.mkdirs()) {
            error("Failed to create recordings directory: ${recordingsDir.absolutePath}")
        }
        // Both formats land as `.ts` on disk — ExoPlayer reads
        // concatenated TS segments (HLS) and a continuous TS body
        // (Xtream catch-up) the same way.
        val ext =
            when (format) {
                RecordingFormat.HLS -> "ts"
                RecordingFormat.MPEG_TS -> "ts"
            }
        // Slug the id for filesystem safety: Android's external storage
        // accepts most chars but underscores keep the path readable in
        // a file manager.
        val slug = recordId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return File(recordingsDir, "$slug.$ext")
    }

    /**
     * Open a buffered [Sink] at [file]. Caller must close the sink
     * when done — typically via `sink.use { ... }` or the
     * RecordingService's per-recording cleanup block.
     *
     * Buffered to amortise the 16 KiB-ish writes that
     * [com.yancotv.shared.recording.MpegTsRecorder] /
     * [com.yancotv.shared.recording.HlsRecorder] issue.
     */
    fun openSink(file: File): Sink = FileOutputStream(file).asSink().buffered()
}
