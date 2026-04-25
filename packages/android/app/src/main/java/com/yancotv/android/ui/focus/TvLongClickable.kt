package com.yancotv.android.ui.focus

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * MB-98 — fire [onLongClick] when D-pad CENTER / ENTER is held past the
 * system long-press timeout (~500 ms).
 *
 * Why this exists: Compose Foundation's `Modifier.combinedClickable
 * (onLongClick = ...)` is reliable for touch but does not fire for
 * D-pad CENTER long-press on Android TV / Fire TV (verified on Compose
 * BOM 2025.01.00 / Foundation 1.7). The cards in [ContentRail] wired
 * `onLongClick` to open [ChannelActionsMenu] but the menu never opened
 * — short and long press both fell through to `onClick` (= "play").
 *
 * Implementation: watch the underlying [AndroidKeyEvent] flag
 * [AndroidKeyEvent.isLongPress] on the down event. When the OS marks an
 * event as long-press (it sets FLAG_LONG_PRESS at the timeout), fire
 * the callback and consume both that DOWN and the eventual UP, so
 * [androidx.compose.foundation.combinedClickable]'s `onClick` doesn't
 * also fire when the user releases.
 *
 * Apply this modifier ALONGSIDE `combinedClickable` on the same node;
 * it lives in the focused-element's preview-key chain so it sees the
 * event first. Intentionally also keeps `combinedClickable.onLongClick`
 * intact so touch users on phone still get the same gesture.
 *
 * Re-test on Compose Foundation upgrades — if the upstream primitive
 * starts honouring TV long-press, this modifier becomes redundant.
 */
fun Modifier.tvLongClickable(onLongClick: () -> Unit): Modifier =
    composed {
        // Tracks whether the current key gesture has already fired a
        // long-press. Survives recompositions so the matching UP can be
        // consumed even if the parent recomposes while the key is held.
        val swallowNextUp = remember { mutableStateOf(false) }
        onPreviewKeyEvent { event ->
            val keyCode = event.nativeKeyEvent.keyCode
            val isCenterKey =
                keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
            if (!isCenterKey) return@onPreviewKeyEvent false

            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (event.nativeKeyEvent.isLongPress) {
                        onLongClick()
                        swallowNextUp.value = true
                        // Consume this DOWN — combinedClickable would treat
                        // it as a repeat and could leak haptic / visual press
                        // state.
                        true
                    } else {
                        // First (and intermediate-repeat) DOWNs flow through
                        // to combinedClickable so it can keep its press-in-
                        // progress visual state coherent.
                        false
                    }
                }
                KeyEventType.KeyUp -> {
                    if (swallowNextUp.value) {
                        swallowNextUp.value = false
                        // Swallow the UP so combinedClickable doesn't fire
                        // its `onClick` on release after we already fired
                        // the long-press.
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }
