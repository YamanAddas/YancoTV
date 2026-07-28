package com.yancotv.android.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * MK.31.2 — logical ("startward" / "endward") D-pad navigation.
 *
 * ### The problem
 *
 * YancoTV's shell is a start-anchored rail: sidebar, then categories, then
 * content. Every "go deeper" / "come back" handler in the app was written
 * against **physical** keys — `Key.DirectionRight` to move into content,
 * `Key.DirectionLeft` to come back — because in LTR physical and logical
 * happen to coincide.
 *
 * They stop coinciding under RTL. Arabic puts the sidebar on the **right**, so
 * coming back from content means pressing physical RIGHT, and the hardcoded
 * handlers drive focus the wrong way. This is not cosmetic: the sidebar-exit
 * handlers are how you leave a pane, so an Arabic user could get focus stuck.
 *
 * ### Why the fix has to be explicit
 *
 * Compose mirrors a lot for free — `padding(start = …)`, `Alignment.CenterStart`,
 * `Row` order, `Modifier.offset`'s x axis — and this codebase already uses those
 * logical forms everywhere (no `padding(left =)`, no `Alignment.Absolute*`, no
 * `absoluteOffset` anywhere in the tree). Two things it does **not** mirror:
 *
 *  - `Key.DirectionLeft` / `Key.DirectionRight` are hardware key codes. There is
 *    nothing to mirror; the user physically pressed a side.
 *  - `FocusDirection.Left` / `.Right` are physical too. `focusProperties` offers
 *    RTL-aware `start` / `end` slots, but the `exit` lambda receives the raw
 *    physical direction, and `FocusManager.moveFocus` takes a physical one. Both
 *    are used here deliberately (see `SettingsScreen`'s ContentPane: `exit` only
 *    fires when no in-group target exists, which is what lets chip rows keep
 *    their own horizontal navigation — `start`/`end` would always redirect).
 *
 * So: resolve intent through these helpers instead of naming a side.
 *
 * ### Vocabulary
 *
 * **Startward** = toward the layout's start edge = toward the sidebar = "back
 * out". **Endward** = toward the content = "go deeper". Both are pure functions
 * of [LayoutDirection] so they're unit-testable; the `@Composable` overloads
 * just read [LocalLayoutDirection].
 */

/** Physical D-pad key that moves toward the layout start (LTR: left, RTL: right). */
fun startwardKey(layoutDirection: LayoutDirection): Key = if (layoutDirection == LayoutDirection.Rtl) Key.DirectionRight else Key.DirectionLeft

/** Physical D-pad key that moves toward the layout end (LTR: right, RTL: left). */
fun endwardKey(layoutDirection: LayoutDirection): Key = if (layoutDirection == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight

/** [FocusDirection] that moves toward the layout start. */
fun startwardFocus(layoutDirection: LayoutDirection): FocusDirection = if (layoutDirection == LayoutDirection.Rtl) FocusDirection.Right else FocusDirection.Left

/** [FocusDirection] that moves toward the layout end. */
fun endwardFocus(layoutDirection: LayoutDirection): FocusDirection = if (layoutDirection == LayoutDirection.Rtl) FocusDirection.Left else FocusDirection.Right

/**
 * True when [direction] points toward the layout start — i.e. "the user is
 * trying to back out of this pane". Use in `focusProperties { exit = { … } }`,
 * whose lambda receives a physical direction.
 */
fun isStartward(direction: FocusDirection, layoutDirection: LayoutDirection): Boolean = direction == startwardFocus(layoutDirection)

/** True when [direction] points toward the layout end — "go deeper". */
fun isEndward(direction: FocusDirection, layoutDirection: LayoutDirection): Boolean = direction == endwardFocus(layoutDirection)

// ───── Modifiers ─────

/**
 * Runs [onStartward] when the user presses the D-pad toward the layout start —
 * "back out of this pane". Replaces hand-rolled
 * `onPreviewKeyEvent { it.key == Key.DirectionLeft }` blocks, which are correct
 * only in LTR, and folds in the `KeyDown` filter every one of them needed
 * (without it the handler fires twice, once on the key-up).
 *
 * [onStartward] returns whether it **consumed** the press. That is a real
 * per-site decision, not boilerplate: most of these are edge-escape handlers
 * that must consume, because letting the press also reach Compose's spatial
 * search would move focus twice. But the coverflow only escapes from its
 * leading orb — mid-wheel presses have to fall through to the `LazyRow`'s own
 * traversal. Returning Boolean makes forgetting that a compile error rather
 * than a silent double-move.
 */
@Composable
fun Modifier.onStartwardKey(onStartward: () -> Boolean): Modifier {
    val key = startwardKey()
    return onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown && event.key == key && onStartward()
    }
}

/** Mirror of [onStartwardKey] for the "go deeper" direction. */
@Composable
fun Modifier.onEndwardKey(onEndward: () -> Boolean): Modifier {
    val key = endwardKey()
    return onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown && event.key == key && onEndward()
    }
}

// ───── Composable conveniences ─────

@Composable
@ReadOnlyComposable
fun startwardKey(): Key = startwardKey(LocalLayoutDirection.current)

@Composable
@ReadOnlyComposable
fun endwardKey(): Key = endwardKey(LocalLayoutDirection.current)

@Composable
@ReadOnlyComposable
fun startwardFocus(): FocusDirection = startwardFocus(LocalLayoutDirection.current)

@Composable
@ReadOnlyComposable
fun endwardFocus(): FocusDirection = endwardFocus(LocalLayoutDirection.current)
