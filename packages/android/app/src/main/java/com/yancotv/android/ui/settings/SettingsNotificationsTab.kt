package com.yancotv.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Notifications placeholder. EPG reminders, sync-complete banners,
 * recording-ready alerts, and app-update prompts are routed through
 * a single WorkManager pipeline that ships in a later milestone.
 */
@Composable
fun SettingsNotificationsTab(modifier: Modifier = Modifier) {
    SettingsPlaceholder(
        modifier = modifier,
        kicker = "Later milestone",
        title = "Notifications",
        body =
        "Per-event toggles for EPG reminders, source-sync completion, recording-ready " +
            "alerts, and update prompts. Routes through WorkManager — unblocked once " +
            "MK.14 adds the DVR event producer.",
    )
}
