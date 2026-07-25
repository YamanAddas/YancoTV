package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.AppearancePrefs
import com.yancotv.android.ui.theme.AccentId
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.ThemeController
import com.yancotv.android.ui.theme.ThemeId
import com.yancotv.android.ui.theme.YancoPalette
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Appearance — theme picker, accent picker, font scale. Wrapped in
 * [SettingsSection]s so the type scale matches the rest of Settings,
 * but the theme rows + accent chips keep their bespoke focus chrome
 * (these are visual previews, not generic chips, so the existing
 * MB-110 audit-pass-5 fix stays in place).
 */
@Composable
fun SettingsAppearanceTab(modifier: Modifier = Modifier, themeController: ThemeController = koinInject(), prefs: AppPreferences = koinInject()) {
    val active by themeController.themeId.collectAsState()
    val activeAccent by themeController.accentId.collectAsState()
    val appearance by prefs.appearanceFlow.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 80.dp),
    ) {
        SettingsSection(
            title = "Theme",
            sub = "Pick a palette. Changes apply instantly across the app — no restart.",
        ) {
            ThemeId.values().forEachIndexed { idx, id ->
                if (idx > 0) SettingsRowSpacer()
                ThemeRow(
                    id = id,
                    selected = id == active,
                    palette = themeController.paletteFor(id),
                    onClick = {
                        themeController.setTheme(id)
                        scope.launch { prefs.setThemeId(id.name) }
                    },
                )
            }
        }

        SettingsSection(
            title = "Accent",
            sub = "Tint applied to focus rings, progress bars, chips and other interactive accents.",
        ) {
            SettingsRow(
                label = "Accent colour",
                hint = "Independent of the theme palette — any base theme can carry any accent.",
                content = {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AccentId.values().forEach { id ->
                            AccentChip(
                                id = id,
                                selected = id == activeAccent,
                                swatch = swatchColourFor(id, themeController, active),
                                onClick = {
                                    themeController.setAccent(id)
                                    scope.launch { prefs.setAccentId(id.name) }
                                },
                            )
                        }
                    }
                },
            )
        }

        SettingsSection(
            title = "Type",
            sub = "Live-applies via LocalDensity override — sp-sized text rescales without restart, dp layouts stay put.",
        ) {
            SettingsRow(
                label = "Font scale",
                hint = "Multiplies base body size. 100% is the default.",
                content = {
                    SettingsChipRow(
                        options = AppearancePrefs.FONT_SCALE_PRESETS.map { "$it%" },
                        selected = "${appearance.fontScalePercent}%",
                        onSelect = { selection ->
                            val pct = selection.removeSuffix("%").toIntOrNull() ?: return@SettingsChipRow
                            scope.launch { prefs.setFontScalePercent(pct) }
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun ThemeRow(id: ThemeId, selected: Boolean, palette: YancoPalette, onClick: () -> Unit) {
    // Audit-pass-5: focus + selected as separate visual states. Without
    // a shared interactionSource the border only updated on `selected`,
    // so D-pad navigation across rows had no visible cursor — user had
    // to commit blind.
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pal = LocalYancoPalette.current
    val borderColour =
        when {
            focused -> pal.FocusRing
            selected -> pal.Accent
            else -> pal.BorderSubtle
        }
    val borderWidth = if (focused || selected) 1.5.dp else 1.dp
    Row(
        modifier =
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(pal.BackgroundRaised.copy(alpha = if (focused) 0.65f else 0.5f))
            .border(
                width = borderWidth,
                color = borderColour,
                shape = RoundedCornerShape(12.dp),
            )
            .focusable(interactionSource = interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SwatchTriple(palette = palette)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = id.displayName,
                color = pal.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (selected) "Active" else "Tap to apply",
                color = if (selected) pal.Accent else pal.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (selected) {
            SettingsKicker(text = "ACTIVE", accent = true)
        }
    }
}

/** Three side-by-side swatches sampled from the candidate palette so
 *  the user previews the *new* theme's colours, not the active one's. */
@Composable
private fun SwatchTriple(palette: YancoPalette) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Swatch(palette.BackgroundRaised)
        Swatch(palette.Accent)
        Swatch(palette.FocusRing)
    }
}

@Composable
private fun AccentChip(id: AccentId, selected: Boolean, swatch: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pal = LocalYancoPalette.current
    val borderColour =
        when {
            focused -> pal.FocusRing
            selected -> pal.Accent
            else -> pal.BorderSubtle
        }
    val borderWidth = if (focused || selected) 1.5.dp else 1.dp
    Row(
        modifier =
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) pal.Accent.copy(alpha = 0.18f) else pal.BackgroundRaised)
            .border(
                width = borderWidth,
                color = borderColour,
                shape = RoundedCornerShape(8.dp),
            )
            .focusable(interactionSource = interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            // MK.28.8 (MB-276) — announce selected state to TalkBack so the
            // active accent is distinguishable from the rest.
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Swatch(swatch, size = 22.dp)
        Text(
            text = id.displayName,
            color = if (selected || focused) pal.TextPrimary else pal.TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun swatchColourFor(id: AccentId, controller: ThemeController, activeTheme: ThemeId): Color = controller.resolved(activeTheme, id).Accent

@Composable
private fun Swatch(colour: Color, size: androidx.compose.ui.unit.Dp = 28.dp) {
    Box(
        modifier =
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(colour)
            .border(1.dp, LocalYancoPalette.current.BorderSubtle, CircleShape),
    )
}
