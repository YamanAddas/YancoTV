package com.yancotv.android.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.player.PlayerLauncher
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.OpenOn
import com.yancotv.android.ui.detail.ContentDetailScreen
import com.yancotv.android.ui.nav.AppSection
import com.yancotv.android.ui.parental.PinEntryDialog
import com.yancotv.android.ui.settings.SettingsScreen
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.types.EpisodeInfo
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.history.WatchHistoryRepository
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.EpgGuideChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Adaptive shell. TV gets the 3-column layout (sidebar | filter | content | info);
 * phone stacks the filter + content with the sidebar in a drawer-equivalent rail.
 *
 * The outer state owned here is deliberately small — selected section, selected
 * group, currently-focused item — so rotating or backgrounding restores cleanly
 * via `rememberSaveable`.
 */
@UnstableApi
@Composable
fun HomeScreen(
    isTv: Boolean,
    repo: ContentRepository = koinInject(),
    controller: PlaybackController = koinInject(),
    parental: ParentalRepository = koinInject(),
    prefs: AppPreferences = koinInject(),
    history: WatchHistoryRepository = koinInject(),
) {
    // Resolve the "Open app on" preference once at first composition. For
    // `LAST_USED` we defer to rememberSaveable's persisted value; for
    // explicit choices we seed the initial state and let the user navigate
    // away freely afterwards.
    val openOn = remember { prefs.generalSnapshot().openOn }
    val initialSection = remember(openOn) {
        when (openOn) {
            OpenOn.LIVE_TV -> AppSection.LiveTv
            OpenOn.LAST_USED, OpenOn.HOME -> AppSection.Home
        }
    }
    var section by rememberSaveable { mutableStateOf(initialSection) }

    // OpenOn.LAST_USED auto-resumes the last played item into the mini
    // preview so the user lands on the section with their stream already
    // starting to warm up. Fullscreen is not triggered — the user still
    // has to OK/tap into it, matching the "returning to where I left
    // off" expectation without stealing focus into the player.
    var autoplayAttempted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openOn) {
        if (autoplayAttempted) return@LaunchedEffect
        if (openOn != OpenOn.LAST_USED) return@LaunchedEffect
        if (controller.currentId != null) { autoplayAttempted = true; return@LaunchedEffect }
        val entry = withContext(Dispatchers.IO) {
            runCatching { history.recent(limit = 1).firstOrNull() }.getOrNull()
        }
        autoplayAttempted = true
        val item = entry?.content ?: return@LaunchedEffect
        // Respect the parental lock. If the last item is gated, skip the
        // auto-resume rather than popping a PIN dialog the user didn't ask
        // for on launch — they can click through manually if they want.
        if (item.id in parental.lockedIds.value) return@LaunchedEffect
        controller.play(listOf(item), 0)
    }
    val contentType = section.contentType
    val context = LocalContext.current
    val searchOverlayVisible by SearchOverlayState.visible.collectAsState()

    // Back on TV + phone dismisses the overlay without going through the
    // shell's navigation — matches the behavior users expect from any
    // "press a hotkey, overlay appears, press Back, overlay disappears"
    // surface. Scoped on searchOverlayVisible so it's a no-op otherwise.
    BackHandler(enabled = searchOverlayVisible) { SearchOverlayState.hide() }

    // MK.8.7 PIN gate — a locked channel's play attempt is deferred into
    // `pendingPlay` while the PinEntryDialog sits on top. On success we
    // invoke the captured action (usually controller.play + launch).
    // Observing the lockedIds flow keeps the check live without a per-
    // press DB read.
    val lockedIds by parental.lockedIds.collectAsState()
    val parentalSettings by parental.settings.collectAsState()
    var pendingPlay by remember { mutableStateOf<(() -> Unit)?>(null) }
    val gatedPlay: (String, () -> Unit) -> Unit = { id, action ->
        if (id in lockedIds) pendingPlay = action else action()
    }

    // Detail overlay state. Movies + series route here on activation
    // instead of straight to the player so the user can read plot / pick
    // an episode / add to favourites before committing to playback. Live
    // channels bypass the detail page entirely.
    var detailItem by remember { mutableStateOf<ContentItem?>(null) }
    BackHandler(enabled = detailItem != null) { detailItem = null }

    // MK.8.7.b — Settings-entry gate. When the user has enabled
    // "Require PIN for Settings", navigating to the Settings section sets
    // this flag until the PIN is verified. Clears when the user leaves
    // Settings (section changes) so re-entry re-prompts.
    var settingsUnlocked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(section) {
        if (section != AppSection.Settings) settingsUnlocked = false
    }
    val needsSettingsGate =
        section == AppSection.Settings &&
            parentalSettings.pinSet &&
            parentalSettings.requirePinForSettings &&
            !settingsUnlocked

    // Progressive panel reveal — TiviMate-style. Only Live/Movies/Series
    // sections participate; everything else (Home, Guide, Favorites,
    // Search, Settings) forces all panels visible so their navigation
    // stays intuitive.
    //
    // State:
    //   revealLevel = 0 → content full-width, groups + sidebar hidden
    //   revealLevel = 1 → groups visible, sidebar hidden
    //   revealLevel = 2 → all three panels visible
    //
    // currentZone tracks which panel has focus, updated by each panel's
    // [Modifier.onFocusChanged]. We need this to decide whether LEFT
    // should expand the next panel (from content → groups, groups →
    // sidebar) or is a no-op (already at sidebar).
    val progressiveSection = contentType != null
    var revealLevel by rememberSaveable(progressiveSection) {
        mutableStateOf(if (progressiveSection) 0 else 2)
    }
    var currentZone by remember { mutableStateOf(ShellZone.CONTENT) }
    val focusManager = LocalFocusManager.current
    val revealScope = rememberCoroutineScope()

    // LEFT handler runs BEFORE any panel sees the key (onPreviewKeyEvent
    // on the outer Box). When the revealed panel first becomes visible
    // via AnimatedVisibility we have to wait for composition + layout to
    // settle before moveFocus(Left) can walk into it — otherwise focus
    // falls off the edge and stays put. A short delay + focusRestorer()
    // on each panel is what makes "LEFT lands on the selected row" work.
    val onShellLeft: () -> Boolean = left@{
        if (!progressiveSection) return@left false
        when (currentZone) {
            ShellZone.CONTENT -> {
                if (revealLevel < 1) {
                    revealLevel = 1
                    revealScope.launch {
                        // Two frames is enough for AnimatedVisibility to
                        // compose its children; moveFocus then walks the
                        // focus graph which now includes the groups panel.
                        delay(60)
                        focusManager.moveFocus(FocusDirection.Left)
                    }
                } else {
                    focusManager.moveFocus(FocusDirection.Left)
                }
                true
            }
            ShellZone.GROUPS -> {
                if (revealLevel < 2) {
                    revealLevel = 2
                    revealScope.launch {
                        delay(60)
                        focusManager.moveFocus(FocusDirection.Left)
                    }
                } else {
                    focusManager.moveFocus(FocusDirection.Left)
                }
                true
            }
            ShellZone.SIDEBAR -> false // already leftmost — let default handling apply
        }
    }

    // BACK collapses the outer-most revealed panel one step at a time,
    // then falls back to default (which will exit the app). Mirrors the
    // pairing users expect: "LEFT opens, BACK closes".
    BackHandler(enabled = progressiveSection && revealLevel > 0) {
        revealLevel = (revealLevel - 1).coerceAtLeast(0)
        revealScope.launch {
            delay(60)
            focusManager.moveFocus(FocusDirection.Right)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.DirectionLeft) onShellLeft() else false
            },
    ) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(YancoPalette.BackgroundDeep),
    ) {
        // Sidebar slides in when revealLevel >= 2. For non-progressive
        // sections (contentType == null) revealLevel is pinned to 2 so
        // the sidebar is always visible. [onFocusChanged] keeps the
        // shell state aware of which panel has focus so LEFT knows when
        // to expand the next panel vs just move focus.
        AnimatedVisibility(
            visible = revealLevel >= 2,
            enter = slideInHorizontally(animationSpec = tween(200)) { -it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(animationSpec = tween(160)) { -it } + fadeOut(tween(160)),
        ) {
            AppSidebar(
                current = section,
                onSelect = { section = it },
                modifier = Modifier.onFocusChanged {
                    if (it.hasFocus) currentZone = ShellZone.SIDEBAR
                },
            )
        }

        if (contentType != null) {
            ContentArea(
                isTv = isTv,
                type = contentType,
                repo = repo,
                revealLevel = revealLevel,
                onGroupsFocusChanged = { hasFocus ->
                    if (hasFocus) currentZone = ShellZone.GROUPS
                },
                onContentFocusChanged = { hasFocus ->
                    if (hasFocus) currentZone = ShellZone.CONTENT
                },
                onActivate = { list, idx ->
                    val target = list.getOrNull(idx) ?: return@ContentArea
                    gatedPlay(target.id) {
                        when (target.type) {
                            ContentType.LIVE -> {
                                // Two-tap activation on TV (TiviMate-style):
                                // first press starts the stream in the mini
                                // preview, second press on the same channel
                                // fullscreens with zero rebuffer. Phone skips
                                // the mini because the shell doesn't dedicate
                                // screen real-estate to InfoPanel — tap goes
                                // straight to the player.
                                val alreadyPlaying = controller.currentId == target.id
                                if (!alreadyPlaying) controller.play(list, idx)
                                if (!isTv || alreadyPlaying) {
                                    PlayerLauncher.launch(context)
                                }
                            }
                            ContentType.MOVIE, ContentType.SERIES -> {
                                // Open the detail overlay instead of auto-
                                // playing — the user almost always wants to
                                // see a plot/poster/episode list first.
                                detailItem = target
                            }
                        }
                    }
                },
            )
        } else if (section == AppSection.Settings) {
            Box(modifier = Modifier.weight(1f)) {
                if (needsSettingsGate) {
                    SettingsLockedPlaceholder()
                } else {
                    SettingsScreen()
                }
            }
        } else if (section == AppSection.Guide) {
            Box(modifier = Modifier.weight(1f)) {
                GuideScreen(
                    onPlay = { channel, _ ->
                        val item = guideChannelToContentItem(channel) ?: return@GuideScreen
                        gatedPlay(item.id) {
                            controller.play(listOf(item), 0)
                            PlayerLauncher.launch(context)
                        }
                    },
                    onPlayCatchup = { item ->
                        // Catchup id is `catchup:<contentId>:<start>` — gate
                        // against the underlying content id, not the catchup
                        // pseudo-id, so lock status on the live channel
                        // covers its replays too.
                        val underlying = item.id.removePrefix("catchup:").substringBefore(':')
                        gatedPlay(underlying) {
                            controller.play(listOf(item), 0)
                            PlayerLauncher.launch(context)
                        }
                    },
                )
            }
        } else if (section == AppSection.Favorites) {
            Box(modifier = Modifier.weight(1f)) {
                FavoritesScreen(isTv = isTv)
            }
        } else if (section == AppSection.Search) {
            Box(modifier = Modifier.weight(1f)) {
                SearchScreen(isTv = isTv)
            }
        } else if (section == AppSection.Home) {
            Box(modifier = Modifier.weight(1f)) {
                HomeContent(
                    onPlay = { list, idx ->
                        val target = list.getOrNull(idx) ?: return@HomeContent
                        gatedPlay(target.id) {
                            val alreadyPlaying = controller.currentId == target.id
                            if (!alreadyPlaying) controller.play(list, idx)
                            if (!isTv || alreadyPlaying) PlayerLauncher.launch(context)
                        }
                    },
                )
            }
        } else {
            Box(modifier = Modifier.weight(1f)) {
                PlaceholderArea(section = section)
            }
        }
    }

        // Overlay rides above the Row so it dims the whole shell, not just
        // one region. Scrim is a dark translucent layer that swallows clicks
        // so focus doesn't leak to the underlying sidebar while typing.
        if (searchOverlayVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { SearchOverlayState.hide() },
                    ),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (isTv) 0.6f else 1f)
                        .fillMaxHeight()
                        .background(YancoPalette.BackgroundDeep)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { /* swallow — keep overlay open when clicking inside */ },
                        ),
                ) {
                    SearchScreen(isTv = isTv)
                }
            }
        }

        // Movie / Series detail overlay — rides above the shell Row so it
        // can occupy the full screen while the shell keeps its state
        // intact underneath (focus memory, scroll position, etc.).
        detailItem?.let { item ->
            ContentDetailScreen(
                item = item,
                onPlayContent = { target ->
                    controller.play(listOf(target), 0)
                    detailItem = null
                    PlayerLauncher.launch(context)
                },
                onPlayEpisode = { target, ep ->
                    val episodeItem = target.copy(
                        id = "${target.id}:ep:${ep.id}",
                        streamUrl = ep.streamUrl,
                        title = "${target.title} — ${ep.title}",
                        cleanTitle = ep.title,
                    )
                    controller.play(listOf(episodeItem), 0)
                    detailItem = null
                    PlayerLauncher.launch(context)
                },
                onDismiss = { detailItem = null },
            )
        }

        // Parental PIN gate (MK.8.7). Rides above the shell so the dialog
        // dims the sidebar + whatever content was under it. Dismissing via
        // the Cancel button or the implicit tap-outside clears the pending
        // play without running it — user opted out.
        pendingPlay?.let { action ->
            PinEntryDialog(
                title = "Channel locked",
                body = "Enter your PIN to watch this channel.",
                repo = parental,
                onSuccess = {
                    action()
                    pendingPlay = null
                },
                onDismiss = { pendingPlay = null },
            )
        }

        // Settings PIN gate (MK.8.7.b). Shown when the user has opted into
        // "Require PIN for Settings" and they just navigated here. Cancel
        // takes them back to the previous section rather than leaving
        // them stranded in a locked Settings pane.
        if (needsSettingsGate) {
            PinEntryDialog(
                title = "PIN required",
                body = "Enter your PIN to open Settings.",
                repo = parental,
                onSuccess = { settingsUnlocked = true },
                onDismiss = { section = AppSection.LiveTv },
            )
        }
    }
}

@Composable
private fun SettingsLockedPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(YancoPalette.BackgroundDeep),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Enter PIN to access Settings.",
            color = YancoPalette.TextMuted,
        )
    }
}

/**
 * Fabricate a `ContentItem` for the player queue from an [EpgGuideChannel].
 * The live content row in the DB has richer metadata, but the playback
 * controller only needs `streamUrl` + identity — fetching the real row
 * would force a tvgId → content lookup that doesn't exist yet.
 */
private fun guideChannelToContentItem(channel: EpgGuideChannel): ContentItem? {
    val url = channel.streamUrl?.takeIf { it.isNotBlank() } ?: return null
    return ContentItem(
        id = "guide:${channel.tvgId}",
        sourceId = "",
        type = ContentType.LIVE,
        title = channel.name,
        cleanTitle = channel.name,
        groupName = null,
        streamUrl = url,
        logoUrl = channel.logoUrl,
        tvgId = channel.tvgId,
        metadataJson = null,
        sortOrder = 0,
        createdAt = 0L,
    )
}

enum class ShellZone { SIDEBAR, GROUPS, CONTENT }

@UnstableApi
@Composable
private fun RowScope.ContentArea(
    isTv: Boolean,
    type: ContentType,
    repo: ContentRepository,
    revealLevel: Int,
    onGroupsFocusChanged: (Boolean) -> Unit,
    onContentFocusChanged: (Boolean) -> Unit,
    onActivate: (List<ContentItem>, Int) -> Unit,
    prefs: AppPreferences = koinInject(),
) {
    val groupsState = remember(type) { mutableStateListOf<String>() }
    var totalCount by remember(type) { mutableStateOf(0L) }
    LaunchedEffect(type) {
        val loaded = withContext(Dispatchers.IO) { repo.groups(type) }
        groupsState.clear()
        groupsState.addAll(loaded)
        totalCount = withContext(Dispatchers.IO) { runCatching { repo.count(type) }.getOrElse { 0L } }
    }
    // Hidden-groups filter runs in the sidebar only, not the DB. A hidden
    // group is never auto-selected; if the user's saved selection is now
    // hidden we fall back to "All" so they don't end up stuck on an
    // invisible category.
    val hiddenGroups by prefs.hiddenGroupsFlow.collectAsState()
    val visibleGroups = remember(groupsState.toList(), hiddenGroups) {
        groupsState.filter { it !in hiddenGroups }
    }

    var group by rememberSaveable(type) { mutableStateOf(ALL_GROUPS) }
    LaunchedEffect(hiddenGroups) {
        if (group != ALL_GROUPS && group != FAVORITES_GROUP && group in hiddenGroups) {
            group = ALL_GROUPS
        }
    }
    var focused by remember(type) { mutableStateOf<ContentItem?>(null) }
    val groupFilter = group.takeIf { it != ALL_GROUPS }

    // Groups slide in when revealLevel >= 1. onFocusChanged flips the
    // shell's zone state so the outer LEFT handler knows we're now
    // "inside" the groups panel and the next LEFT should reveal the
    // sidebar, not no-op at the leftmost content row.
    AnimatedVisibility(
        visible = revealLevel >= 1,
        enter = slideInHorizontally(animationSpec = tween(200)) { -it } + fadeIn(tween(200)),
        exit = slideOutHorizontally(animationSpec = tween(160)) { -it } + fadeOut(tween(160)),
    ) {
        CategoryFilterPanel(
            groups = visibleGroups,
            selected = group,
            onSelect = { group = it },
            smartGrouping = prefs.generalFlow.collectAsState().value.smartGrouping,
            modifier = Modifier.onFocusChanged { if (it.hasFocus) onGroupsFocusChanged(true) },
        )
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .onFocusChanged { if (it.hasFocus) onContentFocusChanged(true) },
    ) {
        SectionHeader(type = type, total = totalCount)
        Box(modifier = Modifier.fillMaxSize()) {
            ContentPanel(
                type = type,
                group = groupFilter,
                onItemFocus = { focused = it },
                onItemActivate = onActivate,
            )
        }
    }
    // Info rail: slides out once the user pushes LEFT into groups so the
    // channel list gets the recovered width. Slides back in when focus
    // returns to content. Only on TV — phones don't have the space.
    AnimatedVisibility(
        visible = isTv && revealLevel == 0 && focused != null,
        enter = slideInHorizontally(animationSpec = tween(180)) { it } + fadeIn(tween(180)),
        exit = slideOutHorizontally(animationSpec = tween(140)) { it } + fadeOut(tween(140)),
    ) {
        InfoPanel(item = focused)
    }
}

/**
 * Desktop-parity banner above the channel list: italic section title +
 * channel count, with a muted rule at the bottom. Visible in every
 * progressive section (Live TV / Movies / Series) so the user always
 * knows which catalog they're in and how big it is.
 */
@Composable
private fun SectionHeader(type: ContentType, total: Long) {
    val label = when (type) {
        ContentType.LIVE -> "Live TV"
        ContentType.MOVIE -> "Movies"
        ContentType.SERIES -> "Series"
    }
    val suffix = when (type) {
        ContentType.LIVE -> "channels"
        ContentType.MOVIE -> "titles"
        ContentType.SERIES -> "shows"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = label,
            color = YancoPalette.TextPrimary,
            fontSize = 28.sp,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = "${formatCount(total)} $suffix",
            color = YancoPalette.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%,d".format(n)
    else -> n.toString()
}

@Composable
private fun PlaceholderArea(section: AppSection) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(YancoPalette.BackgroundDeep)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = section.label,
                color = YancoPalette.TextPrimary,
                style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Coming in a later milestone.",
                color = YancoPalette.TextMuted,
            )
        }
    }
}
