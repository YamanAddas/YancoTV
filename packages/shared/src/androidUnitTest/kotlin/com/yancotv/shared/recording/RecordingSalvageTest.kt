package com.yancotv.shared.recording

import com.yancotv.shared.sources.testDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MB-418 — closing a single stranded recording.
 *
 * A row that says RECORDING with nothing writing to it is the state the owner
 * hit: they pressed Stop, the service had already killed its own scope
 * mid-transition, and every later Stop returned in silence. `salvage` is what
 * that press now reaches.
 *
 * The rule it applies is [RecordingsRepository.sweepOrphans]'s, extracted
 * rather than copied — the decision "is there enough on disk to be worth
 * keeping" is the load-bearing part, and two copies of it would be free to
 * disagree about the same file.
 */
class RecordingSalvageTest {

    private fun repo(bytesOnDisk: Long?, now: Long = 1_800_000_000_000L) = RecordingsRepository(
        db = testDb(),
        clock = { now },
        fileBytesIfExists = { bytesOnDisk },
    )

    private fun RecordingsRepository.startOne(id: String = "rec-1"): String {
        markStarted(
            id = id,
            contentId = "chan-1",
            title = "Match of the Day",
            streamUrl = "http://example.test/live.ts",
            filePath = "/movies/$id.ts",
            format = RecordingFormat.MPEG_TS,
        )
        return id
    }

    @Test
    fun `a real capture is kept, not written off`() {
        // 40 MB on disk. The recorder did its job; only the status flip was
        // lost. Marking this FAILED is the outcome the owner described as the
        // recording being deleted.
        val repo = repo(bytesOnDisk = 40L * 1024 * 1024)
        val id = repo.startOne()
        assertTrue(repo.salvage(id))
        val row = repo.getById(id)!!
        assertEquals(RecordingStatus.COMPLETED, row.status)
        assertEquals(40L * 1024 * 1024, row.fileSizeBytes)
    }

    @Test
    fun `a file too small to play is not dressed up as a recording`() {
        // Header-only captures and HTTP error bodies land here. COMPLETED
        // would invite a tap that opens the player onto an unplayable file.
        val repo = repo(bytesOnDisk = 1_024L)
        val id = repo.startOne()
        assertTrue(repo.salvage(id))
        assertEquals(RecordingStatus.FAILED, repo.getById(id)!!.status)
    }

    @Test
    fun `no file on disk at all`() {
        val repo = repo(bytesOnDisk = null)
        val id = repo.startOne()
        assertTrue(repo.salvage(id))
        val row = repo.getById(id)!!
        assertEquals(RecordingStatus.FAILED, row.status)
        assertEquals("orphaned_by_app_kill", row.error)
    }

    @Test
    fun `a row that is already terminal is left exactly as it was`() {
        // The ordinary race: the real stop path finished a moment before this
        // press arrived. Touching the row here would overwrite a correct
        // COMPLETED with a salvage guess.
        val repo = repo(bytesOnDisk = null)
        val id = repo.startOne()
        repo.markCompleted(id = id, bytesWritten = 99L, durationSeconds = 12L)
        assertFalse(repo.salvage(id))
        val row = repo.getById(id)!!
        assertEquals(RecordingStatus.COMPLETED, row.status)
        assertEquals(99L, row.fileSizeBytes)
    }

    @Test
    fun `an unknown id is not an error`() {
        // The service calls this for any id it has no job for, including ids
        // whose row was deleted by the user in between.
        assertFalse(repo(bytesOnDisk = null).salvage("rec-that-never-existed"))
    }

    @Test
    fun `salvage and the boot sweep reach the same verdict on the same row`() {
        // The whole reason salvage was extracted instead of written twice.
        val viaSalvage = repo(bytesOnDisk = 40L * 1024 * 1024).let {
            val id = it.startOne("rec-a")
            it.salvage(id)
            it.getById(id)!!.status
        }
        val viaSweep = repo(bytesOnDisk = 40L * 1024 * 1024).let {
            val id = it.startOne("rec-b")
            it.sweepOrphans(orphanThresholdMs = 0L)
            it.getById(id)!!.status
        }
        assertEquals(viaSweep, viaSalvage)
        assertEquals(RecordingStatus.COMPLETED, viaSalvage)
    }
}
