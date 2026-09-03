package com.yancotv.shared.downloads

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yancotv.shared.db.YancoDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * [DownloadsRepository] — the state machine, and the launch reconcile that
 * has to behave differently from the recordings sweep.
 */
class DownloadsRepositoryTest {
    private var now = 1_700_000_000_000L

    private fun makeRepo(): DownloadsRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        YancoDb.Schema.create(driver)
        return DownloadsRepository(YancoDb(driver)) { now }
    }

    private fun DownloadsRepository.film(id: String = "dl-1") = enqueue(
        id = id,
        contentId = "movie-1",
        episodeId = null,
        title = "Inception",
        streamUrl = "http://provider.example/movie/u/p/1.mkv",
        filePath = "/Documents/Downloads/Inception.mkv",
    )

    @Test fun enqueueStartsQueuedWithNoBytes() {
        val repo = makeRepo()
        val entry = repo.film()
        assertEquals(DownloadStatus.QUEUED, entry.status)
        assertEquals(0L, entry.bytesDownloaded)
        assertNull(entry.startedAt)
        assertNull(entry.fraction)
        assertEquals(false, entry.resumable)
    }

    @Test fun progressIsRecordedAndReportedAsAFraction() {
        val repo = makeRepo()
        repo.film()
        repo.markStarted("dl-1")
        repo.updateProgress("dl-1", bytesDownloaded = 250L, bytesTotal = 1_000L)
        val entry = repo.getById("dl-1")!!
        assertEquals(DownloadStatus.DOWNLOADING, entry.status)
        assertEquals(250L, entry.bytesDownloaded)
        assertEquals(0.25, entry.fraction)
    }

    /** A server that sends no length leaves the bar indeterminate rather
     *  than inventing a denominator. */
    @Test fun fractionIsNullWithoutATotal() {
        val repo = makeRepo()
        repo.film()
        repo.markStarted("dl-1")
        repo.updateProgress("dl-1", bytesDownloaded = 250L, bytesTotal = null)
        assertNull(repo.getById("dl-1")!!.fraction)
    }

    /** Pause keeps the bytes; that is the whole difference from cancel. */
    @Test fun pauseKeepsProgressAndResumeContinuesFromIt() {
        val repo = makeRepo()
        repo.film()
        repo.markStarted("dl-1")
        repo.updateProgress("dl-1", 500L, 1_000L)
        val paused = repo.markPaused("dl-1", bytesDownloaded = 500L, resumable = true)
        assertEquals(DownloadStatus.PAUSED, paused.status)
        assertEquals(500L, paused.bytesDownloaded)
        assertTrue(paused.resumable)

        repo.markStarted("dl-1")
        repo.updateProgress("dl-1", 750L, 1_000L)
        assertEquals(750L, repo.getById("dl-1")!!.bytesDownloaded)
    }

    @Test fun completingSetsTheFinalSizeAndTheTime() {
        val repo = makeRepo()
        repo.film()
        repo.markStarted("dl-1")
        now += 60_000L
        val done = repo.markCompleted("dl-1", bytesTotal = 1_000L)
        assertEquals(DownloadStatus.COMPLETED, done.status)
        assertEquals(1_000L, done.bytesDownloaded)
        assertEquals(1_000L, done.bytesTotal)
        assertEquals(1.0, done.fraction)
        assertEquals(now, done.completedAt)
    }

    @Test fun terminalRowsRefuseFurtherTransitions() {
        val repo = makeRepo()
        repo.film()
        repo.markStarted("dl-1")
        repo.markCompleted("dl-1", 1_000L)
        assertFailsWith<IllegalStateException> { repo.markPaused("dl-1", 10L, resumable = true) }
        assertFailsWith<IllegalStateException> { repo.markFailed("dl-1", "nope", 10L) }
        assertFailsWith<IllegalStateException> { repo.markCancelled("dl-1") }
    }

    /** Progress arriving late for a finished row must not revive it. */
    @Test fun progressOnATerminalRowIsIgnored() {
        val repo = makeRepo()
        repo.film()
        repo.markStarted("dl-1")
        repo.markCompleted("dl-1", 1_000L)
        repo.updateProgress("dl-1", 12L, 1_000L)
        val entry = repo.getById("dl-1")!!
        assertEquals(DownloadStatus.COMPLETED, entry.status)
        assertEquals(1_000L, entry.bytesDownloaded)
    }

    @Test fun failureKeepsTheReasonAndTheBytesItGot() {
        val repo = makeRepo()
        repo.film()
        repo.markStarted("dl-1")
        val failed = repo.markFailed("dl-1", reason = "http_404", bytesDownloaded = 42L)
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertEquals("http_404", failed.error)
        assertEquals(42L, failed.bytesDownloaded)
    }

    /**
     * The reconcile, and why it is not the recordings sweep: a transfer the
     * system is still running must be left alone.
     */
    @Test fun reconcileLeavesTransfersTheSystemStillHas() {
        val repo = makeRepo()
        repo.film("dl-live")
        repo.markStarted("dl-live")
        val parked = repo.reconcile(activeIds = setOf("dl-live"))
        assertEquals(0, parked)
        assertEquals(DownloadStatus.DOWNLOADING, repo.getById("dl-live")!!.status)
    }

    /** One the system has forgotten is parked, not failed — the bytes are
     *  still on disk and the viewer can carry on. */
    @Test fun reconcileParksStrandedRowsAsResumable() {
        val repo = makeRepo()
        repo.film("dl-gone")
        repo.markStarted("dl-gone")
        repo.updateProgress("dl-gone", 900L, 1_000L)
        val parked = repo.reconcile(activeIds = emptySet())
        assertEquals(1, parked)
        val entry = repo.getById("dl-gone")!!
        assertEquals(DownloadStatus.PAUSED, entry.status)
        assertEquals(900L, entry.bytesDownloaded)
        assertTrue(entry.resumable, "bytes on disk means it can be resumed")
    }

    /** Nothing downloaded yet is parked too, but starts over. */
    @Test fun reconcileMarksAnUnstartedRowUnresumable() {
        val repo = makeRepo()
        repo.film("dl-fresh")
        repo.reconcile(activeIds = emptySet())
        val entry = repo.getById("dl-fresh")!!
        assertEquals(DownloadStatus.PAUSED, entry.status)
        assertEquals(false, entry.resumable)
    }

    @Test fun reconcileLeavesFinishedRowsAlone() {
        val repo = makeRepo()
        repo.film("dl-done")
        repo.markStarted("dl-done")
        repo.markCompleted("dl-done", 10L)
        assertEquals(0, repo.reconcile(activeIds = emptySet()))
        assertEquals(DownloadStatus.COMPLETED, repo.getById("dl-done")!!.status)
    }

    @Test fun theListIsReactive() = runTest {
        val repo = makeRepo()
        repo.film()
        assertEquals(1, repo.allFlow().first().size)
        repo.film("dl-2")
        assertEquals(2, repo.allFlow().first().size)
        repo.deleteById("dl-2")
        assertEquals(1, repo.allFlow().first().size)
    }
}
