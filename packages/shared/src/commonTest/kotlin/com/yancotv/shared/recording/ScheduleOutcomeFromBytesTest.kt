package com.yancotv.shared.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * MB-212 — pins the bytes → schedule-terminal-state decision so the
 * service-side transition in `RecordingService.handleStop` and any
 * future caller agree on the cutoff between FAILED and COMPLETED.
 *
 * Pre-MB-212 the same logic lived inline in
 * `RecordingScheduleReceiver.handleEnd` and read the recording row's
 * `fileSizeBytes` BEFORE the service's async flush completed — racing
 * the row's own `markCompleted`. Extracted as a pure function (analog
 * to `RecordingRouting.decide` from MB-213, `resumePointDecision` from
 * MK.23.C.1, `decideRecoveryAction` from MK.24.G.2) so the contract is
 * pinned without standing up a Service / Receiver / DB.
 */
class ScheduleOutcomeFromBytesTest {
    @Test
    fun zeroBytesIsFailedNoResponseFromServer() {
        val out = scheduleOutcomeFromBytes(0L)
        assertEquals(RecordingScheduleState.FAILED, out.state)
        assertEquals(
            RecordingScheduleRepository.REASON_NO_RESPONSE_FROM_SERVER,
            out.reason,
        )
    }

    @Test
    fun negativeBytesIsAlsoFailed() {
        // size() can theoretically return a sentinel < 0 from a backend
        // whose probe failed. Treat the same as zero — the row is in
        // no shape to be considered COMPLETED.
        val out = scheduleOutcomeFromBytes(-1L)
        assertEquals(RecordingScheduleState.FAILED, out.state)
        assertEquals(
            RecordingScheduleRepository.REASON_NO_RESPONSE_FROM_SERVER,
            out.reason,
        )
    }

    @Test
    fun oneByteIsCompleted() {
        // Boundary case — strictly more than zero. We trust the recording
        // row's status to flag short or corrupt files; the schedule's
        // job is just "did the user's request capture anything at all".
        val out = scheduleOutcomeFromBytes(1L)
        assertEquals(RecordingScheduleState.COMPLETED, out.state)
        assertNull(out.reason)
    }

    @Test
    fun typicalRecordingIsCompleted() {
        // ~30 minutes of 5 Mbps live IPTV is ~1.1 GB. Pick a round
        // number well above zero so the test doc reads naturally.
        val out = scheduleOutcomeFromBytes(1_200_000_000L)
        assertEquals(RecordingScheduleState.COMPLETED, out.state)
        assertNull(out.reason)
    }
}
