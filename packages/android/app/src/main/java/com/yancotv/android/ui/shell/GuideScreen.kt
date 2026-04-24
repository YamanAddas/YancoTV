package com.yancotv.android.ui.shell

import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.yancotv.android.reminders.ReminderScheduler
import com.yancotv.android.ui.parental.ChannelActionsMenu
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.catchup.CatchupService
import com.yancotv.shared.content.ContentRepository
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.parental.ParentalRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.EpgGuideChannel
import com.yancotv.shared.types.EpgGuideData
import com.yancotv.shared.types.EpgProgramme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

// Layout constants. 4 dp per minute = 24 dp per 10min = 120 dp per 30min =
// 1440 dp across the 6h window. Wide enough to read programme titles on
// typical TV panels (1080p ≈ 960 dp logical width, so ~1.5 screens scroll).
private const val PX_PER_MIN = 4
private val ROW_HEIGHT = 56.dp
private val HEADER_HEIGHT = 28.dp
private val CHANNEL_COL_WIDTH = 160.dp
private val MIN_PROG_WIDTH = 48.dp

// 6-hour window is the sweet spot: longer = tiny programme blocks that
// don't fit a title; shorter = user scrolls too often. TiviMate / desktop
// default matches.
private const val WINDOW_HOURS = 6L

// Recompute the red "now" line every minute. Programme blocks only redraw
// when the window slides — which happens on a coarser 30-min grain.
private const val NOW_TICK_MS = 60_000L

// Paged guide load: 100 channels per page, extend when the user scrolls
// within PREFETCH_THRESHOLD rows of the end. Tuned for Fire TV: a bigger
// page buys fewer DB round-trips but each one locks main for longer when
// the result is mapped to domain objects.
private const val GUIDE_PAGE_SIZE = 100L
private const val PREFETCH_THRESHOLD = 20

/**
 * 2D EPG guide: channels on the Y axis, time on the X axis. Horizontal
 * scroll is shared across the header and every channel row so the time
 * labels stay aligned.
 *
 * Limitations for MK.7.4:
 *  - Window doesn't slide automatically as hours pass; the user reopens
 *    Guide for a fresh 6h slice. Continuous sliding lands in MK.7.5 once
 *    the reminders workflow needs the same timeline.
 *  - D-pad focus navigation between programme blocks uses Compose's
 *    default focus finder, which is "good enough" but doesn't auto-scroll
 *    the window horizontally when you focus an off-screen block. Polishing
 *    that needs bringIntoViewRequester wiring — deferred.
 */
@UnstableApi
@Composable
fun GuideScreen(
    onPlay: (EpgGuideChannel, EpgProgramme?) -> Unit,
    onPlayCatchup: (ContentItem) -> Unit,
    modifier: Modifier = Modifier,
    epg: EpgRepository = koinInject(),
    scheduler: ReminderScheduler = koinInject(),
    catchup: CatchupService = koinInject(),
    contentRepo: ContentRepository = koinInject(),
    parental: ParentalRepository = koinInject(),
) {
    // Channel list is grown via pagination — initial 100, then more as the
    // user scrolls. Holds bounded memory even for 250k-channel catalogs.
    var channels by remember { mutableStateOf<List<EpgGuideChannel>>(emptyList()) }
    var totalChannels by remember { mutableStateOf(0L) }
    var windowStartState by remember { mutableStateOf(0L) }
    var windowEndState by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var allLoaded by remember { mutableStateOf(false) }
    var nowSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    var actionTarget by remember { mutableStateOf<ProgrammeAction?>(null) }
    var reloadTick by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    // Initial / forced reload — resets pagination and fetches page 0.
    LaunchedEffect(reloadTick) {
        val initial = channels.isEmpty()
        if (initial) loading = true
        val now = System.currentTimeMillis() / 1000L
        val windowStart = now - (now % (30L * 60L))
        val windowEnd = windowStart + WINDOW_HOURS * 60L * 60L
        windowStartState = windowStart
        windowEndState = windowEnd

        val loaded =
            withContext(Dispatchers.IO) {
                val total =
                    runCatching {
                        epg.countGuideChannels(startTime = windowStart, endTime = windowEnd)
                    }.onFailure { Log.w("Yanco", "GuideScreen.countGuideChannels failed: ${it.message}", it) }
                        .getOrElse { 0L }
                val page =
                    runCatching {
                        epg.getGuideData(
                            startTime = windowStart,
                            endTime = windowEnd,
                            sourceId = null,
                            limit = GUIDE_PAGE_SIZE,
                            offset = 0L,
                        )
                    }.onFailure { Log.w("Yanco", "GuideScreen.getGuideData(initial) failed: ${it.message}", it) }
                        .getOrElse { EpgGuideData(channels = emptyList(), startTime = windowStart, endTime = windowEnd) }
                total to page
            }
        totalChannels = loaded.first
        // Defensive dedup: LazyColumn crashes hard on duplicate keys, and the
        // SQL is supposed to return one row per tvg_id — but this belt-and-
        // suspenders keeps the UI alive if the query ever regresses.
        channels = loaded.second.channels.distinctBy { it.tvgId }
        allLoaded = channels.size >= totalChannels.toInt() || loaded.second.channels.isEmpty()
        if (initial) loading = false
    }

    // Scroll-triggered pagination. Fires when the user's close to the end of
    // the currently-loaded window; loads the next page in the background.
    val lastVisibleIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo
            if (info.isEmpty()) -1 else info.last().index
        }
    }
    LaunchedEffect(lastVisibleIndex, allLoaded) {
        if (allLoaded || loadingMore || channels.isEmpty()) return@LaunchedEffect
        if (lastVisibleIndex < channels.size - PREFETCH_THRESHOLD) return@LaunchedEffect
        loadingMore = true
        val nextPage =
            withContext(Dispatchers.IO) {
                runCatching {
                    epg.getGuideData(
                        startTime = windowStartState,
                        endTime = windowEndState,
                        sourceId = null,
                        limit = GUIDE_PAGE_SIZE,
                        offset = channels.size.toLong(),
                    )
                }.onFailure { Log.w("Yanco", "GuideScreen.getGuideData(more) failed: ${it.message}", it) }
                    .getOrElse { EpgGuideData(channels = emptyList(), startTime = windowStartState, endTime = windowEndState) }
            }
        // Same defensive dedup: if a page overlaps with what's already loaded
        // (e.g. two calls racing, or a tvg_id appearing on a page boundary)
        // we drop the duplicates so LazyColumn keys stay unique.
        val existing = channels.mapTo(HashSet(channels.size)) { it.tvgId }
        val newOnly = nextPage.channels.distinctBy { it.tvgId }.filter { it.tvgId !in existing }
        val appended = channels + newOnly
        channels = appended
        allLoaded = nextPage.channels.isEmpty() || appended.size >= totalChannels.toInt()
        loadingMore = false
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(NOW_TICK_MS)
            nowSeconds = System.currentTimeMillis() / 1000L
        }
    }

    val guide =
        if (channels.isEmpty()) {
            null
        } else {
            EpgGuideData(
                channels = channels,
                startTime = windowStartState,
                endTime = windowEndState,
            )
        }
    val guideEmpty = guide == null

    // MK.8.7.c — channel long-press action menu. Resolve the Guide's
    // EpgGuideChannel (which only carries tvg_id) into a real ContentItem
    // via ContentRepository.findLiveByTvgId so the menu can key its
    // lock/hide actions off the content row's id. Declared here (before
    // the Column so the GuideGrid callsite can reference it) instead of
    // after — Kotlin's lexical scoping requires the lambda to be in
    // scope when passed down.
    var actionsFor by remember { mutableStateOf<ContentItem?>(null) }
    val onChannelLongPress: (EpgGuideChannel) -> Unit = { channel ->
        val tvg = channel.tvgId
        if (tvg.isNotBlank()) {
            // Set a placeholder immediately; the LaunchedEffect below
            // upgrades it to the real DB-resolved ContentItem.
            actionsFor =
                ContentItem(
                    id = "guide:$tvg",
                    sourceId = "",
                    type = com.yancotv.shared.types.ContentType.LIVE,
                    title = channel.name,
                    cleanTitle = channel.name,
                    groupName = null,
                    streamUrl = channel.streamUrl ?: "",
                    logoUrl = channel.logoUrl,
                    tvgId = tvg,
                    metadataJson = null,
                    sortOrder = 0,
                    createdAt = 0L,
                )
        }
    }

    // Upgrade the placeholder to the real ContentItem (with a valid db id)
    // so lock/hide writes reach the right row.
    LaunchedEffect(actionsFor?.tvgId) {
        val snapshot = actionsFor ?: return@LaunchedEffect
        if (snapshot.id.startsWith("guide:")) {
            val real =
                withContext(Dispatchers.IO) {
                    runCatching { snapshot.tvgId?.let { contentRepo.findLiveByTvgId(it) } }
                        .onFailure { Log.w("Yanco", "GuideScreen.findLiveByTvgId(${snapshot.tvgId}) failed: ${it.message}", it) }
                        .getOrNull()
                }
            if (real != null) actionsFor = real
        }
    }

    actionsFor?.let { item ->
        if (!item.id.startsWith("guide:")) {
            ChannelActionsMenu(
                item = item,
                repo = parental,
                onDismiss = { actionsFor = null },
            )
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (guideEmpty) {
            if (loading) {
                GuideEmptyState(text = "Loading guide…", modifier = Modifier.fillMaxSize())
            } else {
                // Diagnostics panel takes over the full area, carrying the
                // refresh + re-sync actions so the user never has to dig
                // through Settings to unstick an empty guide.
                GuideSyncPanel(
                    compact = false,
                    onRefreshed = { reloadTick++ },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            GuideSyncPanel(
                compact = true,
                onRefreshed = { reloadTick++ },
            )
            GuideGrid(
                guide = guide!!,
                nowSeconds = nowSeconds,
                listState = listState,
                totalCount = totalChannels,
                loadingMore = loadingMore,
                onPlay = onPlay,
                onProgrammeAction = { channel, programme ->
                    actionTarget = ProgrammeAction(channel, programme)
                },
                onChannelLongPress = onChannelLongPress,
                modifier = Modifier.weight(1f),
            )
        }
    }

    val target = actionTarget
    if (target != null) {
        // Cheap availability probe for past programmes: DB-only, no network.
        // Running it in a LaunchedEffect keyed on the target keeps the probe
        // off the main thread while the dialog renders immediately.
        var catchupItem by remember(target.programme.id) { mutableStateOf<ContentItem?>(null) }
        LaunchedEffect(target.programme.id) {
            val programme = target.programme
            if (programme.endTime > nowSeconds) {
                catchupItem = null
                return@LaunchedEffect
            }
            val resolved =
                withContext(Dispatchers.IO) {
                    runCatching { catchup.resolve(programme) }
                        .onFailure { Log.w("Yanco", "GuideScreen.catchup.resolve(${programme.id}) failed: ${it.message}", it) }
                        .getOrNull()
                }
            catchupItem = (resolved as? CatchupService.Resolution.Playable)?.item
        }

        ProgrammeActionDialog(
            channel = target.channel,
            programme = target.programme,
            nowSeconds = nowSeconds,
            isReminderSet = scheduler.isSet(target.programme.id),
            catchupItem = catchupItem,
            onWatch = {
                actionTarget = null
                onPlay(target.channel, target.programme)
            },
            onPlayCatchup = { item ->
                actionTarget = null
                onPlayCatchup(item)
            },
            onSetReminder = {
                scheduler.set(target.channel.tvgId, target.programme)
                actionTarget = null
            },
            onCancelReminder = {
                scheduler.cancel(target.programme.id)
                actionTarget = null
            },
            onDismiss = { actionTarget = null },
        )
    }
}

private data class ProgrammeAction(
    val channel: EpgGuideChannel,
    val programme: EpgProgramme,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideGrid(
    guide: EpgGuideData,
    nowSeconds: Long,
    listState: LazyListState,
    totalCount: Long,
    loadingMore: Boolean,
    onPlay: (EpgGuideChannel, EpgProgramme?) -> Unit,
    onProgrammeAction: (EpgGuideChannel, EpgProgramme) -> Unit,
    onChannelLongPress: (EpgGuideChannel) -> Unit,
    modifier: Modifier,
) {
    val hScroll = rememberScrollState()
    val totalMinutes = ((guide.endTime - guide.startTime) / 60L).toInt()
    val timelineWidth = (totalMinutes * PX_PER_MIN).dp

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(LocalYancoPalette.current.BackgroundDeep),
    ) {
        TimeHeader(
            startTime = guide.startTime,
            totalMinutes = totalMinutes,
            timelineWidth = timelineWidth,
            hScroll = hScroll,
        )

        if (totalCount > guide.channels.size) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(LocalYancoPalette.current.BackgroundRaised)
                        .padding(horizontal = 24.dp, vertical = 4.dp),
            ) {
                androidx.compose.material3.Text(
                    text =
                        if (loadingMore) {
                            "Showing ${guide.channels.size} of $totalCount channels · loading more…"
                        } else {
                            "Showing ${guide.channels.size} of $totalCount channels"
                        },
                    color = LocalYancoPalette.current.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(guide.channels, key = { it.tvgId }) { channel ->
                    ChannelRow(
                        channel = channel,
                        windowStart = guide.startTime,
                        windowEnd = guide.endTime,
                        timelineWidth = timelineWidth,
                        hScroll = hScroll,
                        onPlayChannel = { onPlay(channel, null) },
                        onLongPressChannel = { onChannelLongPress(channel) },
                        onProgrammeAction = { prog -> onProgrammeAction(channel, prog) },
                    )
                }
            }

            // Vertical "now" indicator line. Rendered outside the lazy rows so
            // it sits above the programme blocks, offset in sync with the
            // shared horizontal scroll state. We use the lambda form of
            // Modifier.offset so recomposition is cheap when hScroll.value
            // ticks every frame during a swipe.
            val nowOffsetMin = ((nowSeconds - guide.startTime) / 60L).toInt()
            if (nowOffsetMin in 0..totalMinutes) {
                val density = LocalDensity.current
                val leftPx =
                    with(density) {
                        CHANNEL_COL_WIDTH.toPx() + (nowOffsetMin * PX_PER_MIN).dp.toPx() - hScroll.value
                    }
                Box(
                    modifier =
                        Modifier
                            .offset { IntOffset(leftPx.toInt(), 0) }
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(Color(0xFFE25555)),
                )
            }
        }
    }
}

@Composable
private fun TimeHeader(
    startTime: Long,
    totalMinutes: Int,
    timelineWidth: androidx.compose.ui.unit.Dp,
    hScroll: androidx.compose.foundation.ScrollState,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(HEADER_HEIGHT)
                .background(LocalYancoPalette.current.BackgroundRaised),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(CHANNEL_COL_WIDTH))
        Row(
            modifier =
                Modifier
                    .horizontalScroll(hScroll)
                    .width(timelineWidth)
                    .fillMaxHeight(),
        ) {
            // Ticks every 30 min. Each tick owns its 30-min slice width so
            // the label stays left-aligned with the tick line.
            var minute = 0
            while (minute < totalMinutes) {
                val slice = minOf(30, totalMinutes - minute)
                val label = formatHourMinute(startTime + minute * 60L)
                Box(
                    modifier =
                        Modifier
                            .width((slice * PX_PER_MIN).dp)
                            .fillMaxHeight()
                            .border(0.5.dp, LocalYancoPalette.current.BorderSubtle),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = label,
                        color = LocalYancoPalette.current.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                minute += slice
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: EpgGuideChannel,
    windowStart: Long,
    windowEnd: Long,
    timelineWidth: androidx.compose.ui.unit.Dp,
    hScroll: androidx.compose.foundation.ScrollState,
    onPlayChannel: () -> Unit,
    onLongPressChannel: () -> Unit,
    onProgrammeAction: (EpgProgramme) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT)
                .background(LocalYancoPalette.current.BackgroundRaised),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sticky channel cell — stays visible even as the timeline scrolls.
        ChannelCell(
            channel = channel,
            onClick = onPlayChannel,
            onLongPress = onLongPressChannel,
        )

        // Programme lane. We manually position each programme with a leading
        // Spacer-ish gap, then a clickable block sized to its duration. Any
        // trailing empty space after the last programme is left blank.
        Row(
            modifier =
                Modifier
                    .horizontalScroll(hScroll)
                    .width(timelineWidth)
                    .fillMaxHeight(),
        ) {
            var cursor = windowStart
            for (prog in channel.programmes) {
                val clampedStart = prog.startTime.coerceAtLeast(windowStart)
                val clampedEnd = prog.endTime.coerceAtMost(windowEnd)
                if (clampedEnd <= clampedStart) continue
                val gapMin = ((clampedStart - cursor) / 60L).toInt().coerceAtLeast(0)
                if (gapMin > 0) {
                    Box(modifier = Modifier.width((gapMin * PX_PER_MIN).dp).fillMaxHeight())
                }
                val durMin = ((clampedEnd - clampedStart) / 60L).toInt().coerceAtLeast(1)
                ProgrammeBlock(
                    programme = prog,
                    widthDp = (durMin * PX_PER_MIN).dp.coerceAtLeast(MIN_PROG_WIDTH),
                    onActivate = { onProgrammeAction(prog) },
                )
                cursor = clampedEnd
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelCell(
    channel: EpgGuideChannel,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) LocalYancoPalette.current.BackgroundHover else LocalYancoPalette.current.BackgroundRaised
    val border = if (focused) LocalYancoPalette.current.FocusRing else LocalYancoPalette.current.BorderSubtle

    Row(
        modifier =
            Modifier
                .width(CHANNEL_COL_WIDTH)
                .fillMaxHeight()
                .background(bg)
                .border(0.5.dp, border)
                .focusable(interactionSource = interaction)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongPress,
                ).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(LocalYancoPalette.current.BackgroundDeep),
            contentAlignment = Alignment.Center,
        ) {
            if (!channel.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp),
                )
            }
        }
        Text(
            text = channel.name,
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProgrammeBlock(
    programme: EpgProgramme,
    widthDp: androidx.compose.ui.unit.Dp,
    onActivate: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) LocalYancoPalette.current.BackgroundHover else LocalYancoPalette.current.BackgroundDeep
    val border = if (focused) LocalYancoPalette.current.FocusRing else LocalYancoPalette.current.BorderSubtle

    Column(
        modifier =
            Modifier
                .width(widthDp)
                .fillMaxHeight()
                .padding(1.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(bg)
                .border(0.5.dp, border, RoundedCornerShape(4.dp))
                .focusable(interactionSource = interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onActivate)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = programme.title,
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatHourMinute(programme.startTime),
            color = LocalYancoPalette.current.TextMuted,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun GuideEmptyState(
    text: String,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(LocalYancoPalette.current.BackgroundDeep),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = LocalYancoPalette.current.TextMuted)
    }
}

@Composable
private fun ProgrammeActionDialog(
    channel: EpgGuideChannel,
    programme: EpgProgramme,
    nowSeconds: Long,
    isReminderSet: Boolean,
    catchupItem: ContentItem?,
    onWatch: () -> Unit,
    onPlayCatchup: (ContentItem) -> Unit,
    onSetReminder: () -> Unit,
    onCancelReminder: () -> Unit,
    onDismiss: () -> Unit,
) {
    // "Future" means the programme hasn't started yet. Setting a reminder on
    // something already live is pointless — the user should just press Watch.
    val isFuture = programme.startTime > nowSeconds
    val isPast = programme.endTime <= nowSeconds

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LocalYancoPalette.current.BackgroundRaised,
        title = {
            Text(text = programme.title, color = LocalYancoPalette.current.TextPrimary)
        },
        text = {
            Column {
                Text(
                    text = channel.name,
                    color = LocalYancoPalette.current.TextMuted,
                    fontSize = 13.sp,
                )
                Text(
                    text = "${formatHourMinute(programme.startTime)} – ${formatHourMinute(programme.endTime)}",
                    color = LocalYancoPalette.current.TextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            // For past programmes with a resolvable catchup URL, surface the
            // replay button as the primary action — that's what the user
            // almost certainly wants when they tap an ended programme. The
            // live "Watch channel" option stays available underneath so the
            // user can still bail out to live playback.
            if (isPast && catchupItem != null) {
                TextButton(onClick = { onPlayCatchup(catchupItem) }) {
                    Text(text = "Play catch-up", color = LocalYancoPalette.current.Accent)
                }
            } else {
                TextButton(onClick = onWatch) {
                    Text(text = "Watch channel", color = LocalYancoPalette.current.Accent)
                }
            }
        },
        dismissButton = {
            when {
                isFuture -> {
                    if (isReminderSet) {
                        TextButton(onClick = onCancelReminder) {
                            Text(text = "Cancel reminder", color = LocalYancoPalette.current.TextPrimary)
                        }
                    } else {
                        TextButton(onClick = onSetReminder) {
                            Text(text = "Set reminder", color = LocalYancoPalette.current.TextPrimary)
                        }
                    }
                }
                isPast && catchupItem != null -> {
                    TextButton(onClick = onWatch) {
                        Text(text = "Watch channel", color = LocalYancoPalette.current.TextPrimary)
                    }
                }
                else -> Unit
            }
        },
    )
}

/** Formats a unix-second timestamp in the device's local HH:mm form. */
private fun formatHourMinute(unixSeconds: Long): String {
    val calendar =
        java.util.Calendar.getInstance().apply {
            timeInMillis = unixSeconds * 1000L
        }
    val h = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val m = calendar.get(java.util.Calendar.MINUTE)
    return buildString {
        if (h < 10) append('0')
        append(h)
        append(':')
        if (m < 10) append('0')
        append(m)
    }
}
