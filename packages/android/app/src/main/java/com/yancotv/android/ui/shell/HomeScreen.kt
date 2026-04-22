package com.yancotv.android.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.player.PlayerLauncher
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.OpenOn
import com.yancotv.android.ui.components.CinematicBackground
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
    val openOn = remember { prefs.generalSnapshot().openOn }
    val initialSection = remember(openOn) {
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
        if (controller.currentId != null) { autoplayAttempted = true; return@LaunchedEffect }
        val entry = withContext(Dispatchers.IO) {
            runCatching { history.recent(limit = 1).firstOrNull() }.getOrNull()
        }
        autoplayAttempted = true
        val item = entry?.content ?: return@LaunchedEffect
        if (item.id in parental.lockedIds.value) return@LaunchedEffect
        controller.play(listOf(item), 0)
    }

    val contentType = section.contentType
    val context = LocalContext.current
    val searchOverlayVisible by SearchOverlayState.visible.collectAsState()

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
    var detailItem by remember { mutableStateOf<ContentItem?>(null) }
    BackHandler(enabled = detailItem != null) { detailItem = null }

    // "Require PIN for Settings" gate.
    var settingsUnlocked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(section) {
        if (section != AppSection.Settings) settingsUnlocked = false
    }
    val needsSettingsGate =
        section == AppSection.Settings &&
            parentalSettings.pinSet &&
            parentalSettings.requirePinForSettings &&
            !settingsUnlocked

    // Initial focus request. Without this, a user landing on any section
    // has to press a random d-pad key before the focus manager wakes up.
    val mainContentFocus = remember { FocusRequester() }
    LaunchedEffect(section) {
        delay(120)
        runCatching { mainContentFocus.requestFocus() }
    }

    val onBrowseActivate = fun(list: List<ContentItem>, idx: Int) {
        val target = list.getOrNull(idx) ?: return
        gatedPlay(target.id) {
            when (target.type) {
                ContentType.LIVE -> {
                    // Two-tap activation: first OK plays in preview, second
                    // OK on the same channel opens fullscreen without a
                    // rebuffer. Phone skips the preview since it has no
                    // dedicated preview surface.
                    val alreadyPlaying = controller.currentId == target.id
                    if (!alreadyPlaying) controller.play(list, idx)
                    if (!isTv || alreadyPlaying) {
                        PlayerLauncher.launch(context)
                    }
                }
                ContentType.MOVIE, ContentType.SERIES -> {
                    detailItem = target
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CinematicBackground(modifier = Modifier.fillMaxSize())
        Row(modifier = Modifier.fillMaxSize()) {
            AppSidebar(
                current = section,
                onSelect = { section = it },
            )

            if (contentType != null) {
                BrowseShell(
                    type = contentType,
                    repo = repo,
                    onActivate = onBrowseActivate,
                    onChipsFocusChanged = { /* unused — shell is flat now */ },
                    onRailFocusChanged = { /* unused */ },
                    railFocus = mainContentFocus,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            } else if (section == AppSection.Settings) {
                Box(modifier = Modifier.weight(1f).focusRequester(mainContentFocus)) {
                    if (needsSettingsGate) {
                        SettingsLockedPlaceholder()
                    } else {
                        SettingsScreen()
                    }
                }
            } else if (section == AppSection.Guide) {
                Box(modifier = Modifier.weight(1f).focusRequester(mainContentFocus)) {
                    GuideScreen(
                        onPlay = { channel, _ ->
                            val item = guideChannelToContentItem(channel) ?: return@GuideScreen
                            gatedPlay(item.id) {
                                controller.play(listOf(item), 0)
                                PlayerLauncher.launch(context)
                            }
                        },
                        onPlayCatchup = { item ->
                            val underlying = item.id.removePrefix("catchup:").substringBefore(':')
                            gatedPlay(underlying) {
                                controller.play(listOf(item), 0)
                                PlayerLauncher.launch(context)
                            }
                        },
                    )
                }
            } else if (section == AppSection.Favorites) {
                Box(modifier = Modifier.weight(1f).focusRequester(mainContentFocus)) {
                    FavoritesScreen(isTv = isTv)
                }
            } else if (section == AppSection.Search) {
                Box(modifier = Modifier.weight(1f).focusRequester(mainContentFocus)) {
                    SearchScreen(isTv = isTv)
                }
            } else if (section == AppSection.Home) {
                Box(modifier = Modifier.weight(1f).focusRequester(mainContentFocus)) {
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

        // Search overlay — rides above the Row so it dims everything.
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
                            onClick = { /* swallow */ },
                        ),
                ) {
                    SearchScreen(isTv = isTv)
                }
            }
        }

        // Movie / series detail overlay.
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
        modifier = Modifier
            .fillMaxSize()
            .background(YancoPalette.BackgroundDeep),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${section.label} — coming in a later milestone.",
            color = YancoPalette.TextMuted,
        )
    }
}
