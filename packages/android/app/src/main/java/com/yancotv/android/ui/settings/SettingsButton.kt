package com.yancotv.android.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoPalette

/**
 * Settings-tab button helpers — Verdant Frost spec (`YancoTV Settings
 * Redesign.html` §04·06–07). Replaces the previous Material3
 * `Button` / `OutlinedButton` wrappers, which produced a flat saturated-
 * green primary button (the source of the user's "Export backup is
 * filled with green" complaint) and a low-contrast secondary button
 * that disappeared against the dark theme.
 *
 * **Primary** — `linear-gradient(180deg, accent → accent-deep)` fill,
 * dark ink text (`OnAccentInk`), inner top highlight via inset border,
 * lift shadow. On focus: scale 1.04 + 1.5dp emerald ring + accent halo.
 *
 * **Secondary** — translucent surface (`rgba(255,255,255,0.03)`) with a
 * 1.5dp hairline border. On focus: scale 1.04 + 1.5dp emerald ring +
 * accent-tinted bg + accent-glow text. The bg/text shift is what makes
 * the cursor unmistakable at 3 m on Fire TV — the previous
 * `OutlinedButton + 2dp border` swap was reading as "the same outline
 * just a bit thicker", which the user couldn't see from the couch.
 *
 * Both buttons use a custom `Modifier.clickable` chain (no Material3
 * Button wrapper) so:
 *   - We control the brush/gradient directly without fighting
 *     `ButtonDefaults.buttonColors`.
 *   - The focus interaction source is hooked to a single shared scope —
 *     no Material3 ripple / state-layer fighting our visual.
 *   - Keeping the slot-based `RowScope.() -> Unit` content keeps every
 *     existing call site intact: `SettingsAccentButton(onClick = ...) {
 *     Text("Export") }` still compiles unchanged.
 */
private val OnAccentInk: Color = Color(0xFF04130C)

/**
 * Sizing presets for the button helpers. `Standard` is the default
 * (48dp tall, 22dp horizontal padding, 13sp label, 88dp min-width) and
 * suits headers and confirm-row CTAs. `Compact` (36dp / 14dp / 11sp /
 * 64dp) is for inline row actions where two buttons sit beside meta on
 * a list row — without it the SYNC + DELETE pair on a Source row eats
 * the available width and squashes the name. Mirrors the design's
 * `.btn` (52h) vs the inline-row "small" buttons (38h) spec.
 */
internal enum class ButtonSize { Standard, Compact }

private data class ButtonMetrics(
    val height: Int,
    val horizontalPadding: Int,
    val fontSize: Int,
    val minWidth: Int,
    val cornerRadius: Int,
)

private fun metricsFor(size: ButtonSize): ButtonMetrics =
    when (size) {
        ButtonSize.Standard -> ButtonMetrics(48, 22, 13, 88, 14)
        ButtonSize.Compact -> ButtonMetrics(36, 14, 11, 64, 10)
    }

@Composable
internal fun SettingsAccentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Standard,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val palette = LocalYancoPalette.current
    val m = metricsFor(size)
    val shape = RoundedCornerShape(m.cornerRadius.dp)

    val targetScale = if (focused && enabled) 1.04f else 1.0f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 180),
        label = "primaryScale",
    )

    val fill = primaryFillBrush(palette, enabled)
    val borderColor = if (focused && enabled) palette.FocusRing else Color.Transparent
    val textColor = if (enabled) OnAccentInk else palette.TextMuted

    Row(
        modifier =
            modifier
                .height(m.height.dp)
                .defaultMinSize(minWidth = m.minWidth.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = if (focused && enabled) 16.dp else 6.dp,
                    shape = shape,
                    ambientColor = palette.AccentGlow,
                    spotColor = palette.AccentGlow,
                )
                .clip(shape)
                .background(fill)
                .border(
                    width = if (focused && enabled) 1.5.dp else 1.dp,
                    color = if (focused && enabled) borderColor else Color.White.copy(alpha = 0.18f),
                    shape = shape,
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(horizontal = m.horizontalPadding.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides textColor,
            LocalTextStyle provides
                LocalTextStyle.current.copy(
                    color = textColor,
                    fontSize = m.fontSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                ),
            content = { content() },
        )
    }
}

@Composable
internal fun SettingsOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Standard,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val palette = LocalYancoPalette.current
    val m = metricsFor(size)
    val shape = RoundedCornerShape(m.cornerRadius.dp)

    val targetScale = if (focused && enabled) 1.04f else 1.0f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 180),
        label = "secondaryScale",
    )

    val bgColor =
        when {
            !enabled -> Color.White.copy(alpha = 0.02f)
            focused -> palette.Accent.copy(alpha = 0.18f)
            else -> Color.White.copy(alpha = 0.04f)
        }
    val borderColor =
        when {
            !enabled -> palette.BorderSubtle
            focused -> palette.FocusRing
            else -> palette.PanelBorder
        }
    val textColor =
        when {
            !enabled -> palette.TextMuted
            focused -> palette.AccentGlow
            else -> palette.TextPrimary
        }

    Row(
        modifier =
            modifier
                .height(m.height.dp)
                .defaultMinSize(minWidth = m.minWidth.dp)
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
                .background(bgColor)
                .border(
                    width = if (focused && enabled) 1.5.dp else 1.dp,
                    color = borderColor,
                    shape = shape,
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(horizontal = m.horizontalPadding.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides textColor,
            LocalTextStyle provides
                LocalTextStyle.current.copy(
                    color = textColor,
                    fontSize = m.fontSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                ),
            content = { content() },
        )
    }
}

/**
 * Destructive-action button — Verdant Frost spec (`.btn-danger`).
 * Soft red bg + red 1.5dp border, brighter red ring on focus. Use for
 * deletes, removes, "remove PIN permanently", etc. — anywhere a press
 * would lose user data without confirmation.
 */
@Composable
internal fun SettingsDangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Standard,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val palette = LocalYancoPalette.current
    val m = metricsFor(size)
    val shape = RoundedCornerShape(m.cornerRadius.dp)

    val targetScale = if (focused && enabled) 1.04f else 1.0f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 180),
        label = "dangerScale",
    )

    val bgColor =
        when {
            !enabled -> palette.Error.copy(alpha = 0.06f)
            focused -> palette.Error.copy(alpha = 0.32f)
            else -> palette.Error.copy(alpha = 0.16f)
        }
    val borderColor =
        when {
            !enabled -> palette.Error.copy(alpha = 0.16f)
            focused -> palette.Error
            else -> palette.Error.copy(alpha = 0.4f)
        }
    val textColor = if (enabled) palette.Error else palette.TextMuted

    Row(
        modifier =
            modifier
                .height(m.height.dp)
                .defaultMinSize(minWidth = m.minWidth.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = if (focused && enabled) 16.dp else 0.dp,
                    shape = shape,
                    ambientColor = palette.Error,
                    spotColor = palette.Error,
                )
                .clip(shape)
                .background(bgColor)
                .border(
                    width = if (focused && enabled) 1.5.dp else 1.dp,
                    color = borderColor,
                    shape = shape,
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(horizontal = m.horizontalPadding.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides textColor,
            LocalTextStyle provides
                LocalTextStyle.current.copy(
                    color = textColor,
                    fontSize = m.fontSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                ),
            content = { content() },
        )
    }
}

private fun primaryFillBrush(palette: YancoPalette, enabled: Boolean): Brush =
    if (enabled) {
        Brush.verticalGradient(
            listOf(palette.Accent, palette.AccentDeep),
        )
    } else {
        Brush.verticalGradient(
            listOf(
                palette.AccentMuted.copy(alpha = 0.6f),
                palette.AccentMuted.copy(alpha = 0.4f),
            ),
        )
    }

/**
 * Internal sentinel used by tests to verify the button never falls back
 * to the Material3 ripple — kept here so production code still has the
 * suppress alias and removal of the helper is a single-symbol grep.
 */
@Suppress("unused")
private object NoRippleMarker
