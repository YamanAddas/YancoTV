package com.yancotv.android.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.theme.LocalYancoPalette

/**
 * Verdant Frost toggle row (`YancoTV Settings Redesign.html` §04·03).
 *
 * The whole row is the focus target — clicking anywhere flips the
 * switch. On focus the row scales 1.02, gets a 1.5dp emerald ring,
 * and projects an accent halo via colored shadow. The switch itself
 * is a custom 56×32 pill with a 24dp knob — the previous Material3
 * `Switch` was a strong "ON tinted track" but the unchecked thumb
 * was barely visible against the dark BackgroundRaised. The custom
 * track / knob pair plus the accent gradient when ON read at 3 m
 * on Fire TV without squinting.
 *
 * Replaces the previous Material3-Switch wrapper that the user
 * called out as "the selector is not so visible" — focus visualization
 * is now identical to the sidebar tab item, so the cursor reads the
 * same everywhere.
 */
@Composable
internal fun SettingsToggleRow(label: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val palette = LocalYancoPalette.current
    // Unified with SettingsRow (12dp / 22×16 / 15sp label / 12sp hint).
    // Mixed primitives previously rendered at different shapes (14dp vs
    // 12dp) and paddings (18×14 vs 22×16) so a column with both row
    // types looked "all over" — same vertical rhythm now reads as one
    // family.
    val shape = RoundedCornerShape(12.dp)

    val targetScale = if (focused && enabled) 1.02f else 1.0f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 200),
        label = "toggleScale",
    )
    // LEFT from this row escapes to the active inner-sidebar tab — same
    // boundary contract every Settings row owns. See [SettingsRow].
    val activeTabFocus = LocalActiveSettingsTabFocus.current

    val rowBg =
        when {
            !enabled -> palette.BackgroundRaised.copy(alpha = 0.5f)
            focused -> palette.BackgroundRaised.copy(alpha = 0.65f)
            else -> palette.BackgroundRaised.copy(alpha = 0.5f)
        }
    val borderColor =
        when {
            !enabled -> palette.BorderSubtle.copy(alpha = 0.4f)
            focused -> palette.FocusRing
            else -> palette.BorderSubtle
        }

    // MK.29.2 — Box wraps the Row so the ON-state accent rail can paint
    // on top, aligned to CenterStart of the visual rectangle. The Row
    // owns layout + click input; the Box owns the focus chrome (scale,
    // shadow, clip, background, border) so the rail isn't pushed
    // around by the 22dp horizontal padding.
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (focused && enabled) 14.dp else 0.dp,
                shape = shape,
                ambientColor = palette.AccentGlow,
                spotColor = palette.AccentGlow,
            )
            .clip(shape)
            .background(rowBg)
            .border(
                width = if (focused && enabled) 1.5.dp else 1.dp,
                color = borderColor,
                shape = shape,
            )
            .leftExitsTo(activeTabFocus)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            ),
    ) {
        // MK.29.2 — ON-state rail. Mirrors the sidebar's selected-tab
        // rail (3dp × 44dp, vertical accent gradient, square leading
        // edge clipped by the row's 12dp corner). The whole row reads
        // as ON from across the room — the small VerdantSwitch on the
        // right only confirms it up close. Hidden when off OR disabled
        // (a disabled-but-ON toggle is conceptually still ON, but the
        // row chrome is muted anyway so the rail at full saturation
        // would clash; mute it via alpha).
        if (checked) {
            Box(
                modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(
                                if (enabled) palette.Accent else palette.AccentMuted,
                                if (enabled) palette.AccentDeep else palette.AccentMuted,
                            ),
                        ),
                    ),
            )
        }
        Row(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = if (enabled) palette.TextPrimary else palette.TextMuted,
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    color = if (enabled) palette.TextMuted else palette.TextFaint,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            VerdantSwitch(checked = checked, enabled = enabled)
        }
    }
}

/**
 * Custom 56×32 pill switch with a 24dp knob — Verdant Frost spec.
 *
 * - Track ON: vertical accent gradient (Accent → AccentDeep) + inner
 *   glow inset.
 * - Track OFF: low-alpha white surface, faint hairline border.
 * - Knob: gradient between TextPrimary and TextSecondary so it has a
 *   slight 3D pillow look against either track state. Slides 24dp.
 * - Disabled: 42% opacity, no animation.
 *
 * No interaction surface of its own — this is a pure visual indicator;
 * the parent row owns input. That's deliberate: putting input here
 * would create a second focus target and the row's focus halo would
 * stop firing. (Same model as the `MB-107a` toggle row, just visually
 * upgraded.)
 */
@Composable
private fun VerdantSwitch(checked: Boolean, enabled: Boolean) {
    val palette = LocalYancoPalette.current
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 27.dp else 3.dp,
        animationSpec = tween(durationMillis = 220),
        label = "switchKnob",
    )

    val trackBrush =
        when {
            !enabled && checked -> Brush.verticalGradient(listOf(palette.AccentMuted, palette.AccentMuted))
            checked -> Brush.verticalGradient(listOf(palette.AccentDeep, palette.Accent))
            else -> Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.05f)),
            )
        }
    val trackBorder =
        if (checked) {
            palette.Accent.copy(alpha = 0.6f)
        } else {
            Color.White.copy(alpha = 0.12f)
        }

    Row(
        modifier =
        Modifier
            .size(width = 54.dp, height = 30.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .clip(RoundedCornerShape(15.dp))
            .background(trackBrush)
            .border(1.dp, trackBorder, RoundedCornerShape(15.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Spacer pushes the knob from the left; width animates 3dp → 27dp
        // for the OFF → ON transition. Keeps the knob a fixed 24dp circle
        // and avoids manual px conversion that needs LocalDensity.
        Spacer(modifier = Modifier.width(knobOffset))
        Box(
            modifier =
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(palette.TextPrimary, palette.TextSecondary),
                    ),
                ),
        )
    }
}
