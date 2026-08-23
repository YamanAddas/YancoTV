package com.yancotv.android.player

import kotlin.math.abs

/**
 * MK.11.2 — pure decision layer for the phone player's swipe controls.
 *
 * Split out of [PlayerActivity] on the same reasoning as [AutoplayGuard] and
 * `DockAutoHide`: gesture arbitration is fiddly, easy to get subtly wrong,
 * and impossible to test through a `GestureDetector` on a real Surface.
 * Nothing here touches Android — it takes numbers and returns a decision.
 *
 * **Phone only.** The caller must gate on form factor; there is no touch
 * screen on a TV and a stray gesture layer there would compete with the
 * D-pad. This object deliberately knows nothing about that — it cannot
 * enforce it, so the gate lives at the single call site.
 */
enum class PlayerGesture {
    /** Vertical drag on the leading half — screen brightness. */
    BRIGHTNESS,

    /** Vertical drag on the trailing half — media volume. */
    VOLUME,

    /** Horizontal drag anywhere — scrub. */
    SEEK,

    /** Movement too small to be intentional. */
    NONE,
}

object PlayerGestures {
    /**
     * Full-height drag = this much brightness/volume change. 1.0 means a
     * single top-to-bottom swipe covers the entire range, which is the
     * behaviour every other video app has trained users to expect.
     */
    const val FULL_TRAVEL_FRACTION = 1.0f

    /** Full-width horizontal drag seeks this far. */
    const val FULL_WIDTH_SEEK_MS = 120_000L

    /**
     * Classify a drag, given where it started and how far it has travelled.
     *
     * The axis is decided from the DOMINANT component and is expected to be
     * latched by the caller for the rest of the drag: re-classifying every
     * frame lets a slightly diagonal swipe flicker between seeking and
     * changing volume, which feels broken and can leave the user having
     * done both.
     *
     * @param startX where the finger went down, in view pixels.
     * @param viewWidth width of the touch surface; <= 0 yields [PlayerGesture.NONE]
     *        rather than dividing by zero during teardown.
     * @param slopPx movement below this is ignored, so a tap that wobbles a
     *        pixel does not dim the screen.
     */
    fun classify(startX: Float, viewWidth: Int, totalDx: Float, totalDy: Float, slopPx: Float): PlayerGesture {
        if (viewWidth <= 0) return PlayerGesture.NONE
        if (abs(totalDx) < slopPx && abs(totalDy) < slopPx) return PlayerGesture.NONE
        if (abs(totalDx) >= abs(totalDy)) return PlayerGesture.SEEK
        return if (startX < viewWidth / 2f) PlayerGesture.BRIGHTNESS else PlayerGesture.VOLUME
    }

    /**
     * Convert vertical travel to a signed level change in 0..1 terms.
     *
     * Negated on purpose: screen coordinates grow downward, and dragging UP
     * must increase the value.
     */
    fun levelDelta(dy: Float, viewHeight: Int): Float {
        if (viewHeight <= 0) return 0f
        return -(dy / viewHeight) * FULL_TRAVEL_FRACTION
    }

    /** Convert horizontal travel to a signed seek offset in milliseconds. */
    fun seekDeltaMs(dx: Float, viewWidth: Int): Long {
        if (viewWidth <= 0) return 0L
        return ((dx / viewWidth) * FULL_WIDTH_SEEK_MS).toLong()
    }

    /**
     * Apply [delta] to [current], clamped to 0..1.
     *
     * Clamping here rather than at each call site is deliberate: brightness
     * and volume have different backing ranges and both were getting it
     * wrong independently in the first draft.
     */
    fun applyLevel(current: Float, delta: Float): Float = (current + delta).coerceIn(0f, 1f)
}
