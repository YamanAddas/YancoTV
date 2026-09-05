package com.yancotv.android.recording

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MB-421 — which delete call a recording's stored path needs.
 *
 * The screen used to answer this with `filePath.startsWith("content://")` and
 * send everything to `DocumentFile`, which is the Storage Access Framework's
 * surface. Recordings are allocated through MediaStore, whose provider refuses
 * it — `UnsupportedOperationException: Unsupported call: android:deleteDocument`
 * — and the throw was swallowed while the row was deleted anyway.
 *
 * So every delete looked clean and left its file. Measured on the owner's
 * television: **0 rows listed, 9 files on disk, 238 MB**, oldest orphan from
 * April, and the device eventually filled to 100% at which point recording
 * stopped working with no message at all.
 *
 * The paths below are real ones taken from that device's logs and database.
 */
class RecordingPathKindTest {

    @Test
    fun `a MediaStore uri is deleted through the ContentResolver`() {
        // The exact shape the recorder allocates on API 29+.
        assertEquals(
            RecordingPathKind.MEDIA_STORE,
            recordingPathKind("content://media/external_primary/video/media/1000000066"),
        )
        assertEquals(
            RecordingPathKind.MEDIA_STORE,
            recordingPathKind("content://media/external/video/media/42"),
        )
    }

    @Test
    fun `a document the user picked is a SAF document`() {
        // The custom-folder case: the viewer chose a directory through the
        // system picker, so the file really is a SAF document.
        assertEquals(
            RecordingPathKind.SAF_DOCUMENT,
            recordingPathKind(
                "content://com.android.externalstorage.documents/document/primary%3AMovies%2Frec.ts",
            ),
        )
    }

    @Test
    fun `a legacy absolute path is a plain file`() {
        // API 28 and below — the Fire TV — never touch MediaStore.
        assertEquals(
            RecordingPathKind.FILE,
            recordingPathKind("/storage/emulated/0/Movies/YancoTV/BEIN - 2026-09-04 22-39.ts"),
        )
    }

    @Test
    fun `the authority decides, not a prefix of the whole string`() {
        // The bug was a decision made on `startsWith("content://")`. These two
        // agree on that prefix and need different calls, which is the entire
        // point of the classification.
        assertEquals(
            recordingPathKind("content://media/external/video/media/1"),
            RecordingPathKind.MEDIA_STORE,
        )
        assertEquals(
            recordingPathKind("content://media.example.provider/video/1"),
            RecordingPathKind.SAF_DOCUMENT,
        )
    }

    @Test
    fun `an empty or odd path does not throw`() {
        // `file_path` is a TEXT column with no constraint; a corrupted row must
        // not crash the delete of every other recording.
        assertEquals(RecordingPathKind.FILE, recordingPathKind(""))
        assertEquals(RecordingPathKind.FILE, recordingPathKind("   "))
        assertEquals(RecordingPathKind.SAF_DOCUMENT, recordingPathKind("content://"))
    }
}
