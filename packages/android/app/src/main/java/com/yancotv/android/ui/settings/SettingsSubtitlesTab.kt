package com.yancotv.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Subtitles placeholder. The picker itself already lives in the player
 * overlay (MK.12a.3). This tab collects the global defaults — language
 * preference, font size, color, background opacity, vertical position —
 * so users can set them once instead of re-picking every launch.
 */
@Composable
fun SettingsSubtitlesTab(modifier: Modifier = Modifier) {
    SettingsPlaceholder(
        modifier = modifier,
        kicker = "Milestone MK.12a.3+",
        title = "Subtitles",
        body =
            "Default caption language, font size, font color, background opacity, " +
                "and vertical position. Player-overlay picker already ships (MK.12a.3); " +
                "these are the defaults persisted across restarts.",
    )
}
