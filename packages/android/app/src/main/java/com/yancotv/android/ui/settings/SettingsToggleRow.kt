package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.theme.LocalYancoPalette

/**
 * MB-107a — shared focus-aware toggle row for Settings tabs.
 *
 * Replaces three near-identical private `ToggleRow` definitions across
 * [SettingsParentalTab], [SettingsGeneralTab], [SettingsPlaybackTab] (and
 * the row-shape in [SettingsGroupsTab]) that all suffered from the same
 * Fire TV problem: a Material3 `Switch`'s default focus indicator is
 * invisible against the dark `BackgroundRaised` palette, so users
 * couldn't tell which toggle had focus when navigating with the D-pad.
 *
 * **Behaviour changes from the per-tab versions:**
 *
 * 1. The whole [Row] is the focus target, not the [Switch]. Bigger D-pad
 *    landing zone, matches Android TV's standard settings UX, and means
 *    we own the focus indicator at the row level (visible accent border)
 *    instead of fighting the Switch's tiny built-in halo.
 * 2. The [Switch] becomes a pure visual indicator (`onCheckedChange = null`)
 *    — input is handled by the row's `.clickable`. Without this we'd have
 *    two focus-takers on the same row and CENTER would race.
 * 3. Disabled state propagates to text colour AND the row's clickable
 *    `enabled` flag, so a greyed-out row also can't grab focus and confuse
 *    the cascade-nav (the previous Parental tab's "Require PIN to open
 *    Settings" toggle could be focused while disabled, which looked like
 *    a bug).
 *
 * The [Switch] colours match the previous per-tab definitions so the
 * checked/unchecked appearance is unchanged — only the focus indicator
 * is new.
 */
@Composable
internal fun SettingsToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val palette = LocalYancoPalette.current
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(palette.BackgroundRaised)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) palette.FocusRing else Color.Transparent,
                    shape = shape,
                )
                .clickable(
                    interactionSource = interaction,
                    // The accent border IS our focus indication; suppress
                    // the default ripple so it doesn't fight the border.
                    indication = null,
                    enabled = enabled,
                    onClick = { onCheckedChange(!checked) },
                )
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (enabled) palette.TextPrimary else palette.TextMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = palette.TextMuted,
                fontSize = 11.sp,
            )
        }
        // Display-only — `onCheckedChange = null` removes its own click /
        // focus surface so the row owns input. Compose still updates the
        // visual state from `checked`.
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = palette.Accent,
                    checkedTrackColor = palette.Accent.copy(alpha = 0.4f),
                    uncheckedThumbColor = palette.TextMuted,
                    uncheckedTrackColor = palette.BackgroundHover,
                ),
        )
    }
}
