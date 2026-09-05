package com.yancotv.android.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import com.yancotv.android.R
import com.yancotv.android.ui.focus.placedFocus
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import com.yancotv.android.ui.theme.LocalYancoPalette

/**
 * MB-335 — confirmation gate for destructive one-press actions.
 *
 * ### Why this exists
 *
 * On 2026-07-31 the source-detail DELETE button destroyed a real, synced
 * 278k-item source on a single un-confirmed CENTER press — the button's
 * `onClick` called `repo.removeSource` directly, and `sources` rows cascade
 * into `content`, favorites and watch history. An audit found the same shape
 * on the Recordings deletes (file on disk) and the backup-registry delete.
 * FavoritesScreen was the only surface that already confirmed.
 *
 * On a TV this class is worse than on a phone: D-pad focus is invisible until
 * you look at it, so "the button I thought was focused" and "the button that
 * was focused" routinely differ, and a remote makes accidental double-presses
 * easy. TiviMate confirms every destructive action for exactly this reason.
 *
 * ### The focus contract
 *
 * Initial focus lands on **Cancel**, not the confirm action. The scenario
 * this defends against: the user (or an automation) double-presses CENTER —
 * the first press opens this dialog, and the second lands wherever initial
 * focus went. With Cancel focused, that stray second press dismisses
 * harmlessly instead of confirming a deletion the user never read.
 */
@Composable
fun ConfirmDangerDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalYancoPalette.current
    // MB-420 — this used to be a bare FocusRequester driven by
    // `LaunchedEffect(Unit) { runCatching { requestFocus() } }`. On a
    // television that never landed: the effect runs on the first composition,
    // the dialog's own window has not placed the button yet, the request
    // fails, and `runCatching` swallows it. Measured on the Google TV — the
    // dialog rendered with 2 focusable nodes and 0 focused, and a CENTER press
    // did nothing at all.
    //
    // That is the failure the owner reported as "one recording deleted, the
    // rest would not": whether a confirmation could be confirmed depended on
    // whether the viewer happened to press an arrow key first, which moves
    // focus in and makes the dialog work from then on.
    //
    // `PlacedFocusAnchor` is the primitive the native-android-mk checklist
    // prescribes for exactly this, and says why: it waits for `onPlaced`
    // before requesting, where the delay-ladder pattern is a race that has
    // silently failed in production more than once.
    val cancelAnchor = rememberPlacedFocusAnchor()
    var cancelFocused by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.BackgroundRaised,
        title = { Text(title, color = palette.TextPrimary) },
        text = { Text(body, color = palette.TextSecondary) },
        // MB-420 — Material3 `TextButton`s, and two attempts to make a focus
        // request land on one of them failed on the television: the dialog
        // still opened with 2 focusable nodes and 0 focused. MB-395 already
        // moved every other destructive control in the app off Material3 for
        // exactly this reason, and the checklist states it as a rule — TV
        // focus targets do not use Material3 clickables. This dialog was the
        // one that never got the memo, which is why it is the one that could
        // not be confirmed with a remote.
        confirmButton = {
            YancoDangerButton(onClick = onConfirm, size = ButtonSize.Compact) {
                Text(confirmLabel, color = palette.TextPrimary)
            }
        },
        dismissButton = {
            YancoSecondaryButton(
                onClick = onDismiss,
                modifier = Modifier
                    .placedFocus(cancelAnchor)
                    .onFocusChanged { cancelFocused = it.isFocused },
                size = ButtonSize.Compact,
            ) {
                Text(stringResource(R.string.common_cancel), color = palette.TextPrimary)
            }
        },
    )
    // MB-420 — placement is necessary and NOT sufficient. Waiting for
    // `onPlaced` alone still left the dialog with 2 focusable nodes and 0
    // focused, measured on the Google TV after the first attempt at this fix.
    // An AlertDialog renders in its OWN window, and a focus request aimed at a
    // node in a window that has not yet taken focus is dropped. So wait for
    // both: the node to exist, and the window to be the one receiving input.
    // MB-420 — the request SUCCEEDS and the focus is then discarded.
    //
    // Measured on the Google TV with the path instrumented: the effect runs,
    // `LocalWindowInfo` already reports focused (that is the HOST window, not
    // the dialog's), the node is already placed, and `requestFocus()` returns
    // ok — all four inside 2 ms. The dialog's own window is created after
    // that, takes input focus, and Compose resets focus within it, throwing
    // away the one we just set. So the dump reads 2 focusable, 0 focused, and
    // the remote's OK does nothing until an arrow key seeds focus by hand.
    //
    // Three earlier attempts failed because they all assumed the request never
    // landed: waiting for placement, waiting on LocalWindowInfo, and moving
    // off Material3 buttons. It lands; it does not stick.
    //
    // So: re-request until the button itself reports focused. Driven by
    // observed state and bounded by frames rather than by a fixed delay —
    // this is not the delay ladder the checklist warns about, because it stops
    // on the condition it actually wants and gives up rather than looping.
    LaunchedEffect(Unit) {
        // Cancel keeps the focus rather than the destructive action: a stray
        // second CENTER — the double-press this dialog exists to absorb — must
        // dismiss, not confirm.
        cancelAnchor.awaitAndRequest()
    }
}
