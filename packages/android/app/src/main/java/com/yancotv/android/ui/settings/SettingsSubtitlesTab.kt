package com.yancotv.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.yancotv.android.R

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
        // MB-365 — was ph_subtitles_kicker ("Milestone MK.12a.3+"), a plan
        // identifier rendered to users. Same localized kicker as the other
        // placeholder tabs.
        kicker = stringResource(R.string.ph_later_milestone),
        title = stringResource(R.string.ph_subtitles_title),
        body =
        stringResource(R.string.ph_subtitles_body),
    )
}
