package com.yancotv.android.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MB-418 — "no jobs left" was never the same question as "nothing left to do".
 *
 * The service stopped itself the instant `activeJobs` emptied. `handleStop`
 * empties it synchronously and then writes the recording's outcome in a
 * coroutine, so the service killed its own scope mid-transition and the row
 * stayed RECORDING for ever — which the owner saw as "I stopped it, it did not
 * stop, and then the recording was deleted".
 *
 * These hold the corrected question. They are small on purpose: the bug was
 * one line, and one line is exactly what had no name and therefore no test.
 */
class RecordingServiceLifecycleTest {

    @Test
    fun `nothing running and nothing finalising - the service may stop`() {
        assertTrue(canStopService(activeJobs = 0, finalising = 0))
    }

    @Test
    fun `a recording still running holds the service open`() {
        assertFalse(canStopService(activeJobs = 1, finalising = 0))
    }

    @Test
    fun `THE BUG - an empty job map while a stop is still finalising`() {
        // This is the exact state handleStop creates: the job is out of the
        // map, and its outcome has not been written yet. Answering true here
        // is what cancelled the service scope out from under the transition.
        assertFalse(canStopService(activeJobs = 0, finalising = 1))
    }

    @Test
    fun `two stops racing each other both have to finish`() {
        // handleStopAll fans out over every id at once.
        assertFalse(canStopService(activeJobs = 0, finalising = 2))
    }

    @Test
    fun `a negative count is treated as none, not as work`() {
        // A decrement that outran its increment must not wedge the service
        // open for the rest of the process's life. Belt and braces: the
        // counter is balanced by try/finally, but a service that can never
        // stop is a worse failure than one that stops slightly early.
        assertTrue(canStopService(activeJobs = 0, finalising = -1))
        assertTrue(canStopService(activeJobs = -1, finalising = 0))
    }
}
