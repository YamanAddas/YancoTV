package com.yancotv.android.ui.focus

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

/**
 * Focus anchor that guarantees [awaitAndRequest] lands on a node that is
 * actually in the composition tree. Replaces the delay-ladder pattern
 * that plagued the app's focus-on-open effects.
 *
 * Compose's [FocusRequester.requestFocus] throws when the backing node
 * isn't placed yet. The usual workaround:
 *
 *     LaunchedEffect(key) {
 *         for (d in longArrayOf(60L, 180L, 400L)) {
 *             delay(d); if (runCatching { focus.requestFocus() }.isSuccess) break
 *         }
 *     }
 *
 * That's a race: if placement takes longer than the ladder, focus silently
 * fails; if it lands sooner, we burn frames for nothing. [PlacedFocusAnchor]
 * replaces the ladder with a suspend [awaitAndRequest] that waits for
 * [Modifier.placedFocus]' onPlaced hook to fire, then issues the request
 * once — deterministically.
 *
 * Usage:
 *
 *     val anchor = rememberPlacedFocusAnchor()
 *     LaunchedEffect(loaded) { anchor.awaitAndRequest() }
 *     // ...
 *     Row(modifier = Modifier.placedFocus(anchor)) { ... }
 */
@Stable
class PlacedFocusAnchor internal constructor() {
    internal val requester = FocusRequester()
    internal var isPlaced: Boolean by mutableStateOf(false)
        private set

    internal fun markPlaced() {
        isPlaced = true
    }

    /**
     * Suspend until the anchor's modified node has been placed, then
     * request focus. If already placed, fires immediately. Safe to call
     * more than once per recomposition — each call is a fresh request.
     */
    suspend fun awaitAndRequest() {
        snapshotFlow { isPlaced }.first { it }
        runCatching { requester.requestFocus() }
    }
}

@Composable
fun rememberPlacedFocusAnchor(): PlacedFocusAnchor =
    remember { PlacedFocusAnchor() }

/**
 * Attach an anchor to this node: the anchor's [FocusRequester] is bound
 * and the anchor flips to "placed" once Compose calls onPlaced. Does NOT
 * make the node focusable on its own — combine with [Modifier.focusable]
 * or a clickable/focusable child.
 */
fun Modifier.placedFocus(anchor: PlacedFocusAnchor): Modifier =
    this
        .onPlaced { anchor.markPlaced() }
        .focusRequester(anchor.requester)

/**
 * Invisible 0-dp focus trap. Drop this as the first child of an overlay
 * that must own focus before its "real" focus target (Play button, first
 * list item) is ready. Combined with [PlacedFocusAnchor.awaitAndRequest],
 * the overlay reliably holds focus from the moment it appears.
 *
 * NOTE: this is a [Spacer] with `.focusable()` — NOT `.clickable` — so
 * CENTER presses on the anchor are no-ops that never intercept child
 * activations. This is the explicit fix for the "wrapper .clickable{}
 * swallows episode-row onClick" bug.
 */
@Composable
fun FocusTrap(anchor: PlacedFocusAnchor) {
    Spacer(
        modifier = Modifier
            .size(0.dp)
            .placedFocus(anchor)
            .focusable(),
    )
}
