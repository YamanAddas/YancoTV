package com.yancotv.android.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.player.PlayerLauncher
import com.yancotv.android.ui.nav.AppSection
import com.yancotv.android.ui.parental.PinEntryDialog
import com.yancotv.android.ui.settings.SettingsScreen
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.content.ContentRepository
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
) {
    var section by rememberSaveable { mutableStateOf(AppSection.LiveTv) }
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

    Box(modifier = Modifier.fillMaxSize()) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(YancoPalette.BackgroundDeep),
    ) {
        AppSidebar(current = section, onSelect = { section = it })

        if (contentType != null) {
            ContentArea(
                isTv = isTv,
                type = contentType,
                repo = repo,
                onActivate = { list, idx ->
                    val target = list.getOrNull(idx) ?: return@ContentArea
                    gatedPlay(target.id) {
                        // Two-tap activation on TV (TiviMate-style): first
                        // press starts the stream in the mini preview,
                        // second press on the same channel fullscreens with
                        // zero rebuffer. Phone skips the mini because the
                        // shell doesn't dedicate screen real-estate to
                        // InfoPanel — tap goes straight to the player.
                        val alreadyPlaying = controller.currentId == target.id
                        if (!alreadyPlaying) controller.play(list, idx)
                        if (!isTv || alreadyPlaying) {
                            PlayerLauncher.launch(context)
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

@UnstableApi
@Composable
private fun RowScope.ContentArea(
    isTv: Boolean,
    type: ContentType,
    repo: ContentRepository,
    onActivate: (List<ContentItem>, Int) -> Unit,
) {
    val groupsState = remember(type) { mutableStateListOf<String>() }
    LaunchedEffect(type) {
        val loaded = withContext(Dispatchers.IO) { repo.groups(type) }
        groupsState.clear()
        groupsState.addAll(loaded)
    }

    var group by rememberSaveable(type) { mutableStateOf(ALL_GROUPS) }
    var focused by remember(type) { mutableStateOf<ContentItem?>(null) }
    val groupFilter = group.takeIf { it != ALL_GROUPS }

    CategoryFilterPanel(
        groups = groupsState.toList(),
        selected = group,
        onSelect = { group = it },
    )
    Box(modifier = Modifier.weight(1f)) {
        ContentPanel(
            type = type,
            group = groupFilter,
            onItemFocus = { focused = it },
            onItemActivate = onActivate,
        )
    }
    if (isTv) {
        InfoPanel(item = focused)
    }
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
