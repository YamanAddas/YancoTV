package com.yancotv.android.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yancotv.android.ui.theme.LocalYancoPalette

/**
 * Settings-tab button helpers — Material3 `Button` / `OutlinedButton`
 * with a **visible focus border** for D-pad use on Fire TV.
 *
 * Material3's default focus indicator is a faint inner halo that
 * disappears against the dark theme at 3m. Settings tabs that built
 * buttons directly with `Button(...)` / `OutlinedButton(...)` (Backup,
 * Parental) had focus-visibility complaints from the user — the cursor
 * was invisible next to the [SettingsChip] / [SettingsToggleRow] rows
 * that paint a 2dp accent ring on focus.
 *
 * These helpers wrap the Material3 components with a shared
 * `MutableInteractionSource`, watch its focused state, and pass a
 * matching 2dp `BorderStroke` to the underlying button when focused —
 * preserves shape, padding, content, and click semantics; just adds
 * the missing focus ring. Slot-based content matches Compose's normal
 * `Button { Text(...) }` shape so callers replace the function name
 * and nothing else.
 */
@Composable
internal fun SettingsAccentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val palette = LocalYancoPalette.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = palette.Accent),
        border = if (focused) BorderStroke(2.dp, palette.FocusRing) else null,
        interactionSource = interaction,
        content = content,
    )
}

@Composable
internal fun SettingsOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val palette = LocalYancoPalette.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.TextPrimary),
        border =
            if (focused) {
                BorderStroke(2.dp, palette.FocusRing)
            } else {
                BorderStroke(1.dp, palette.PanelBorder)
            },
        interactionSource = interaction,
        content = content,
    )
}
