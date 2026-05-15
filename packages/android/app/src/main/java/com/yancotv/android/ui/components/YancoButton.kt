package com.yancotv.android.ui.components

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoPalette

/**
 * App-wide button family. Hoisted from the original Verdant Frost settings
 * spec (`SettingsButton.kt`, MK.16 / `YancoTV Settings Redesign.html`
 * §04·06–07) so every screen — settings, content-detail hero, source
 * dialog, favorites, recordings, guide — speaks the same focus language.
 *
 * Three signals stack on focus, the same as `Modifier.focusStyle`:
 *   1. Scale 1.04 spring (180ms tween) — eye-tracking cue at 10 ft.
 *   2. 1.5dp FocusRing border — the FRAME tells the user where the cursor
 *      is. Selected-but-unfocused never paints a coloured border (see
 *      MB-112 / `SettingsChipColorsTest`) so "frame present" means one
 *      thing only: focus.
 *   3. Colored shadow tinted with `AccentGlow` (or `Error` for danger) —
 *      the lit-from-behind halo that reads as "this is the active one"
 *      from across the room.
 *
 * Three buttons cover every spot in the app:
 *   - [YancoPrimaryButton]   — gradient-fill emerald CTA. THE action on a screen.
 *     Translucent variant for actions that should feel primary inside a
 *     larger section (Detail-hero "Resume", Backup "Export").
 *   - [YancoSecondaryButton] — outlined translucent. Companion actions
 *     (Play-from-start, Cancel, secondary settings rows).
 *   - [YancoDangerButton]    — soft red fill, red ring. Destructive
 *     (delete source, remove PIN, clear history).
 *
 * Two size presets — see [ButtonSize]. Standard for hero / confirm-row
 * CTAs (48dp tall). Compact for inline-row actions where space is tight
 * (favorites action chip, recordings inline, guide "Now" jump).
 *
 * The old `SettingsAccentButton` / `SettingsOutlinedButton` /
 * `SettingsDangerButton` names live on as thin forwarders in
 * `ui/settings/SettingsButton.kt` so existing settings call sites compile
 * unchanged.
 */
private val OnAccentInk: Color = Color(0xFF04130C)

/**
 * Sizing presets shared by every button in the family.
 *
 * - `Standard` (48dp / 22dp pad / 13sp / 88dp min-width / 14dp radius) —
 *   the default. Used for confirm-row CTAs and hero buttons.
 * - `Compact` (36dp / 14dp pad / 11sp / 64dp min-width / 10dp radius) —
 *   for inline row actions sitting beside list-row meta. Without it the
 *   SYNC + DELETE pair on a Source row eats available width and squashes
 *   the name.
 */
enum class ButtonSize { Standard, Compact }

private data class ButtonMetrics(val height: Int, val horizontalPadding: Int, val fontSize: Int, val minWidth: Int, val cornerRadius: Int)

private fun metricsFor(size: ButtonSize): ButtonMetrics = when (size) {
    ButtonSize.Standard -> ButtonMetrics(48, 22, 13, 88, 14)
    ButtonSize.Compact -> ButtonMetrics(36, 14, 11, 64, 10)
}

/**
 * Primary accent CTA. Two visual variants share identical focus chrome:
 *
 *   - **Solid** (default) — vertical `Accent → AccentDeep` gradient fill,
 *     dark `OnAccentInk` text. The "this is THE action" button. Used for
 *     Resume / Play, ADD SOURCE, Restore, Save.
 *   - **Translucent** ([translucent] = `true`) — accent-tinted alpha fill,
 *     accent text + accent border. Reads as "primary action, but part of
 *     a longer section" — for hero actions embedded in a wider screen
 *     where a fully saturated gradient would dominate (Backup → Export,
 *     Guide → Now).
 *
 * Rest vs focus: rest already uses the darker `AccentDeep` end of the
 * gradient as its bottom stop, so the focus state — same gradient +
 * halo + ring + scale 1.04 — reads as the LIT version of the rest state,
 * not a marginal hue shift. This is what fixes the "focus selector
 * looks like a slightly brighter button" complaint that prompted the
 * unification.
 */
@Composable
fun YancoPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Standard,
    translucent: Boolean = false,
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

    val fill: Brush
    val borderColor: Color
    val borderWidth: androidx.compose.ui.unit.Dp
    val textColor: Color
    if (translucent) {
        fill =
            Brush.verticalGradient(
                listOf(
                    palette.Accent.copy(alpha = if (focused && enabled) 0.32f else 0.18f),
                    palette.Accent.copy(alpha = if (focused && enabled) 0.20f else 0.10f),
                ),
            )
        borderColor =
            when {
                !enabled -> palette.Accent.copy(alpha = 0.20f)
                focused -> palette.FocusRing
                else -> palette.Accent.copy(alpha = 0.55f)
            }
        borderWidth = if (focused && enabled) 1.5.dp else 1.dp
        textColor =
            when {
                !enabled -> palette.TextMuted
                focused -> palette.AccentGlow
                else -> palette.Accent
            }
    } else {
        fill = primaryFillBrush(palette, enabled)
        borderColor =
            if (focused && enabled) palette.FocusRing else Color.White.copy(alpha = 0.18f)
        borderWidth = if (focused && enabled) 1.5.dp else 1.dp
        textColor = if (enabled) OnAccentInk else palette.TextMuted
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
                elevation = if (focused && enabled) {
                    16.dp
                } else if (translucent) {
                    0.dp
                } else {
                    6.dp
                },
                shape = shape,
                ambientColor = palette.AccentGlow,
                spotColor = palette.AccentGlow,
            )
            .clip(shape)
            .background(fill)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
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
 * Secondary outlined button. Translucent surface at rest with a thin
 * hairline border; on focus the bg tints toward accent, the border
 * upgrades to `FocusRing` at 1.5dp, the text shifts to `AccentGlow`, and
 * the scale-up + colored shadow project the same halo as the primary.
 *
 * The bg + text colour shift is what makes the cursor unmistakable at
 * 3 m on Fire TV. A thin border-only swap was indistinguishable from
 * "the same outline, just slightly thicker" at TV distance — the bug
 * we shipped pre-MK.16.
 */
@Composable
fun YancoSecondaryButton(
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
                role = Role.Button,
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
 * Destructive-action button. Soft red fill + red 1.5dp border, brighter
 * red ring on focus, red-tinted halo shadow. For deletes, removes,
 * "remove PIN permanently" — anywhere a press would lose user data
 * without confirmation.
 */
@Composable
fun YancoDangerButton(
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
                role = Role.Button,
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

private fun primaryFillBrush(palette: YancoPalette, enabled: Boolean): Brush = if (enabled) {
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
 * Pure-function colour resolver for [YancoPrimaryButton]'s solid variant
 * — exposed (internal) so the colour contract can be unit-tested without
 * spinning up Compose. Mirrors the `chipColors` pattern from
 * `SettingsChip` / `SettingsChipColorsTest`.
 */
internal data class YancoButtonColors(val borderColor: Color, val textColor: Color, val borderWidthIsFocus: Boolean)

internal fun primarySolidColors(palette: YancoPalette, focused: Boolean, enabled: Boolean): YancoButtonColors = YancoButtonColors(
    borderColor = if (focused && enabled) palette.FocusRing else Color.White.copy(alpha = 0.18f),
    textColor = if (enabled) OnAccentInk else palette.TextMuted,
    borderWidthIsFocus = focused && enabled,
)

internal fun primaryTranslucentColors(palette: YancoPalette, focused: Boolean, enabled: Boolean): YancoButtonColors = YancoButtonColors(
    borderColor =
    when {
        !enabled -> palette.Accent.copy(alpha = 0.20f)
        focused -> palette.FocusRing
        else -> palette.Accent.copy(alpha = 0.55f)
    },
    textColor =
    when {
        !enabled -> palette.TextMuted
        focused -> palette.AccentGlow
        else -> palette.Accent
    },
    borderWidthIsFocus = focused && enabled,
)

internal fun secondaryColors(palette: YancoPalette, focused: Boolean, enabled: Boolean): YancoButtonColors = YancoButtonColors(
    borderColor =
    when {
        !enabled -> palette.BorderSubtle
        focused -> palette.FocusRing
        else -> palette.PanelBorder
    },
    textColor =
    when {
        !enabled -> palette.TextMuted
        focused -> palette.AccentGlow
        else -> palette.TextPrimary
    },
    borderWidthIsFocus = focused && enabled,
)

internal fun dangerColors(palette: YancoPalette, focused: Boolean, enabled: Boolean): YancoButtonColors = YancoButtonColors(
    borderColor =
    when {
        !enabled -> palette.Error.copy(alpha = 0.16f)
        focused -> palette.Error
        else -> palette.Error.copy(alpha = 0.4f)
    },
    textColor = if (enabled) palette.Error else palette.TextMuted,
    borderWidthIsFocus = focused && enabled,
)
