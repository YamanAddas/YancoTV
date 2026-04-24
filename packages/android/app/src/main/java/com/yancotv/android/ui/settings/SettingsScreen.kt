package com.yancotv.android.ui.settings

import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.android.ui.theme.YancoType

/**
 * Settings shell — Concept A "Configure" layout (docs/design/design_handoff_yancotv/
 * designs/settings.html). Two hex-cut panels: a 380dp sidebar with a hex-nav rail
 * of all 14 tabs, and a content pane that renders the active tab's body.
 *
 * Focus model: the sidebar is the canonical entry point. `focusRestorer` on the
 * rail remembers the last-selected tab so D-pad RIGHT → content → D-pad LEFT
 * lands back on the same tab instead of the first entry. Content-side focus is
 * owned by each tab's composable.
 *
 * Tab-body swap is a pure re-parent — the sidebar and outer card stay mounted,
 * only the breadcrumb + content scroll re-render. Prevents tab swaps from
 * resetting scroll state in sibling panes.
 */
enum class SettingsTab(
    val label: String,
    val sub: String,
    val icon: ImageVector,
) {
    General("General", "01 · lang · startup", YancoIcons.Settings),
    Appearance("Appearance", "02 · theme · font", YancoIcons.Theme),
    Playback("Playback", "03 · video · audio", YancoIcons.Play),
    Subtitles("Subtitles", "04 · captions", YancoIcons.Subtitles),
    Network("Network", "05 · http · proxy", YancoIcons.Signal),
    Sources("Sources", "06 · playlists · sync", YancoIcons.Link),
    Groups("Groups", "07 · rails · pin", YancoIcons.Grid),
    Epg("EPG", "08 · guide · timing", YancoIcons.Guide),
    Parental("Parental", "09 · pin · adult", YancoIcons.Shield),
    Recordings("Recordings", "10 · dvr · storage", YancoIcons.Record),
    Notifications("Notifications", "11 · events", YancoIcons.Bell),
    Storage("Storage", "12 · cache", YancoIcons.Hdd),
    Shortcuts("Shortcuts", "13 · remote · key", YancoIcons.Key),
    About("About", "14 · version · data", YancoIcons.Info),
}

// Dark ink used for text/icons on top of the accent gradient fills. Matches
// the #04130C value in the design CSS — near-black with a touch of green so
// it doesn't clash with the emerald gradient.
private val OnAccentInk: Color = Color(0xFF04130C)

@OptIn(ExperimentalComposeUiApi::class)
@UnstableApi
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    initialTab: SettingsTab = SettingsTab.General,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }

    Row(
        modifier =
            modifier
                .fillMaxSize()
                .background(YancoPalette.BackgroundDeep)
                .padding(
                    start = Space.page,
                    top = Space.section,
                    end = Space.page,
                    bottom = Space.section,
                ),
        horizontalArrangement = Arrangement.spacedBy(Space.xxl),
    ) {
        Sidebar(
            current = tab,
            onSelect = { tab = it },
            modifier =
                Modifier
                    .width(380.dp)
                    .fillMaxHeight(),
        )
        ContentPane(
            current = tab,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Sidebar(
    current: SettingsTab,
    onSelect: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(YancoShapes.CutCornerCardLarge)
                .background(YancoPalette.BackgroundRaised)
                .border(1.dp, YancoPalette.PanelBorder, YancoShapes.CutCornerCardLarge)
                .focusGroup()
                .focusRestorer(),
    ) {
        SidebarHeader()
        HairlineDivider()
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (entry in SettingsTab.entries) {
                TabItem(
                    entry = entry,
                    selected = entry == current,
                    onClick = { onSelect(entry) },
                )
            }
        }
        HairlineDivider()
        SidebarFooter()
    }
}

@Composable
private fun SidebarHeader() {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 18.dp),
    ) {
        Text(
            text = "YANCOTV · SETTINGS",
            color = YancoPalette.Accent,
            style = YancoType.Overline,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Configure",
            color = YancoPalette.TextPrimary,
            fontSize = 34.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.6).sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Preferences apply instantly and sync across restarts.",
            color = YancoPalette.TextMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun SidebarFooter() {
    val context = LocalContext.current
    val version = remember(context) { readVersionName(context) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, top = 18.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "v$version · MK.16.shell",
            color = YancoPalette.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.4.sp,
            modifier = Modifier.weight(1f),
        )
        HexChip(text = "SYNCED", active = false, icon = YancoIcons.Cloud)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TabItem(
    entry: SettingsTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val rowBrush =
        when {
            focused ->
                Brush.horizontalGradient(
                    listOf(
                        YancoPalette.Accent.copy(alpha = 0.22f),
                        Color.Transparent,
                    ),
                )
            selected ->
                Brush.horizontalGradient(
                    listOf(
                        YancoPalette.Accent.copy(alpha = 0.14f),
                        Color.Transparent,
                    ),
                )
            else -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
        }

    val iconBg =
        if (selected) {
            Brush.verticalGradient(
                listOf(YancoPalette.Accent, YancoPalette.AccentDeep),
            )
        } else {
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.04f)),
            )
        }
    val iconTint = if (selected) OnAccentInk else YancoPalette.TextMuted
    val labelColor =
        when {
            focused -> YancoPalette.TextPrimary
            selected -> YancoPalette.TextPrimary
            else -> YancoPalette.TextSecondary
        }
    val subColor = if (selected) YancoPalette.Accent else YancoPalette.TextMuted
    val borderColor = if (focused) YancoPalette.FocusRing else Color.Transparent

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(rowBrush)
                .border(if (focused) 2.dp else 0.dp, borderColor)
                .focusable(interactionSource = interaction)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                ).semantics {
                    role = Role.Tab
                    contentDescription = "${entry.label} settings tab"
                },
    ) {
        if (selected) {
            // Left accent bar — 3dp wide, inset 10dp top/bottom so it reads as
            // a marker rather than a full-height divider. Vertical gradient
            // matches the hex-icon tile so the two accents feel linked.
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(3.dp)
                        .fillMaxHeight()
                        .padding(vertical = 10.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(YancoPalette.Accent, YancoPalette.AccentDeep),
                            ),
                        ),
            )
        }
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(start = 22.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(YancoShapes.HexCapsuleSoft)
                        .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.label,
                    color = labelColor,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.14.sp,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entry.sub.uppercase(),
                    color = subColor,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                )
            }
            Text(
                text = twoDigit(entry.ordinal + 1),
                color = YancoPalette.TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
            if (selected) {
                Icon(
                    imageVector = YancoIcons.ChevronRight,
                    contentDescription = null,
                    tint = YancoPalette.Accent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@UnstableApi
@Composable
private fun ContentPane(
    current: SettingsTab,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(YancoShapes.CutCornerCardLarge)
                .background(YancoPalette.BackgroundRaised)
                .border(1.dp, YancoPalette.PanelBorder, YancoShapes.CutCornerCardLarge),
    ) {
        Breadcrumb(current = current)
        HairlineDivider()
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 44.dp, end = 44.dp, top = 32.dp, bottom = 32.dp),
        ) {
            TabContent(tab = current)
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Breadcrumb(current: SettingsTab) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HexChip(text = "SETTINGS", active = false)
        Icon(
            imageVector = YancoIcons.ChevronRight,
            contentDescription = null,
            tint = YancoPalette.TextMuted,
            modifier = Modifier.size(12.dp),
        )
        HexChip(
            text = current.label.uppercase(),
            active = true,
            icon = current.icon,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = current.sub.uppercase(),
            color = YancoPalette.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.8.sp,
        )
    }
}

@UnstableApi
@Composable
private fun TabContent(tab: SettingsTab) {
    when (tab) {
        SettingsTab.General -> SettingsGeneralTab()
        SettingsTab.Appearance -> SettingsAppearanceTab()
        SettingsTab.Playback -> SettingsPlaybackTab()
        SettingsTab.Subtitles -> SettingsSubtitlesTab()
        SettingsTab.Network -> SettingsNetworkTab()
        SettingsTab.Sources -> SourcesScreen()
        SettingsTab.Groups -> SettingsGroupsTab()
        SettingsTab.Epg -> SettingsEpgTab()
        SettingsTab.Parental -> SettingsParentalTab()
        SettingsTab.Recordings -> SettingsRecordingsTab()
        SettingsTab.Notifications -> SettingsNotificationsTab()
        SettingsTab.Storage -> SettingsStorageTab()
        SettingsTab.Shortcuts -> SettingsShortcutsTab()
        SettingsTab.About -> SettingsAboutTab()
    }
}

@Composable
private fun HexChip(
    text: String,
    active: Boolean,
    icon: ImageVector? = null,
) {
    val bg =
        if (active) {
            Brush.verticalGradient(
                listOf(YancoPalette.Accent, YancoPalette.AccentDeep),
            )
        } else {
            Brush.verticalGradient(
                listOf(YancoPalette.BackgroundElevated, YancoPalette.BackgroundElevated),
            )
        }
    val fg = if (active) OnAccentInk else YancoPalette.TextMuted
    Row(
        modifier =
            Modifier
                .height(30.dp)
                .clip(YancoShapes.ChipBevel)
                .background(bg)
                .border(
                    1.dp,
                    if (active) Color.Transparent else YancoPalette.BorderSubtle,
                    YancoShapes.ChipBevel,
                ).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = text,
            color = fg,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold,
            letterSpacing = 1.32.sp,
        )
    }
}

@Composable
private fun HairlineDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(YancoPalette.BorderSubtle),
    )
}

// --- helpers ----------------------------------------------------------------

private fun twoDigit(n: Int): String = if (n < 10) "0$n" else n.toString()

private fun readVersionName(ctx: Context): String =
    try {
        ctx.packageManager
            .getPackageInfo(ctx.packageName, 0)
            .versionName
            ?: "?"
    } catch (_: PackageManager.NameNotFoundException) {
        "?"
    }
