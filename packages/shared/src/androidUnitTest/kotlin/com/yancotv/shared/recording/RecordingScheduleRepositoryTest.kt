package com.yancotv.shared.recording

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yancotv.shared.db.YancoDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Tests for [RecordingScheduleRepository] — covers the seven-state
 * transition graph (the contract that guards the alarm/receiver
 * pipeline) and the two reconciliation sweeps that run at app boot.
 *
 * The state machine is the most important contract here: the receiver
 * fires from a `BroadcastReceiver` which has a 10-second budget; if
 * a transition is illegal we want it to fail loudly *here* in tests,
 * not silently in the receiver where a swallowed throw means the
 * schedule stays stuck.
 */
class RecordingScheduleRepositoryTest {
    private fun makeDb(): YancoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // FKs OFF in tests — these tests verify the state-machine
        // transition graph and reconciliation logic, not the schema's
        // FK enforcement (that's the database's contract, not the
        // repo's). Matches the pattern in `RecordingsRepositoryTest`
        // which uses synthetic content IDs without seeding the content
        // table.
        driver.execute(null, "PRAGMA foreign_keys = OFF;", 0)
        YancoDb.Schema.create(driver)
        return YancoDb(driver)
    }

    private fun makeRepo(db: YancoDb = makeDb(), clock: () -> Long = { 1_700_000_000_000L }): RecordingScheduleRepository =
        RecordingScheduleRepository(db, clock)

    // ── insert + getById + getAll ─────────────────────────────────

    @Test fun insertCreatesScheduledRowWithExpectedFields() {
        val repo = makeRepo()
        val entry =
            repo.insert(
                id = "sched-1",
                contentId = "ch-1",
                programmeId = "prog-1",
                title = "Champions League Final",
                streamUrl = "https://example.com/cl-final.m3u8",
                scheduledStart = 1_700_000_100_000L,
                scheduledEnd = 1_700_000_200_000L,
            )

        assertEquals("sched-1", entry.id)
        assertEquals(RecordingScheduleState.SCHEDULED, entry.state)
        assertEquals("ch-1", entry.contentId)
        assertEquals("prog-1", entry.programmeId)
        assertEquals(1_700_000_100_000L, entry.scheduledStart)
        assertEquals(1_700_000_200_000L, entry.scheduledEnd)
        assertNull(entry.error)
    }

    @Test fun insertRejectsEndBeforeStart() {
        val repo = makeRepo()
        assertFailsWith<IllegalArgumentException> {
            repo.insert(
                id = "sched-bad",
                contentId = null,
                programmeId = null,
                title = "Bad",
                streamUrl = "u",
                scheduledStart = 1_700_000_200_000L,
                scheduledEnd = 1_700_000_100_000L,
            )
        }
    }

    @Test fun getByIdReturnsNullForUnknownId() {
        val repo = makeRepo()
        assertNull(repo.getById("nope"))
    }

    @Test fun getAllReturnsEmptyOnFreshDb() {
        val repo = makeRepo()
        assertTrue(repo.getAll().isEmpty())
    }

    @Test fun getByStateFiltersCorrectly() {
        val repo = makeRepo()
        repo.insert("a", null, null, "A", "u", 1L, 100L)
        repo.insert("b", null, null, "B", "u", 1L, 100L)
        repo.transitionTo("b", RecordingScheduleState.ARMED)

        val scheduled = repo.getByState(RecordingScheduleState.SCHEDULED)
        val armed = repo.getByState(RecordingScheduleState.ARMED)

        assertEquals(1, scheduled.size)
        assertEquals("a", scheduled[0].id)
        assertEquals(1, armed.size)
        assertEquals("b", armed[0].id)
    }

    // ── transitionTo: legal transitions ───────────────────────────

    @Test fun scheduledArmsCleanly() {
        val repo = makeRepo()
        repo.insert("s", null, null, "T", "u", 1L, 100L)
        val armed = repo.transitionTo("s", RecordingScheduleState.ARMED)
        assertEquals(RecordingScheduleState.ARMED, armed.state)
    }

    // MB-211 — was `armedFiresViaLinkRecording`, which asserted that firing
    // stored a recording id. Nothing is stored any more: the id is derived
    // from the schedule id, so what remains worth asserting is the state
    // transition itself.
    @Test fun armedTransitionsToFiring() {
        val repo = makeRepo()
        repo.insert("s", null, null, "T", "u", 1L, 100L)
        repo.transitionTo("s", RecordingScheduleState.ARMED)
        val firing = repo.transitionTo("s", RecordingScheduleState.FIRING)
        assertEquals(RecordingScheduleState.FIRING, firing.state)
    }

    @Test fun firingCompletesCleanly() {
        val repo = makeRepo()
        repo.insert("s", null, null, "T", "u", 1L, 100L)
        repo.transitionTo("s", RecordingScheduleState.ARMED)
        repo.transitionTo("s", RecordingScheduleState.FIRING)
        val done = repo.transitionTo("s", RecordingScheduleState.COMPLETED)
        assertEquals(RecordingScheduleState.COMPLETED, done.state)
    }

    @Test fun scheduledCanCancel() {
        val repo = makeRepo()
        repo.insert("s", null, null, "T", "u", 1L, 100L)
        val cancelled = repo.transitionTo("s", RecordingScheduleState.CANCELLED)
        assertEquals(RecordingScheduleState.CANCELLED, cancelled.state)
    }

    @Test fun armedCanCancel() {
        val repo = makeRepo()
        repo.insert("s", null, null, "T", "u", 1L, 100L)
        repo.transitionTo("s", RecordingScheduleState.ARMED)
        val cancelled = repo.transitionTo("s", RecordingScheduleState.CANCELLED)
        assertEquals(RecordingScheduleState.CANCELLED, cancelled.state)
    }

    @Test fun firingCanCancel() {
        val repo = makeRepo()
        repo.insert("s", null, null, "T", "u", 1L, 100L)
        repo.transitionTo("s", RecordingScheduleState.ARMED)
        repo.transitionTo("s", RecordingScheduleState.FIRING)
        val cancelled = repo.transitionTo("s", RecordingScheduleState.CANCELLED)
        assertEquals(RecordingScheduleState.CANCELLED, cancelled.state)
    }

    @Test fun firingCanFailWithReason() {
        val repo = makeRepo()
        repo.insert("s", null, null, "T", "u", 1L, 100L)
        repo.transitionTo("s", RecordingScheduleState.ARMED)
        repo.transitionTo("s", RecordingScheduleState.FIRING)
        val failed =
            repo.transitionTo(
                "s",
                RecordingScheduleState.FAILED,
                errorReason = "stream_404",
            )
        assertEquals(RecordingScheduleState.FAILED, failed.state)
        assertEquals("stream_404", failed.error)
    }

    // ── transitionTo: illegal transitions ─────────────────────────

    @Test fun cannotMoveScheduledStraightToFiring() {
        val repo = makeRepo()
        repo.insert("s", null, null, "T", "u", 1L, 100L)
        assertFailsWith<IllegalArgumentException> {
            repo.transitionTo("s", RecordingScheduleState.FIRING)
        }
    }

    @Test fun cannotMoveScheduledStraightToCompleted() {
        val repo = makeRepo()
        repo.insert("s", null, null, "T", "u", 1L, 100L)
        assertFailsWith<IllegalArgumentException> {
            repo.transitionTo("s", RecordingScheduleState.COMPLETED)
        }
    }

    @Test fun cannotMoveTerminalRowAtAll() {
        val repo = makeRepo()
        repo.insert("s", null, null, "T", "u", 1L, 100L)
        repo.transitionTo("s", RecordingScheduleState.CANCELLED)
        assertFailsWith<IllegalArgumentException> {
            repo.transitionTo("s", RecordingScheduleState.ARMED)
        }
        assertFailsWith<IllegalArgumentException> {
            repo.transitionTo("s", RecordingScheduleState.MISSED)
        }
    }

    @Test fun cannotLinkRecordingFromScheduled() {
        val repo = makeRepo()
        repo.insert("s", null, null, "T", "u", 1L, 100L)
        assertFailsWith<IllegalArgumentException> {
            repo.transitionTo("s", RecordingScheduleState.FIRING)
        }
    }

    @Test fun transitionThrowsOnUnknownId() {
        val repo = makeRepo()
        assertFailsWith<IllegalStateException> {
            repo.transitionTo("ghost", RecordingScheduleState.ARMED)
        }
    }

    // ── MB-214: race-condition coverage ───────────────────────────

    @Test fun endAlarmAfterCancelIsRejected() {
        // MB-214 — end alarm fires AFTER the user cancelled the
        // schedule (or some other terminal transition won the race).
        // The state machine must reject a fresh COMPLETED/FAILED
        // transition; without this guard the receiver could clobber
        // a CANCELLED row back into COMPLETED on a late alarm.
        val repo = makeRepo()
        repo.insert("s", null, null, "T", "u", 1L, 100L)
        repo.transitionTo("s", RecordingScheduleState.ARMED)
        repo.transitionTo("s", RecordingScheduleState.FIRING)
        repo.transitionTo("s", RecordingScheduleState.CANCELLED)
        assertFailsWith<IllegalArgumentException> {
            repo.transitionTo("s", RecordingScheduleState.COMPLETED)
        }
        assertFailsWith<IllegalArgumentException> {
            repo.transitionTo("s", RecordingScheduleState.FAILED, errorReason = "x")
        }
    }

    @Test fun secondTerminalTransitionFromFiringIsRejected() {
        // MB-214 — receiver's handleEnd path could race with the
        // service's own terminal transition (MB-212 fix territory).
        // Whoever wins lands a terminal state; the loser must throw
        // (caller wraps in runCatching; bug surfaced if it didn't).
        val repo = makeRepo()
        repo.insert("s", null, null, "T", "u", 1L, 100L)
        repo.transitionTo("s", RecordingScheduleState.ARMED)
        repo.transitionTo("s", RecordingScheduleState.FIRING)
        repo.transitionTo("s", RecordingScheduleState.COMPLETED)
        // Second attempt — whether by handleEnd or by handleStop.
        assertFailsWith<IllegalArgumentException> {
            repo.transitionTo("s", RecordingScheduleState.FAILED, errorReason = "race")
        }
    }

    @Test fun deletedScheduleEndAlarmThrows() {
        // MB-214 — schedule deletion races with end alarm. After
        // deleteById, transitionTo for the same id must throw cleanly
        // (IllegalStateException, not a NullPointerException) so the
        // receiver's runCatching catches it as a transient error.
        val repo = makeRepo()
        repo.insert("s", null, null, "T", "u", 1L, 100L)
        repo.transitionTo("s", RecordingScheduleState.ARMED)
        repo.deleteById("s")
        assertFailsWith<IllegalStateException> {
            repo.transitionTo("s", RecordingScheduleState.MISSED)
        }
    }

    // ── deleteById / reapOlderThan ────────────────────────────────

    @Test fun deleteByIdRemovesRow() {
        val repo = makeRepo()
        repo.insert("s", null, null, "T", "u", 1L, 100L)
        repo.deleteById("s")
        assertNull(repo.getById("s"))
    }

    @Test fun reapOlderThanOnlyRemovesTerminalRows() {
        var nowMs = 1_700_000_000_000L
        val repo = makeRepo(clock = { nowMs })
        // A: scheduled (non-terminal) — must NOT be reaped
        repo.insert("a", null, null, "A", "u", 1L, 100L)
        // B: cancelled at t=now (terminal) — must be reaped after the cutoff
        repo.insert("b", null, null, "B", "u", 1L, 100L)
        repo.transitionTo("b", RecordingScheduleState.CANCELLED)
        // C: scheduled (non-terminal) — must NOT be reaped even though it's old
        repo.insert("c", null, null, "C", "u", 1L, 100L)

        // Advance clock far enough that all `updated_at` values are older than keepMs
        nowMs += 60L * 24L * 60L * 60_000L // 60 days

        val reaped = repo.reapOlderThan(keepMs = 30L * 24L * 60L * 60_000L)
        assertEquals(1, reaped) // only B was terminal AND old enough
        assertNotNull(repo.getById("a"))
        assertNull(repo.getById("b"))
        assertNotNull(repo.getById("c"))
    }

    // ── reconcileAfterBoot ────────────────────────────────────────

    @Test fun reconcileMarksLapsedArmedAsMissed() {
        var nowMs = 1_700_000_000_000L
        val repo = makeRepo(clock = { nowMs })
        // ARMED schedule whose window passed while the device was off:
        // scheduled_start was 10 minutes ago, grace is 5 minutes.
        repo.insert("late", null, null, "Late", "u", 1L, 100L)
        repo.transitionTo("late", RecordingScheduleState.ARMED)
        nowMs = 1L + 10L * 60_000L // start was at 1L, advance 10 minutes

        val result = repo.reconcileAfterBoot()

        assertEquals(1, result.markedMissed)
        assertEquals(0, result.markedFailedFromOrphan)
        val reconciled = repo.getById("late")!!
        assertEquals(RecordingScheduleState.MISSED, reconciled.state)
        assertEquals(
            RecordingScheduleRepository.REASON_DEVICE_OFFLINE,
            reconciled.error,
        )
    }

    @Test fun reconcileLeavesArmedFutureAlone() {
        var nowMs = 1_700_000_000_000L
        val repo = makeRepo(clock = { nowMs })
        // ARMED for 1 hour from now — must stay ARMED.
        repo.insert("future", null, null, "Future", "u", nowMs + 60L * 60_000L, nowMs + 90L * 60_000L)
        repo.transitionTo("future", RecordingScheduleState.ARMED)

        val result = repo.reconcileAfterBoot()

        assertEquals(0, result.markedMissed)
        val unchanged = repo.getById("future")!!
        assertEquals(RecordingScheduleState.ARMED, unchanged.state)
    }

    @Test fun reconcileMarksFiringAsFailedFromOrphan() {
        val repo = makeRepo()
        repo.insert("rebooted", null, null, "Rebooted", "u", 1L, 100L)
        repo.transitionTo("rebooted", RecordingScheduleState.ARMED)
        repo.transitionTo("rebooted", RecordingScheduleState.FIRING)
        // Reboot wiped the recording but the schedule's still in FIRING.

        val result = repo.reconcileAfterBoot()

        assertEquals(0, result.markedMissed)
        assertEquals(1, result.markedFailedFromOrphan)
        val reconciled = repo.getById("rebooted")!!
        assertEquals(RecordingScheduleState.FAILED, reconciled.state)
        assertEquals(
            RecordingScheduleRepository.REASON_ORPHANED_BY_APP_KILL,
            reconciled.error,
        )
    }

    @Test fun reconcileLeavesScheduledAlone() {
        // SCHEDULED rows haven't been armed yet; the alarm scheduler
        // will handle them on next pass. Reconcile shouldn't touch them.
        val repo = makeRepo()
        repo.insert("not-yet-armed", null, null, "T", "u", 1L, 100L)

        val result = repo.reconcileAfterBoot()

        assertEquals(0, result.markedMissed)
        assertEquals(0, result.markedFailedFromOrphan)
        assertEquals(
            RecordingScheduleState.SCHEDULED,
            repo.getById("not-yet-armed")!!.state,
        )
    }

    // ── allFlow reactivity (smoke) ────────────────────────────────

    @Test fun allFlowEmitsInitialEmpty() = runTest {
        val repo = makeRepo()
        assertTrue(repo.allFlow().first().isEmpty())
    }

    // MB-211 — the `deletingRecordingNullsScheduleRecordingId_doesNotCascade`
    // test lived here. It guarded ON DELETE SET NULL on
    // `recording_schedules.recording_id`. That column and its FK were
    // removed in schema v16, so the behaviour it asserted no longer
    // exists to assert. Deleting a recording still leaves the schedule
    // row untouched — now by construction rather than by FK action,
    // since nothing links them any more.
}
