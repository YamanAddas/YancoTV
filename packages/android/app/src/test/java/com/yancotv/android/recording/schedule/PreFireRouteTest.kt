package com.yancotv.android.recording.schedule

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MB-337 — [preFireRoute] contract.
 *
 * The defect these pin: the active-recording read was
 * `runCatching { … }.getOrDefault(emptyList())`, so "the query failed" and
 * "nothing is recording" produced the same value and the same branch. The
 * receiver then switched the player off the channel being recorded and opened a
 * second connection against a 1-stream provider — corrupt file, rejected
 * connection, no user-visible sign.
 *
 * `activeRecordingCount = null` versus `0` IS the fix, so most of these tests
 * exist to hold those two apart.
 *
 * Negative control (run 2026-07-31, required by the session rule): reverting
 * `preFireRoute` to treat null as 0 — i.e. `activeRecordingCount ?: 0` and
 * dropping the null branch — turns
 * `unreadable recording state fails CLOSED, it does not look idle` and
 * `unreadable is never confused with an idle recorder` RED. A suite that stays
 * green against the old behaviour would have pinned nothing.
 */
class PreFireRouteTest {
    // ───── The fix itself ─────

    @Test
    fun `unreadable recording state fails CLOSED, it does not look idle`() {
        // The whole bug. Nothing playing, count unreadable: the OLD code read
        // this as "0 recordings, nothing playing" and went HEADLESS_FRESH_GET,
        // opening a connection it could not know was the second one.
        assertEquals(
            PreFireRoute.MISSED_STATE_UNREADABLE,
            preFireRoute(sameChannel = false, activeRecordingCount = null, currentUrlPresent = false),
        )
        // Player on another channel + unreadable: the old code went
        // SWITCH_THEN_TEE and yanked the player off the recording channel.
        assertEquals(
            PreFireRoute.MISSED_STATE_UNREADABLE,
            preFireRoute(sameChannel = false, activeRecordingCount = null, currentUrlPresent = true),
        )
    }

    @Test
    fun `unreadable is never confused with an idle recorder`() {
        // Same inputs apart from null-vs-0, and the routes must differ. If these
        // two ever agree, the fix has been undone.
        val unreadable = preFireRoute(false, null, currentUrlPresent = true)
        val idle = preFireRoute(false, 0, currentUrlPresent = true)
        assertEquals(PreFireRoute.MISSED_STATE_UNREADABLE, unreadable)
        assertEquals(PreFireRoute.SWITCH_THEN_TEE, idle)
    }

    // ───── Teeing stays safe even when the state is unknown ─────

    @Test
    fun `same channel tees even when the recording state is unreadable`() {
        // Deliberate ordering: teeing off a connection that is ALREADY open
        // cannot breach the 1-stream cap, so refusing here would skip a
        // recording for no benefit. sameChannel therefore outranks the null
        // check.
        assertEquals(
            PreFireRoute.TEE_SAME_CHANNEL,
            preFireRoute(sameChannel = true, activeRecordingCount = null, currentUrlPresent = true),
        )
    }

    @Test
    fun `same channel tees even when another recording is already running`() {
        // Same reasoning: no new connection is opened.
        assertEquals(
            PreFireRoute.TEE_SAME_CHANNEL,
            preFireRoute(sameChannel = true, activeRecordingCount = 3, currentUrlPresent = true),
        )
    }

    // ───── Pre-existing behaviour that must not regress ─────

    @Test
    fun `a recording already in flight on another channel is missed as concurrent`() {
        assertEquals(
            PreFireRoute.MISSED_CONCURRENT,
            preFireRoute(sameChannel = false, activeRecordingCount = 1, currentUrlPresent = true),
        )
        assertEquals(
            PreFireRoute.MISSED_CONCURRENT,
            preFireRoute(sameChannel = false, activeRecordingCount = 1, currentUrlPresent = false),
        )
    }

    @Test
    fun `nothing playing and nothing recording takes the headless fresh-GET path`() {
        // MB-209: must NOT kick ExoPlayer with no Surface attached.
        assertEquals(
            PreFireRoute.HEADLESS_FRESH_GET,
            preFireRoute(sameChannel = false, activeRecordingCount = 0, currentUrlPresent = false),
        )
    }

    @Test
    fun `playing another channel with nothing recording switches then tees`() {
        assertEquals(
            PreFireRoute.SWITCH_THEN_TEE,
            preFireRoute(sameChannel = false, activeRecordingCount = 0, currentUrlPresent = true),
        )
    }

    // ───── Exhaustiveness ─────

    @Test
    fun `every input combination resolves to exactly one route and none are unreachable`() {
        // 2 x 3 x 2 = 12 combinations; asserts totality (no exception, no null)
        // and that the enum has no dead members — a route nobody can reach is
        // either a bug or dead code.
        val seen = mutableSetOf<PreFireRoute>()
        for (same in listOf(true, false)) {
            for (count in listOf(null, 0, 5)) {
                for (playing in listOf(true, false)) {
                    seen.add(preFireRoute(same, count, playing))
                }
            }
        }
        assertEquals(
            PreFireRoute.entries.toSet(),
            seen,
            "some routes are unreachable from any input combination",
        )
    }

    @Test
    fun `a large active count is still just concurrent`() {
        // No special-casing on magnitude — one is as blocking as ten.
        assertEquals(
            PreFireRoute.MISSED_CONCURRENT,
            preFireRoute(sameChannel = false, activeRecordingCount = 99, currentUrlPresent = true),
        )
    }
}
