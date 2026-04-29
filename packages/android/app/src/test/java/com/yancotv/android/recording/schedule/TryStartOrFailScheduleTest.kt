package com.yancotv.android.recording.schedule

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * MB-215 — pin the FGS-start-failure → schedule-FAILED contract. The
 * receiver wraps RecordingService.start in runCatching so a thrown
 * `ForegroundServiceStartNotAllowedException` (Fire TV background-
 * restriction edge) routes the schedule to FAILED instead of leaving
 * it stuck in FIRING for the end alarm to optimistically COMPLETE.
 *
 * Tests use captured-lambda fakes so the body runs as JVM unit tests
 * without standing up a Receiver, Service, Context, or Koin graph.
 */
class TryStartOrFailScheduleTest {
    @Test fun successfulStart_doesNotCallTransitionFailed() {
        var failedReason: String? = null
        var startCalled = false

        tryStartOrFailSchedule(
            scheduleId = "s",
            tag = "TestTag",
            startService = { startCalled = true },
            transitionFailed = { failedReason = it },
        )

        assertTrue(startCalled)
        assertNull(failedReason)
    }

    @Test fun thrownStart_routesToTransitionFailedWithMessagePrefix() {
        var failedReason: String? = null

        tryStartOrFailSchedule(
            scheduleId = "s",
            tag = "TestTag",
            startService = { error("FGS not allowed") },
            transitionFailed = { failedReason = it },
        )

        // Reason format pinned: importable into UI / Sentry without
        // surprises. Receiver writes this verbatim into the schedule's
        // `error` column.
        assertEquals("service_start_failed: FGS not allowed", failedReason)
    }

    @Test fun thrownWithNullMessage_fallsBackToClassName() {
        var failedReason: String? = null

        tryStartOrFailSchedule(
            scheduleId = "s",
            tag = "TestTag",
            startService = { throw RuntimeException() }, // null message
            transitionFailed = { failedReason = it },
        )

        // RuntimeException with no message → simple class name in the
        // reason so the user / log reader sees something concrete.
        assertEquals("service_start_failed: RuntimeException", failedReason)
    }
}
