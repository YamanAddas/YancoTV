package com.yancotv.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.player.PlayerLauncher
import com.yancotv.android.ui.nav.AppSection
import com.yancotv.android.ui.settings.SettingsScreen
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.content.ContentRepository
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
) {
    var section by rememberSaveable { mutableStateOf(AppSection.LiveTv) }
    val contentType = section.contentType
    val context = LocalContext.current

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
                    // Two-tap activation on TV (TiviMate-style): first press
                    // starts the stream in the mini preview, second press on
                    // the same channel fullscreens with zero rebuffer. Phone
                    // skips the mini because the shell doesn't dedicate
                    // screen real-estate to InfoPanel — tap goes straight to
                    // the player.
                    val alreadyPlaying = controller.currentId == target.id
                    if (!alreadyPlaying) controller.play(list, idx)
                    if (!isTv || alreadyPlaying) {
                        PlayerLauncher.launch(context)
                    }
                },
            )
        } else if (section == AppSection.Settings) {
            Box(modifier = Modifier.weight(1f)) {
                SettingsScreen()
            }
        } else if (section == AppSection.Guide) {
            Box(modifier = Modifier.weight(1f)) {
                GuideScreen(
                    onPlay = { channel, _ ->
                        val item = guideChannelToContentItem(channel) ?: return@GuideScreen
                        controller.play(listOf(item), 0)
                        PlayerLauncher.launch(context)
                    },
                )
            }
        } else {
            Box(modifier = Modifier.weight(1f)) {
                PlaceholderArea(section = section)
            }
        }
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
