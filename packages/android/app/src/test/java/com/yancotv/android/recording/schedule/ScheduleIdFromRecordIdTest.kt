package com.yancotv.android.recording.schedule

import androidx.media3.common.util.UnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * MB-212 — pins the recordId → scheduleId inverse mapping that
 * `RecordingService.handleStop` uses to derive the schedule for a
 * post-flush transition without plumbing the id through RecordInput /
 * Intent extras / activeJobs.
 *
 * The forward direction is `recordIdForSchedule(scheduleId) =
 * "sched-rec-$scheduleId"` (the convention has been stable since
 * MK.14.3 / 2026-04-26). The inverse strips the prefix when present
 * and returns null otherwise — so manual record-now recordings,
 * ad-hoc URL captures, or any other id without the prefix are
 * correctly identified as "no schedule to transition".
 */
@UnstableApi
class ScheduleIdFromRecordIdTest {
    @Test
    fun roundTripsCanonicalScheduleId() {
        val scheduleId = "sched-2026-04-28-bbc-news-23h00"
        val recordId = RecordingScheduleScheduler.recordIdForSchedule(scheduleId)
        assertEquals(scheduleId, RecordingScheduleScheduler.scheduleIdFromRecordId(recordId))
    }

    @Test
    fun roundTripsUuidScheduleId() {
        // Schedule ids are generated as UUIDs in the EPG long-press flow.
        val scheduleId = "9f3b2a01-7e6d-4c3a-91ab-cde012345678"
        val recordId = RecordingScheduleScheduler.recordIdForSchedule(scheduleId)
        assertEquals(scheduleId, RecordingScheduleScheduler.scheduleIdFromRecordId(recordId))
    }

    @Test
    fun manualRecordingIdReturnsNull() {
        // A UUID without the sched-rec- prefix means the recording was
        // started by the player options sheet's "Record now" path.
        // Service should NOT try to transition any schedule for it.
        val manualRecordId = "9f3b2a01-7e6d-4c3a-91ab-cde012345678"
        assertNull(RecordingScheduleScheduler.scheduleIdFromRecordId(manualRecordId))
    }

    @Test
    fun emptyStringReturnsNull() {
        // Defensive — an empty recordId should never reach this code path,
        // but if it does (Intent extras corruption?) we want null, not "".
        assertNull(RecordingScheduleScheduler.scheduleIdFromRecordId(""))
    }

    @Test
    fun prefixOnlyExtractsEmptyScheduleId() {
        // "sched-rec-" with nothing after it is malformed — no schedule
        // could exist with an empty id (insert validates non-empty in
        // practice). The inverse correctly extracts what's there; the
        // downstream `transitionTo("")` would error on getById, swallowed
        // by the caller's runCatching. This test pins the literal string
        // behaviour, not a "should we treat empty as null" policy
        // (handled in the caller).
        assertEquals("", RecordingScheduleScheduler.scheduleIdFromRecordId("sched-rec-"))
    }

    @Test
    fun prefixCaseSensitive() {
        // Naming convention is lowercase. An id with mixed-case prefix
        // (typo, legacy data, manually-typed test fixture) is treated
        // as a non-scheduled id.
        assertNull(RecordingScheduleScheduler.scheduleIdFromRecordId("SCHED-REC-foo"))
        assertNull(RecordingScheduleScheduler.scheduleIdFromRecordId("Sched-Rec-foo"))
    }

    @Test
    fun prefixMustBeAtStart() {
        // The substring "sched-rec-" appearing later in an id (as part of
        // user-supplied content) should not be treated as a schedule id.
        assertNull(
            RecordingScheduleScheduler.scheduleIdFromRecordId("manual-sched-rec-xyz"),
        )
    }
}
