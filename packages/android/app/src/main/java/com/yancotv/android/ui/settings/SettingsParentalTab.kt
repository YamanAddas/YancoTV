package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.R
import com.yancotv.android.ui.focus.snapToTopNearStart
import com.yancotv.android.ui.parental.PinEntryDialog
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.types.ContentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

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
fun SettingsParentalTab(modifier: Modifier = Modifier, repo: ParentalRepository = koinInject(), contentRepo: ContentRepository = koinInject()) {
    val scope = rememberCoroutineScope()
    val settings = repo.settings.collectAsState().value
    val hiddenIds = repo.hiddenIds.collectAsState().value

    // MK.28.4 (MB-258) — saveable so a mid-setup recreation doesn't silently
    // clear the first PIN entry. The Bundle is process-private and the value
    // short-lived; same trade as AddSourceDialog's password field.
    var newPin by rememberSaveable { mutableStateOf("") }
    var confirmPin by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var showGate by remember { mutableStateOf<GateAction?>(null) }
    var awaitingNewPin by remember { mutableStateOf(false) }

    // Hidden-channels roster, hydrated lazily from the content table via
    // findById. Missing rows (content deleted mid-session) drop silently
    // rather than rendering a ghost entry.
    val hiddenItems = remember { mutableStateListOf<ContentItem>() }
    LaunchedEffect(hiddenIds) {
        val resolved =
            withContext(Dispatchers.IO) {
                hiddenIds
                    .mapNotNull { id -> runCatching { contentRepo.findById(id) }.getOrNull() }
                    .sortedBy { it.cleanTitle?.ifBlank { null } ?: it.title }
            }
        hiddenItems.clear()
        hiddenItems.addAll(resolved)
    }

    // MB-107: vertical scroll on the outer Column. Without it the
    // "Require PIN to open Settings" toggle and "Hidden channels" panel
    // sit below the fold on Fire TV — D-pad DOWN can't reach them
    // because there's no scrollable parent. The nested hidden-items
    // LazyColumn below has bounded `heightIn(max = 280.dp)` so it
    // measures fine inside the verticalScroll.
    // MK.31.20 — `status` is assigned from inside coroutines, which are not
    // composable scope, so these three resolve up front.
    val pinSavedMsg = stringResource(R.string.par_pin_saved)
    val currentOkMsg = stringResource(R.string.par_current_ok)
    val pinRemovedMsg = stringResource(R.string.par_pin_removed)

    // MK.30.6 — hoisted so snapToTopNearStart can see the same state.
    val tabScroll = rememberScrollState()

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(tabScroll)
            .snapToTopNearStart(tabScroll)
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.par_title),
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.par_intro),
            color = LocalYancoPalette.current.TextMuted,
            fontSize = 12.sp,
        )

        // ───── PIN section ─────
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(LocalYancoPalette.current.BackgroundRaised)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(
                    if (settings.pinSet) R.string.par_pin_set else R.string.par_pin_not_set,
                ),
                color = if (settings.pinSet) LocalYancoPalette.current.Accent else LocalYancoPalette.current.TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )

            if (!settings.pinSet || awaitingNewPin) {
                PinField(
                    value = newPin,
                    onChange = {
                        newPin = it
                        status = null
                    },
                    label = stringResource(
                        if (awaitingNewPin) R.string.par_new_pin else R.string.pin_label,
                    ),
                )
                PinField(
                    value = confirmPin,
                    onChange = {
                        confirmPin = it
                        status = null
                    },
                    label = stringResource(R.string.par_confirm_pin),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsAccentButton(
                        enabled = newPin.length in 4..8 && newPin == confirmPin,
                        onClick = {
                            scope.launch {
                                // MK.28.3 (MB-251) — the settings-table writes
                                // inside setPin run on the caller context; only
                                // the hash is offloaded internally. Dispatch to
                                // IO like the sibling toggles in this file.
                                withContext(Dispatchers.IO) { repo.setPin(newPin) }
                                newPin = ""
                                confirmPin = ""
                                awaitingNewPin = false
                                status = pinSavedMsg
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (awaitingNewPin) R.string.par_save_new_pin else R.string.par_set_pin,
                            ),
                        )
                    }
                    if (awaitingNewPin) {
                        SettingsOutlinedButton(
                            onClick = {
                                awaitingNewPin = false
                                newPin = ""
                                confirmPin = ""
                                status = null
                            },
                        ) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                }
                if (newPin.isNotEmpty() && newPin.length !in 4..8) {
                    Text(
                        stringResource(R.string.par_pin_len_err),
                        color = LocalYancoPalette.current.Error,
                        fontSize = 12.sp,
                    )
                } else if (newPin.isNotEmpty() && newPin != confirmPin) {
                    Text(
                        stringResource(R.string.par_pin_mismatch),
                        color = LocalYancoPalette.current.Error,
                        fontSize = 12.sp,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsAccentButton(
                        onClick = { showGate = GateAction.CHANGE },
                    ) {
                        Text(stringResource(R.string.par_change_pin))
                    }
                    SettingsOutlinedButton(
                        onClick = { showGate = GateAction.REMOVE },
                    ) {
                        Text(stringResource(R.string.par_remove_pin))
                    }
                }
            }

            status?.let {
                Text(text = it, color = LocalYancoPalette.current.Accent, fontSize = 12.sp)
            }
        }

        // ───── Toggles ─────
        // MB-107a: shared focus-aware row. Material3 `Switch`'s default
        // focus indicator is invisible against `BackgroundRaised` on Fire
        // TV, so users couldn't tell which toggle had focus. The shared
        // composable owns a row-level accent border instead.
        SettingsToggleRow(
            label = stringResource(R.string.par_hide_adult),
            description = stringResource(R.string.par_hide_adult_desc),
            checked = settings.hideAdultContent,
            // MK.8 threading: setHideAdultContent is non-suspend and runs
            // a SQLDelight upsert + StateFlow mutation on the caller
            // thread. Compose onCheckedChange runs on Main — dispatch.
            onCheckedChange = { scope.launch(Dispatchers.IO) { repo.setHideAdultContent(it) } },
        )
        SettingsToggleRow(
            label = stringResource(R.string.par_require_pin),
            description = stringResource(R.string.par_require_pin_desc),
            enabled = settings.pinSet,
            checked = settings.requirePinForSettings,
            // MK.8 threading: same as above.
            onCheckedChange = { scope.launch(Dispatchers.IO) { repo.setRequirePinForSettings(it) } },
        )

        // Hidden-channels manager. Hide is one-way from list screens —
        // without this panel there's no way to unhide except reinstalling.
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(LocalYancoPalette.current.BackgroundRaised)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.par_hidden_channels),
                    color = LocalYancoPalette.current.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (hiddenIds.isNotEmpty()) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    SettingsOutlinedButton(
                        onClick = {
                            val toUnhide = hiddenIds.toList()
                            val count = toUnhide.size
                            scope.launch(Dispatchers.IO) {
                                toUnhide.forEach(repo::unhideChannel)
                            }
                            // MK.32.2 — Confirm the action: bulk-unhide
                            // updates the list silently, the toast says
                            // it landed.
                            android.widget.Toast.makeText(
                                context,
                                context.resources.getQuantityString(
                                    R.plurals.par_restored_count,
                                    count,
                                    count,
                                ),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                    ) {
                        Text(stringResource(R.string.par_unhide_all), fontSize = 12.sp)
                    }
                }
            }
            Text(
                text =
                if (hiddenIds.isEmpty()) {
                    stringResource(R.string.par_none_hidden)
                } else {
                    pluralStringResource(R.plurals.par_hidden_count, hiddenIds.size, hiddenIds.size)
                },
                color = LocalYancoPalette.current.TextMuted,
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
                            // MK.8 threading: dispatch the SQLDelight write off
                            // the Compose Main dispatcher.
                            onUnhide = { scope.launch(Dispatchers.IO) { repo.unhideChannel(item.id) } },
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
            title =
            when (action) {
                GateAction.CHANGE -> stringResource(R.string.par_gate_change_title)
                GateAction.REMOVE -> stringResource(R.string.par_gate_remove_title)
            },
            body =
            when (action) {
                GateAction.CHANGE -> stringResource(R.string.par_gate_change_body)
                GateAction.REMOVE -> stringResource(R.string.par_gate_remove_body)
            },
            repo = repo,
            onSuccess = {
                when (action) {
                    GateAction.CHANGE -> {
                        awaitingNewPin = true
                        status = currentOkMsg
                    }
                    GateAction.REMOVE -> {
                        scope.launch {
                            // MB-251 — DB writes off-main (see setPin above).
                            withContext(Dispatchers.IO) { repo.removePin() }
                            status = pinRemovedMsg
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
    // MB-117: click-to-edit so the IME never auto-pops on D-pad focus.
    // Bare mode keeps the field flush inside the dialog body that hosts it.
    SettingsClickToEditField(
        label = label,
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(8)) },
        keyboardType = KeyboardType.NumberPassword,
        transformation = PasswordVisualTransformation(),
        bare = true,
    )
}

@Composable
private fun HiddenRow(item: ContentItem, onUnhide: () -> Unit) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(LocalYancoPalette.current.BackgroundDeep)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.cleanTitle?.ifBlank { null } ?: item.title,
                color = LocalYancoPalette.current.TextPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.groupName?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, color = LocalYancoPalette.current.TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SettingsOutlinedButton(
            onClick = onUnhide,
        ) {
            Text(stringResource(R.string.par_unhide), fontSize = 12.sp)
        }
    }
}
