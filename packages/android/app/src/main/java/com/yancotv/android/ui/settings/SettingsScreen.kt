package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.ui.theme.YancoPalette

/**
 * Top-level Settings shell. Left-rail vertical tab selector + right
 * content pane — TV-friendly (D-pad up/down on the rail, right into
 * content) and translates directly to a phone-sized column stack.
 *
 * MK.8.6 lands the expanded IA: Sources was the only tab since MK.6,
 * now we add Epg, Playback, Network, Parental, Shortcuts, About so
 * later milestones (MK.8.6.b playback prefs, MK.8.7 parental PIN) have
 * a pre-built home. Stub tabs render a placeholder that tells the user
 * which milestone adds their content — better than a dead-end "blank".
 */
enum class SettingsTab(val label: String) {
    Sources("Sources"),
    General("General"),
    Groups("Groups"),
    Epg("EPG"),
    Playback("Playback"),
    Network("Network"),
    Parental("Parental"),
    Shortcuts("Shortcuts"),
    About("About"),
}

@UnstableApi
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    initialTab: SettingsTab = SettingsTab.Sources,
) {
    // rememberSaveable survives rotation + process death so the user returns
    // to the tab they were on. `initialTab` only seeds the first render; we
    // don't overwrite it on recomposition.
    var tab by rememberSaveable { mutableStateOf(initialTab) }

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
            style = MaterialTheme.typography.headlineSmall,
        )
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            TabRail(
                current = tab,
                onSelect = { tab = it },
                modifier = Modifier.width(180.dp).fillMaxHeight(),
            )
            Box(modifier = Modifier.fillMaxSize()) {
                when (tab) {
                    SettingsTab.Sources -> SourcesScreen()
                    SettingsTab.General -> SettingsGeneralTab()
                    SettingsTab.Groups -> SettingsGroupsTab()
                    SettingsTab.Epg -> SettingsEpgTab()
                    SettingsTab.Playback -> SettingsPlaybackTab()
                    SettingsTab.Network -> SettingsNetworkTab()
                    SettingsTab.Parental -> SettingsParentalTab()
                    SettingsTab.Shortcuts -> SettingsShortcutsTab()
                    SettingsTab.About -> SettingsAboutTab()
                }
            }
        }
    }
}

@Composable
private fun TabRail(
    current: SettingsTab,
    onSelect: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (entry in SettingsTab.values()) {
            TabRailItem(
                label = entry.label,
                selected = entry == current,
                onClick = { onSelect(entry) },
            )
        }
    }
}

@Composable
private fun TabRailItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        selected -> YancoPalette.Accent.copy(alpha = 0.18f)
        focused -> YancoPalette.BackgroundHover
        else -> Color.Transparent
    }
    val border = when {
        focused -> YancoPalette.Accent
        selected -> YancoPalette.Accent.copy(alpha = 0.6f)
        else -> Color.Transparent
    }
    val textColor = when {
        selected || focused -> YancoPalette.TextPrimary
        else -> YancoPalette.TextMuted
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
    // Draw a thin left-border accent via a separate Box so we don't need
    // a BorderStroke composable in the Row signature. Renders ABOVE bg.
    if (selected || focused) {
        Box(
            modifier = Modifier
                .width(0.dp) // placeholder — actual accent baked into bg + textColor above
                .background(border),
        )
    }
}

@Composable
private fun SettingsStubTab(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            color = YancoPalette.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = body,
            color = YancoPalette.TextMuted,
            fontSize = 13.sp,
        )
    }
}
