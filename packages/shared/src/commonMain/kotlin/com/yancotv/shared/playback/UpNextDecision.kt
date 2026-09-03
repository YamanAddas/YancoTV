package com.yancotv.shared.playback

/**
 * MB-343 (plan W4 / MK.25.B.3) — when the up-next card is on screen.
 *
 * Autoplay fires from `STATE_ENDED` with no warning, so a binge either lurches
 * into the next episode or the user sits through credits with no idea anything
 * is queued. This decides the *warning* half; the card itself never advances
 * anything (see [UpNextOverlay]).
 *
 * Pure and Compose-free, mirroring [SeekAccelerator] and [DockTimeFormatter],
 * so the window arithmetic is unit-testable without a player.
 *
 * **Hysteresis is the whole point of having a kernel here.** A single threshold
 * (`remaining <= LEAD`) flickers: MB-338's accelerated hold reaches 300 s per
 * tick, so one gesture can cross the boundary, leave and re-cross several
 * times, and the card would blink on every crossing. Show and hide therefore
 * use *different* thresholds and the previous state is an input.
 */
object UpNextDecision {
    /** Remaining time at or below which the card appears. */
    const val SHOW_LEAD_MS: Long = 20_000L

    /**
     * Remaining time above which a shown card hides again — deliberately
     * larger than [SHOW_LEAD_MS]. The 5 s dead band means a seek has to move
     * the head meaningfully backwards to dismiss the card, not just jitter
     * across a single line.
     */
    const val HIDE_LEAD_MS: Long = 25_000L

    /**
     * Below this duration there is no card at all.
     *
     * Providers routinely ship stub rows typed as episodes — a 45 s "coming
     * soon" clip, a broken row whose duration is a few seconds. A fixed 20 s
     * lead on a 45 s item would cover the last 44% of it, which reads as a
     * bug rather than a courtesy.
     */
    const val MIN_DURATION_MS: Long = 120_000L

    /**
     * True when the card should be on screen this tick.
     *
     * @param hasTarget a next episode has been resolved. False for movies,
     *   live, the last episode of a series, and while the lookup is still in
     *   flight — the card must never promise something that cannot be played.
     * @param isPlaying the position is actually advancing. **Required**, and the
     *   reason is a countdown's credibility: while playback is paused the next
     *   episode is not starting in fourteen seconds, it is not starting at all,
     *   so a card that says otherwise is simply false. This also covers three
     *   states that are easy to miss — `onStop` pauses non-live playback and
     *   nothing resumes it, so every foreground return lands here; the sleep
     *   timer pauses; and a player parked at `STATE_ENDED` reports false too,
     *   which is what stops the card sitting at "NEXT IN 0:00" on the end frame
     *   when autoplay is off. A mid-playback rebuffer will blink the card, which
     *   is an accepted cosmetic cost: during a stall the countdown really has
     *   stopped, so hiding it is the honest answer.
     * @param suppressed another surface owns the screen (dock, chrome, options,
     *   surf list, PiP). Passing this in rather than reading it in the renderer
     *   keeps "never two cards in the same corner" a tested rule.
     * @param wasShowing the previous tick's answer — drives the hysteresis.
     */
    fun shouldShow(hasTarget: Boolean, positionMs: Long, durationMs: Long, isPlaying: Boolean, suppressed: Boolean, wasShowing: Boolean): Boolean {
        if (!hasTarget || suppressed || !isPlaying) return false
        // Media3 reports C.TIME_UNSET (a large negative) for live and for a
        // player that has not prepared yet, so any non-positive duration is
        // "unknown" — same rule DockTimeFormatter encodes. Without this the
        // card fires instantly on every live stream and every cold start.
        if (durationMs < MIN_DURATION_MS) return false
        val remaining = remainingMs(positionMs, durationMs)
        return if (wasShowing) remaining <= HIDE_LEAD_MS else remaining <= SHOW_LEAD_MS
    }

    /**
     * Milliseconds left, clamped to zero.
     *
     * The position can legitimately exceed the duration: ExoPlayer reports a
     * position past the end between the final buffer and `STATE_ENDED`, and
     * MB-338's accelerated seek deliberately clamps *to* the duration.
     */
    fun remainingMs(positionMs: Long, durationMs: Long): Long = (durationMs - positionMs).coerceAtLeast(0L)
}
