package com.yancotv.android.ui.shell

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [resolveActivation] — the two-tap TV activation guard.
 *
 * Rule (native-android-mk skill):
 *   "Every launch site checks `controller.currentId == target.id` first.
 *   If already playing, go straight to fullscreen — do NOT call
 *   controller.play() again (re-creates the MediaItem, rebuffers)."
 *
 * This pins the routing table so a future refactor of FavoritesScreen,
 * BrowseShell, or any new launch site that uses resolveActivation can't
 * accidentally re-introduce the rebuffer bug.
 *
 * Why the guard matters: ExoPlayer's play(list, index) always calls
 * setMediaItem() + prepare(). On a second tap of the same live channel,
 * that rebuffers the stream — the user sees a spinner mid-watch. On
 * IPTV live streams (no pre-roll, no offline manifest cache), rebuffers
 * routinely take 1–3 seconds. The guard turns second-tap into a free
 * fullscreen open.
 */
class ActivationGuardTest {
    // ── TV, not already playing ──────────────────────────────────────────

    @Test fun tvFirstTapOnNewItem_callsPlayAndDoesNotLaunchFullscreen() {
        val action = resolveActivation(currentId = "ch-1", targetId = "ch-2", isTv = true)
        assertTrue(action.shouldCallPlay, "first tap on different item must call play()")
        assertFalse(
            action.shouldLaunchFullscreen,
            "TV first tap on different item must not launch fullscreen — " +
                "second tap does that",
        )
    }

    @Test fun tvSecondTapOnCurrentItem_skipsPlayAndLaunchesFullscreen() {
        // The critical path: user taps a live channel that's already loaded
        // in the MiniPlayer. Second tap must go straight to fullscreen
        // without calling play() (which would rebuffer the stream).
        val action = resolveActivation(currentId = "ch-1", targetId = "ch-1", isTv = true)
        assertFalse(
            action.shouldCallPlay,
            "second tap on already-playing item must NOT call play() — " +
                "would rebuffer the stream",
        )
        assertTrue(
            action.shouldLaunchFullscreen,
            "second tap on already-playing item must launch fullscreen",
        )
    }

    @Test fun tvNothingPlaying_callsPlayAndDoesNotLaunchFullscreen() {
        // Nothing in the player yet (cold launch, user just opened the app).
        val action = resolveActivation(currentId = null, targetId = "ch-1", isTv = true)
        assertTrue(action.shouldCallPlay)
        assertFalse(action.shouldLaunchFullscreen)
    }

    // ── Phone, single-tap-to-fullscreen ──────────────────────────────────

    @Test fun phoneNewItem_callsPlayAndLaunchesFullscreen() {
        // Phone UX: one tap does everything — play + fullscreen in one action.
        val action = resolveActivation(currentId = null, targetId = "ch-1", isTv = false)
        assertTrue(action.shouldCallPlay)
        assertTrue(action.shouldLaunchFullscreen)
    }

    @Test fun phoneAlreadyPlaying_skipsPlayAndLaunchesFullscreen() {
        // Phone: tapping the currently-playing item reopens the fullscreen
        // player without re-preparing (same guard as TV, just also launches).
        val action = resolveActivation(currentId = "ch-1", targetId = "ch-1", isTv = false)
        assertFalse(action.shouldCallPlay)
        assertTrue(action.shouldLaunchFullscreen)
    }

    @Test fun phoneNewItem_differentFromCurrent_callsPlayAndLaunchesFullscreen() {
        val action = resolveActivation(currentId = "ch-1", targetId = "ch-2", isTv = false)
        assertTrue(action.shouldCallPlay)
        assertTrue(action.shouldLaunchFullscreen)
    }
}
