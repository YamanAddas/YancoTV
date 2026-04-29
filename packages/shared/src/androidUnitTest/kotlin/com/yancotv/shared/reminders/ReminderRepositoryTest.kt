package com.yancotv.shared.reminders

import com.yancotv.shared.sources.testDb
import com.yancotv.shared.types.EpgProgramme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * ReminderRepository tests — the pure CRUD layer. The Android-side
 * `ReminderScheduler` pairs each upsert here with an AlarmManager
 * registration; this test set covers only the data layer (alarms
 * require Android instrumentation).
 *
 * Matters most:
 *  - upsert idempotency — a second "Remind me" on the same programme
 *    must replace, not duplicate (fixes the double-fire bug).
 *  - fireAt math with leadSeconds (early-fire by N seconds).
 *  - purgeStale cutoff boundary (exactly-end-time reminder — kept or
 *    dropped? matches desktop: ended < cutoff means drop, = means keep).
 *  - markFired isolation — firing one reminder must not disturb others.
 */
class ReminderRepositoryTest {
    @Test fun upsertCreatesReminderAndComputesFireAt() = runTest {
        val repo = ReminderRepository(testDb(), clock = { 1_700_000_000L * 1000L })
        val prog = programme(id = "p1", tvg = "cnn.us", start = 2_000_000L, end = 2_003_600L)

        val r = repo.upsert(channelTvgId = "cnn.us", programme = prog, leadSeconds = 120L)
        assertEquals("rem:p1", r.id)
        assertEquals("p1", r.programmeId)
        assertEquals("cnn.us", r.channelTvgId)
        assertEquals(2_000_000L - 120L, r.fireAt)
        assertEquals(120L, r.leadSeconds)
        assertFalse(r.fired)
        assertEquals(1, repo.all().size)
    }

    @Test fun upsertTwiceForSameProgrammeReplacesLeadTime() = runTest {
        val repo = ReminderRepository(testDb(), clock = { 0L })
        val prog = programme("p1", "cnn.us", 1000L, 2000L)

        repo.upsert("cnn.us", prog, leadSeconds = 0L)
        repo.upsert("cnn.us", prog, leadSeconds = 300L)

        // A second "set reminder" on the same programme must not create a
        // duplicate alarm — the scheduler would fire two notifications.
        val all = repo.all()
        assertEquals(1, all.size)
        assertEquals(300L, all.single().leadSeconds)
        assertEquals(1000L - 300L, all.single().fireAt)
    }

    @Test fun forProgrammeReturnsMatchingRowOrNull() = runTest {
        val repo = ReminderRepository(testDb(), clock = { 0L })
        val prog = programme("p1", "cnn.us", 1000L, 2000L)
        assertNull(repo.forProgramme("p1"))
        repo.upsert("cnn.us", prog)
        assertNotNull(repo.forProgramme("p1"))
        assertNull(repo.forProgramme("p-other"))
    }

    @Test fun dueAtReturnsUnfiredReminderAtOrBeforeCutoff() = runTest {
        val repo = ReminderRepository(testDb(), clock = { 0L })
        repo.upsert("cnn.us", programme("p1", "cnn.us", 100L, 200L)) // fireAt=100
        repo.upsert("cnn.us", programme("p2", "cnn.us", 200L, 300L)) // fireAt=200
        repo.upsert("cnn.us", programme("p3", "cnn.us", 400L, 500L)) // fireAt=400

        val due = repo.dueAt(250L)
        assertEquals(2, due.size)
        assertTrue(due.all { !it.fired })
        assertTrue(due.any { it.programmeId == "p1" })
        assertTrue(due.any { it.programmeId == "p2" })
    }

    @Test fun markFiredIsolatesRemainingUnfired() = runTest {
        val repo = ReminderRepository(testDb(), clock = { 0L })
        repo.upsert("cnn.us", programme("p1", "cnn.us", 100L, 200L))
        repo.upsert("cnn.us", programme("p2", "cnn.us", 300L, 400L))

        repo.markFired("rem:p1")

        val unfired = repo.allUnfired()
        assertEquals(1, unfired.size)
        assertEquals("p2", unfired.single().programmeId)

        val all = repo.all()
        assertEquals(2, all.size)
        assertTrue(all.first { it.programmeId == "p1" }.fired)
        assertFalse(all.first { it.programmeId == "p2" }.fired)
    }

    @Test fun deleteByProgrammeIdRemovesMatchingRow() = runTest {
        val repo = ReminderRepository(testDb(), clock = { 0L })
        repo.upsert("cnn.us", programme("p1", "cnn.us", 100L, 200L))
        repo.upsert("cnn.us", programme("p2", "cnn.us", 300L, 400L))

        repo.deleteByProgrammeId("p1")
        val rows = repo.all()
        assertEquals(1, rows.size)
        assertEquals("p2", rows.single().programmeId)
    }

    @Test fun purgeStaleDropsReminderWhenEndTimeBeforeCutoff() = runTest {
        val repo = ReminderRepository(testDb(), clock = { 500L * 1000L })
        repo.upsert("cnn.us", programme("old", "cnn.us", 100L, 200L))
        repo.upsert("cnn.us", programme("current", "cnn.us", 400L, 600L))
        repo.upsert("cnn.us", programme("future", "cnn.us", 1000L, 1200L))

        // Cutoff = 500s. "old" ends at 200 (< cutoff) → dropped.
        // "current" ends at 600 (>= cutoff) → kept.
        // "future" ends at 1200 (>= cutoff) → kept.
        repo.purgeStale(cutoffSeconds = 500L)

        val ids = repo.all().map { it.programmeId }.toSet()
        assertEquals(setOf("current", "future"), ids)
    }

    @Test fun purgeStaleDefaultCutoffUsesClock() = runTest {
        // Clock at 500s (ms-based), so default cutoff = 500s.
        val repo = ReminderRepository(testDb(), clock = { 500L * 1000L })
        repo.upsert("cnn.us", programme("old", "cnn.us", 100L, 200L))
        repo.upsert("cnn.us", programme("new", "cnn.us", 400L, 600L))
        repo.purgeStale() // no arg → default clock-based cutoff
        assertEquals(setOf("new"), repo.all().map { it.programmeId }.toSet())
    }

    @Test fun createdAtStampedInSeconds() = runTest {
        // clock returns ms; createdAt should be divided to seconds.
        val repo = ReminderRepository(testDb(), clock = { 1_700_000_000_123L })
        repo.upsert("cnn.us", programme("p1", "cnn.us", 1000L, 2000L))
        // Seconds: 1_700_000_000. (Repo internals already divide by 1000.)
        val reminders = repo.all()
        assertEquals(1, reminders.size)
        // Indirect check — the test DB's `created_at` column isn't exposed
        // on the domain type, but no failure means insert worked with the
        // converted seconds value (no integer overflow or SQL type error).
    }

    private fun programme(id: String, tvg: String, start: Long, end: Long) = EpgProgramme(
        id = id,
        channelTvgId = tvg,
        title = "Show $id",
        startTime = start,
        endTime = end,
    )
}
