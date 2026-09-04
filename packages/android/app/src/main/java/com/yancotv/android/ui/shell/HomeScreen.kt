package com.yancotv.android.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.R
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.player.PlayerLauncher
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.OpenOn
import com.yancotv.android.ui.components.CinematicBackground
import com.yancotv.android.ui.detail.ContentDetailScreen
import com.yancotv.android.ui.nav.AppSection
import com.yancotv.android.ui.parental.PinEntryDialog
import com.yancotv.android.ui.settings.SettingsScreen
import com.yancotv.android.ui.theme.LocalShellMetrics
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.rememberShellMetrics
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.history.WatchHistoryRepository
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.playback.toPlayable
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.EpgGuideChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

// Kept internal for tests; stop any playing item when the section changes —
// live, VOD, and episode previews must all stop so audio doesn't bleed.
internal fun shouldStopPlaybackOnSectionChange(playing: ContentItem?): Boolean = playing != null

/**
 * Adaptive shell. The browse sections (Live / Movies / Series) now delegate
 * to [BrowseShell] — category chips across the top, a feature hero driven
 * by the focused rail card, and a horizontal card rail beneath. The
 * progressive-reveal column dance of the previous revision is gone: with a
 * chip bar instead of a full-height group column, LEFT-to-reveal no longer
 * maps to the visual direction, so the sidebar just stays visible.
 *
 * Non-browse sections (Home, Guide, Favorites, Search, Settings) keep their
 * bespoke layouts.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun HomeScreen(
    isTv: Boolean,
    repo: ContentRepository = koinInject(),
    controller: PlaybackController = koinInject(),
    parental: ParentalRepository = koinInject(),
    prefs: AppPreferences = koinInject(),
    history: WatchHistoryRepository = koinInject(),
    sources: com.yancotv.shared.sources.SourceRepository = koinInject(),
) {
    val openOn = remember { prefs.generalSnapshot().openOn }
    val initialSection =
        remember(openOn) {
            when (openOn) {
                OpenOn.LIVE_TV -> AppSection.LiveTv
                OpenOn.LAST_USED, OpenOn.HOME -> AppSection.Home
            }
        }
    var section by rememberSaveable { mutableStateOf(initialSection) }

    // "Open on last used" auto-warms the previously played item into the
    // preview surface so the user's return-to-where-I-left-off case feels
    // instant. Fullscreen is not triggered — pressing OK still opts in.
    var autoplayAttempted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openOn) {
        if (autoplayAttempted) return@LaunchedEffect
        if (openOn != OpenOn.LAST_USED) return@LaunchedEffect
        if (controller.currentId != null) {
            autoplayAttempted = true
            return@LaunchedEffect
        }
        val entry =
            withContext(Dispatchers.IO) {
                runCatching { history.recent(limit = 1).firstOrNull() }.getOrNull()
            }
        autoplayAttempted = true
        val item = entry?.content ?: return@LaunchedEffect
        if (item.id in parental.lockedIds.value) return@LaunchedEffect
        // Series containers have no playable stream_url — only episodes do.
        // Never auto-warm a series row; the user has to open detail and
        // pick an episode.
        if (item.type == ContentType.SERIES) return@LaunchedEffect
        if (item.streamUrl.isBlank()) return@LaunchedEffect
        controller.play(listOf(item), 0)
    }

    val contentType = section.contentType
    val context = LocalContext.current
    val searchOverlayVisible by SearchOverlayState.visible.collectAsState()
    // Used by the Home Continue Watching → series resume flow to dispatch
    // the episode lookup to IO without blocking the click lambda.
    val homeScope = rememberCoroutineScope()

    // MK.28.4 (MB-261) — stop-on-section-change must fire on a genuine
    // CHANGE only. LaunchedEffect(section) also runs on the initial
    // composition after an activity recreation (uiMode flip, split-screen,
    // locale), where it silently killed the live mini-preview the user was
    // watching — and autoplayAttempted (saveable) blocked the re-warm.
    var prevSection by rememberSaveable { mutableStateOf<AppSection?>(null) }
    LaunchedEffect(section) {
        val previous = prevSection
        prevSection = section
        if (previous == null || previous == section) return@LaunchedEffect
        if (shouldStopPlaybackOnSectionChange(controller.currentItem.value)) {
            controller.stop()
        }
    }

    BackHandler(enabled = searchOverlayVisible) { SearchOverlayState.hide() }

    // Parental gate — locked rows defer their onPlay action into
    // pendingPlay until PIN verification.
    val lockedIds by parental.lockedIds.collectAsState()
    val parentalSettings by parental.settings.collectAsState()
    var pendingPlay by remember { mutableStateOf<(() -> Unit)?>(null) }
    val gatedPlay: (String, () -> Unit) -> Unit = { id, action ->
        if (id in lockedIds) pendingPlay = action else action()
    }

    // Movie / series route to the detail overlay on activation; live
    // channels bypass it and go straight through to the player.
    //
    // MK.28.4 (MB-260) — the open detail page is persisted BY ID in
    // rememberSaveable (a ContentItem doesn't fit a Bundle) and re-hydrated
    // from the repo after recreation, so backgrounding the app on a detail
    // page no longer dumps the user back onto the rail. Row gone from the
    // DB on restore → overlay closes cleanly.
    var detailItem by remember { mutableStateOf<ContentItem?>(null) }
    var detailItemId by rememberSaveable { mutableStateOf<String?>(null) }
    val openDetail: (ContentItem?) -> Unit = { item ->
        detailItem = item
        detailItemId = item?.id
    }
    LaunchedEffect(detailItemId) {
        val id = detailItemId
        if (id == null) {
            detailItem = null
            return@LaunchedEffect
        }
        if (detailItem?.id == id) return@LaunchedEffect
        val restored =
            withContext(Dispatchers.IO) {
                runCatching { repo.findById(id) }.getOrNull()
            }
        if (restored != null) detailItem = restored else detailItemId = null
    }
    BackHandler(enabled = detailItem != null) { openDetail(null) }

    // "Require PIN for Settings" gate.
    var settingsUnlocked by rememberSaveable { mutableStateOf(false) }
    // MK.32.4 — One-shot hint for which Settings tab to land on next
    // time the section becomes Settings. EmptyHome's "Add your first
    // source" CTA sets this to Sources before flipping section; sidebar
    // taps don't, so a normal Settings open still lands on the default
    // initialTab (General). Cleared on every Settings exit so a
    // sidebar-driven re-open after EmptyHome doesn't keep landing on
    // Sources.
    var pendingSettingsTab by rememberSaveable { mutableStateOf<com.yancotv.android.ui.settings.SettingsTab?>(null) }
    LaunchedEffect(section) {
        if (section != AppSection.Settings) {
            settingsUnlocked = false
            pendingSettingsTab = null
        }
    }
    // MK.30.4 — external "open Settings on this tab" request, currently only
    // raised by the update notification. Keyed on the flow so a tap while the
    // shell is already running (onNewIntent) is honoured, not just cold start.
    // Deliberately does NOT bypass the parental gate below: needsSettingsGate
    // is evaluated from `section`, so a deep link into Settings still has to
    // clear the PIN like any other entry.
    val deepLinkTab by SettingsDeepLinkState.pendingTab.collectAsState()
    LaunchedEffect(deepLinkTab) {
        val tab = SettingsDeepLinkState.consume() ?: return@LaunchedEffect
        pendingSettingsTab = tab
        section = AppSection.Settings
    }
    val needsSettingsGate =
        section == AppSection.Settings &&
            parentalSettings.pinSet &&
            parentalSettings.requirePinForSettings &&
            !settingsUnlocked

    // Initial focus request. Without this, a user landing on any section
    // has to press a random d-pad key before the focus manager wakes up.
    // For non-browse sections (Home / Guide / Favorites / Search / Settings)
    // mainContentFocus is the entry point. For browse sections (Live /
    // Movies / Series), focus into the CategoryRail is owned by
    // BrowseSection's PlacedFocusAnchor — we just flip panelFocus to
    // Categories and that effect lands focus on the active pill.
    val mainContentFocus = remember { FocusRequester() }
    // coverflowFocus moved INTO BrowseSection so it's re-created per type
    // swap (BrowseSection is wrapped in key(contentType) below). Sharing a
    // single FocusRequester across types meant requestFocus() targeted the
    // previous type's now-unmounted leftmost orb; the press silently
    // landed on no node, contributing to the "Movies retains Live TV
    // category" symptom because the cascade transition completed without
    // ever giving focus to the new section's coverflow.
    // Cascade focus state. Sidebar full-width when focused; collapses to
    // icon-only otherwise. Categories rail mounts only when focus is in
    // the sidebar or the rail itself — content takes the floor when it
    // owns focus. Hoisted above the section LaunchedEffect so that effect
    // can flip panelFocus → Categories on browse-section entry.
    // Persisted via rememberSaveable so process death keeps the user
    // where they were.
    var panelFocus by rememberSaveable { mutableStateOf(PanelFocus.Sidebar) }
    // MB-89: track the last section we focused so a same-section recomposition
    // (e.g. favorites flow update) doesn't yank focus back to main content.
    // MK.28.4 (MB-261) — saveable: after a recreation a plain-remember null
    // made this effect re-fire and overwrite the rememberSaveable-restored
    // panelFocus, contradicting the persistence comment above. Restored
    // state now stays put; the first D-pad press (TV) or tap (phone) wakes
    // focus, and the window-regain handlers cover the player round-trip.
    var lastFocusedSection by rememberSaveable { mutableStateOf<AppSection?>(null) }
    LaunchedEffect(section) {
        if (section == lastFocusedSection) return@LaunchedEffect
        lastFocusedSection = section
        // MK.22.A.1 (MB-221): the old code slept 120 ms here before
        // flipping panelFocus, gating the sidebar's expand-on-focus from
        // re-firing for ~120 ms after the user pressed BACK / LEFT to
        // return. The original justification ("wait for the previous
        // section's composable to leave") is obsolete — section content
        // is wrapped in `key()` so the new composition is ready by the
        // next frame. One `withFrameNanos` gives the new tree one
        // layout pass; that's all `mainContentFocus.requestFocus()`
        // needs to land on a placed node.
        withFrameNanos { }
        // Browse sections enter via the CategoryRail so the user lands on
        // a pill (one press away from the coverflow). Flipping panelFocus
        // to Categories lets BrowseSection's PlacedFocusAnchor land focus
        // on the actively-selected pill once it's placed in composition.
        // Non-browse sections enter via mainContentFocus on their own Box.
        if (contentType != null) {
            panelFocus = PanelFocus.Categories
        } else {
            panelFocus = PanelFocus.Content
            runCatching { mainContentFocus.requestFocus() }
        }
    }

    // Sidebar focus + tracking. BACK from any non-sidebar zone (detail,
    // chips on "All", non-browse content) returns focus here; BACK while
    // the sidebar itself has focus falls through to the system default
    // (exit). `sidebarHasFocus` gates the escape handlers so they don't
    // fire while the user is already on the sidebar.
    val sidebarFocus = remember { FocusRequester() }
    var sidebarHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(sidebarHasFocus) {
        if (sidebarHasFocus) panelFocus = PanelFocus.Sidebar
    }

    // Re-focus content when the detail overlay is dismissed. Compose
    // doesn't automatically reassign focus to the underlying rail / chip
    // bar when a focusGroup leaves composition, so the selector would
    // otherwise go dark until the user presses a d-pad key. Fires only
    // on the open→closed transition to avoid grabbing focus at startup.
    //
    // Audit catch — was `delay(80) + requestFocus()`, the exact
    // anti-pattern the MK skill bans ("PlacedFocusAnchor is the only
    // safe focus-on-open primitive; delay+requestFocus is a known
    // race"). On Fire TV with a cold detail-close the Box hosting
    // mainContentFocus may not be re-placed within 80 ms, so the
    // requestFocus() lands on an unplaced node and runCatching
    // swallows the throw — the symptom the LaunchedEffect was meant
    // to fix. Mirror the section-change pattern at line ~195:
    // `withFrameNanos { }` waits for *actual* placement before the
    // focus request — one layout pass is enough now that the
    // ContentDetail Box always remounts the focusGroup on close.
    var prevDetailOpen by remember { mutableStateOf(false) }
    LaunchedEffect(detailItem) {
        val isOpen = detailItem != null
        val justClosed = prevDetailOpen && !isOpen
        prevDetailOpen = isOpen
        if (justClosed) {
            withFrameNanos { }
            runCatching { mainContentFocus.requestFocus() }
        }
    }

    // Top-level escape for non-browse sections. BrowseShell handles its
    // own back chain (rail → chips → sidebar); this handler covers the
    // sections that don't use BrowseShell (Home / Guide / Favorites /
    // Search / Settings): any BACK while content has focus returns to
    // the sidebar, and a further BACK on the sidebar exits.
    BackHandler(
        enabled =
        !sidebarHasFocus &&
            detailItem == null &&
            !searchOverlayVisible &&
            contentType == null,
    ) {
        runCatching { sidebarFocus.requestFocus() }
    }

    val onBrowseActivate = fun(list: List<ContentItem>, idx: Int) {
        val target = list.getOrNull(idx) ?: return
        gatedPlay(target.id) {
            when (target.type) {
                ContentType.LIVE -> {
                    // Single-tap to fullscreen. The rail already auto-previews
                    // the focused channel in the hero MiniPlayer (see
                    // BrowseShell), so the first OK press no longer has to
                    // start the stream — it just escalates the running
                    // preview to fullscreen. If the user hits OK faster than
                    // the 400ms auto-preview debounce, start the stream here
                    // so fullscreen doesn't open on a stale channel.
                    val alreadyPlaying = controller.currentId == target.id
                    if (!alreadyPlaying) controller.play(list, idx)
                    PlayerLauncher.launch(context)
                }
                ContentType.MOVIE, ContentType.SERIES -> {
                    openDetail(target)
                }
            }
        }
    }

    // MK.29.3 — preview-pane Watch. Same parental gate and same
    // already-playing guard as onBrowseActivate's LIVE branch (hard rule 8:
    // re-calling play() on the running item would re-prepare the MediaItem
    // and rebuffer); it just skips the detail page for movies.
    val onBrowsePlayNow = fun(list: List<ContentItem>, idx: Int) {
        val target = list.getOrNull(idx) ?: return
        gatedPlay(target.id) {
            if (controller.currentId != target.id) controller.play(list, idx)
            PlayerLauncher.launch(context)
        }
    }

    // MK.37.H — the branded splash covers the first read of the source list and
    // nothing more. Gated on real work rather than a timer: a splash held open
    // by `delay()` costs the viewer time on every launch. `runCatching` means
    // the gate releases whether the read returns or throws — it can never stick
    // the app on a logo.
    var shellReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { sources.getAll() } }
        shellReady = true
    }

    // MK.37.A — the shell measures its window once, here, and publishes the
    // result. Nothing reads it yet: this slice adds the layer and the rotation
    // unlock and changes no rendering, so the television is byte-for-byte what
    // it was. Screens adopt `LocalShellMetrics` one at a time from MK.37.B,
    // each with its own TV pass.
    CompositionLocalProvider(LocalShellMetrics provides rememberShellMetrics()) {
    if (!shellReady) {
        BrandSplash()
        return@CompositionLocalProvider
    }
    val shellMetrics = LocalShellMetrics.current
    val usesSidebar = shellMetrics.usesSidebar
    var showOverflow by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        CinematicBackground(modifier = Modifier.fillMaxSize())
        // MK.37.B — the existing Row is untouched and simply gains a Column
        // around it, so the television takes a single weighted child and lays
        // out exactly as before. On a phone in portrait the rail is dropped and
        // the bar is appended; nothing in between changes.
        Column(modifier = Modifier.fillMaxSize()) {
        // MK.28.1 — the background above stays full-bleed under the
        // transparent system bars; every interactive child is inset by
        // safeDrawing (system bars + display cutout + IME). Zero on TV.
        //
        // MK.37.B — when the bar is present it owns the BOTTOM inset, so the
        // content above must not consume it too; otherwise the bar floats above
        // a stripe of background instead of running under the gesture bar.
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .windowInsetsPadding(
                    if (usesSidebar) {
                        WindowInsets.safeDrawing
                    } else {
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                        )
                    },
                ),
        ) {
            if (usesSidebar) {
            AppSidebar(
                current = section,
                onSelect = { newSection ->
                    // Click on a sidebar tab does TWO things:
                    //   1. Switch section (no-op if same).
                    //   2. Always navigate INTO the section — clicking the
                    //      current tab while focused on sidebar must still
                    //      pull focus to that section's content. Without
                    //      step 2, clicking your already-active tab is a
                    //      dead press, which is what the user hit.
                    // For browse sections, BrowseSection's PlacedFocusAnchor
                    // lands focus on the active pill once it's placed.
                    section = newSection
                    // Guide also has a CategoryRail (MK.guide.groups), so
                    // treat it like the browse sections: forward into
                    // Categories, not Content. GuideScreen's
                    // LaunchedEffect(panelFocus) lands focus on the rail.
                    if (newSection.contentType != null || newSection == AppSection.Guide) {
                        panelFocus = PanelFocus.Categories
                    } else {
                        panelFocus = PanelFocus.Content
                        // One frame so the section's content composable has
                        // mounted before we try to focus it (esp. when
                        // section actually changed and the old composable
                        // just left composition).
                        homeScope.launch {
                            withFrameNanos { }
                            runCatching { mainContentFocus.requestFocus() }
                        }
                    }
                },
                // Direct focus binding: expanded ⇔ sidebar has focus.
                // The previous binding routed through `panelFocus`, which
                // had to be kept in sync from multiple call sites
                // (LaunchedEffects + onSelect + onMoveRight + browse-section
                // callbacks). Any path that didn't update panelFocus left
                // the sidebar at the wrong width — most visibly when
                // returning from a content pane via BACK / LEFT, where the
                // focus animation finished but the panelFocus update lagged.
                // Tying width straight to `sidebarHasFocus` makes the
                // contract obvious: focus enters → expand; focus leaves →
                // collapse. The `panelFocus` state is still used elsewhere
                // (categories/content gating in browse + guide), so we keep
                // it but stop using it as the width signal.
                expanded = sidebarHasFocus,
                onMoveRight = {
                    // RIGHT mirrors click — both navigate into the section.
                    // Browse sections (Live/Movies/Series) advance to the
                    // categories rail; non-browse sections jump straight
                    // into their content. The sidebar collapses to icon-only
                    // because expanded = (panelFocus == Sidebar).
                    if (contentType != null || section == AppSection.Guide) {
                        panelFocus = PanelFocus.Categories
                    } else {
                        panelFocus = PanelFocus.Content
                        homeScope.launch {
                            withFrameNanos { }
                            runCatching { mainContentFocus.requestFocus() }
                        }
                    }
                },
                // MB-106: requester binds to the active SidebarRow inside
                // AppSidebar (not the wrapper Column) so BACK / detail-close
                // / onExitToSidebar land focus on the row directly — no
                // need for the user to nudge the D-pad to "wake up" the
                // selector. focusRestorer on the wrapper kept misfiring
                // because requestFocus on the wrapper required a child
                // discovery pass before the row's interactionSource flipped.
                activeRowFocus = sidebarFocus,
                modifier = Modifier.onFocusChanged { sidebarHasFocus = it.hasFocus },
            )
            }

            if (contentType != null) {
                // Concept A — Live TV / Movies / Series share the cascading
                // sidebar→categories→content shell. CategoryRail (vertical
                // hex pills) drives selection; the coverflow + preview pane
                // live in CoverflowSectionScreen.
                //
                // key(contentType) forces a complete unmount/remount on
                // every type swap. All `remember` / `rememberSaveable` /
                // PlacedFocusAnchor / FocusRequester instances inside
                // BrowseSection are guaranteed fresh — no anchor reused
                // across types (was firing requestFocus on the wrong
                // node), no stale selectedGroup leaking, no items list
                // bleeding the previous type's content into the new
                // section's coverflow during the recompose window.
                key(contentType) {
                    BrowseSection(
                        type = contentType,
                        panelFocus = panelFocus,
                        onPanelFocusChanged = { panelFocus = it },
                        onActivate = onBrowseActivate,
                        onPlayNow = onBrowsePlayNow,
                        onExitToSidebar = { runCatching { sidebarFocus.requestFocus() } },
                        restoreFocusOnWindowRegain =
                        detailItem == null && !searchOverlayVisible && pendingPlay == null,
                        // Same path as HomeContent.onAddSource — surfaces
                        // when the coverflow empty pane is rendered.
                        onAddSource = {
                            pendingSettingsTab = com.yancotv.android.ui.settings.SettingsTab.Sources
                            section = AppSection.Settings
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else if (section == AppSection.Settings) {
                // MB-111: .focusGroup() makes each non-browse content Box a
                // deterministic descendant-focus search root. Without it,
                // `mainContentFocus.requestFocus()` on a bare wrapper Box can
                // silently fail on Compose 1.7 when the first focusable child
                // isn't placed yet — the search bails instead of waiting. The
                // group flag tells Compose "this wrapper owns focus search for
                // its subtree" so the request reliably forwards to the next
                // focusable child once placed. Same pattern repeats below for
                // Guide / Favorites / Search / Home.
                Box(modifier = Modifier.weight(1f).focusRequester(mainContentFocus).focusGroup()) {
                    if (needsSettingsGate) {
                        SettingsLockedPlaceholder()
                    } else {
                        // Audit catch — when the user has zero sources
                        // configured and opens Settings (via sidebar or
                        // any path other than the EmptyHome CTA), default
                        // to Sources rather than General. Sources is the
                        // one tab they actually need to make the app
                        // useful. Returning users with ≥1 source still
                        // land on General. The EmptyHome CTA path
                        // already wins via pendingSettingsTab.
                        val hasSources by remember {
                            sources.allFlow()
                                .map { it.isNotEmpty() }
                                .catch { emit(true) }
                        }.collectAsState(initial = true)
                        SettingsScreen(
                            initialTab = pendingSettingsTab
                                ?: if (hasSources) {
                                    com.yancotv.android.ui.settings.SettingsTab.General
                                } else {
                                    com.yancotv.android.ui.settings.SettingsTab.Sources
                                },
                            onExitToMainSidebar = {
                                runCatching { sidebarFocus.requestFocus() }
                            },
                        )
                    }
                }
            } else if (section == AppSection.Guide) {
                Box(modifier = Modifier.weight(1f).focusRequester(mainContentFocus).focusGroup()) {
                    GuideScreen(
                        panelFocus = panelFocus,
                        onPanelFocusChanged = { panelFocus = it },
                        onExitToSidebar = { runCatching { sidebarFocus.requestFocus() } },
                        onPlay = { channel, _ ->
                            val item = guideChannelToContentItem(channel) ?: return@GuideScreen
                            gatedPlay(item.id) {
                                if (controller.currentId != item.id) {
                                    controller.play(listOf(item), 0)
                                }
                                PlayerLauncher.launch(context)
                            }
                        },
                        onPlayCatchup = { item ->
                            val underlying = item.id.removePrefix("catchup:").substringBefore(':')
                            gatedPlay(underlying) {
                                if (controller.currentId != item.id) {
                                    controller.play(listOf(item), 0)
                                }
                                PlayerLauncher.launch(context)
                            }
                        },
                    )
                }
            } else if (section == AppSection.Favorites) {
                Box(modifier = Modifier.weight(1f).focusRequester(mainContentFocus).focusGroup()) {
                    FavoritesScreen(
                        isTv = isTv,
                        onOpenDetail = { item -> openDetail(item) },
                    )
                }
            } else if (section == AppSection.Recordings) {
                Box(modifier = Modifier.weight(1f).focusRequester(mainContentFocus).focusGroup()) {
                    com.yancotv.android.ui.shell.RecordingsScreen(isTv = isTv)
                }
            } else if (section == AppSection.Search) {
                Box(modifier = Modifier.weight(1f).focusRequester(mainContentFocus).focusGroup()) {
                    SearchScreen(isTv = isTv, onShowDetail = { openDetail(it) })
                }
            } else if (section == AppSection.Home) {
                Box(modifier = Modifier.weight(1f).focusRequester(mainContentFocus).focusGroup()) {
                    HomeContent(
                        // MK.32.4 — Empty-Home Quick Start CTA. Switches
                        // the active section to Settings and asks the
                        // shell to open Settings on the Sources tab
                        // (instead of the default General).
                        onAddSource = {
                            pendingSettingsTab = com.yancotv.android.ui.settings.SettingsTab.Sources
                            section = AppSection.Settings
                        },
                        onPlay = { list, idx, resumeEpisodeId ->
                            val target = list.getOrNull(idx) ?: return@HomeContent
                            gatedPlay(target.id) {
                                when (target.type) {
                                    ContentType.LIVE, ContentType.MOVIE -> {
                                        if (controller.currentId != target.id) controller.play(list, idx)
                                        PlayerLauncher.launch(context)
                                    }
                                    ContentType.SERIES -> {
                                        // Continue Watching path. The episode the
                                        // user gets depends on the most-recent
                                        // watch_history row's state:
                                        //   - No history → open detail page so the
                                        //     user can pick (matches "first visit"
                                        //     UX).
                                        //   - Mid-stream → resume that episode at
                                        //     its stored offset.
                                        //   - Finished (≥95% per `isFinished()`) →
                                        //     advance to the next episode in the
                                        //     series. If no next episode (end of
                                        //     series), restart the last one from 0.
                                        // The "finished → next" routing is the
                                        // Netflix-style behavior; without it,
                                        // tapping a binge-watched series re-plays
                                        // the just-finished episode, which is what
                                        // the loop bug was masking.
                                        if (resumeEpisodeId == null) {
                                            openDetail(target)
                                            return@gatedPlay
                                        }
                                        homeScope.launch(Dispatchers.IO) {
                                            val resumeInfo =
                                                runCatching {
                                                    history.mostRecentEpisode(target.id)
                                                }.getOrNull()
                                            val (episode, fromStart) =
                                                if (resumeInfo != null && resumeInfo.isFinished()) {
                                                    val next =
                                                        runCatching {
                                                            repo.nextEpisodeAfter(
                                                                seriesId = target.id,
                                                                currentEpisodeId = resumeInfo.episodeId,
                                                            )
                                                        }.getOrNull()
                                                    if (next != null) {
                                                        next to false
                                                    } else {
                                                        // End of series — restart the last
                                                        // watched episode from 0. positionForEpisode
                                                        // returns null on finished rows so the
                                                        // setMediaItem path already skips the
                                                        // seek; fromStart=true is belt-and-
                                                        // suspenders.
                                                        val last =
                                                            runCatching {
                                                                repo.episodeById(resumeInfo.episodeId)
                                                            }.getOrNull()
                                                        last to true
                                                    }
                                                } else {
                                                    val ep =
                                                        runCatching {
                                                            repo.episodeById(resumeEpisodeId)
                                                        }.getOrNull()
                                                    ep to false
                                                }
                                            val playable = episode?.toPlayable(target)
                                            withContext(Dispatchers.Main) {
                                                if (playable == null) {
                                                    openDetail(target)
                                                } else {
                                                    // Always go through controller.play —
                                                    // its SameTarget branch is a cheap
                                                    // unpause-only no-rebuffer path now
                                                    // (see PlaybackController.play).
                                                    // The prior `if (currentId != target)`
                                                    // guard meant tapping Continue Watching
                                                    // on the same episode that was just
                                                    // paused did NOTHING — the launch fired
                                                    // but the player stayed paused.
                                                    controller.play(playable, fromStart = fromStart)
                                                    PlayerLauncher.launch(context)
                                                }
                                            }
                                        }
                                    }
                                }
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

        if (!usesSidebar) {
            SectionFlowBar(
                current = section,
                onSelect = { section = it },
                onOpenOverflow = { showOverflow = true },
            )
        }
        }

        // Search overlay — rides above the Row so it dims everything.
        // Audit-pass-1: `.clickable` on the scrim and the inner Box was
        // creating two extra focusable D-pad targets on TV (same root
        // cause the legacy PlayerOptionsSheet documented). Touch dismiss
        // now goes through `pointerInput { detectTapGestures }` so the
        // scrim doesn't register as a focus target — D-pad navigation
        // reaches the actual SearchScreen widgets directly.
        if (searchOverlayVisible) {
            Box(
                modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .pointerInput(Unit) {
                        detectTapGestures { SearchOverlayState.hide() }
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier =
                    Modifier
                        .fillMaxWidth(if (isTv) 0.6f else 1f)
                        .fillMaxHeight()
                        .background(LocalYancoPalette.current.BackgroundDeep)
                        // MK.28.1 — panel background full-bleed, content inset
                        // (order matters: padding after background).
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        // MK.28.5 (MB-262) — trap D-pad focus inside the
                        // overlay (SeasonPickerOverlay pattern). Without it,
                        // DOWN from an empty result list / LEFT from the
                        // leftmost orb escaped to the dimmed shell behind the
                        // scrim: CENTER then activated invisible controls and
                        // the shell's BackHandlers (registered later = higher
                        // priority) ate BACK so the overlay looked stuck.
                        .focusGroup()
                        .focusProperties { exit = { FocusRequester.Cancel } }
                        // Eat tap so the outer scrim's dismiss
                        // doesn't fire when the user taps inside the
                        // search panel itself. Empty handler is
                        // enough — touch consumed by pointerInput.
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    SearchScreen(isTv = isTv, onShowDetail = { openDetail(it) })
                }
            }
        }

        // Movie / series detail overlay.
        //
        // Detail is NOT dismissed when the player launches — the overlay
        // lives behind PlayerActivity so BACK from the player returns the
        // user to the episodes page / movie detail, then another BACK
        // dismisses detail and drops back onto the rail. Matches the
        // hierarchical BACK chain: Player → Detail → Rail → Chips → Sidebar.
        detailItem?.let { item ->
            ContentDetailScreen(
                item = item,
                onPlayContent = { target ->
                    // Series containers have no playable stream — they reach
                    // this branch only as a last-ditch fallback (detail's
                    // HeroBlock routes to onPlayEpisode when episodes exist).
                    // Blank-URL short-circuit: stay on detail, no dead player.
                    if (target.streamUrl.isNotBlank() && target.type != ContentType.SERIES) {
                        if (controller.currentId != target.id) {
                            controller.play(listOf(target), 0)
                        }
                        PlayerLauncher.launch(context)
                    }
                },
                onPlayEpisode = { target, ep ->
                    // Type-safe episode play via the Playable sealed type.
                    // toPlayable() returns null for blank stream URLs.
                    // PlaybackController.play(Playable.Episode) writes
                    // watch_history with content_id = seriesId (the FK
                    // target — series rows live in `content`, episodes
                    // don't) and episode_id = ep.id, so each episode gets
                    // its own resume key without violating the FK.
                    //
                    // Always call controller.play — even when the player
                    // already has this episode loaded (same-id). The
                    // SameTarget branch in PlaybackController is now a
                    // cheap unpause-only path. The prior `if (currentId !=
                    // ep.id)` guard meant resuming the same paused episode
                    // did nothing.
                    val playable = ep.toPlayable(target)
                    if (playable != null) {
                        controller.play(playable)
                        PlayerLauncher.launch(context)
                    }
                },
                onPlayFromStart = { target, ep ->
                    // "Play from beginning" — never honour the stored
                    // resume offset, but also don't delete the row so a
                    // brief restart-then-exit preserves the prior
                    // mid-stream position. The fromStart flag on
                    // controller.play tells loadCurrent to skip the
                    // positionFor / positionForEpisode lookup entirely.
                    if (ep != null) {
                        val playable = ep.toPlayable(target)
                        if (playable != null) {
                            controller.play(playable, fromStart = true)
                            PlayerLauncher.launch(context)
                        }
                    } else if (target.streamUrl.isNotBlank() && target.type != ContentType.SERIES) {
                        controller.play(listOf(target), 0, fromStart = true)
                        PlayerLauncher.launch(context)
                    }
                },
                onResetProgress = { _ ->
                    // No-op at the parent level — the detail screen
                    // already executed the wipe via watchHistory.removeForContent
                    // before calling back. Hook kept on the callback
                    // surface so callers that want to react (snackbar,
                    // analytics) have one place to do it.
                },
                onDismiss = { openDetail(null) },
            )
        }

        pendingPlay?.let { action ->
            PinEntryDialog(
                title = stringResource(R.string.fav_channel_locked),
                body = stringResource(R.string.fav_channel_locked_body),
                repo = parental,
                onSuccess = {
                    action()
                    pendingPlay = null
                },
                onDismiss = { pendingPlay = null },
            )
        }

        if (needsSettingsGate) {
            PinEntryDialog(
                title = stringResource(R.string.pin_required),
                body = stringResource(R.string.pin_required_settings),
                repo = parental,
                onSuccess = { settingsUnlocked = true },
                onDismiss = { section = AppSection.LiveTv },
            )
        }
    }
    }
}

@Composable
private fun SettingsLockedPlaceholder() {
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(LocalYancoPalette.current.BackgroundDeep),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.pin_gate_settings),
            color = LocalYancoPalette.current.TextMuted,
        )
    }
}

/**
 * Fabricate a [ContentItem] for the player queue from an [EpgGuideChannel].
 * The real content row may have richer metadata but the player only needs
 * streamUrl + identity; fetching the backing row would force a tvgId →
 * content lookup that doesn't exist yet.
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

@Composable
private fun PlaceholderArea(section: AppSection) {
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(LocalYancoPalette.current.BackgroundDeep),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.section_placeholder, stringResource(section.labelRes)),
            color = LocalYancoPalette.current.TextMuted,
        )
    }
}
