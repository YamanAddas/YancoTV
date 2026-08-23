package com.yancotv.android.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MK.11.2 — the phone player's swipe arbitration. */
class PlayerGesturesTest {
    private val width = 1080
    private val height = 1920
    private val slop = 16f

    // ── classification ──────────────────────────────────────────────

    @Test fun tinyMovementIsIgnored() {
        // a tap that wobbles must not dim the screen
        assertEquals(PlayerGesture.NONE, PlayerGestures.classify(100f, width, 4f, 5f, slop))
    }

    @Test fun leadingHalfVerticalIsBrightness() {
        assertEquals(PlayerGesture.BRIGHTNESS, PlayerGestures.classify(100f, width, 2f, -200f, slop))
    }

    @Test fun trailingHalfVerticalIsVolume() {
        assertEquals(PlayerGesture.VOLUME, PlayerGestures.classify(900f, width, 2f, -200f, slop))
    }

    @Test fun horizontalIsSeekFromEitherHalf() {
        assertEquals(PlayerGesture.SEEK, PlayerGestures.classify(100f, width, 300f, 20f, slop))
        assertEquals(PlayerGesture.SEEK, PlayerGestures.classify(900f, width, -300f, 20f, slop))
    }

    @Test fun exactlyDiagonalPrefersSeek() {
        // arbitrary but must be DECIDED, not left to float noise
        assertEquals(PlayerGesture.SEEK, PlayerGestures.classify(100f, width, 200f, 200f, slop))
    }

    @Test fun theHalfwayLineGoesToVolume() {
        assertEquals(PlayerGesture.VOLUME, PlayerGestures.classify(540f, width, 0f, -200f, slop))
        assertEquals(PlayerGesture.BRIGHTNESS, PlayerGestures.classify(539f, width, 0f, -200f, slop))
    }

    @Test fun zeroWidthIsSafe() {
        // can happen during teardown; must not divide by zero
        assertEquals(PlayerGesture.NONE, PlayerGestures.classify(0f, 0, 500f, 500f, slop))
    }

    // ── magnitudes ──────────────────────────────────────────────────

    @Test fun draggingUpIncreasesTheLevel() {
        // screen y grows downward, so up must be positive
        assertTrue(PlayerGestures.levelDelta(-height / 2f, height) > 0f)
        assertTrue(PlayerGestures.levelDelta(height / 2f, height) < 0f)
    }

    @Test fun fullHeightDragCoversTheWholeRange() {
        assertEquals(1.0f, PlayerGestures.levelDelta(-height.toFloat(), height), 0.001f)
    }

    @Test fun seekScalesWithWidthAndDirection() {
        assertEquals(PlayerGestures.FULL_WIDTH_SEEK_MS, PlayerGestures.seekDeltaMs(width.toFloat(), width))
        assertEquals(-PlayerGestures.FULL_WIDTH_SEEK_MS, PlayerGestures.seekDeltaMs(-width.toFloat(), width))
        assertEquals(PlayerGestures.FULL_WIDTH_SEEK_MS / 2, PlayerGestures.seekDeltaMs(width / 2f, width))
    }

    @Test fun levelsClampToTheUsableRange() {
        assertEquals(1f, PlayerGestures.applyLevel(0.9f, 0.5f))
        assertEquals(0f, PlayerGestures.applyLevel(0.1f, -0.5f))
        assertEquals(0.6f, PlayerGestures.applyLevel(0.5f, 0.1f), 0.001f)
    }
}
