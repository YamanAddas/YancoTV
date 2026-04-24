package com.yancotv.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Appearance placeholder. Theme switcher, accent picker, font scale slider,
 * and launcher-icon variants land here after MK.16.1 turns [YancoPalette]
 * into a reactive data class + CompositionLocalProvider so a user swap can
 * re-tint the shell without restart.
 */
@Composable
fun SettingsAppearanceTab(modifier: Modifier = Modifier) {
    SettingsPlaceholder(
        modifier = modifier,
        kicker = "Milestone MK.16",
        title = "Appearance",
        body =
            "Theme presets (Emerald, Copper, Obsidian, Hi-Contrast), " +
                "accent chooser, font scale, and launcher icon variants. " +
                "Blocked by MK.16.1 theme refactor — the current YancoPalette is a static " +
                "singleton and can't react to user swaps until it moves behind a " +
                "CompositionLocalProvider.",
    )
}
