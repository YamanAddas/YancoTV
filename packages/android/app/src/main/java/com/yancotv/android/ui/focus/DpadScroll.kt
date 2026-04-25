package com.yancotv.android.ui.focus

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * MB-116 — make a read-only [androidx.compose.foundation.verticalScroll]
 * container drivable by the D-pad on TV.
 *
 * Compose only auto-scrolls when focus traversal moves the focused
 * descendant past the viewport edge — i.e. you need a focusable child
 * inside the scroll. Read-only tabs (About, Shortcuts, placeholders)
 * are pure `Text` columns with nothing to focus, so the focused element
 * stays on the parent's [FocusableSpacer] and D-pad UP/DOWN do nothing.
 *
 * This modifier:
 *   1. Makes the node itself focusable, so [SettingsScreen]'s
 *      `moveFocus(Right)` from the sub-sidebar lands inside the tab pane
 *      rather than on a sibling spacer.
 *   2. Intercepts D-pad UP / DOWN via [onPreviewKeyEvent] and calls
 *      [ScrollState.animateScrollBy] for one viewport-page worth of
 *      pixels (≈240dp) per press.
 *   3. Returns `false` from the handler when the scroll is already at
 *      its boundary, so a press at the top still bubbles up and the
 *      user can D-pad LEFT back to the sub-sidebar — focus is never
 *      trapped on a fully-scrolled page.
 *
 * Usage:
 * ```
 * val scroll = rememberScrollState()
 * Column(
 *     modifier = Modifier
 *         .verticalScroll(scroll)
 *         .dpadVerticalScroll(scroll)
 *         .padding(...)
 * ) { ... }
 * ```
 */
@Composable
fun Modifier.dpadVerticalScroll(scrollState: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val pagePx = with(density) { 240.dp.toPx() }
    return this
        .focusable()
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionDown -> {
                    if (scrollState.value >= scrollState.maxValue) {
                        false
                    } else {
                        scope.launch { scrollState.animateScrollBy(pagePx) }
                        true
                    }
                }
                Key.DirectionUp -> {
                    if (scrollState.value <= 0) {
                        false
                    } else {
                        scope.launch { scrollState.animateScrollBy(-pagePx) }
                        true
                    }
                }
                else -> false
            }
        }
}

// Note: this lives next to [PlacedFocusAnchor] / [FocusableSpacer] because
// it's the same family of "make TV focus + D-pad work properly" primitives.
// Implementation is composable-aware (rememberCoroutineScope / LocalDensity)
// so the signature is @Composable rather than a plain Modifier extension —
// matches the convention already in use in this file family.
