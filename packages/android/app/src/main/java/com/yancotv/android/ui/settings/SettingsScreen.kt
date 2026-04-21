package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yancotv.android.ui.theme.YancoPalette

/**
 * Top-level Settings section. MK.6 ships with Sources only — other tabs
 * (Playback, EPG, Parental, About) land in MK.8+.
 */
enum class SettingsTab(val label: String) {
    Sources("Sources"),
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableStateOf(SettingsTab.Sources) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(YancoPalette.BackgroundDeep)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Settings",
            color = YancoPalette.TextPrimary,
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
        )
        when (tab) {
            SettingsTab.Sources -> SourcesScreen()
        }
    }
}
