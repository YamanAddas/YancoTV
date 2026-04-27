package com.yancotv.shared.recording

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.yancotv.shared.db.YancoDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * MB-216 — end-to-end coverage of the boot-time orphan-recovery flow.
 * Individually `RecordingsRepository.sweepOrphans` and
 * `RecordingScheduleRepository.reconcileAfterBoot` are unit-tested.
 * This test verifies that — given the realistic crash-mid-recording
 * scenario where both halves leave artefacts in the DB — running both
 * sweeps in sequence (the production order in `YancoApp.onCreate` +
 * `RecordingScheduleBootReceiver`) lands every row in a consistent
 * terminal state with no double-counting.
 */
class OrphanRecoveryIntegrationTest {
    private fun makeDb(): YancoDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // FKs OFF — the schedule.recording_id FK to recordings would
        // otherwise force ordering constraints that aren't relevant to
        // the orphan flow being tested. Mirrors the pattern in
        // RecordingScheduleRepositoryTest.
        driver.execute(null, "PRAGMA foreign_keys = OFF;", 0)
        YancoDb.Schema.create(driver)
        return YancoDb(driver)
    }

    @Test fun crashMidRecording_thenBoot_bothHalvesLandTerminal() {
        // Simulate: app crashed mid-recording at T=1_700_000_500_000.
        // - recordings row in RECORDING (no ended_at).
        // - recording_schedules row in FIRING with recording_id linked.
        // Reboot at T = original + 30 minutes (well past the orphan
        // threshold default of 10 min for recordings + 1 day for the
        // missed-window gate on schedules).
        val crashMs = 1_700_000_500_000L
        val rebootMs = crashMs + 30L * 60L * 1000L

        val db = makeDb()
        // Seed-time repos use the crash-time clock so the inserted rows
        // get realistic timestamps. Sweep-time repos use the reboot
        // clock — that's how the production sweeps see "this row is N
        // minutes old" against ORPHAN_THRESHOLD_MS_DEFAULT.
        val seedTimeRecordings = RecordingsRepository(db, clock = { crashMs - 30L * 60L * 1000L })
        val seedTimeSchedules = RecordingScheduleRepository(db, clock = { crashMs - 60_000L })

        val schedule =
            seedTimeSchedules.insert(
                id = "sched-mid",
                contentId = "ch-1",
                programmeId = "prog-1",
                title = "Champions League",
                streamUrl = "https://example.com/cl.m3u8",
                scheduledStart = crashMs - 60_000L,
                scheduledEnd = crashMs + 90L * 60L * 1000L,
            )
        seedTimeSchedules.transitionTo(schedule.id, RecordingScheduleState.ARMED)

        val recordingId = "rec-mid"
        seedTimeRecordings.markStarted(
            id = recordingId,
            contentId = "ch-1",
            title = "Champions League",
            streamUrl = "https://example.com/cl.m3u8",
            filePath = "/storage/cl-mid.ts",
            format = RecordingFormat.HLS,
        )
        seedTimeSchedules.linkRecording(schedule.id, recordingId)

        // Now switch to reboot-time repos for the sweep phase.
        val recordings = RecordingsRepository(db, clock = { rebootMs })
        val schedules = RecordingScheduleRepository(db, clock = { rebootMs })

        // ── Run both sweeps in production order ─────────────────────

        // 1. RecordingsRepository.sweepOrphans (called first in
        //    YancoApp.onCreate so any RECORDING row ≥ threshold flips
        //    to FAILED before the schedule reconcile reads it).
        val sweptRecordings = recordings.sweepOrphans()
        assertEquals(1, sweptRecordings, "sweepOrphans should reap exactly the one stale RECORDING row")

        // 2. RecordingScheduleRepository.reconcileAfterBoot.
        val report = schedules.reconcileAfterBoot()
        assertEquals(0, report.markedMissed, "FIRING row isn't 'missed', it's 'orphaned-from-firing'")
        assertEquals(1, report.markedFailedFromOrphan, "FIRING row should be reaped via the orphan path")

        // ── Verify terminal state ────────────────────────────────────

        val finalRecording = recordings.getById(recordingId)
        assertNotNull(finalRecording)
        assertEquals(RecordingStatus.FAILED, finalRecording.status)
        assertEquals("orphaned_by_app_kill", finalRecording.error)

        val finalSchedule = schedules.getById(schedule.id)
        assertNotNull(finalSchedule)
        assertEquals(RecordingScheduleState.FAILED, finalSchedule.state)
        // Reason is whatever reconcileAfterBoot writes — pin via the
        // existing repo test (`reconcileMarksFiringAsFailedFromOrphan`)
        // so we don't double-pin here.
    }

    @Test fun crashWithFreshRowAndArmedSchedule_neitherIsReaped() {
        // Negative case: row not yet stale + schedule still armed but
        // window not yet passed. Both sweeps should be no-ops.
        val nowMs = 1_700_000_000_000L
        val db = makeDb()
        val recordings = RecordingsRepository(db, clock = { nowMs })
        val schedules = RecordingScheduleRepository(db, clock = { nowMs })

        // Schedule starts 5 minutes from now — still armable, not lapsed.
        schedules.insert(
            id = "future",
            contentId = "ch-1",
            programmeId = null,
            title = "Future broadcast",
            streamUrl = "u",
            scheduledStart = nowMs + 5L * 60L * 1000L,
            scheduledEnd = nowMs + 95L * 60L * 1000L,
        )
        schedules.transitionTo("future", RecordingScheduleState.ARMED)
        // Recording started 1 second ago — not stale.
        recordings.markStarted("rec-fresh", null, "live", "u", "/p", RecordingFormat.HLS)

        assertEquals(0, recordings.sweepOrphans())
        val report = schedules.reconcileAfterBoot()
        assertEquals(0, report.total)

        // Verify nothing changed.
        assertEquals(RecordingStatus.RECORDING, recordings.getById("rec-fresh")?.status)
        assertEquals(RecordingScheduleState.ARMED, schedules.getById("future")?.state)
    }
}
