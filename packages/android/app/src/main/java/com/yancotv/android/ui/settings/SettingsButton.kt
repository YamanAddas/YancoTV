package com.yancotv.android.ui.settings

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yancotv.android.ui.components.YancoDangerButton
import com.yancotv.android.ui.components.YancoPrimaryButton
import com.yancotv.android.ui.components.YancoSecondaryButton

/**
 * Settings-tab button forwarders.
 *
 * The Verdant Frost button family that used to live here was hoisted to
 * `ui/components/YancoButton.kt` so every screen (settings, content-detail
 * hero, source dialog, favorites, recordings, guide, …) shares one focus
 * visual language. Existing settings call sites kept their old names —
 * these forwarders make the rename a zero-touch change for callers.
 *
 * Behaviour, signature, and TV-focus chrome are identical to the hoisted
 * versions; see `YancoButton.kt` for the canonical documentation and the
 * `YancoButtonColorsTest` for the contract.
 */
internal typealias ButtonSize = com.yancotv.android.ui.components.ButtonSize

@Composable
internal fun SettingsAccentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Standard,
    translucent: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) = YancoPrimaryButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    size = size,
    translucent = translucent,
    content = content,
)

@Composable
internal fun SettingsOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Standard,
    content: @Composable RowScope.() -> Unit,
) = YancoSecondaryButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    size = size,
    content = content,
)

@Composable
internal fun SettingsDangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Standard,
    content: @Composable RowScope.() -> Unit,
) = YancoDangerButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    size = size,
    content = content,
)
