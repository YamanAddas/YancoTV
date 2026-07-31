package com.yancotv.android.player

/**
 * MB-338 — how far a held D-pad seek should jump on each auto-repeat tick.
 *
 * ### The defect this replaces
 *
 * `PlayerActivity.onKeyDown` handled `DPAD_LEFT` / `DPAD_RIGHT` by calling
 * `seekTo(currentPosition ± 10_000)` on **every** key-down with no
 * `repeatCount` filter (`repeatCount` was checked only for the CENTER
 * long-press, at PlayerActivity.kt:2239 and 2266). Android's auto-repeat then
 * delivered one discrete 10-second jump per tick, so holding the key scrubbed in
 * uneven 10s lurches at whatever rate the OS happened to repeat at — on the
 * most-used control on the remote.
 *
 * ### The model
 *
 * A tap stays exactly what it was: one 10-second step, so no muscle memory
 * breaks. Holding escalates through tiers by *elapsed hold time*, not by tick
 * count — tick cadence is an OS/hardware detail that differs between a Fire TV
 * remote, a Google TV remote and a USB keyboard, and pinning behaviour to it
 * would make the same gesture feel different on each. Elapsed time is the thing
 * the user is actually controlling.
 *
 * Deliberately NOT a continuous rate: discrete tiers are predictable and land on
 * round numbers, and each tier change is something the on-screen flash can
 * announce. A smooth curve reads as drift and is impossible to aim.
 *
 * Pure and frame-independent so the tiers are unit-testable without a player,
 * a looper, or a device — see `SeekAcceleratorTest`.
 */
object SeekAccelerator {
    /** A single tap, and the first tick of any hold. Unchanged from pre-MB-338. */
    const val BASE_STEP_SEC: Int = 10

    /**
     * Elapsed-hold thresholds and the step size that applies from each.
     *
     * Ordered longest-first so [stepSecondsForHold] can return on the first
     * match. Tuned so a ~1s hold is still fine-grained (10s), ~2s covers an ad
     * break (30s), ~4s crosses a scene (60s), and a long hold moves in
     * five-minute chunks for feature-length content — while staying slow enough
     * that overshooting is recoverable with a tap or two in the other direction.
     */
    private val TIERS: List<Pair<Long, Int>> = listOf(
        6_000L to 300,
        4_000L to 60,
        2_000L to 30,
        0L to BASE_STEP_SEC,
    )

    /**
     * Step size, in seconds, for a hold that started [holdElapsedMs] ago.
     *
     * A negative elapsed time (clock skew, or a stale gesture-start stamp)
     * degrades to [BASE_STEP_SEC] rather than throwing or picking a wild tier —
     * a suspect timestamp must never turn into a five-minute jump.
     */
    fun stepSecondsForHold(holdElapsedMs: Long): Int {
        if (holdElapsedMs <= 0L) return BASE_STEP_SEC
        return TIERS.first { holdElapsedMs >= it.first }.second
    }

    /**
     * The signed seek delta for one tick.
     *
     * @param forward RIGHT/fast-forward when true, LEFT/rewind when false.
     * @param repeatCount the `KeyEvent.repeatCount`. Zero means a fresh press —
     *   always [BASE_STEP_SEC], which is what preserves tap behaviour exactly.
     * @param holdElapsedMs how long the current hold has been in progress.
     */
    fun deltaSecondsForTick(forward: Boolean, repeatCount: Int, holdElapsedMs: Long): Int {
        val magnitude = if (repeatCount == 0) BASE_STEP_SEC else stepSecondsForHold(holdElapsedMs)
        return if (forward) magnitude else -magnitude
    }

    /**
     * Clamp a target position into the seekable range.
     *
     * Extracted with the tiers because at 300 s/tick a hold overruns the ends of
     * the content in a fraction of a second, and an unclamped `seekTo` past the
     * duration is what makes a player jump to the next item or report STATE_ENDED
     * mid-gesture. [durationMs] <= 0 means unknown (live, or not yet prepared),
     * in which case only the lower bound is enforceable.
     */
    fun clampTargetMs(targetMs: Long, durationMs: Long): Long = when {
        targetMs < 0L -> 0L
        durationMs > 0L && targetMs > durationMs -> durationMs
        else -> targetMs
    }
}
