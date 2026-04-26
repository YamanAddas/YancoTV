package com.yancotv.shared.recording

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yancotv.shared.db.YancoDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [RecordingsRepository] — covers state-transition validation
 * (the contract that guards the rest of the recording pipeline) and
 * the orphan-recovery sweep that runs at app start.
 */
class RecordingsRepositoryTest {
    private fun makeDb(): YancoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        YancoDb.Schema.create(driver)
        return YancoDb(driver)
    }

    private fun makeRepo(
        db: YancoDb = makeDb(),
        clock: () -> Long = { 1_700_000_000_000L },
    ): RecordingsRepository = RecordingsRepository(db, clock)

    @Test fun markStartedInsertsRecordingRowInRecordingState() {
        val repo = makeRepo()
        val entry =
            repo.markStarted(
                id = "rec-1",
                contentId = "ch-1",
                title = "Football",
                streamUrl = "https://example.com/stream.m3u8",
                filePath = "/storage/yanco/rec-1.ts",
                format = RecordingFormat.HLS,
            )
        assertEquals("rec-1", entry.id)
        assertEquals(RecordingStatus.RECORDING, entry.status)
        assertEquals(RecordingFormat.HLS, entry.format)
        assertNull(entry.endedAt)
        assertNull(entry.fileSizeBytes)
    }

    @Test fun markCompletedTransitionsAndPopulatesTerminalFields() {
        var nowMs = 1_700_000_000_000L
        val repo = makeRepo(clock = { nowMs })
        repo.markStarted("rec-2", null, "VOD", "u", "/p", RecordingFormat.MPEG_TS)
        nowMs += 60_000L // 60 s pass
        val entry = repo.markCompleted(id = "rec-2", bytesWritten = 1_048_576L, durationSeconds = 60L)

        assertEquals(RecordingStatus.COMPLETED, entry.status)
        assertEquals(1_048_576L, entry.fileSizeBytes)
        assertEquals(60L, entry.durationSeconds)
        assertEquals(1_700_000_060_000L, entry.endedAt)
        assertNull(entry.error)
    }

    @Test fun markFailedRecordsReason() {
        val repo = makeRepo()
        repo.markStarted("rec-3", null, "T", "u", "/p", RecordingFormat.HLS)
        val entry = repo.markFailed("rec-3", reason = "heartbeat_timeout", bytesWritten = 524_288L)

        assertEquals(RecordingStatus.FAILED, entry.status)
        assertEquals("heartbeat_timeout", entry.error)
        assertEquals(524_288L, entry.fileSizeBytes)
    }

    @Test fun markCancelledIsTerminal() {
        val repo = makeRepo()
        repo.markStarted("rec-4", null, "T", "u", "/p", RecordingFormat.HLS)
        val entry = repo.markCancelled("rec-4", bytesWritten = 100L)
        assertEquals(RecordingStatus.CANCELLED, entry.status)
        assertEquals(100L, entry.fileSizeBytes)
        assertNull(entry.error)
    }

    @Test fun cannotTransitionFromTerminalStateAgain() {
        val repo = makeRepo()
        repo.markStarted("rec-5", null, "T", "u", "/p", RecordingFormat.HLS)
        repo.markCompleted("rec-5", 100L, 1L)
        // Cannot complete twice; cannot fail an already-completed row.
        assertFailsWith<IllegalArgumentException> { repo.markCompleted("rec-5", 200L, 2L) }
        assertFailsWith<IllegalArgumentException> { repo.markFailed("rec-5", "x", 200L) }
        assertFailsWith<IllegalArgumentException> { repo.markCancelled("rec-5", 200L) }
    }

    @Test fun terminalCallOnMissingIdRaises() {
        val repo = makeRepo()
        // No row exists for "missing".
        assertFailsWith<IllegalStateException> { repo.markCompleted("missing", 0L, 0L) }
    }

    @Test fun getByStatusReturnsOnlyMatchingRows() {
        var nowMs = 1_700_000_000_000L
        val repo = makeRepo(clock = { nowMs })
        repo.markStarted("a", null, "A", "u", "/p", RecordingFormat.HLS)
        nowMs += 1_000L
        repo.markStarted("b", null, "B", "u", "/p", RecordingFormat.MPEG_TS)
        nowMs += 1_000L
        repo.markCompleted("a", 100L, 1L)

        val recording = repo.getByStatus(RecordingStatus.RECORDING).map { it.id }
        val completed = repo.getByStatus(RecordingStatus.COMPLETED).map { it.id }
        assertEquals(listOf("b"), recording)
        assertEquals(listOf("a"), completed)
    }

    @Test fun sweepOrphansFlipsOldRecordingRowsToFailed() {
        var nowMs = 1_700_000_000_000L
        val repo = makeRepo(clock = { nowMs })
        repo.markStarted("old", null, "Old", "u", "/p", RecordingFormat.HLS)
        nowMs += 11L * 60_000L // 11 minutes pass — exceeds 10 min default threshold
        repo.markStarted("young", null, "Young", "u", "/p", RecordingFormat.HLS) // timestamp = nowMs
        nowMs += 1_000L

        val swept = repo.sweepOrphans()
        assertEquals(1, swept)

        val olderRow = repo.getById("old")
        assertNotNull(olderRow)
        assertEquals(RecordingStatus.FAILED, olderRow.status)
        assertEquals("orphaned_by_app_kill", olderRow.error)

        val youngerRow = repo.getById("young")
        assertNotNull(youngerRow)
        assertEquals(RecordingStatus.RECORDING, youngerRow.status, "young recording shouldn't be swept")
    }

    @Test fun sweepOrphansCustomThresholdHonored() {
        var nowMs = 1_700_000_000_000L
        val repo = makeRepo(clock = { nowMs })
        repo.markStarted("a", null, "A", "u", "/p", RecordingFormat.HLS)
        nowMs += 30_000L
        // With a 20s threshold, the 30s-old row is an orphan.
        val swept = repo.sweepOrphans(orphanThresholdMs = 20_000L)
        assertEquals(1, swept)
        assertEquals(RecordingStatus.FAILED, repo.getById("a")?.status)
    }

    @Test fun deleteRemovesRow() {
        val repo = makeRepo()
        repo.markStarted("d", null, "T", "u", "/p", RecordingFormat.HLS)
        assertNotNull(repo.getById("d"))
        repo.deleteById("d")
        assertNull(repo.getById("d"))
    }

    @Test fun formatRoundTripsThroughDb() {
        val repo = makeRepo()
        repo.markStarted("h", null, "Hls", "u", "/p", RecordingFormat.HLS)
        repo.markStarted("t", null, "Ts", "u", "/p", RecordingFormat.MPEG_TS)
        assertEquals(RecordingFormat.HLS, repo.getById("h")?.format)
        assertEquals(RecordingFormat.MPEG_TS, repo.getById("t")?.format)
    }

    @Test fun allFlowReflectsLiveWrites() =
        runTest {
            val repo = makeRepo()
            // First snapshot before any rows: empty.
            assertTrue(repo.allFlow().first().isEmpty())

            repo.markStarted("a", null, "A", "u", "/p", RecordingFormat.HLS)
            val after = repo.allFlow().first()
            assertEquals(1, after.size)
            assertEquals("a", after[0].id)
        }
}
