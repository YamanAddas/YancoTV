package com.yancotv.android.ui.settings

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.ui.focus.FocusableSpacer
import com.yancotv.android.ui.focus.ProvideFocusScrollSpec
import com.yancotv.android.ui.focus.endwardFocus
import com.yancotv.android.ui.focus.endwardKey
import com.yancotv.android.ui.focus.isStartward
import com.yancotv.android.ui.focus.startwardKey
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.ShellDim
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
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
enum class SettingsTab(val label: String, val icon: ImageVector) {
    // Subtitles, Notifications, Storage tabs are removed from the
    // sidebar (2026-04-27) — they were never more than placeholder
    // bodies. Their `Settings*Tab.kt` files stay in tree so post-v1
    // work can wire them back when the underlying features ship.
    // Bringing the tab back is one line in this enum + one branch in
    // `TabContent`.
    //
    // The previous `sub` ("01 · lang · startup", etc.) and ordinal
    // numbering rendered in TabItem were dropped 2026-04-28 — the
    // numbering / sub-caption read as visual noise on a 12-tab list
    // and the user's redesign pass collapsed them away.
    // MK.29.5 — Order is by FREQUENCY OF USE, not alphabet:
    //   Sources / General / Playback / Parental / Recordings — the five
    //     surfaces a user touches in a normal week.
    //   Network / Groups / EPG / Appearance — power-user / one-time.
    //   Backup / Shortcuts / About — rare / reference-only.
    // initialTab still defaults to General (cold-open destination), so
    // a user landing in Settings sees a familiar home; only the list
    // order changes so Sources is the first thing to scroll past.
    Sources("Sources", YancoIcons.Link),
    General("General", YancoIcons.Settings),
    Playback("Playback", YancoIcons.Play),
    Parental("Parental", YancoIcons.Shield),
    Recordings("Recordings", YancoIcons.Record),
    Network("Network", YancoIcons.Signal),
    Groups("Groups", YancoIcons.Grid),
    Epg("EPG", YancoIcons.Guide),
    Appearance("Appearance", YancoIcons.Theme),
    Backup("Backup", YancoIcons.Save),
    Shortcuts("Shortcuts", YancoIcons.Key),
    About("About", YancoIcons.Info),
}

// Dark ink used for text/icons on top of the accent gradient fills. Matches
// the #04130C value in the design CSS — near-black with a touch of green so
// it doesn't clash with the emerald gradient.
private val OnAccentInk: Color = Color(0xFF04130C)

// MK.27.C — phone-class threshold (Android's sw600dp). Below it a PHONE gets the
// single-pane Settings in BOTH orientations; tablets (sw ≥ 600) and TVs keep the
// two-pane. smallestScreenWidthDp is orientation-independent, so a landscape
// phone (wide, but sw ≈ 411) still collapses — which the old slot-width check
// missed (a landscape phone's slot is huge → it fell into the two-pane).
private const val COMPACT_SETTINGS_SW_DP = 600

/**
 * Settings entry. Branches on the available width: a phone (compact) gets a
 * single-pane master/detail (tab list → one tab body full-width); everything
 * wider keeps the unchanged TV two-pane layout. (MK.27.C)
 */
@UnstableApi
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, initialTab: SettingsTab = SettingsTab.General, onExitToMainSidebar: () -> Unit = {}) {
    // Keyed off FORM FACTOR, not the slot width: a landscape phone is wide but
    // still wants the focused tab full-screen, so a width breakpoint missed it.
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val isTv =
        (config.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    val singlePane = config.smallestScreenWidthDp < COMPACT_SETTINGS_SW_DP && !isTv
    // MK.30.1 (MB-307) — one focus-scroll spec for the whole Settings surface.
    // It used to be provided inside ContentPane, which meant the TV two-pane
    // body got it but the phone drilled-in body (and any dialog hosted from a
    // tab) fell back to Compose's flush-to-viewport-edge default. Provided
    // here it covers both layouts and every scroll container they nest.
    ProvideFocusScrollSpec {
        Box(modifier = modifier.fillMaxSize()) {
            if (singlePane) {
                SettingsPhoneLayout(initialTab = initialTab, onExit = onExitToMainSidebar)
            } else {
                SettingsTwoPaneLayout(initialTab = initialTab, onExitToMainSidebar = onExitToMainSidebar)
            }
        }
    }
}

/**
 * Phone single-pane Settings (MK.27.C). Master = the full-width tab list;
 * detail = one tab's body full-width with a touch Back. Tap a tab to drill in;
 * the top-bar Back (or system BACK) returns to the list, and BACK from the list
 * exits Settings — touch-first, no D-pad required.
 */
@OptIn(ExperimentalComposeUiApi::class)
@UnstableApi
@Composable
private fun SettingsPhoneLayout(initialTab: SettingsTab, onExit: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var drilledIn by rememberSaveable { mutableStateOf(false) }
    // MK.30 — Search query for the master list. Cleared when the user
    // drills in so coming back out shows the full list again, matching
    // a typical phone "search → tap → back → reset" pattern.
    var query by rememberSaveable { mutableStateOf("") }

    // MK.28.4 (MB-255) — the handler must DISABLE itself once there is
    // nothing left to pop. It was enabled=true with an onExit() else-branch,
    // but onExit only requests TV sidebar focus — it never changes section —
    // so on a touch phone every BACK/gesture-back re-entered the same
    // handler forever: BACK could never leave Settings or exit the app, and
    // (being registered after the shell's search-overlay handler) it also
    // preempted overlay dismissal. With enabled gated, the root-list BACK
    // falls through to the shell/system like every TV handler in this file.
    BackHandler(enabled = drilledIn || query.isNotBlank()) {
        when {
            drilledIn -> drilledIn = false
            else -> query = ""
        }
    }

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(LocalYancoPalette.current.BackgroundDeep),
    ) {
        if (!drilledIn) {
            SidebarHeader()
            // MK.30 — Search field. Same shape as the TV sidebar's
            // search; the master/detail layout puts it just under the
            // header so it's reachable without scrolling.
            SettingsSearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HairlineDivider()
            val visibleTabs = remember(query) { searchTabs(query) }
            val results = remember(query) { searchSettings(query) }
            Column(
                modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (entry in visibleTabs) {
                    TabItem(
                        entry = entry,
                        selected = entry == tab,
                        onClick = {
                            tab = entry
                            drilledIn = true
                            query = ""
                        },
                    )
                }
                if (results.isNotEmpty()) {
                    SearchResultsSection(
                        results = results,
                        onSelect = { selected ->
                            tab = selected
                            drilledIn = true
                            query = ""
                        },
                    )
                }
                if (visibleTabs.isEmpty() && results.isEmpty() && query.isNotBlank()) {
                    SearchEmptyState(query = query)
                }
            }
        } else {
            PhoneTabTopBar(tab = tab, onBack = { drilledIn = false })
            HairlineDivider()
            Box(
                modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                androidx.compose.runtime.key(tab) {
                    TabContent(tab = tab)
                }
            }
        }
    }
}

/** Top bar for a drilled-in phone tab: a touch Back + the tab's icon + label. */
@Composable
private fun PhoneTabTopBar(tab: SettingsTab, onBack: () -> Unit) {
    val palette = LocalYancoPalette.current
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back to settings list" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = com.yancotv.android.R.drawable.ic_player_back),
                contentDescription = null,
                tint = palette.TextPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = palette.Accent,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = tab.label,
            color = palette.TextPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@UnstableApi
@Composable
private fun SettingsTwoPaneLayout(initialTab: SettingsTab = SettingsTab.General, onExitToMainSidebar: () -> Unit = {}) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val scope = rememberCoroutineScope()
    // MK.30 — Search query. Owned by the parent so BackHandler can
    // clear it on BACK (matches the "Back exits the current state, not
    // the screen" pattern the sidebar / content layers already use).
    var query by rememberSaveable { mutableStateOf("") }
    // Two layers, two keys, one rule per layer:
    //   - Settings tab CONTENT: BACK or LEFT-from-leftmost → focus the
    //     active tab in the inner sidebar.
    //   - Settings tab SIDEBAR: BACK or LEFT → exit Settings entirely
    //     ([onExitToMainSidebar]) — the host (HomeScreen) refocuses the
    //     main app sidebar, which auto-expands.
    //
    // [contentHasFocus] gates the per-layer BackHandlers so the inner
    // tab BACK and the outer sidebar BACK don't fire on the same press.
    var contentHasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // MK.31.2 — the direction that moves from the sub-sidebar INTO the content
    // pane. Physical RIGHT in LTR, physical LEFT in RTL: moveFocus takes a
    // physical direction, so this has to be resolved rather than named.
    val intoContent = endwardFocus()
    // [activeTabFocus] is bound to the currently-selected TabItem only
    // (mirrors `AppSidebar.activeRowFocus` — the canonical pattern from
    // MB-106). `requestFocus()` lands on that exact node, so we no longer
    // depend on `moveFocus(Left)` finding a spatial neighbour across the
    // focusGroup boundary — that was the failure mode that left users
    // stuck inside Network when a chip or slider was focused.
    val activeTabFocus = remember { FocusRequester() }

    BackHandler(enabled = contentHasFocus) {
        // Explicit requester instead of moveFocus(Left). The previous
        // implementation called moveFocus(Left), which on Fire TV
        // returned false silently when the focused descendant (chip,
        // slider knob, click-to-edit field) couldn't find a spatial
        // neighbour to the left through the cross-pane focusGroup
        // boundary. The BackHandler still consumed the event (enabled =
        // contentHasFocus stayed true), so subsequent BACK presses also
        // no-op'd — the user was trapped. requestFocus() always lands
        // on the active tab node directly.
        runCatching { activeTabFocus.requestFocus() }
    }

    Row(
        modifier =
        Modifier
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
            query = query,
            onQueryChange = { query = it },
            onSelect = {
                tab = it
                // MK.30 — Clear the search after selecting a result so
                // the user lands on the tab body, not on more search
                // results in the sidebar.
                query = ""
                scope.launch {
                    // MK.22.B.1: tabs with heavy bodies (LazyColumn-based)
                    // need two frames + a retry — the first moveFocus(Right)
                    // can land before the LazyColumn places its first item.
                    // Cheap tabs (verticalScroll-based or read-only Column)
                    // place every focusable in their first composition, so
                    // one frame is enough and the retry never fires.
                    // Skipping the retry on cheap tabs trims ~16-32 ms off
                    // every settings tab swap (was ~50 ms minimum).
                    val isHeavyTab =
                        it == SettingsTab.Sources ||
                            it == SettingsTab.Groups ||
                            it == SettingsTab.Parental ||
                            it == SettingsTab.Recordings
                    withFrameNanos { }
                    if (isHeavyTab) withFrameNanos { }
                    val moved = focusManager.moveFocus(intoContent)
                    if (!moved && isHeavyTab) {
                        // Retry only on heavy tabs — the original "first
                        // attempt sometimes loses to LazyColumn placement"
                        // race. Cheap tabs that fail to land focus here
                        // would also fail the retry; logging once is fine.
                        withFrameNanos { }
                        val movedRetry = focusManager.moveFocus(intoContent)
                        if (!movedRetry) {
                            Log.w(
                                "SettingsScreen",
                                "MB-108: moveFocus(into content) failed twice for tab=$it; focus stays on sidebar",
                            )
                        }
                    } else if (!moved) {
                        Log.w(
                            "SettingsScreen",
                            "MB-108: moveFocus(Right) failed for cheap tab=$it; focus stays on sidebar",
                        )
                    }
                }
            },
            onExit = onExitToMainSidebar,
            activeTabFocus = activeTabFocus,
            modifier =
            Modifier
                .width(ShellDim.settingsRailWidth)
                .fillMaxHeight(),
        )
        // Provide the active-tab requester to every Settings row primitive
        // beneath this point so each row's focusGroup boundary can redirect
        // a LEFT-exit back to the inner sidebar without per-call-site
        // wiring (see [LocalActiveSettingsTabFocus] / [startExitsTo]).
        CompositionLocalProvider(LocalActiveSettingsTabFocus provides activeTabFocus) {
            ContentPane(
                current = tab,
                activeTabFocus = activeTabFocus,
                modifier =
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { contentHasFocus = it.hasFocus },
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Sidebar(
    current: SettingsTab,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (SettingsTab) -> Unit,
    onExit: () -> Unit,
    activeTabFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val panelShape = RoundedCornerShape(28.dp)
    var sidebarHasFocus by remember { mutableStateOf(false) }
    // MK.31.2 — startward, not physical LEFT. In RTL the settings content pane
    // is on the left, so leaving the sidebar entirely is a physical RIGHT press.
    val sidebarExitKey = startwardKey()
    // MK.30 — Gate the exit handler so the user can move the text caret inside
    // the search field with the same key without accidentally exiting
    // Settings. The handler still fires for every startward press when focus is
    // on a TabItem / footer / etc.
    var searchHasFocus by remember { mutableStateOf(false) }

    // BACK behaviour:
    //   - search has text → clear text (consume BACK).
    //   - sidebar otherwise → exit Settings (mirrors CategoryRail).
    BackHandler(enabled = sidebarHasFocus) {
        if (query.isNotBlank()) onQueryChange("") else onExit()
    }

    val visibleTabs = remember(query) { searchTabs(query) }
    val results = remember(query) { searchSettings(query) }

    Column(
        modifier =
        modifier
            .clip(panelShape)
            .background(LocalYancoPalette.current.BackgroundRaised)
            .border(1.dp, LocalYancoPalette.current.PanelBorder, panelShape)
            .onFocusChanged { sidebarHasFocus = it.hasFocus }
            // D-pad LEFT inside the inner sidebar exits Settings —
            // UNLESS the search field has focus (then LEFT moves the
            // text caret within the typed query, not out of Settings).
            //
            // MK.31 — Numpad 1-9 jumps to that ordinal Settings tab
            // when the sidebar has focus and the search field is empty.
            // Skipped when the search field has focus so the user can
            // type a query like "1080p" without accidentally jumping.
            // Skipped when query is non-blank so digits typed *into*
            // search continue to land in the field if focus is
            // elsewhere on the panel.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // MK.31.2 — startward, not physical LEFT: in RTL leaving the
                // settings sidebar is a physical RIGHT press.
                if (event.key == sidebarExitKey && !searchHasFocus) {
                    onExit()
                    return@onPreviewKeyEvent true
                }
                if (!searchHasFocus && query.isBlank()) {
                    val n = when (event.key) {
                        Key.One, Key.NumPad1 -> 1
                        Key.Two, Key.NumPad2 -> 2
                        Key.Three, Key.NumPad3 -> 3
                        Key.Four, Key.NumPad4 -> 4
                        Key.Five, Key.NumPad5 -> 5
                        Key.Six, Key.NumPad6 -> 6
                        Key.Seven, Key.NumPad7 -> 7
                        Key.Eight, Key.NumPad8 -> 8
                        Key.Nine, Key.NumPad9 -> 9
                        else -> 0
                    }
                    if (n in 1..SettingsTab.entries.size) {
                        onSelect(SettingsTab.entries[n - 1])
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusGroup()
            .focusRestorer(),
    ) {
        SidebarHeader()
        // MK.30 — Search field. Sits between the header and the tab
        // rail; takes voice / soft-keyboard input on TV (Fire TV remote
        // mic dictates straight into BasicTextField). Filters the tab
        // list AND surfaces a per-setting matches block below.
        SettingsSearchField(
            value = query,
            onValueChange = onQueryChange,
            modifier =
            Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .onFocusChanged { searchHasFocus = it.hasFocus },
        )
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
            for (entry in visibleTabs) {
                val isActive = entry == current
                TabItem(
                    entry = entry,
                    selected = isActive,
                    onClick = { onSelect(entry) },
                    // Bind the requester only to the active row. requestFocus
                    // then lands on that exact node — no spatial / restorer
                    // race. Mirrors AppSidebar.bindActiveRowFocus (MB-106).
                    focusRequester = if (isActive) activeTabFocus else null,
                )
            }
            if (results.isNotEmpty()) {
                SearchResultsSection(results = results, onSelect = onSelect)
            }
            if (visibleTabs.isEmpty() && results.isEmpty() && query.isNotBlank()) {
                SearchEmptyState(query = query)
            }
        }
        // MK.29.4 — Persistent BACK-to-exit hint. Phone layout
        // [SettingsPhoneLayout] has its own touch Back button + system
        // gesture and uses a different shell, so this footer only
        // appears in the two-pane (TV / tablet) sidebar.
        SidebarFooterHint()
    }
}

/**
 * MK.30 — Search field used in both Settings layouts (TV sidebar +
 * phone master list). A magnifying-glass icon, a single-line
 * [BasicTextField], and a clear-× button when the field has content.
 *
 * Voice search "just works" on Fire TV: the remote mic dictates
 * directly into BasicTextField via the platform IME. On phone, the
 * soft keyboard auto-shows when focused.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SettingsSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val palette = LocalYancoPalette.current
    val shape = RoundedCornerShape(10.dp)
    val border = if (focused) palette.FocusRing else palette.BorderSubtle

    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.BackgroundElevated)
            .border(if (focused) 1.5.dp else 1.dp, border, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = YancoIcons.Search,
            contentDescription = null,
            tint = if (focused) palette.Accent else palette.TextMuted,
            modifier = Modifier.size(16.dp),
        )
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = palette.TextPrimary,
                fontSize = 14.sp,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.Accent),
            interactionSource = interaction,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Search settings" },
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = "Search settings…",
                        color = palette.TextFaint,
                        fontSize = 14.sp,
                    )
                }
                inner()
            },
        )
        if (value.isNotEmpty()) {
            val clearInteraction = remember { MutableInteractionSource() }
            val clearFocused by clearInteraction.collectIsFocusedAsState()
            Box(
                modifier =
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (clearFocused) palette.Accent.copy(alpha = 0.18f) else Color.Transparent,
                    )
                    .border(
                        width = if (clearFocused) 1.5.dp else 0.dp,
                        color = if (clearFocused) palette.FocusRing else Color.Transparent,
                        shape = RoundedCornerShape(11.dp),
                    )
                    .clickable(
                        interactionSource = clearInteraction,
                        indication = null,
                        role = Role.Button,
                        onClick = { onValueChange("") },
                    )
                    .semantics { contentDescription = "Clear search" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "×",
                    color = if (clearFocused) palette.Accent else palette.TextMuted,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        // Audit catch — voice affordance for remotes / phones whose IME
        // doesn't expose a mic key. RecognizerIntent is permission-free
        // (handoff to a system activity), so this is a 1-line wire-up.
        VoiceInputButton(onResult = { spoken -> onValueChange(spoken) })
    }
}

/**
 * MK.30 — "Matching settings" block rendered below the (possibly
 * filtered) tab list when the search query is non-empty. Each row
 * shows the setting name + the tab it belongs to so the user knows
 * where they'll land.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SearchResultsSection(results: List<SettingsSearchEntry>, onSelect: (SettingsTab) -> Unit) {
    val palette = LocalYancoPalette.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = "MATCHING SETTINGS",
            color = palette.TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 8.dp),
        )
        for (entry in results) {
            SearchResultRow(entry = entry, onClick = { onSelect(entry.tab) })
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SearchResultRow(entry: SettingsSearchEntry, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val palette = LocalYancoPalette.current
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (focused) palette.BackgroundElevated else Color.Transparent,
            )
            .border(
                width = if (focused) 1.5.dp else 0.dp,
                color = if (focused) palette.FocusRing else Color.Transparent,
                shape = shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "${entry.label} in ${entry.tab.label}"
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entry.tab.icon,
            contentDescription = null,
            tint = if (focused) palette.Accent else palette.TextMuted,
            modifier = Modifier.size(14.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                color = palette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "in ${entry.tab.label}",
                color = palette.TextMuted,
                fontSize = 12.sp,
            )
        }
        Icon(
            imageVector = YancoIcons.ChevronRight,
            contentDescription = null,
            tint = if (focused) palette.Accent else palette.TextFaint,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun SearchEmptyState(query: String) {
    val palette = LocalYancoPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        Text(
            text = "No matches for “$query”.",
            color = palette.TextMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Press BACK to clear search.",
            color = palette.TextFaint,
            fontSize = 12.sp,
        )
    }
}

/**
 * MK.29.4 — Persistent footer at the bottom of the Settings sidebar
 * with the keyboard contract: BACK exits, D-pad up/down navigates the
 * tab list. 11sp / muted / mono-spaced for the keycap glyphs so the
 * hint reads as system chrome, not content.
 */
@Composable
private fun SidebarFooterHint() {
    val palette = LocalYancoPalette.current
    HairlineDivider()
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "BACK",
            color = palette.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
        Text(
            text = "exit  ·  ↑↓ navigate  ·  1-9 jump",
            color = palette.TextMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SidebarHeader() {
    val palette = LocalYancoPalette.current
    // 'Settings' wordmark on the left, shipped raster logo pinned to the
    // far right via SpaceBetween. The user's preferred order — title
    // reads as the screen's title, the logo sits as a brand sign-off in
    // the corner instead of leading the row.
    //
    // The logo height (64dp) drives the row height — calibrated to read
    // at 3 m on Fire TV without crowding the divider.
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Settings",
            color = palette.TextPrimary,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id = com.yancotv.android.R.drawable.ic_logo),
            contentDescription = "YancoTV",
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.height(64.dp),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TabItem(entry: SettingsTab, selected: Boolean, onClick: () -> Unit, focusRequester: FocusRequester? = null) {
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
    // MK.31.2 — endward, not physical RIGHT. The content pane is on the left
    // in RTL, so "select this tab and move into it" is a physical LEFT press.
    val enterKey = endwardKey()
    val onTabKey =
        Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
                event.key == enterKey &&
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
    val borderColor = if (focused) palette.FocusRing else Color.Transparent
    // 2026-06-15 — dropped the `.shadow(18dp, …)` focus halo: its elevation
    // shadow rendered as a dark horizontal band THROUGH the row's translucent
    // green fill (≈30% darker, full-width, at the vertical centre — the
    // "annoying black line" the user reported). The focus state still reads
    // clearly from the accent border + brighter `rowBrush` + 1.04 scale.

    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(rowBrush)
            .border(
                width = if (focused) 1.5.dp else 0.dp,
                color = borderColor,
                shape = shape,
            )
            .then(onTabKey)
            .let { base ->
                // Bind the requester to the SAME node as `.focusable`
                // (Modifier order matters in Compose 1.7) — putting it on a
                // wrapper Box made requestFocus unreliable, see MB-106 v2.
                if (focusRequester != null) base.focusRequester(focusRequester) else base
            }
            .focusable(interactionSource = interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ).semantics {
                role = Role.Tab
                contentDescription = "${entry.label} settings tab"
                // MK.28.8 (MB-276) — announce selected state to TalkBack.
                this.selected = selected
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
                .padding(start = 22.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Single-glyph layout: icon tile + label. The ordinal number
            // and the mono sub-caption (e.g. "03 · video · audio") were
            // dropped 2026-04-28 — three text widgets per row read as
            // visual noise on a 12-tab list and the user's redesign
            // pass collapsed them away.
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
            Text(
                text = entry.label,
                color = labelColor,
                fontSize = 16.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.1).sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@UnstableApi
@Composable
private fun ContentPane(current: SettingsTab, activeTabFocus: FocusRequester, modifier: Modifier = Modifier) {
    val panelShape = RoundedCornerShape(28.dp)
    // MK.31.2 — captured for the `exit` lambda below, which receives a PHYSICAL
    // FocusDirection and so cannot tell startward from endward on its own.
    val paneLayoutDirection = LocalLayoutDirection.current
    Column(
        modifier =
        modifier
            .clip(panelShape)
            .background(LocalYancoPalette.current.BackgroundRaised)
            .border(1.dp, LocalYancoPalette.current.PanelBorder, panelShape)
            // Define the content pane as a focus group so we can
            // redirect focus exits from it. `focusProperties.exit`
            // runs inside Compose's focus traversal — it fires only
            // when there is NO in-group target in the requested
            // direction, so chip rows / sliders / button rows with
            // horizontal LEFT siblings keep their natural navigation
            // (e.g. "VLC chip" → "System chip" stays in-row). At the
            // leftmost edge, the exit lambda redirects to the active
            // tab in the inner sidebar instead of letting Compose's
            // spatial search find an unrelated focusable above or
            // below in the same column.
            .focusGroup()
            .focusProperties {
                exit = { direction ->
                    // MK.31.2 — startward, not physical Left. `exit` receives a
                    // physical direction; in RTL the sub-sidebar is to the right.
                    if (isStartward(direction, paneLayoutDirection)) {
                        activeTabFocus
                    } else {
                        FocusRequester.Default
                    }
                }
            },
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
        //
        // The focus-scroll spec that keeps section headers from being clipped
        // is provided once for the whole Settings surface in [SettingsScreen]
        // (MK.30.1 / MB-307) — see [com.yancotv.android.ui.focus.ProvideFocusScrollSpec].
        // It used to live here, which left the phone layout uncovered.
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
private fun HexChip(text: String, active: Boolean, icon: ImageVector? = null) {
    // Symmetric horizontal hex with slightly rounded points. The previous
    // `ChipBevel` was asymmetric — a sharp angular cut on the left and a
    // half-pill rounded right — which read as 'half circle on a side and
    // pointed in the other'. HexCapsuleSoft matches the rest of the
    // theme's hex family (the live-TV channel tiles, the category rail
    // pills) so the breadcrumb belongs to the same vocabulary.
    //
    // MB-300 — padding was 22dp on the theory that the label needed to
    // clear the hex side points. Measured, that was over-provisioned by
    // 18dp per side: HexCapsuleSoft at h=34dp has cut = (68 * 0.30) = 20.4px
    // = 10.2dp, and the 13sp text band only reaches the bevel at
    // x ~= 4.4dp. 14dp clears it with room and returns 16dp of glyph
    // budget per chip — which, on the *active* chip, is the entire
    // difference between "SOURCES" fitting and wrapping.
    //
    // Height was a hard `.height(30.dp)` (min == max) on the same modifier
    // chain as `.clip(shape)`, so an overflowing label was silently sliced
    // top and bottom rather than ellipsised. `heightIn(min = ...)` lets the
    // chip grow instead of clipping if a label ever does wrap.
    val shape = YancoShapes.HexCapsuleSoft
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
            .heightIn(min = 34.dp)
            .clip(shape)
            .background(bg)
            .border(
                1.dp,
                if (active) Color.Transparent else LocalYancoPalette.current.BorderSubtle,
                shape,
            ).padding(horizontal = 14.dp, vertical = 6.dp),
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
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold,
            letterSpacing = 1.32.sp,
            // MB-300 — a chip is a one-line element by construction. Without
            // these three the label wrapped inside a fixed-height clipped box
            // and lost characters with no visible marker. `softWrap = false`
            // is the one that actually prevents the second line; maxLines
            // alone still lays out two and then drops one.
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
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
