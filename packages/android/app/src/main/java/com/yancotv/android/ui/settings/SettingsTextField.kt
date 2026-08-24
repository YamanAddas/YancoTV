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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.theme.LocalYancoPalette

/**
 * MB-117 — shared click-to-edit field for every Settings text input. The
 * default state is a focusable read-only row that shows the current value
 * (or [hint] placeholder). Pressing OK flips it into edit mode, composes
 * a [BasicTextField] inside, and only then does the embedded field
 * request focus and pop the IME. Pressing Done / Enter / Back collapses
 * back to the read-only display.
 *
 * Originally lived as a private helper inside [AddSourceDialog]; promoted
 * here so every Settings tab gets the same "the keyboard only appears
 * when I ask for it" UX. Plain Material3 [androidx.compose.material3.OutlinedTextField]
 * pops the IME the moment focus lands on it via D-pad — on Fire TV that
 * means flicking past a field opens the system keyboard with no way to
 * dismiss without entering text, which is the bug this composable fixes.
 *
 * Two render modes:
 *   - **Card**: title + optional [description] sit above the field, all
 *     wrapped in the standard Settings PrefCard background. Use for
 *     long-form labelled fields (User-Agent, language codes, timeouts).
 *   - **Bare**: just the row, no card chrome. Use inside dialogs or
 *     when the parent already provides the framing (AddSourceDialog).
 *
 * @param label tab-card title (Card mode) or inline label (Bare mode)
 * @param description prose under the title — Card mode only; ignored when bare
 * @param value the current committed value
 * @param onValueChange called per keystroke while editing
 * @param hint placeholder shown when value is blank
 * @param keyboardType IME variant — Text / Number / NumberPassword / Ascii
 * @param transformation [VisualTransformation.None] or [PasswordVisualTransformation]
 * @param bare true → no PrefCard chrome (used inside dialogs)
 * @param modifier outer modifier — applies to the whole field column
 */
@Composable
fun SettingsClickToEditField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    description: String? = null,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    transformation: VisualTransformation = VisualTransformation.None,
    bare: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    val editFocus = remember { FocusRequester() }
    val readOnlyFocus = remember { FocusRequester() }
    // MB-398 — has this field been in edit mode at least once? See below.
    var hasEdited by remember { mutableStateOf(false) }

    LaunchedEffect(editing) {
        if (editing) {
            hasEdited = true
            runCatching { editFocus.requestFocus() }
        } else if (hasEdited) {
            // MB-302 — hand focus back to the read-only row after an edit. An
            // earlier version gated this on a "was this a deliberate exit"
            // flag; on TV that distinction is unreachable (the IME owns the
            // D-pad while open, so the field can only lose focus by the IME
            // closing) and the flag just left nothing focused and a dead
            // D-pad. `hasEdited` is NOT that flag: every exit from edit mode
            // still requests, deliberate or not — it only excludes the
            // never-edited case.
            //
            // MB-398 — that never-edited case is why this needs a gate at
            // all. `LaunchedEffect(editing)` also runs on FIRST composition,
            // where editing is false, so every field grabbed focus onto its
            // own row the moment it appeared. On a screen with several of
            // them the last one to compose won and dragged the scroll
            // container to itself: the Add-source dialog opened scrolled to
            // its bottom (focus on Referer, the source-type chips not even
            // rendered), and settings tabs jumped to their last field.
            // Screens now own their entry focus (moveFocus into the pane, or
            // an explicit PlacedFocusAnchor) instead of losing it to whichever
            // field happened to compose last.
            runCatching { readOnlyFocus.requestFocus() }
        }
    }

    val labelText: @Composable () -> Unit = {
        Text(
            text = label,
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = if (bare) 12.sp else 14.sp,
            fontWeight = if (bare) FontWeight.Medium else FontWeight.SemiBold,
        )
    }

    val descText: @Composable () -> Unit = {
        if (!description.isNullOrBlank() && !bare) {
            Text(
                text = description,
                color = LocalYancoPalette.current.TextMuted,
                fontSize = 12.sp,
            )
        }
    }

    val fieldRow: @Composable () -> Unit = {
        if (editing) {
            EditableFieldBody(
                value = value,
                onValueChange = onValueChange,
                transformation = transformation,
                keyboardType = keyboardType,
                focusRequester = editFocus,
                onDone = { editing = false },
                // MB-301 — the field lost focus, which on TV means the IME
                // closed. Drop out of edit mode so returning to this row later
                // does NOT land on a live text field and pop the keyboard
                // unasked. The effect above then puts focus back on the row.
                onFocusLost = { editing = false },
            )
        } else {
            ReadOnlyFieldBody(
                display = transformForDisplay(value, transformation),
                placeholder = hint?.takeIf { value.isBlank() } ?: "",
                empty = value.isBlank(),
                onClick = { editing = true },
                focusRequester = readOnlyFocus,
            )
        }
    }

    val activeTabFocus = LocalActiveSettingsTabFocus.current
    if (bare) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labelText()
                if (!hint.isNullOrBlank() && !editing) {
                    Text(text = hint, color = LocalYancoPalette.current.TextMuted, fontSize = 12.sp)
                }
            }
            fieldRow()
        }
    } else {
        Column(
            modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(LocalYancoPalette.current.BackgroundRaised)
                // LEFT from the inner field escapes to the active inner-
                // sidebar tab — same boundary contract every Settings row
                // owns. Bare mode is used inside dialogs, so it doesn't
                // need this — the dialog owns its own focus boundary.
                .startExitsTo(activeTabFocus)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            labelText()
            descText()
            fieldRow()
        }
    }
}

@Composable
private fun ReadOnlyFieldBody(
    display: String,
    placeholder: String,
    empty: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester = FocusRequester.Default,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border = if (focused) LocalYancoPalette.current.FocusRing else LocalYancoPalette.current.BorderSubtle
    val bg = if (focused) LocalYancoPalette.current.BackgroundHover else LocalYancoPalette.current.BackgroundDeep
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(if (focused) 2.dp else 1.dp, border, RoundedCornerShape(8.dp))
            .focusRequester(focusRequester)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Single-line clamp + ellipsis — long URLs like
        // 'https://example.com/xmltv.xml?token=…' would wrap to two lines
        // and push the field to ~64dp tall, which the user called out
        // ("box too small / not put right" — the wrap was making the
        // field look wonky). Single line keeps the field a clean strip
        // and the value still scrolls horizontally if you tap to edit.
        Text(
            text = if (empty) placeholder else display,
            color = if (empty) LocalYancoPalette.current.TextMuted else LocalYancoPalette.current.TextPrimary,
            fontSize = 14.sp,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EditableFieldBody(
    value: String,
    onValueChange: (String) -> Unit,
    transformation: VisualTransformation,
    keyboardType: KeyboardType,
    focusRequester: FocusRequester,
    onDone: () -> Unit,
    onFocusLost: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // MB-301 — see the SearchScreen field for the full rationale. Only a real
    // focused -> unfocused transition counts: `onFocusChanged` also reports the
    // initial unfocused state before `focusRequester.requestFocus()` lands, and
    // acting on that would cancel edit mode the instant it opened. Scoped here
    // rather than hoisted so it resets every time the field re-enters edit mode.
    var hadFocus by remember { mutableStateOf(false) }
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(LocalYancoPalette.current.BackgroundHover)
            .border(2.dp, LocalYancoPalette.current.FocusRing, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            interactionSource = interaction,
            textStyle = TextStyle(color = LocalYancoPalette.current.TextPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(LocalYancoPalette.current.FocusRing),
            visualTransformation = transformation,
            keyboardOptions =
            KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier =
            Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        hadFocus = false
                        onFocusLost()
                    }
                },
        )
    }
}

private fun transformForDisplay(value: String, transformation: VisualTransformation): String {
    if (value.isEmpty()) return ""
    if (transformation is PasswordVisualTransformation) return "•".repeat(value.length)
    return value
}
