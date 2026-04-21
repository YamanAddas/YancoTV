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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
 * Premium add-source modal. The old MK.6 dialog was a plain rectangle with
 * label-over-field rows; it looked like a form generator had coughed it up.
 *
 * This rewrite (MK.6.b) does three things:
 *  1. Segmented type picker up top, sized for remote + touch alike.
 *  2. Two-column field grid on wide layouts so the eye scans down a short
 *     list instead of a long one.
 *  3. Explicit focus-ring outline on fields, consistent with the rest of
 *     the TV shell — makes D-pad travel visible without highlighting every
 *     pixel.
 *
 * D-pad flow: [Xtream chip] → [M3U chip] → Name → URL → Username → Password
 *  → EPG URL → Save → Cancel. The Name field auto-focuses on open so remote
 * users can start typing immediately.
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
    val nameFocus = remember { FocusRequester() }

    // First field auto-focus. Leanback D-pad users open the dialog and
    // expect typing to go somewhere — without this they'd have to DPAD-down
    // twice before anything takes input.
    LaunchedEffect(Unit) { nameFocus.requestFocus() }

    fun submit() {
        if (saving) return
        if (name.isBlank()) {
            validationError = "Give the source a name."
            return
        }
        if (url.isBlank()) {
            validationError = if (type == SourceType.XTREAM)
                "Host URL is required (e.g. http://provider.tv:8080)."
            else
                "M3U URL is required."
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
            )
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
                    text = "Point YancoTV at your IPTV provider. Credentials are encrypted on-device.",
                    color = YancoPalette.TextMuted,
                    fontSize = 13.sp,
                )
            }

            Divider()

            // Scrollable body — on phones in landscape the two-row credential
            // block otherwise clips against the IME.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
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
                LabeledField(
                    label = "Name",
                    hint = "Shown in the Sources list",
                    value = name,
                    onValueChange = { name = it },
                    voice = true,
                    focusRequester = nameFocus,
                )
                LabeledField(
                    label = if (type == SourceType.XTREAM) "Host URL" else "M3U URL",
                    hint = if (type == SourceType.XTREAM) "http://host:port" else "https://provider.tv/list.m3u",
                    value = url,
                    onValueChange = { url = it },
                    voice = true,
                    voiceTransform = ::normalizeUrlFromSpeech,
                )

                if (type == SourceType.XTREAM) {
                    SectionLabel("Credentials")
                    LabeledField(
                        label = "Username",
                        hint = null,
                        value = username,
                        onValueChange = { username = it },
                    )
                    LabeledField(
                        label = "Password",
                        hint = null,
                        value = password,
                        onValueChange = { password = it },
                        transformation = PasswordVisualTransformation(),
                    )
                    Text(
                        text = "Tip: use the keyboard for credentials. Voice transcription mangles the special characters these tokens usually contain.",
                        color = YancoPalette.TextMuted,
                        fontSize = 11.sp,
                    )
                }

                SectionLabel("Electronic program guide")
                LabeledField(
                    label = "EPG URL",
                    hint = "Optional — leave blank to use the provider's built-in guide",
                    value = epgUrl,
                    onValueChange = { epgUrl = it },
                    voice = true,
                    voiceTransform = ::normalizeUrlFromSpeech,
                )

                validationError?.let { ErrorBanner(text = it) }
                saveError?.let { ErrorBanner(text = "Couldn't save: $it") }
            }

            Divider()

            // Footer action row. Primary (Save) takes the accent fill; the
            // secondary (Cancel) is a ghost — same contrast hierarchy as the
            // desktop settings screens.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GhostButton(
                    label = "Cancel",
                    onClick = onDismiss,
                    enabled = !saving,
                )
                PrimaryButton(
                    label = if (saving) "Saving…" else "Save",
                    onClick = { submit() },
                    enabled = !saving,
                )
            }
        }
    }
}

// ─────────────────────────── private building blocks ───────────────────────────

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
        Text(
            text = text,
            color = YancoPalette.Error,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun LabeledField(
    label: String,
    hint: String?,
    value: String,
    onValueChange: (String) -> Unit,
    transformation: VisualTransformation = VisualTransformation.None,
    voice: Boolean = false,
    voiceTransform: (String) -> String = { it },
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border = if (focused) YancoPalette.FocusRing else YancoPalette.BorderSubtle
    val bg = if (focused) YancoPalette.BackgroundHover else YancoPalette.BackgroundDeep

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                color = YancoPalette.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!hint.isNullOrBlank()) {
                Text(
                    text = hint,
                    color = YancoPalette.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .border(1.dp, border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                val textFieldModifier = Modifier
                    .fillMaxWidth()
                    .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    interactionSource = interaction,
                    textStyle = TextStyle(color = YancoPalette.TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(YancoPalette.FocusRing),
                    visualTransformation = transformation,
                    modifier = textFieldModifier,
                )
            }
            if (voice) {
                VoiceInputButton(onResult = { onValueChange(voiceTransform(it)) })
            }
        }
    }
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
        focused -> YancoPalette.FocusRing
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

/**
 * Speech-to-text emits friendly English — collapse it to a URL shape.
 * "H T T P colon slash slash foo dot bar" → "http://foo.bar", drop spaces.
 */
private fun normalizeUrlFromSpeech(raw: String): String {
    var s = raw.trim().lowercase()
    s = s.replace(Regex("\\s*colon\\s*"), ":")
    s = s.replace(Regex("\\s*(forward\\s+slash|slash)\\s*"), "/")
    s = s.replace(Regex("\\s*dot\\s*"), ".")
    s = s.replace(Regex("\\s*dash\\s*"), "-")
    s = s.replace(Regex("\\s+"), "")
    return s
}
