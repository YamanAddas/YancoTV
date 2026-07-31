package com.yancotv.android.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.yancotv.android.R
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
    val cancelFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.BackgroundRaised,
        title = { Text(title, color = palette.TextPrimary) },
        text = { Text(body, color = palette.TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = palette.Error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocus),
            ) {
                Text(stringResource(R.string.common_cancel), color = palette.TextPrimary)
            }
        },
    )
    LaunchedEffect(Unit) {
        // The dialog's window may not have placed the button on the first
        // frame; a failed request here must not crash the dialog that is
        // guarding a deletion.
        runCatching { cancelFocus.requestFocus() }
    }
}
