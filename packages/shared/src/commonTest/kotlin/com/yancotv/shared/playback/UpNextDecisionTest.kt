package com.yancotv.shared.playback

/**
 * MB-343 — the up-next card's visibility window.
 *
 * The tests that matter here are the ASYMMETRY ones. A kernel with a single
 * threshold passes any test that only ever checks one value per state, so
 * several cases below deliberately feed the *same* remaining time with both
 * `wasShowing` values and assert the answers differ. Collapsing show and hide
 * onto one constant turns those red; nothing else in the file would notice.
 */
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpNextDecisionTest {
    private val dur = 30 * 60_000L // 30-minute episode

    private fun at(remainingMs: Long) = dur - remainingMs

    private fun show(
        remainingMs: Long,
        wasShowing: Boolean = false,
        hasTarget: Boolean = true,
        suppressed: Boolean = false,
        durationMs: Long = dur,
        isPlaying: Boolean = true,
    ) = UpNextDecision.shouldShow(
        hasTarget = hasTarget,
        positionMs = durationMs - remainingMs,
        durationMs = durationMs,
        isPlaying = isPlaying,
        suppressed = suppressed,
        wasShowing = wasShowing,
    )

    @Test
    fun `hidden in the body of the episode`() {
        assertFalse(show(remainingMs = 10 * 60_000L))
    }

    @Test
    fun `appears at the show lead`() {
        assertTrue(show(remainingMs = UpNextDecision.SHOW_LEAD_MS))
        assertTrue(show(remainingMs = UpNextDecision.SHOW_LEAD_MS - 1))
    }

    @Test
    fun `does not appear one millisecond early`() {
        assertFalse(show(remainingMs = UpNextDecision.SHOW_LEAD_MS + 1))
    }

    // ───── the asymmetry pair: same input, different previous state ─────

    @Test
    fun `between the two leads a hidden card stays hidden`() {
        val between = (UpNextDecision.SHOW_LEAD_MS + UpNextDecision.HIDE_LEAD_MS) / 2
        assertFalse(show(between, wasShowing = false), "$between ms left with the card down must not raise it")
    }

    @Test
    fun `between the two leads a shown card stays shown`() {
        val between = (UpNextDecision.SHOW_LEAD_MS + UpNextDecision.HIDE_LEAD_MS) / 2
        assertTrue(show(between, wasShowing = true), "$between ms left with the card up must not drop it")
    }

    @Test
    fun `a shown card hides only past the hide lead`() {
        assertTrue(show(UpNextDecision.HIDE_LEAD_MS, wasShowing = true))
        assertFalse(show(UpNextDecision.HIDE_LEAD_MS + 1, wasShowing = true))
    }

    @Test
    fun `an accelerated backward seek dismisses the card and a small jitter does not`() {
        // MB-338's hold reaches 300 s per tick. A real backward seek must clear
        // the card; sub-second position jitter around the show line must not.
        assertFalse(show(300_000L, wasShowing = true), "a 300s rewind must dismiss")
        assertTrue(show(UpNextDecision.SHOW_LEAD_MS + 400L, wasShowing = true), "400ms of jitter must not dismiss")
    }

    @Test
    fun `a stopped clock shows no countdown`() {
        // The card asserts something about the future. While playback is paused
        // the next episode is not starting in ten seconds, it is not starting at
        // all, so the card must go — including on the hide edge, where the
        // hysteresis would otherwise hold it up.
        assertFalse(show(remainingMs = 10_000L, isPlaying = false))
        assertFalse(show(remainingMs = 10_000L, isPlaying = false, wasShowing = true))
    }

    @Test
    fun `pausing is what hides it - not the position moving`() {
        // Asymmetry guard: same remaining time, same previous state, only
        // isPlaying differs. A kernel that ignored the input would pass every
        // other test in this file.
        val remaining = 8_000L
        assertTrue(show(remaining, wasShowing = true, isPlaying = true))
        assertFalse(show(remaining, wasShowing = true, isPlaying = false))
    }

    // ───── the gates ─────

    @Test
    fun `no card without a resolved target`() {
        // Movies, live, last episode of a series, and the in-flight window
        // before the DB lookup returns all land here. The card must never
        // promise an episode that cannot be played.
        assertFalse(show(remainingMs = 5_000L, hasTarget = false))
        assertFalse(show(remainingMs = 5_000L, hasTarget = false, wasShowing = true))
    }

    @Test
    fun `suppressed while another surface owns the screen`() {
        assertFalse(show(remainingMs = 5_000L, suppressed = true))
        // Also drops an already-shown card — opening the dock mid-card must
        // not leave two things in the same corner.
        assertFalse(show(remainingMs = 5_000L, suppressed = true, wasShowing = true))
    }

    @Test
    fun `unknown duration never shows the card`() {
        // Media3 reports C.TIME_UNSET for live and for a player that has not
        // prepared. A naive `duration - position <= LEAD` is TRUE for these,
        // so this is the case that would put an UP NEXT card on live TV.
        // Was `androidx.media3.common.C.TIME_UNSET`, which does not exist
        // outside Android. Its value is what matters: a large negative
        // standing for "unknown duration", which is what live streams and
        // an unprepared player both report. AVFoundation and libVLC use 0
        // or NaN-derived zeros for the same state, covered below.
        assertFalse(show(remainingMs = 0L, durationMs = Long.MIN_VALUE + 1))
        assertFalse(show(remainingMs = 0L, durationMs = 0L))
        assertFalse(show(remainingMs = 0L, durationMs = -1L))
    }

    @Test
    fun `a short provider stub gets no card`() {
        // A 45s row typed as an episode: a 20s lead would cover its last 44%.
        val stub = 45_000L
        assertFalse(show(remainingMs = 20_000L, durationMs = stub))
        // And the floor is a floor, not a suggestion.
        assertFalse(show(remainingMs = 10_000L, durationMs = UpNextDecision.MIN_DURATION_MS - 1))
        assertTrue(show(remainingMs = 10_000L, durationMs = UpNextDecision.MIN_DURATION_MS))
    }

    @Test
    fun `the hide lead is above the show lead`() {
        // The hysteresis only exists if these differ. A refactor that unifies
        // them would otherwise be caught by exactly two tests above; this one
        // states the invariant directly so the reason is findable.
        assertTrue(
            UpNextDecision.HIDE_LEAD_MS > UpNextDecision.SHOW_LEAD_MS,
            "HIDE_LEAD_MS must exceed SHOW_LEAD_MS or the card flickers on every seek across the line",
        )
    }

    // ───── remaining ─────

    @Test
    fun `remaining is clamped at zero past the end`() {
        // ExoPlayer reports a position past the duration between the final
        // buffer and STATE_ENDED; a negative countdown would render as "-0:03".
        assertEquals(0L, UpNextDecision.remainingMs(positionMs = dur + 3_000L, durationMs = dur))
        assertEquals(0L, UpNextDecision.remainingMs(positionMs = dur, durationMs = dur))
    }

    @Test
    fun `remaining counts down`() {
        assertEquals(14_000L, UpNextDecision.remainingMs(positionMs = at(14_000L), durationMs = dur))
    }
}
