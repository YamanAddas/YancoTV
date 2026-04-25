package com.yancotv.android.ui.parental

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.types.ContentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Long-press / MENU-key actions for a channel row. MK.13.3 grew this from
 * three actions (lock / hide / cancel) to six:
 *
 *   1. Favourite        — toggle, reactive via [FavoritesRepository.isFavoriteFlow].
 *   2. Rename           — write to [content.name_override] via [ContentRepository.setOverrides].
 *   3. Custom logo      — write to [content.logo_override] via the same path.
 *   4. Lock / Unlock    — PIN-gated unlock, immediate lock; matches MK.8.7 desktop UX.
 *   5. Hide from lists  — cosmetic filter; no PIN gate (batch cleanup is painful otherwise).
 *   6. Share stream URL — `ACTION_SEND` text/plain so the chooser surfaces messaging /
 *                         clipboard / file save targets.
 *
 * Why a custom [Dialog] instead of [AlertDialog]: AlertDialog's confirm / dismiss
 * slot pair doesn't fit a six-row action sheet without forcing a "Close" affordance
 * the user explicitly didn't want (Back handles dismissal). The plain [Dialog] gives
 * us a vertical action list that D-pads cleanly on TV.
 *
 * Sub-dialog state (rename / logo / unlock-PIN) is local — the menu re-opens with a
 * fresh [ContentItem] snapshot from the caller's repo read each time, so we don't
 * cache override values inside this composable.
 */
@Composable
fun ChannelActionsMenu(
    item: ContentItem,
    repo: ParentalRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val content: ContentRepository = koinInject()
    val favorites: FavoritesRepository = koinInject()

    val isFav by favorites.isFavoriteFlow(item.id).collectAsState(initial = false)
    val lockedIds by repo.lockedIds.collectAsState()
    val locked = item.id in lockedIds

    var gateForUnlock by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showLogoUrl by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = LocalYancoPalette.current.BackgroundRaised,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(min = 280.dp, max = 380.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = item.displayTitle,
                    color = LocalYancoPalette.current.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                ActionRow(
                    label = if (isFav) "Remove from favourites" else "Add to favourites",
                    onClick = {
                        // Favorites repo write — IO per the threading rule. Toggle is
                        // best-effort; if the DB write fails the live `isFavoriteFlow`
                        // simply doesn't flip and the user retries.
                        scope.launch(Dispatchers.IO) {
                            runCatching { favorites.toggle(item.id) }
                        }
                        onDismiss()
                    },
                )
                ActionRow(
                    label = "Rename",
                    onClick = { showRename = true },
                )
                ActionRow(
                    label = "Custom logo",
                    onClick = { showLogoUrl = true },
                )
                ActionRow(
                    label = if (locked) "Unlock (PIN)" else "Lock",
                    accent = true,
                    onClick = {
                        if (locked) {
                            gateForUnlock = true
                        } else {
                            // MB-104: dismiss FIRST so the focus return target (the
                            // orb that triggered the menu) is still alive when the
                            // StateFlow flip recomposes the rail. Then push the
                            // SQLDelight write to IO per the threading rule.
                            onDismiss()
                            scope.launch(Dispatchers.IO) {
                                runCatching { repo.lockChannel(item.id) }
                            }
                        }
                    },
                )
                ActionRow(
                    label = "Hide from lists",
                    onClick = {
                        // MB-104: same shape as Lock above. The original synchronous
                        // call combined with hide actually filtering the focused orb
                        // out of `visible` froze the focus system mid-dismiss → ANR.
                        // Dismiss first, then write off the main thread.
                        onDismiss()
                        scope.launch(Dispatchers.IO) {
                            runCatching { repo.hideChannel(item.id) }
                        }
                    },
                )
                ActionRow(
                    label = "Share stream URL",
                    onClick = {
                        val intent =
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, item.displayTitle)
                                putExtra(Intent.EXTRA_TEXT, item.streamUrl)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(intent, "Share stream URL").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }
                        onDismiss()
                    },
                )
            }
        }
    }

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

    if (showRename) {
        TextEntryDialog(
            title = "Rename channel",
            body = "Leave blank to revert to the M3U title.",
            initial = item.nameOverride.orEmpty(),
            confirmLabel = "Save",
            onConfirm = { entered ->
                // Carry the existing logo override through — repo doesn't have a
                // single-field setter, so we pass both values per its contract.
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        content.setOverrides(
                            contentId = item.id,
                            nameOverride = entered,
                            logoOverride = item.logoOverride,
                        )
                    }
                }
                showRename = false
                onDismiss()
            },
            onDismiss = { showRename = false },
        )
    }

    if (showLogoUrl) {
        TextEntryDialog(
            title = "Custom logo URL",
            body = "Paste an image URL to override the M3U logo. Leave blank to revert.",
            initial = item.logoOverride.orEmpty(),
            confirmLabel = "Save",
            onConfirm = { entered ->
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        content.setOverrides(
                            contentId = item.id,
                            nameOverride = item.nameOverride,
                            logoOverride = entered,
                        )
                    }
                }
                showLogoUrl = false
                onDismiss()
            },
            onDismiss = { showLogoUrl = false },
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalYancoPalette.current
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            color = if (accent) palette.Accent else palette.TextPrimary,
            fontSize = 14.sp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
        )
    }
}

/**
 * Single-field text entry sub-dialog used by Rename + Custom logo. The seed
 * value comes from the parent — [rememberSaveable] keeps it across rotation /
 * process death without re-snapshotting from the (potentially stale) caller.
 */
@Composable
private fun TextEntryDialog(
    title: String,
    body: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalYancoPalette.current.BackgroundRaised,
        title = { Text(title, color = LocalYancoPalette.current.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(body, color = LocalYancoPalette.current.TextMuted, fontSize = 12.sp)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                    textStyle =
                        TextStyle(
                            color = LocalYancoPalette.current.TextPrimary,
                            fontSize = 14.sp,
                        ),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LocalYancoPalette.current.TextPrimary,
                            unfocusedTextColor = LocalYancoPalette.current.TextPrimary,
                            focusedBorderColor = LocalYancoPalette.current.Accent,
                            unfocusedBorderColor = LocalYancoPalette.current.BackgroundHover,
                            cursorColor = LocalYancoPalette.current.Accent,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(confirmLabel, color = LocalYancoPalette.current.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = LocalYancoPalette.current.TextMuted)
            }
        },
    )
}
