package com.yancotv.android.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoPalette

/**
 * Shared settings selector chip. Replaces three near-identical private
 * `Chip`/`ResizeChip`/`TypeChip` definitions across [SettingsGeneralTab],
 * [SettingsPlaybackTab], and [SettingsGroupsTab] that all suffered from
 * the same Fire TV problem: they used a bare `Row + .clickable {}` with
 * NO `.focusable()` and no focus-aware visual state. Result on TV:
 *
 * - The chip would not register as a focus target in the leanback search,
 *   so D-pad RIGHT into the chip row would skip past it entirely or land
 *   on whatever Text came after.
 * - When focus *did* land (e.g. via a programmatic `requestFocus`), there
 *   was no visible indicator — only the "selected" gradient flipped, and
 *   only when the user pressed CENTER to commit a new value.
 *
 * This composable wires:
 * 1. A `MutableInteractionSource` shared between `.focusable()` and
 *    `.clickable()` so `collectIsFocusedAsState()` actually flips when the
 *    chip gains focus.
 * 2. A visible focus border (2dp accent ring) layered on top of the
 *    selected/unselected gradient — same pattern as [SettingsToggleRow]
 *    (MB-107a) so the look feels coherent across the screen.
 * 3. Suppressed indication on `.clickable` (`indication = null`) so the
 *    border owns the focus visualization and the default ripple doesn't
 *    fight it.
 *
 * The colour rules are extracted to [chipColors] so the
 * focused/selected/idle palette decisions can be unit-tested without the
 * Compose runtime — see `SettingsChipColorsTest` (MB-110).
 */
@Composable
internal fun SettingsChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * MK.31.26 — spoken label, when it has to differ from the visible one.
     *
     * Defaults to [label], which is right for every chip whose text a TTS voice
     * can read. The exception is the language picker: its labels are endonyms
     * ("العربية"), unpronounceable by an engine set to the current UI language.
     */
    contentDescription: String = label,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val palette = LocalYancoPalette.current
    val colors = chipColors(palette, selected = selected, focused = focused)
    val shape = RoundedCornerShape(6.dp)

    // MK.29.3 — Focus scale. A 1dp → 2dp border change on a small 6dp chip
    // was hard to track at 3m on Fire TV; D-pad walks across a row of chips
    // and the eye couldn't keep up. Matches the SettingsToggleRow scale
    // (1.02) so chip focus motion belongs to the same family.
    val targetScale = if (focused) 1.02f else 1.0f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 180),
        label = "chipScale",
    )

    Row(
        modifier =
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(colors.background)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = colors.border,
                shape = shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            // MK.29.3 — Merge descendants so TalkBack announces the chip
            // as ONE tab named after its label, with selection state.
            // Pre-fix, custom Row + .clickable surfaced as "Tab" with
            // no name and no selected/unselected announcement (audit
            // finding).
            .semantics(mergeDescendants = true) {
                // MK.31.26 — `this.` is required: an unqualified
                // `contentDescription` binds to this function's PARAMETER of the
                // same name, not the semantics receiver, and the compiler then
                // reports "'val' cannot be reassigned". Same reason `selected`
                // below is qualified.
                this.contentDescription = contentDescription
                this.selected = selected
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = colors.foreground,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * Resolved colour bundle for a settings chip in a given (focused, selected)
 * state. Pure function: takes a palette + booleans, returns colours. Pulled
 * out so the contract is unit-testable without spinning up Compose.
 *
 * Rules locked in by [SettingsChipColorsTest] (MB-112):
 * - **Focus is the FRAME.** Only `focused` paints a border. Selected-but-
 *   unfocused has Color.Transparent — selection is communicated by the
 *   background tint alone. This is the explicit "make the detector a
 *   frame around the thing" rule from the user feedback after MB-110:
 *   on Fire TV at 3 metres, a 1dp accent border on selected and a 2dp
 *   FocusRing border on focused look the same; the eye reads both as a
 *   thin coloured outline, so you can't tell where the cursor actually
 *   is. Pulling the border off `selected` makes "frame present" mean
 *   one thing: focus.
 * - `selected` wins for background tinting (the user keeps the "I picked
 *   this" cue while moving focus away).
 * - Foreground stays primary for `selected || focused`, muted otherwise.
 */
internal data class SettingsChipColors(val background: Color, val border: Color, val foreground: Color)

internal fun chipColors(palette: YancoPalette, selected: Boolean, focused: Boolean): SettingsChipColors {
    val background =
        if (selected) palette.Accent.copy(alpha = 0.22f) else palette.BackgroundDeep
    val border = if (focused) palette.FocusRing else Color.Transparent
    val foreground =
        if (selected || focused) palette.TextPrimary else palette.TextMuted
    return SettingsChipColors(background = background, border = border, foreground = foreground)
}
