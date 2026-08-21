package com.yancotv.shared.history

import com.yancotv.shared.types.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MK.35.3 — the dwell rule that decides what counts as "watched".
 *
 * This is the rule the whole feature stands on. The browse coverflow
 * auto-previews LIVE channels on focus after a 400 ms debounce, so a naive
 * "record on play" would log every channel the cursor rested on and the Home
 * rail would become a replay of the user's scrolling rather than a list of what
 * they watch. The tests below are written against that specific failure.
 *
 * Kept pure and free of a database on purpose, mirroring `resumePointDecision`:
 * the decision is the interesting part, the INSERT is not.
 */
class RecentChannelsTest {

    @Test
    fun `a real viewing is recorded`() {
        assertTrue(shouldRecordChannel(ContentType.LIVE, watchedMs = 60_000L))
    }

    @Test
    fun `scrolling past a channel is not a viewing`() {
        // The coverflow's auto-preview debounce is 400 ms, so a cursor moving
        // through the channel list produces dwells in this range. Every one of
        // these would have landed in the rail without a threshold.
        assertFalse(shouldRecordChannel(ContentType.LIVE, watchedMs = 400L))
        assertFalse(shouldRecordChannel(ContentType.LIVE, watchedMs = 2_000L))
        assertFalse(shouldRecordChannel(ContentType.LIVE, watchedMs = 10_000L))
    }

    @Test
    fun `the threshold is far above VOD's five-second resume rule, deliberately`() {
        // resumePointDecision records a VOD resume point after 5 s. Reusing that
        // number here would put a channel in the rail for pausing on it, and the
        // costs are asymmetric: a wrong resume point costs one seek, a wrong
        // recent-channel entry misleads Home until it is pushed out.
        assertFalse(shouldRecordChannel(ContentType.LIVE, watchedMs = 5_000L))
        assertEquals(30_000L, RECENT_CHANNEL_DWELL_MS)
    }

    @Test
    fun `the boundary itself counts`() {
        assertTrue(shouldRecordChannel(ContentType.LIVE, watchedMs = RECENT_CHANNEL_DWELL_MS))
        assertFalse(shouldRecordChannel(ContentType.LIVE, watchedMs = RECENT_CHANNEL_DWELL_MS - 1))
    }

    @Test
    fun `VOD never enters the channel list, however long it played`() {
        // The rail is live-only. A movie belongs in Continue Watching, which has
        // a resume offset; putting it here would give the user two entry points
        // to the same title with different behaviour.
        assertFalse(shouldRecordChannel(ContentType.MOVIE, watchedMs = 3_600_000L))
        assertFalse(shouldRecordChannel(ContentType.SERIES, watchedMs = 3_600_000L))
    }

    @Test
    fun `a negative or zero dwell is never recorded`() {
        // elapsedRealtime arithmetic can produce these if a start marker was
        // never set — recording on that would put an unwatched channel in the
        // rail on the next stop.
        assertFalse(shouldRecordChannel(ContentType.LIVE, watchedMs = 0L))
        assertFalse(shouldRecordChannel(ContentType.LIVE, watchedMs = -1L))
    }
}
