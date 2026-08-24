package com.yancotv.android.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yancotv.android.R
import com.yancotv.android.ui.components.YancoPrimaryButton
import com.yancotv.android.ui.components.YancoSecondaryButton
import com.yancotv.android.ui.focus.placedFocus
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.types.AddSourceInput
import com.yancotv.shared.types.SourceType

/**
 * Add-source modal. Every field is a click-to-edit row: it sits there as
 * plain read-only text until the user presses OK on it, at which point
 * we flip into edit mode and push focus into the embedded text field —
 * that's the ONLY time the on-screen keyboard appears.
 *
 * Previous versions auto-focused the Name field on open, which popped
 * the IME the instant the dialog opened even when the user just wanted
 * to flick between fields with the D-pad. The click-to-edit pattern
 * matches how TiviMate + Netflix handle TV forms: scroll with remote,
 * OK to type, Back to exit edit mode.
 */
@Composable
fun AddSourceDialog(onDismiss: () -> Unit, onSubmit: (AddSourceInput) -> Unit, saving: Boolean = false, saveError: String? = null) {
    // MK.28.4 (MB-257) — every field is rememberSaveable (hard rule 9):
    // the canonical flow is "app-switch to the provider email / browser to
    // copy URL + credentials, come back" — a background-kill window — and
    // M3U_FILE bounces through the SAF picker activity, another one. Plain
    // remember wiped the whole form on return. The password lives in the
    // saved-state Bundle, which is process-private and transient —
    // deliberate, same trade SettingsBackupTab documents for its picker
    // round-trip.
    var type by rememberSaveable { mutableStateOf(SourceType.XTREAM) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var epgUrl by rememberSaveable { mutableStateOf("") }
    // MK.17.5 — per-source HTTP override fields. Both optional. UA
    // overrides the app-wide pref; Referer is sent only when set
    // (some providers gate playback on Referer presence — see
    // PlaybackController interceptor).
    var userAgent by rememberSaveable { mutableStateOf("") }
    var referer by rememberSaveable { mutableStateOf("") }
    // Audit catch — M3U_FILE picks a content:// URI via SAF; STALKER
    // needs a MAC address alongside the host. Both source types are
    // advertised in the SourcesScreen empty-state copy but the dialog
    // previously offered only Xtream + M3U_URL.
    var filePath by rememberSaveable { mutableStateOf<String?>(null) }
    var fileDisplayName by rememberSaveable { mutableStateOf("") }
    var macAddress by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) {
                // Persist permission so the source survives a process
                // restart — without this the URI becomes unreadable on
                // every cold launch. Memory convention:
                // `file_path` columns store content:// URIs.
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                filePath = uri.toString()
                fileDisplayName = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
            }
        }
    var validationError by remember { mutableStateOf<String?>(null) }

    // MB-398 — deterministic focus-on-open, so the dialog opens at the TOP
    // (source-type chips) instead of wherever a stray focus request dragged
    // the scroll container. Pairs with the SettingsClickToEditField fix: the
    // fields no longer grab focus on first composition, so this anchor is
    // what decides the entry point rather than whichever field composed last.
    // The request is issued inside the Dialog content (below).
    val firstChipAnchor = rememberPlacedFocusAnchor()

    // MK.31.20 — resolved here in composable scope: `submit()` below is a
    // plain local function, where `stringResource` is not available.
    val errName = stringResource(R.string.as_err_name)
    val errFile = stringResource(R.string.as_err_file)
    val errHostUrl = stringResource(R.string.as_err_host_url)
    val errM3uUrl = stringResource(R.string.as_err_m3u_url)
    val errPortalUrl = stringResource(R.string.as_err_portal_url)
    val errUrl = stringResource(R.string.as_err_url)
    val errXtreamCreds = stringResource(R.string.as_err_xtream_creds)
    val errStalkerMac = stringResource(R.string.as_err_stalker_mac)

    fun submit() {
        if (saving) return
        if (name.isBlank()) {
            validationError = errName
            return
        }
        if (type == SourceType.M3U_FILE) {
            if (filePath.isNullOrBlank()) {
                validationError = errFile
                return
            }
        } else if (url.isBlank()) {
            validationError =
                when (type) {
                    SourceType.XTREAM -> errHostUrl
                    SourceType.M3U_URL -> errM3uUrl
                    SourceType.STALKER -> errPortalUrl
                    else -> errUrl
                }
            return
        }
        if (type == SourceType.XTREAM && (username.isBlank() || password.isBlank())) {
            validationError = errXtreamCreds
            return
        }
        if (type == SourceType.STALKER && macAddress.isBlank()) {
            validationError = errStalkerMac
            return
        }
        validationError = null
        onSubmit(
            AddSourceInput(
                name = name.trim(),
                type = type,
                // M3U_FILE persists the content:// URI in `filePath`; the
                // `url` column is unused for that type but the schema
                // wants a non-null value, so seed it with the URI string
                // so the row is consistent.
                url = if (type == SourceType.M3U_FILE) (filePath ?: "") else url.trim(),
                filePath = filePath?.takeIf { type == SourceType.M3U_FILE },
                username = username.takeIf { it.isNotBlank() }?.trim(),
                password = password.takeIf { it.isNotBlank() },
                macAddress = macAddress.takeIf { type == SourceType.STALKER && it.isNotBlank() }?.trim(),
                epgUrl = epgUrl.takeIf { it.isNotBlank() }?.trim(),
                userAgent = userAgent.takeIf { it.isNotBlank() }?.trim(),
                referer = referer.takeIf { it.isNotBlank() }?.trim(),
            ),
        )
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties =
        DialogProperties(
            dismissOnClickOutside = !saving,
            dismissOnBackPress = !saving,
            usePlatformDefaultWidth = false,
            // MB-396 — let the IME insets flow into this window so the
            // imePadding below is the (deterministic, API-independent)
            // mechanism that keeps the dialog above the keyboard. The
            // platform's own dialog resize/pan behaviour varies by API
            // level and was leaving the focused field behind the IME on
            // a landscape phone.
            decorFitsSystemWindows = false,
        ),
    ) {
        // MB-398 — land focus on the first chip once it is actually placed.
        // Keyed on Unit: one request for the life of the dialog, so the SAF
        // file-picker round-trip doesn't yank focus back up to the chips.
        LaunchedEffect(Unit) { firstChipAnchor.awaitAndRequest() }
        Column(
            modifier =
            Modifier
                // MB-396 — with decorFitsSystemWindows=false the window is
                // edge-to-edge; these paddings are counted into the column's
                // measured size, so the Dialog's centering places the visible
                // content fully inside the safe area and above the keyboard.
                .systemBarsPadding()
                .imePadding()
                // Audit catch — was widthIn(min = 560.dp), which a 360-380dp
                // phone clipped horizontally on the entry-point first-run
                // dialog. Drop the min so phone scales to fit; keep the
                // 720dp cap so TV / tablet don't get a 1080p slab.
                // fillMaxWidth(0.95f) keeps a small gutter on phone.
                .widthIn(min = 0.dp, max = 720.dp)
                .fillMaxWidth(0.95f)
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(LocalYancoPalette.current.BackgroundRaised)
                .border(1.dp, LocalYancoPalette.current.BorderSubtle, RoundedCornerShape(16.dp)),
        ) {
            // MB-396 — the header + intro scroll WITH the fields. They were
            // fixed chrome above the scroll area; together with the pinned
            // footer that left ~0dp for the field column once a landscape
            // phone's IME took its half of the window — the row being typed
            // into was simply not on screen. Inside the scroll, tight space
            // costs the decoration first and the focused field's
            // bringIntoView always has room to land. Footer stays pinned.
            Column(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.add_title),
                        color = LocalYancoPalette.current.TextPrimary,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        // Audit catch — was "Scroll with the D-pad. Press OK on
                        // a field to type." which is wrong on touch. Rewritten
                        // form-factor-agnostic so the same string works on
                        // Fire TV remote AND phone.
                        text = stringResource(R.string.add_intro),
                        color = LocalYancoPalette.current.TextMuted,
                        fontSize = 14.sp,
                    )
                }

                Divider()

                Column(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SectionLabel(stringResource(R.string.as_sec_source_type))
                    // Two rows of two chips — keeps the layout readable on
                    // phone widths where 4 inline chips overflow. Audit
                    // catch: M3U_FILE + STALKER were never wired into this
                    // dialog despite SourcesScreen advertising them.
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TypeChip(
                                label = stringResource(R.string.add_type_xtream),
                                description = stringResource(R.string.add_type_xtream_desc),
                                selected = type == SourceType.XTREAM,
                                onSelect = { type = SourceType.XTREAM },
                                // MB-398 — placedFocus binds to the next focus
                                // node down the chain (the chip's own
                                // focusable); no extra .focusable() wrapper
                                // (Backup-tab lesson).
                                modifier = Modifier.placedFocus(firstChipAnchor),
                            )
                            TypeChip(
                                label = stringResource(R.string.add_type_m3u_url),
                                description = stringResource(R.string.add_type_m3u_url_desc),
                                selected = type == SourceType.M3U_URL,
                                onSelect = { type = SourceType.M3U_URL },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TypeChip(
                                label = stringResource(R.string.add_type_m3u_file),
                                description = stringResource(R.string.add_type_m3u_file_desc),
                                selected = type == SourceType.M3U_FILE,
                                onSelect = { type = SourceType.M3U_FILE },
                            )
                            TypeChip(
                                label = stringResource(R.string.add_type_stalker),
                                description = stringResource(R.string.add_type_stalker_desc),
                                selected = type == SourceType.STALKER,
                                onSelect = { type = SourceType.STALKER },
                            )
                        }
                    }

                    SectionLabel("Details")
                    SettingsClickToEditField(
                        label = stringResource(R.string.add_name),
                        hint = stringResource(R.string.add_name_hint),
                        value = name,
                        onValueChange = { name = it },
                        bare = true,
                    )
                    if (type == SourceType.M3U_FILE) {
                        // SAF-backed file picker. The button is the focus
                        // target; the picker hands back a content:// URI
                        // and we take persistent read permission so the
                        // source survives process restarts.
                        SettingsRow(
                            label = stringResource(R.string.add_m3u_file),
                            hint = if (fileDisplayName.isNotBlank()) {
                                stringResource(R.string.as_file_selected, fileDisplayName)
                            } else {
                                stringResource(R.string.as_file_hint)
                            },
                            onClick = {
                                filePickerLauncher.launch(
                                    arrayOf(
                                        "audio/x-mpegurl",
                                        "audio/mpegurl",
                                        "application/x-mpegurl",
                                        "application/vnd.apple.mpegurl",
                                        "*/*",
                                    ),
                                )
                            },
                        )
                    } else {
                        SettingsClickToEditField(
                            label = when (type) {
                                SourceType.XTREAM -> stringResource(R.string.as_host_url)
                                SourceType.STALKER -> stringResource(R.string.as_portal_url)
                                else -> stringResource(R.string.as_m3u_url)
                            },
                            hint = when (type) {
                                SourceType.XTREAM -> "http://host:port"
                                SourceType.STALKER -> "http://portal.tv/c/ or http://portal.tv/stalker_portal/c/"
                                else -> "https://provider.tv/list.m3u"
                            },
                            value = url,
                            onValueChange = { url = it },
                            keyboardType = KeyboardType.Uri,
                            bare = true,
                        )
                    }

                    if (type == SourceType.XTREAM) {
                        SectionLabel(stringResource(R.string.as_sec_credentials))
                        SettingsClickToEditField(
                            label = stringResource(R.string.add_username),
                            hint = null,
                            value = username,
                            onValueChange = { username = it },
                            bare = true,
                        )
                        SettingsClickToEditField(
                            label = stringResource(R.string.add_password),
                            hint = null,
                            value = password,
                            onValueChange = { password = it },
                            transformation = PasswordVisualTransformation(),
                            keyboardType = KeyboardType.Password,
                            bare = true,
                        )
                    }

                    if (type == SourceType.STALKER) {
                        SectionLabel(stringResource(R.string.as_sec_device_identity))
                        SettingsClickToEditField(
                            label = stringResource(R.string.add_mac),
                            hint = stringResource(R.string.add_mac_hint),
                            value = macAddress,
                            onValueChange = { macAddress = it },
                            bare = true,
                        )
                    }

                    SectionLabel(stringResource(R.string.as_sec_epg))
                    SettingsClickToEditField(
                        label = stringResource(R.string.add_epg_url),
                        hint = stringResource(R.string.add_epg_url_hint),
                        value = epgUrl,
                        onValueChange = { epgUrl = it },
                        keyboardType = KeyboardType.Uri,
                        bare = true,
                    )

                    // MK.17.5 — advanced HTTP overrides. Most users leave
                    // these blank; providers that gate on UA / Referer will
                    // surface the requirement in their docs.
                    SectionLabel(stringResource(R.string.as_sec_advanced))
                    SettingsClickToEditField(
                        label = stringResource(R.string.add_ua),
                        hint = stringResource(R.string.add_ua_hint),
                        value = userAgent,
                        onValueChange = { userAgent = it },
                        bare = true,
                    )
                    SettingsClickToEditField(
                        label = stringResource(R.string.add_referer),
                        hint = stringResource(R.string.add_referer_hint),
                        value = referer,
                        onValueChange = { referer = it },
                        keyboardType = KeyboardType.Uri,
                        bare = true,
                    )

                    validationError?.let { ErrorBanner(text = it) }
                    saveError?.let { ErrorBanner(text = stringResource(R.string.add_save_failed, it)) }
                }
            }

            Divider()

            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                YancoSecondaryButton(onClick = onDismiss, enabled = !saving) {
                    Text(text = stringResource(R.string.common_cancel))
                }
                YancoPrimaryButton(onClick = { submit() }, enabled = !saving) {
                    Text(text = if (saving) "Saving…" else "Save")
                }
            }
        }
    }
}

// ─────────────────────────── building blocks ───────────────────────────

@Composable
private fun Divider() {
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalYancoPalette.current.BorderSubtle),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = LocalYancoPalette.current.TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun ErrorBanner(text: String) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LocalYancoPalette.current.Error.copy(alpha = 0.12f))
            .border(1.dp, LocalYancoPalette.current.Error.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, color = LocalYancoPalette.current.Error, fontSize = 12.sp)
    }
}

@Composable
private fun TypeChip(label: String, description: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg =
        when {
            selected -> LocalYancoPalette.current.Accent.copy(alpha = 0.18f)
            focused -> LocalYancoPalette.current.BackgroundHover
            else -> LocalYancoPalette.current.BackgroundDeep
        }
    val borderColor =
        when {
            selected -> LocalYancoPalette.current.Accent
            focused -> LocalYancoPalette.current.FocusRing
            else -> LocalYancoPalette.current.BorderSubtle
        }
    Column(
        modifier =
        modifier
            .widthIn(min = 180.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onSelect)
            // MK.28.8 (MB-276) — announce selected state to TalkBack so the
            // chosen source type is distinguishable from the rest.
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            color = if (selected) LocalYancoPalette.current.Accent else LocalYancoPalette.current.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = description,
            color = LocalYancoPalette.current.TextMuted,
            fontSize = 12.sp,
        )
    }
}
