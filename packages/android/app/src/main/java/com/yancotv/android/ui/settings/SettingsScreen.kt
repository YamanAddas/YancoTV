package com.yancotv.android.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.android.ui.theme.YancoType

/**
 * Top-level Settings shell. Two-pane TV layout: a left rail of
 * vertical tabs and a right content pane. Focus lives in
 * [focusRestorer] + [focusGroup] so returning to the rail snaps
 * back to the last selected tab instead of the first entry.
 *
 * Stub tabs are real composables in this package; this screen only
 * routes to them. The surrounding chrome (eyebrow, title, tab rail,
 * panel shell) is the shared "settings shell" language — the inner
 * tab bodies decide their own content layout.
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

@OptIn(ExperimentalComposeUiApi::class)
@UnstableApi
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    initialTab: SettingsTab = SettingsTab.Sources,
) {
    // rememberSaveable survives rotation + process death so the user returns
    // to the tab they were on. `initialTab` only seeds the first render.
    var tab by rememberSaveable { mutableStateOf(initialTab) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(YancoPalette.BackgroundDeep)
            .padding(horizontal = Space.page, vertical = Space.xxl),
        verticalArrangement = Arrangement.spacedBy(Space.xl),
    ) {
        SettingsHeader()
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(Space.xxl),
        ) {
            TabRail(
                current = tab,
                onSelect = { tab = it },
                modifier = Modifier.width(208.dp).fillMaxHeight(),
            )
            ContentFrame(modifier = Modifier.fillMaxSize()) {
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
private fun SettingsHeader() {
    Column {
        Text(
            text = "PREFERENCES",
            color = YancoPalette.Accent,
            style = YancoType.Overline,
        )
        Spacer(Modifier.height(Space.xxs))
        Text(
            text = "Settings",
            color = YancoPalette.TextPrimary,
            style = YancoType.DisplayS,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TabRail(
    current: SettingsTab,
    onSelect: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val brush = remember {
        Brush.verticalGradient(
            colors = listOf(
                YancoPalette.BackgroundRaised,
                YancoPalette.BackgroundDeep,
            ),
        )
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.panel))
            .background(brush)
            .border(1.dp, YancoPalette.BorderSubtle, RoundedCornerShape(Radius.panel))
            .padding(Space.sm)
            .focusRestorer()
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(Space.xxs),
    ) {
        for (entry in SettingsTab.entries) {
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
        focused -> YancoPalette.BackgroundHover
        selected -> YancoPalette.Accent.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    val border = if (focused) YancoPalette.FocusRing else Color.Transparent
    val fg by animateColorAsState(
        targetValue = when {
            focused -> YancoPalette.TextPrimary
            selected -> YancoPalette.Accent
            else -> YancoPalette.TextSecondary
        },
        label = "settings-tab-fg",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
    ) {
        // Left accent bar marks the currently-selected tab even when focus
        // is elsewhere — matches the sidebar/groups language.
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .padding(vertical = Space.sm)
                .clip(RoundedCornerShape(Radius.pill))
                .background(if (selected) YancoPalette.Accent else Color.Transparent),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = Space.sm)
                .clip(RoundedCornerShape(Radius.control))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(Radius.control))
                .focusable(interactionSource = interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = Space.md, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = fg,
                style = if (selected) YancoType.LabelStrong else YancoType.Label,
            )
        }
    }
}

@Composable
private fun ContentFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // Consistent panel chrome so each tab body lives inside the same
    // card treatment without every tab having to repeat the background +
    // border dance.
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.panel))
            .background(YancoPalette.BackgroundRaised)
            .border(1.dp, YancoPalette.BorderSubtle, RoundedCornerShape(Radius.panel)),
    ) {
        content()
    }
}
