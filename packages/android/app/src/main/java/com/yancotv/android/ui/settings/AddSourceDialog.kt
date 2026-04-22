package com.yancotv.android.ui.settings

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yancotv.android.ui.theme.YancoPalette
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
fun AddSourceDialog(
    onDismiss: () -> Unit,
    onSubmit: (AddSourceInput) -> Unit,
    saving: Boolean = false,
    saveError: String? = null,
) {
    var type by remember { mutableStateOf(SourceType.XTREAM) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (saving) return
        if (name.isBlank()) {
            validationError = "Give the source a name."
            return
        }
        if (url.isBlank()) {
            validationError = if (type == SourceType.XTREAM)
                "Host URL is required (e.g. http://provider.tv:8080)."
            else "M3U URL is required."
            return
        }
        if (type == SourceType.XTREAM && (username.isBlank() || password.isBlank())) {
            validationError = "Xtream needs both a username and a password."
            return
        }
        validationError = null
        onSubmit(
            AddSourceInput(
                name = name.trim(),
                type = type,
                url = url.trim(),
                username = username.takeIf { it.isNotBlank() }?.trim(),
                password = password.takeIf { it.isNotBlank() },
                epgUrl = epgUrl.takeIf { it.isNotBlank() }?.trim(),
            ),
        )
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(
            dismissOnClickOutside = !saving,
            dismissOnBackPress = !saving,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 560.dp, max = 720.dp)
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(YancoPalette.BackgroundRaised)
                .border(1.dp, YancoPalette.BorderSubtle, RoundedCornerShape(16.dp)),
        ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Add source",
                    color = YancoPalette.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Scroll with the D-pad. Press OK on a field to type. Credentials are encrypted on-device.",
                    color = YancoPalette.TextMuted,
                    fontSize = 13.sp,
                )
            }

            Divider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SectionLabel("Source type")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TypeChip(
                        label = "Xtream",
                        description = "Host + user + pass",
                        selected = type == SourceType.XTREAM,
                        onSelect = { type = SourceType.XTREAM },
                    )
                    TypeChip(
                        label = "M3U URL",
                        description = "Hosted playlist link",
                        selected = type == SourceType.M3U_URL,
                        onSelect = { type = SourceType.M3U_URL },
                    )
                }

                SectionLabel("Details")
                ClickToEditField(
                    label = "Name",
                    hint = "e.g. My IPTV",
                    value = name,
                    onValueChange = { name = it },
                )
                ClickToEditField(
                    label = if (type == SourceType.XTREAM) "Host URL" else "M3U URL",
                    hint = if (type == SourceType.XTREAM) "http://host:port" else "https://provider.tv/list.m3u",
                    value = url,
                    onValueChange = { url = it },
                    keyboardType = KeyboardType.Uri,
                )

                if (type == SourceType.XTREAM) {
                    SectionLabel("Credentials")
                    ClickToEditField(
                        label = "Username",
                        hint = null,
                        value = username,
                        onValueChange = { username = it },
                    )
                    ClickToEditField(
                        label = "Password",
                        hint = null,
                        value = password,
                        onValueChange = { password = it },
                        transformation = PasswordVisualTransformation(),
                        keyboardType = KeyboardType.Password,
                    )
                }

                SectionLabel("Electronic program guide")
                ClickToEditField(
                    label = "EPG URL",
                    hint = "Optional — leave blank for the provider's built-in guide",
                    value = epgUrl,
                    onValueChange = { epgUrl = it },
                    keyboardType = KeyboardType.Uri,
                )

                validationError?.let { ErrorBanner(text = it) }
                saveError?.let { ErrorBanner(text = "Couldn't save: $it") }
            }

            Divider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GhostButton(label = "Cancel", onClick = onDismiss, enabled = !saving)
                PrimaryButton(
                    label = if (saving) "Saving…" else "Save",
                    onClick = { submit() },
                    enabled = !saving,
                )
            }
        }
    }
}

// ─────────────────────────── building blocks ───────────────────────────

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(YancoPalette.BorderSubtle),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = YancoPalette.TextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun ErrorBanner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(YancoPalette.Error.copy(alpha = 0.12f))
            .border(1.dp, YancoPalette.Error.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, color = YancoPalette.Error, fontSize = 12.sp)
    }
}

/**
 * Click-to-edit field. Default state: focusable read-only row showing
 * the current value (or hint placeholder). Press OK → [editing] flips
 * true → an embedded [BasicTextField] takes focus, which opens the IME.
 * Press Done / Enter / Back → [editing] flips false, IME dismisses.
 *
 * This is the whole "only open the keyboard when I ask" flow — the
 * field is never auto-focused unless the user explicitly activates it.
 */
@Composable
private fun ClickToEditField(
    label: String,
    hint: String?,
    value: String,
    onValueChange: (String) -> Unit,
    transformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var editing by remember { mutableStateOf(false) }
    val editFocus = remember { FocusRequester() }

    // Push focus into the text field the frame after `editing` flips true.
    // The field is only composed while editing, so this effect is keyed on
    // `editing` — it can't race with its own recomposition.
    LaunchedEffect(editing) {
        if (editing) runCatching { editFocus.requestFocus() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = YancoPalette.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!hint.isNullOrBlank() && !editing) {
                Text(text = hint, color = YancoPalette.TextMuted, fontSize = 11.sp)
            }
        }
        if (editing) {
            EditableBody(
                value = value,
                onValueChange = onValueChange,
                transformation = transformation,
                keyboardType = keyboardType,
                focusRequester = editFocus,
                onDone = { editing = false },
            )
        } else {
            ReadOnlyBody(
                display = transformForDisplay(value, transformation),
                placeholder = hint?.takeIf { value.isBlank() } ?: "",
                empty = value.isBlank(),
                onClick = { editing = true },
            )
        }
    }
}

@Composable
private fun ReadOnlyBody(
    display: String,
    placeholder: String,
    empty: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border = if (focused) YancoPalette.FocusRing else YancoPalette.BorderSubtle
    val bg = if (focused) YancoPalette.BackgroundHover else YancoPalette.BackgroundDeep
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = if (empty) placeholder else display,
            color = if (empty) YancoPalette.TextMuted else YancoPalette.TextPrimary,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun EditableBody(
    value: String,
    onValueChange: (String) -> Unit,
    transformation: VisualTransformation,
    keyboardType: KeyboardType,
    focusRequester: FocusRequester,
    onDone: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(YancoPalette.BackgroundHover)
            .border(1.dp, YancoPalette.FocusRing, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            interactionSource = interaction,
            textStyle = TextStyle(color = YancoPalette.TextPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(YancoPalette.FocusRing),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
    }
}

private fun transformForDisplay(value: String, transformation: VisualTransformation): String {
    if (value.isEmpty()) return ""
    if (transformation is PasswordVisualTransformation) return "•".repeat(value.length)
    return value
}

@Composable
private fun TypeChip(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        selected -> YancoPalette.Accent.copy(alpha = 0.18f)
        focused -> YancoPalette.BackgroundHover
        else -> YancoPalette.BackgroundDeep
    }
    val borderColor = when {
        selected -> YancoPalette.Accent
        focused -> YancoPalette.FocusRing
        else -> YancoPalette.BorderSubtle
    }
    Column(
        modifier = Modifier
            .widthIn(min = 180.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            color = if (selected) YancoPalette.Accent else YancoPalette.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = description,
            color = YancoPalette.TextMuted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        !enabled -> YancoPalette.AccentMuted.copy(alpha = 0.4f)
        focused -> YancoPalette.AccentGlow
        else -> YancoPalette.Accent
    }
    val borderColor = if (focused) YancoPalette.TextPrimary else bg
    Box(
        modifier = Modifier
            .height(42.dp)
            .widthIn(min = 120.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .focusable(enabled = enabled, interactionSource = interaction)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.7f)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = YancoPalette.BackgroundDeep,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GhostButton(label: String, onClick: () -> Unit, enabled: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = if (focused) YancoPalette.FocusRing else YancoPalette.BorderSubtle
    val bg = if (focused) YancoPalette.BackgroundHover else YancoPalette.BackgroundRaised
    Box(
        modifier = Modifier
            .height(42.dp)
            .widthIn(min = 104.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .focusable(enabled = enabled, interactionSource = interaction)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = YancoPalette.TextPrimary,
            fontSize = 14.sp,
        )
    }
}
