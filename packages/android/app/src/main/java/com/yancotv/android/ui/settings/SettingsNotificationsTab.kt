package com.yancotv.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.yancotv.android.R

/**
 * Notifications placeholder. EPG reminders, sync-complete banners,
 * recording-ready alerts, and app-update prompts are routed through
 * a single WorkManager pipeline that ships in a later milestone.
 */
@Composable
fun SettingsNotificationsTab(modifier: Modifier = Modifier) {
    SettingsPlaceholder(
        modifier = modifier,
        kicker = stringResource(R.string.ph_later_milestone),
        title = stringResource(R.string.ph_notifications_title),
        body =
        stringResource(R.string.ph_notifications_body),
    )
}
