package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.parental.PinEntryDialog
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.types.ContentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf

/**
 * Parental controls tab — set/change/remove PIN and toggle the two
 * policy switches. Locked-channel management lives in the channel-row
 * long-press menu (MK.8.7.b); this screen is the PIN + policy home.
 *
 * PIN entry flow:
 *  - First run (pinSet == false): one password field → tap **Set PIN**.
 *  - Subsequent (pinSet == true): **Change PIN** gates via [PinEntryDialog],
 *    on success prompts for the new PIN. **Remove PIN** same gate.
 *
 * All DB touches go through [ParentalRepository] which dispatches scrypt
 * off the main thread — this composable just collects state and fires
 * suspend-wrapped launches.
 */
@Composable
fun SettingsParentalTab(
    modifier: Modifier = Modifier,
    repo: ParentalRepository = koinInject(),
    contentRepo: ContentRepository = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val settings = repo.settings.collectAsState().value
    val hiddenIds = repo.hiddenIds.collectAsState().value

    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var showGate by remember { mutableStateOf<GateAction?>(null) }
    var awaitingNewPin by remember { mutableStateOf(false) }

    // Hidden-channels roster, hydrated lazily from the content table via
    // findById. Missing rows (content deleted mid-session) drop silently
    // rather than rendering a ghost entry.
    val hiddenItems = remember { mutableStateListOf<ContentItem>() }
    LaunchedEffect(hiddenIds) {
        val resolved = withContext(Dispatchers.IO) {
            hiddenIds.mapNotNull { id -> runCatching { contentRepo.findById(id) }.getOrNull() }
                .sortedBy { it.cleanTitle?.ifBlank { null } ?: it.title }
        }
        hiddenItems.clear()
        hiddenItems.addAll(resolved)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Parental controls",
            color = YancoPalette.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Protect sensitive content behind a numeric PIN. Set once, changed later from this screen.",
            color = YancoPalette.TextMuted,
            fontSize = 12.sp,
        )

        // ───── PIN section ─────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(YancoPalette.BackgroundRaised)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (settings.pinSet) "PIN — set" else "PIN — not set",
                color = if (settings.pinSet) YancoPalette.Accent else YancoPalette.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )

            if (!settings.pinSet || awaitingNewPin) {
                PinField(
                    value = newPin,
                    onChange = { newPin = it; status = null },
                    label = if (awaitingNewPin) "New PIN" else "PIN",
                )
                PinField(
                    value = confirmPin,
                    onChange = { confirmPin = it; status = null },
                    label = "Confirm PIN",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = newPin.length in 4..8 && newPin == confirmPin,
                        onClick = {
                            scope.launch {
                                repo.setPin(newPin)
                                newPin = ""
                                confirmPin = ""
                                awaitingNewPin = false
                                status = "PIN saved"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = YancoPalette.Accent),
                    ) {
                        Text(if (awaitingNewPin) "Save new PIN" else "Set PIN")
                    }
                    if (awaitingNewPin) {
                        OutlinedButton(
                            onClick = {
                                awaitingNewPin = false
                                newPin = ""
                                confirmPin = ""
                                status = null
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = YancoPalette.TextPrimary),
                        ) {
                            Text("Cancel")
                        }
                    }
                }
                if (newPin.isNotEmpty() && newPin.length !in 4..8) {
                    Text("PIN must be 4–8 digits.", color = YancoPalette.Error, fontSize = 11.sp)
                } else if (newPin.isNotEmpty() && newPin != confirmPin) {
                    Text("PIN entries don't match.", color = YancoPalette.Error, fontSize = 11.sp)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showGate = GateAction.CHANGE },
                        colors = ButtonDefaults.buttonColors(containerColor = YancoPalette.Accent),
                    ) {
                        Text("Change PIN")
                    }
                    OutlinedButton(
                        onClick = { showGate = GateAction.REMOVE },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = YancoPalette.TextPrimary),
                    ) {
                        Text("Remove PIN")
                    }
                }
            }

            status?.let {
                Text(text = it, color = YancoPalette.Accent, fontSize = 12.sp)
            }
        }

        // ───── Toggles ─────
        ToggleRow(
            label = "Hide adult-tagged content",
            description = "Filter channels / movies / series that a provider marks as adult.",
            checked = settings.hideAdultContent,
            onCheckedChange = { repo.setHideAdultContent(it) },
        )
        ToggleRow(
            label = "Require PIN to open Settings",
            description = "Gate this Settings screen behind the PIN so kids can't change policy.",
            enabled = settings.pinSet,
            checked = settings.requirePinForSettings,
            onCheckedChange = { repo.setRequirePinForSettings(it) },
        )

        // Hidden-channels manager. Hide is one-way from list screens —
        // without this panel there's no way to unhide except reinstalling.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(YancoPalette.BackgroundRaised)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Hidden channels",
                    color = YancoPalette.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (hiddenIds.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            // Bulk unhide — snapshot first so the flow-driven
                            // remove doesn't ConcurrentModification the set.
                            hiddenIds.toList().forEach(repo::unhideChannel)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = YancoPalette.TextPrimary),
                    ) {
                        Text("Unhide all", fontSize = 12.sp)
                    }
                }
            }
            Text(
                text = if (hiddenIds.isEmpty())
                    "No channels are hidden. Long-press a channel in LiveTV / Movies / Series to hide it."
                else
                    "${hiddenIds.size} channel(s) hidden. Tap Unhide to bring one back.",
                color = YancoPalette.TextMuted,
                fontSize = 12.sp,
            )
            if (hiddenItems.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(hiddenItems, key = { it.id }) { item ->
                        HiddenRow(
                            item = item,
                            onUnhide = { repo.unhideChannel(item.id) },
                        )
                    }
                }
            }
        }
    }

    // PIN gate dialog — wraps an action (Change / Remove) that needs the
    // current PIN first. On success, the gate closes and the action runs.
    showGate?.let { action ->
        PinEntryDialog(
            title = when (action) {
                GateAction.CHANGE -> "Enter current PIN"
                GateAction.REMOVE -> "Confirm with current PIN"
            },
            body = when (action) {
                GateAction.CHANGE -> "To change your PIN, confirm the current one first."
                GateAction.REMOVE -> "Removing the PIN will disable parental controls."
            },
            repo = repo,
            onSuccess = {
                when (action) {
                    GateAction.CHANGE -> {
                        awaitingNewPin = true
                        status = "Current PIN OK — enter the new one."
                    }
                    GateAction.REMOVE -> {
                        scope.launch {
                            repo.removePin()
                            status = "PIN removed — parental controls disabled."
                        }
                    }
                }
                showGate = null
            },
            onDismiss = { showGate = null },
        )
    }
}

private enum class GateAction { CHANGE, REMOVE }

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(8)) },
        singleLine = true,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = TextStyle(
            color = YancoPalette.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = YancoPalette.TextPrimary,
            unfocusedTextColor = YancoPalette.TextPrimary,
            focusedBorderColor = YancoPalette.Accent,
            unfocusedBorderColor = YancoPalette.BackgroundHover,
            cursorColor = YancoPalette.Accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(YancoPalette.BackgroundRaised)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (enabled) YancoPalette.TextPrimary else YancoPalette.TextMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = YancoPalette.TextMuted,
                fontSize = 11.sp,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = YancoPalette.Accent,
                checkedTrackColor = YancoPalette.Accent.copy(alpha = 0.4f),
                uncheckedThumbColor = YancoPalette.TextMuted,
                uncheckedTrackColor = YancoPalette.BackgroundHover,
            ),
        )
    }
}

@Composable
private fun HiddenRow(item: ContentItem, onUnhide: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(YancoPalette.BackgroundDeep)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.cleanTitle?.ifBlank { null } ?: item.title,
                color = YancoPalette.TextPrimary,
                fontSize = 13.sp,
                maxLines = 1,
            )
            item.groupName?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, color = YancoPalette.TextMuted, fontSize = 11.sp, maxLines = 1)
            }
        }
        OutlinedButton(
            onClick = onUnhide,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = YancoPalette.Accent),
        ) {
            Text("Unhide", fontSize = 11.sp)
        }
    }
}
