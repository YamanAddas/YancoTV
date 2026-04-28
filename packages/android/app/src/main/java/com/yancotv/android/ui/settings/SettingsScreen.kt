package com.yancotv.android.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.yancotv.android.ui.focus.FocusableSpacer
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoShapes
import kotlinx.coroutines.launch

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
    // Subtitles, Notifications, Storage tabs are removed from the
    // sidebar (2026-04-27) — they were never more than placeholder
    // bodies. Their `Settings*Tab.kt` files stay in tree so post-v1
    // work can wire them back when the underlying features ship
    // (subtitle defaults need a player-prefs flow; notifications
    // route through a WorkManager event bus that doesn't exist yet;
    // storage needs a cache size aggregator). Bringing the tab back
    // is one line in this enum + one branch in `TabContent`.
    General("General", "01 · lang · startup", YancoIcons.Settings),
    Appearance("Appearance", "02 · theme · font", YancoIcons.Theme),
    Playback("Playback", "03 · video · audio", YancoIcons.Play),
    Network("Network", "04 · http · proxy", YancoIcons.Signal),
    Sources("Sources", "05 · playlists · sync", YancoIcons.Link),
    Groups("Groups", "06 · rails · pin", YancoIcons.Grid),
    Epg("EPG", "07 · guide · timing", YancoIcons.Guide),
    Parental("Parental", "08 · pin · adult", YancoIcons.Shield),
    Recordings("Recordings", "09 · dvr · storage", YancoIcons.Record),
    Shortcuts("Shortcuts", "10 · remote · key", YancoIcons.Key),
    Backup("Backup", "11 · export · import", YancoIcons.Save),
    About("About", "12 · version · data", YancoIcons.Info),
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
    val scope = rememberCoroutineScope()
    // User contract: "left dpad and back button go back" — i.e. one
    // BACK press from inside a tab body should return focus to the
    // sidebar, not pop the entire Settings screen. Track when focus
    // is in the content pane and intercept BACK only then; when focus
    // is in the sidebar BACK passes through to the host so the user
    // can leave Settings normally. Mirrors the CategoryRail /
    // CoverflowSectionScreen pattern in the home shell.
    var contentHasFocus by remember { mutableStateOf(false) }
    // MB-108 v2 (hardened): simulate D-pad RIGHT after the tab commits.
    // A focusGroup+focusRestorer wrapper (v1) sometimes lost focus
    // entirely on Fire TV — focus searched up the tree and bounced back
    // to the sidebar. moveFocus(Right) mimics exactly the manual press
    // the user would otherwise do, so we get identical behaviour to
    // pressing CENTER then RIGHT, with no extra focus indirection.
    //
    // Hardening over v2:
    //   1. Wait TWO frames before the first moveFocus call. One frame
    //      gets us past composition; the second covers layout. Heavy
    //      tabs (Sources, Groups) didn't always have placed focusable
    //      children after a single frame.
    //   2. moveFocus returns false when no focus target was found —
    //      retry once after another frame instead of giving up. If
    //      both attempts fail we leave focus on the sidebar tab item
    //      (the user can press RIGHT manually) and log so a Fire TV
    //      regression is diagnosable from logcat instead of by feel.
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = contentHasFocus) {
        // Push focus left out of the content pane; the sidebar's
        // focusGroup + focusRestorer (lines below) lands on the
        // last-focused TabItem, so the user returns to where they
        // entered the body from. If for some reason there's no
        // focusable to the left (heavy tab without a left-edge
        // focusable), the move silently no-ops and the user can
        // press BACK again — host handles it on the second press.
        focusManager.moveFocus(FocusDirection.Left)
    }

    Row(
        modifier =
            modifier
                .fillMaxSize()
                .background(LocalYancoPalette.current.BackgroundDeep)
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
            onSelect = {
                tab = it
                scope.launch {
                    // Two frames: composition pass + layout pass. Heavier
                    // tab bodies (Sources, Groups, Parental) need both.
                    withFrameNanos { }
                    withFrameNanos { }
                    val moved = focusManager.moveFocus(FocusDirection.Right)
                    if (!moved) {
                        // One retry — sometimes the first attempt lands
                        // before the LazyColumn has placed its first item.
                        withFrameNanos { }
                        val movedRetry = focusManager.moveFocus(FocusDirection.Right)
                        if (!movedRetry) {
                            Log.w(
                                "SettingsScreen",
                                "MB-108: moveFocus(Right) failed twice for tab=$it; focus stays on sidebar",
                            )
                        }
                    }
                }
            },
            modifier =
                Modifier
                    .width(380.dp)
                    .fillMaxHeight(),
        )
        ContentPane(
            current = tab,
            modifier =
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { contentHasFocus = it.hasFocus },
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
    // Verdant Frost — clean rounded panel (28dp `--r-xl`) replaces the
    // hex-cut shell so the Settings screen reads as the design's
    // "polished island" within the wider hex-cut app. The cut-corner
    // shape is still used for chips / cards inside the tabs; only the
    // outer Settings panels go rounded to match the redesign brief.
    val panelShape = RoundedCornerShape(28.dp)
    Column(
        modifier =
            modifier
                .clip(panelShape)
                .background(LocalYancoPalette.current.BackgroundRaised)
                .border(1.dp, LocalYancoPalette.current.PanelBorder, panelShape)
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
    val palette = LocalYancoPalette.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 18.dp),
    ) {
        // Verdant Frost brand row — 36dp gradient logo + "YancoTV" wordmark
        // with the "TV" rendered in accent. Replaces the old "YANCOTV ·
        // SETTINGS" overline so the brand identity sits at the top of the
        // sidebar (matches the design's `.shell-side-h .brand-row`).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(palette.Accent, palette.AccentDeep),
                            ),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Y",
                    color = OnAccentInk,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.4.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Yanco",
                    color = palette.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp,
                )
                Text(
                    text = "TV",
                    color = palette.Accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp,
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "Settings",
            color = palette.TextPrimary,
            fontSize = 28.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "System · ${SettingsTab.entries.size} sections",
            color = palette.TextMuted,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.5.sp,
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
            color = LocalYancoPalette.current.TextMuted,
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
    val palette = LocalYancoPalette.current

    // User contract: forward = OK *or* D-pad RIGHT. Without this, RIGHT
    // alone just navigates focus into the currently-active tab's
    // content — so hovering on a different tab and pressing RIGHT lands
    // you in the wrong tab's body. CENTER already commits the tab
    // (clickable's onClick) and the parent's `scope.launch` chain
    // moves focus right after composition + layout settle.
    //
    // Intercept RIGHT only when this tab is NOT already selected.
    val onTabKey =
        Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
                event.key == Key.DirectionRight &&
                !selected
            ) {
                onClick()
                true
            } else {
                false
            }
        }

    // Verdant Frost focus animation — 1.04 scale on the whole row, with
    // graphicsLayer so it doesn't reflow neighbours. Selected rows stay
    // at 1.0 unless also focused. Tween matches the design's 200ms
    // transform-only animation.
    val targetScale = if (focused) 1.04f else 1.0f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 200),
        label = "tabScale",
    )

    val shape = RoundedCornerShape(14.dp)
    val rowBrush =
        when {
            focused && selected ->
                Brush.horizontalGradient(
                    listOf(palette.Accent.copy(alpha = 0.32f), palette.Accent.copy(alpha = 0.14f)),
                )
            focused ->
                Brush.horizontalGradient(
                    listOf(palette.BackgroundElevated, palette.BackgroundHover),
                )
            selected ->
                Brush.horizontalGradient(
                    listOf(palette.Accent.copy(alpha = 0.14f), Color.Transparent),
                )
            else -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
        }

    // Translucent accent fill on the icon tile when selected/focused —
    // matches the design's `--acc-bg-20` / `--acc-bg-14` (vs the previous
    // saturated gradient + dark ink). Keeps the icon legible at 3 m
    // without screaming for attention.
    val iconBgColor =
        when {
            selected -> palette.Accent.copy(alpha = 0.22f)
            focused -> palette.Accent.copy(alpha = 0.14f)
            else -> Color.White.copy(alpha = 0.04f)
        }
    val iconTint =
        when {
            selected -> palette.Accent
            focused -> palette.AccentGlow
            else -> palette.TextSecondary
        }
    val labelColor =
        if (focused || selected) palette.TextPrimary else palette.TextSecondary
    val subColor =
        if (selected) palette.Accent else palette.TextMuted
    val ordinalColor =
        when {
            selected -> palette.Accent
            focused -> palette.AccentGlow
            else -> palette.TextMuted
        }
    val borderColor = if (focused) palette.FocusRing else Color.Transparent

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                // Soft accent halo on focus — colored shadow gives the
                // design's "1.5dp ring + 28px halo" feel without a manual
                // glow layer. Spot/ambient colors render colored on
                // API 28+; on older builds it falls back to a neutral
                // shadow which is still a useful focus cue.
                .shadow(
                    elevation = if (focused) 18.dp else 0.dp,
                    shape = shape,
                    ambientColor = palette.AccentGlow,
                    spotColor = palette.AccentGlow,
                )
                .clip(shape)
                .background(rowBrush)
                .border(
                    width = if (focused) 1.5.dp else 0.dp,
                    color = borderColor,
                    shape = shape,
                )
                .then(onTabKey)
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
            // Selected accent rail — design spec: 3dp wide × 44dp tall,
            // vertically centered. Reads as a "this is committed" marker
            // distinct from the focus ring around the whole row.
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(3.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(palette.Accent, palette.AccentDeep),
                            ),
                        ),
            )
        }
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(start = 18.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Ordinal moved LEFT to match the design's
            // [ordinal | icon | label] order. Mono caption look at 11sp /
            // 1.4sp tracking — the design's `.tab-ord` token.
            Text(
                text = twoDigit(entry.ordinal + 1),
                color = ordinalColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                modifier = Modifier.width(20.dp),
            )
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconBgColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.label,
                    color = labelColor,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.05).sp,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = entry.sub.uppercase(),
                    color = subColor,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.1.sp,
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
    val panelShape = RoundedCornerShape(28.dp)
    Column(
        modifier =
            modifier
                .clip(panelShape)
                .background(LocalYancoPalette.current.BackgroundRaised)
                .border(1.dp, LocalYancoPalette.current.PanelBorder, panelShape),
    ) {
        Breadcrumb(current = current)
        HairlineDivider()
        // No outer scroll — each tab owns its own scroll (LazyColumn for lazy
        // tabs like Parental/Groups/Sources, verticalScroll for the simple
        // Column-based tabs). Wrapping in verticalScroll here crashes with
        // "infinity maximum height constraints" the moment a child tab uses
        // a LazyColumn. `key(current)` resets scroll state when the tab
        // swaps, so switching away from a scrolled tab and back lands at
        // top rather than keeping a stale offset.
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        ) {
            androidx.compose.runtime.key(current) {
                TabContent(tab = current)
            }
            // MB-109 (hoisted): single focus fallback for the entire
            // ContentPane. moveFocus(Right) from the sub-sidebar lands
            // on the first focusable child of the active tab body if it
            // has any (General/Playback/Groups/Network/Parental do); if
            // the tab is read-only (About/Shortcuts) or a placeholder,
            // focus falls through to this 0-dp trap so the request never
            // silently fails and the user always exits the sub-sidebar.
            //
            // Lives at ContentPane scope (not per-tab) so:
            //   1. Tab swap doesn't unmount it — focus traversal sees a
            //      stable target across the `key(current)` re-mount.
            //   2. No per-tab Column.spacedBy gap pushes the visible
            //      content down by 12-16dp (the regression from the
            //      first attempt — see MB-112 commit).
            //   3. One source of truth: future placeholder tabs inherit
            //      the trap automatically; no per-tab boilerplate.
            //
            // Sibling of the keyed TabContent inside the same Box, so
            // it's last in the focus traversal order — real tab
            // focusables come first, the trap is only used when a tab
            // body has no other focus targets.
            FocusableSpacer()
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
            tint = LocalYancoPalette.current.TextMuted,
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
            color = LocalYancoPalette.current.TextMuted,
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
        SettingsTab.Network -> SettingsNetworkTab()
        SettingsTab.Sources -> SourcesScreen()
        SettingsTab.Groups -> SettingsGroupsTab()
        SettingsTab.Epg -> SettingsEpgTab()
        SettingsTab.Parental -> SettingsParentalTab()
        SettingsTab.Recordings -> SettingsRecordingsTab()
        SettingsTab.Shortcuts -> SettingsShortcutsTab()
        SettingsTab.Backup -> SettingsBackupTab()
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
                listOf(LocalYancoPalette.current.Accent, LocalYancoPalette.current.AccentDeep),
            )
        } else {
            Brush.verticalGradient(
                listOf(LocalYancoPalette.current.BackgroundElevated, LocalYancoPalette.current.BackgroundElevated),
            )
        }
    val fg = if (active) OnAccentInk else LocalYancoPalette.current.TextMuted
    Row(
        modifier =
            Modifier
                .height(30.dp)
                .clip(YancoShapes.ChipBevel)
                .background(bg)
                .border(
                    1.dp,
                    if (active) Color.Transparent else LocalYancoPalette.current.BorderSubtle,
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
                .background(LocalYancoPalette.current.BorderSubtle),
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
