package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.ThemeController
import com.yancotv.android.ui.theme.ThemeId
import com.yancotv.android.ui.theme.YancoPalette
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * MK.16.2 — theme picker. Lists every [ThemeId] as a row with a swatch
 * preview (background + accent + focus colours sampled from the
 * resolved palette). Tapping commits instantly via [ThemeController];
 * the whole UI recomposes via [LocalYancoPalette].
 */
@Composable
fun SettingsAppearanceTab(
    modifier: Modifier = Modifier,
    themeController: ThemeController = koinInject(),
    prefs: AppPreferences = koinInject(),
) {
    val active by themeController.themeId.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Theme",
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Pick a palette. Changes apply instantly across the app — no restart.",
            color = LocalYancoPalette.current.TextMuted,
            fontSize = 12.sp,
        )

        ThemeId.values().forEach { id ->
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
}

@Composable
private fun ThemeRow(
    id: ThemeId,
    selected: Boolean,
    palette: YancoPalette,
    onClick: () -> Unit,
) {
    val borderColour =
        if (selected) LocalYancoPalette.current.Accent else LocalYancoPalette.current.BorderSubtle
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(LocalYancoPalette.current.BackgroundRaised)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = borderColour,
                    shape = RoundedCornerShape(8.dp),
                ).clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SwatchTriple(palette = palette)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = id.displayName,
                color = LocalYancoPalette.current.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (selected) "Active" else "Tap to apply",
                color =
                    if (selected) {
                        LocalYancoPalette.current.Accent
                    } else {
                        LocalYancoPalette.current.TextMuted
                    },
                fontSize = 11.sp,
            )
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
private fun Swatch(colour: androidx.compose.ui.graphics.Color) {
    Box(
        modifier =
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colour)
                .border(1.dp, LocalYancoPalette.current.BorderSubtle, CircleShape),
    )
}
