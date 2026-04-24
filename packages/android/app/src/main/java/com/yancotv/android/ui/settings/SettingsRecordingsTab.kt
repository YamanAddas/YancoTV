package com.yancotv.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Recordings placeholder. The DVR feature (schedule, list, playback) is
 * slated for MK.14; this tab will surface storage path, max-size quota,
 * retention policy, and the pad-before / pad-after buffers once MK.14
 * lands the capture pipeline.
 */
@Composable
fun SettingsRecordingsTab(modifier: Modifier = Modifier) {
    SettingsPlaceholder(
        modifier = modifier,
        kicker = "Milestone MK.14",
        title = "Recordings",
        body =
            "Storage location, quota ceiling, retention window, and " +
                "pre-/post-roll padding for scheduled recordings. Ships with the DVR " +
                "capture pipeline in MK.14.",
    )
}
