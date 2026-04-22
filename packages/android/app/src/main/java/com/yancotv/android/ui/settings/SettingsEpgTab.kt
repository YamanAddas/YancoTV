package com.yancotv.android.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.ui.shell.GuideSyncPanel

/**
 * EPG tab — hosts the detailed diagnostics + refresh + URL editor UI.
 *
 * Thin wrapper over [GuideSyncPanel] in non-compact mode. The same panel
 * used to squat in the Guide empty-state; now that Settings has a proper
 * home for it, Settings is where the full controls live. Guide keeps a
 * one-line status strip ([GuideSyncPanel] in compact mode) for quick
 * refresh without leaving the guide grid.
 *
 * Passing a no-op `onRefreshed` is fine here — Settings doesn't care when
 * the EPG finishes; only Guide does, and Guide's own compact panel has
 * its own callback wired.
 */
@UnstableApi
@Composable
fun SettingsEpgTab(modifier: Modifier = Modifier) {
    GuideSyncPanel(
        compact = false,
        onRefreshed = { /* Settings doesn't re-query guide data */ },
        modifier = modifier.fillMaxSize(),
    )
}
