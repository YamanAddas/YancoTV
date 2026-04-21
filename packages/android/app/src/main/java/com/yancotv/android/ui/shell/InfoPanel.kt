package com.yancotv.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.types.ContentItem
import androidx.compose.runtime.collectAsState
import org.koin.compose.koinInject

/**
 * Right-side info strip. MK.6 docks the mini preview at the top; when the
 * queue is empty the slot collapses. Now/next EPG + rich metadata land in
 * MK.7 and MK.14.
 */
@OptIn(UnstableApi::class)
@Composable
fun InfoPanel(
    item: ContentItem?,
    modifier: Modifier = Modifier,
    controller: PlaybackController = koinInject(),
) {
    val playing by controller.currentItem.collectAsState()

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(YancoPalette.BackgroundRaised)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (playing != null) {
            MiniPlayer(controller = controller)
            Spacer(Modifier.height(4.dp))
            Text(text = "Now playing", color = YancoPalette.TextMuted)
            Text(
                text = playing?.cleanTitle?.ifBlank { null } ?: playing?.title.orEmpty(),
                color = YancoPalette.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (item == null) {
            Text(text = "No selection", color = YancoPalette.TextMuted)
            return@Column
        }
        Text(text = item.title, color = YancoPalette.TextPrimary)
        item.groupName?.let { Text(text = it, color = YancoPalette.TextMuted) }
        Text(text = "Source: ${item.sourceId}", color = YancoPalette.TextMuted)
    }
}
