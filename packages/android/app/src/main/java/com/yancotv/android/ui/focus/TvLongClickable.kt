package com.yancotv.android.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * MB-98 — register `onLongPress` as the active context-menu action while
 * this composable holds focus.
 *
 * **History.** v1 used `Modifier.composed { onPreviewKeyEvent { ... } }` —
 * the lambda body never executed in our chain. v2 used a `@Composable`
 * extension that returned `onPreviewKeyEvent { isLongPress -> ... }` plus
 * a parallel KEYCODE_MENU branch — both paths still failed on real Fire TV
 * hardware (long-press AND MENU behaved as a click). Root cause: the
 * system's `isLongPress` flag is only set when `KeyEvent.startTracking()`
 * was called on the matching DOWN, which Compose's preview-key path
 * doesn't do; and MENU appeared to be intercepted before reaching the
 * modifier chain.
 *
 * **Current design (v3).** Long-press detection + MENU handling moved up
 * to `MainActivity`, where they're routine Android patterns:
 *   - `onKeyDown` schedules a manual 500ms timer for CENTER/ENTER.
 *   - `onKeyDown` fires immediately for KEYCODE_MENU.
 *   - `onKeyUp` swallows the matching UP if the long-press already fired,
 *     so `combinedClickable.onClick` doesn't also fire on release.
 *
 * The Activity needs to know WHICH card to act on. That's this modifier's
 * job: when the host composable gains focus, we register `onLongPress` in
 * [TvContextActionState]; when it loses focus, we clear (only if we're
 * still the active token). The Activity calls `TvContextActionState.fire()`
 * and the focused card's handler runs.
 *
 * The call signature is unchanged from v2 so existing call sites (LiveCard,
 * PosterCard, …) need no churn beyond a comment refresh.
 *
 * Why `rememberUpdatedState`: the `onLongPress` lambda is fresh every
 * recomposition (closure over current `item`, `onLongPress` param). We
 * register a stable wrapper that always calls the latest captured value —
 * otherwise we'd register a stale lambda whenever the parent recomposes
 * without the focus state changing.
 */
@Composable
fun Modifier.tvLongClickable(onLongPress: () -> Unit): Modifier {
    val token = remember { Any() }
    val current by rememberUpdatedState(onLongPress)
    return this.onFocusChanged { state ->
        if (state.isFocused) {
            TvContextActionState.set(token) { current() }
        } else {
            TvContextActionState.clearIf(token)
        }
    }
}
