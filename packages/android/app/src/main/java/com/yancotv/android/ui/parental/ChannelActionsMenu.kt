package com.yancotv.android.ui.parental

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.types.ContentItem
import kotlinx.coroutines.launch

/**
 * Long-press / MENU-key actions for a channel row: lock or hide.
 *
 * Shown as an [AlertDialog] (rather than a [androidx.compose.material3.DropdownMenu])
 * because dropdowns are finicky on TV focus — the dialog's button row
 * is D-pad-navigable without surprises.
 *
 * Lock = the row stays visible in lists but requires PIN to play.
 * Hide = the row disappears from non-settings lists entirely; unhide
 * is a separate Settings flow (lands MK.8.7.c).
 *
 * Unlocking requires the current PIN. Hiding does NOT — hiding is a
 * cosmetic list filter, not a security boundary, and gating every
 * hide/unhide would make batch cleanup painful. Matches desktop.
 */
@Composable
fun ChannelActionsMenu(
    item: ContentItem,
    repo: ParentalRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lockedIds by repo.lockedIds.collectAsState()
    val locked = item.id in lockedIds
    var gateForUnlock by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalYancoPalette.current.BackgroundRaised,
        title = {
            Text(
                text = item.cleanTitle?.ifBlank { null } ?: item.title,
                color = LocalYancoPalette.current.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text =
                        if (locked) {
                            "This channel is locked. Unlock to allow anyone to watch without a PIN."
                        } else {
                            "Lock this channel to require a PIN before playing, or hide it from lists entirely."
                        },
                    color = LocalYancoPalette.current.TextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (locked) {
                        gateForUnlock = true
                    } else {
                        repo.lockChannel(item.id)
                        onDismiss()
                    }
                },
            ) {
                Text(
                    text = if (locked) "Unlock (PIN)" else "Lock",
                    color = LocalYancoPalette.current.Accent,
                )
            }
        },
        dismissButton = {
            Column {
                TextButton(
                    onClick = {
                        repo.hideChannel(item.id)
                        onDismiss()
                    },
                ) {
                    Text("Hide from lists", color = LocalYancoPalette.current.TextPrimary)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = LocalYancoPalette.current.TextMuted)
                }
            }
        },
    )

    if (gateForUnlock) {
        PinEntryDialog(
            title = "Enter PIN to unlock",
            body = "This channel is locked. Confirm your PIN to remove the lock.",
            repo = repo,
            onSuccess = {
                scope.launch {
                    repo.unlockChannel(item.id)
                    gateForUnlock = false
                    onDismiss()
                }
            },
            onDismiss = { gateForUnlock = false },
        )
    }
}
