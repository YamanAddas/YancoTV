package com.yancotv.android.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.yancotv.android.R
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import com.yancotv.shared.types.NowNext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * TiviMate-style channel-surf overlay. Slides in from the right while the
 * user watches a channel; picking a row switches the stream without closing
 * the player. The video underneath keeps playing the whole time so there's
 * no rebuffer just from browsing.
 *
 * Keeps its own local list + focused index — independent of the main
 * shell's state so switching channels here doesn't affect what the shell
 * shows when the user returns to it.
 */
@Composable
fun ChannelSurfOverlay(
    currentContentId: String?,
    onPick: (List<ContentItem>, Int) -> Unit,
    onIdleDismiss: () -> Unit = {},
    repo: ContentRepository = koinInject(),
    epg: EpgRepository = koinInject(),
    prefs: AppPreferences = koinInject(),
) {
    var items by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var nowNext by remember { mutableStateOf<Map<String, NowNext>>(emptyMap()) }
    val general by prefs.generalFlow.collectAsState()

    // Load a sensible page of live channels once on open. 200 is enough to
    // cover most personal lists without paying a paginated-load cost while
    // the overlay is open — surf sessions are typically a handful of rows.
    LaunchedEffect(Unit) {
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching { repo.page(ContentType.LIVE, null, 0L, 200L) }
                    .getOrElse { emptyList() }
            }
        items = loaded
        val tvgIds = loaded.mapNotNull { it.tvgId?.takeIf { id -> id.isNotBlank() } }
        if (tvgIds.isNotEmpty()) {
            nowNext =
                withContext(Dispatchers.IO) {
                    runCatching { epg.getNowNextBatch(tvgIds) }.getOrElse { emptyMap() }
                }
        }
    }

    // Seed focus on the currently-playing row so the user doesn't have to
    // scroll back to their own channel after opening the surf overlay.
    val initialIndex =
        remember(items, currentContentId) {
            items.indexOfFirst { it.id == currentContentId }.takeIf { it >= 0 } ?: 0
        }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex.coerceAtLeast(0))

    // MK.10.4 — auto-dismiss after IDLE_TIMEOUT_MS of no scroll. Tracking
    // listState.firstVisibleItemIndex covers D-pad up/down navigation
    // (each step moves the visible window). Bumps reset the timer; when
    // it fires, we hand off to onIdleDismiss so the activity can hide
    // the overlay and put focus back on the player. TiviMate dismisses
    // at ~5s.
    var bumpTick by remember { mutableStateOf(0) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { bumpTick++ }
    }
    LaunchedEffect(bumpTick) {
        delay(IDLE_TIMEOUT_MS)
        onIdleDismiss()
    }

    Column(
        modifier =
        Modifier
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.cs_channels),
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.cs_none),
                color = LocalYancoPalette.current.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(horizontal = 12.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { idx, item ->
                val nn = item.tvgId?.let { nowNext[it] }
                SurfRow(
                    item = item,
                    nowTitle = nn?.now?.title,
                    nowStartSec = nn?.now?.startTime,
                    nowEndSec = nn?.now?.endTime,
                    channelNumberPrefix =
                    if (general.showChannelNumbers) {
                        general.channelNumberFormat.format(item.sortOrder.takeIf { it > 0 })
                            .takeIf { it.isNotBlank() }
                    } else {
                        null
                    },
                    playing = item.id == currentContentId,
                    autoFocus = idx == initialIndex,
                    onClick = { onPick(items, idx) },
                )
            }
        }
        Text(
            text = stringResource(R.string.cs_back_to_close),
            color = LocalYancoPalette.current.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
    // Back press semantics handled at Activity level — no composable
    // BackHandler so the shell's back doesn't fight with Media3's.
}

/** MK.10.4 — TiviMate-style auto-dismiss after no scroll for this long. */
private const val IDLE_TIMEOUT_MS: Long = 5_000L

@Composable
private fun SurfRow(
    item: ContentItem,
    nowTitle: String?,
    nowStartSec: Long?,
    nowEndSec: Long?,
    channelNumberPrefix: String?,
    playing: Boolean,
    autoFocus: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg =
        when {
            focused -> LocalYancoPalette.current.Accent.copy(alpha = 0.28f)
            playing -> LocalYancoPalette.current.BackgroundRaised
            else -> Color.Transparent
        }
    val border = if (focused) LocalYancoPalette.current.FocusRing else Color.Transparent
    val title = item.displayTitle

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focusRequester.requestFocus() } }

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .focusRequester(focusRequester)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(LocalYancoPalette.current.BackgroundDeep),
            contentAlignment = Alignment.Center,
        ) {
            val surfLogo = item.displayLogoUrl
            if (!surfLogo.isNullOrBlank()) {
                AsyncImage(
                    model = surfLogo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().padding(2.dp),
                )
            } else {
                Text(
                    text = title.take(2),
                    color = LocalYancoPalette.current.TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!channelNumberPrefix.isNullOrBlank()) {
                    Text(
                        text = channelNumberPrefix,
                        color = LocalYancoPalette.current.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Text(
                    text = title,
                    color = LocalYancoPalette.current.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = if (playing) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!nowTitle.isNullOrBlank()) {
                Text(
                    text = nowTitle,
                    color = LocalYancoPalette.current.TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // MK.10.4 — now-progress bar. Shows how much of the live
            // programme has aired. Skipped when EPG times are missing
            // or the row isn't currently airing anything.
            val progress =
                remember(nowStartSec, nowEndSec) {
                    val s = nowStartSec ?: return@remember null
                    val e = nowEndSec ?: return@remember null
                    if (e <= s) return@remember null
                    val now = System.currentTimeMillis() / 1000L
                    ((now - s).toFloat() / (e - s).toFloat()).coerceIn(0f, 1f)
                }
            if (progress != null) {
                Spacer(modifier = Modifier.height(3.dp))
                Box(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(LocalYancoPalette.current.BorderSubtle, RoundedCornerShape(1.dp)),
                ) {
                    Box(
                        modifier =
                        Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(LocalYancoPalette.current.Accent, RoundedCornerShape(1.dp)),
                    )
                }
            }
        }
        if (playing) {
            Spacer(modifier = Modifier.width(2.dp))
            Box(
                modifier =
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(LocalYancoPalette.current.Accent, RoundedCornerShape(2.dp)),
            )
        }
    }
}
