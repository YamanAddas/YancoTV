package com.yancotv.android.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import coil3.compose.AsyncImage
import com.yancotv.android.reminders.ReminderScheduler
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.epg.EpgRepository
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
@Composable
fun GuideScreen(
    onPlay: (EpgGuideChannel, EpgProgramme?) -> Unit,
    modifier: Modifier = Modifier,
    epg: EpgRepository = koinInject(),
    scheduler: ReminderScheduler = koinInject(),
) {
    var data by remember { mutableStateOf<EpgGuideData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var nowSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    var actionTarget by remember { mutableStateOf<ProgrammeAction?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        val now = System.currentTimeMillis() / 1000L
        // Floor to the previous 30-min boundary so the header ticks land on
        // round clock times (e.g. 12:00, 12:30) instead of 12:07.
        val windowStart = now - (now % (30L * 60L))
        val windowEnd = windowStart + WINDOW_HOURS * 60L * 60L
        data = withContext(Dispatchers.IO) {
            epg.getGuideData(startTime = windowStart, endTime = windowEnd, sourceId = null)
        }
        loading = false
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(NOW_TICK_MS)
            nowSeconds = System.currentTimeMillis() / 1000L
        }
    }

    val guide = data
    if (loading || guide == null) {
        GuideEmptyState(text = "Loading guide…", modifier = modifier)
        return
    }
    if (guide.channels.isEmpty()) {
        GuideEmptyState(
            text = "No EPG data yet. Add a source with an EPG URL and wait for the next sync.",
            modifier = modifier,
        )
        return
    }

    GuideGrid(
        guide = guide,
        nowSeconds = nowSeconds,
        onPlay = onPlay,
        onProgrammeAction = { channel, programme ->
            actionTarget = ProgrammeAction(channel, programme)
        },
        modifier = modifier,
    )

    val target = actionTarget
    if (target != null) {
        ProgrammeActionDialog(
            channel = target.channel,
            programme = target.programme,
            nowSeconds = nowSeconds,
            isReminderSet = scheduler.isSet(target.programme.id),
            onWatch = {
                actionTarget = null
                onPlay(target.channel, target.programme)
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
    onPlay: (EpgGuideChannel, EpgProgramme?) -> Unit,
    onProgrammeAction: (EpgGuideChannel, EpgProgramme) -> Unit,
    modifier: Modifier,
) {
    val hScroll = rememberScrollState()
    val totalMinutes = ((guide.endTime - guide.startTime) / 60L).toInt()
    val timelineWidth = (totalMinutes * PX_PER_MIN).dp

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(YancoPalette.BackgroundDeep),
    ) {
        TimeHeader(
            startTime = guide.startTime,
            totalMinutes = totalMinutes,
            timelineWidth = timelineWidth,
            hScroll = hScroll,
        )

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
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
                val leftPx = with(density) {
                    CHANNEL_COL_WIDTH.toPx() + (nowOffsetMin * PX_PER_MIN).dp.toPx() - hScroll.value
                }
                Box(
                    modifier = Modifier
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
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .background(YancoPalette.BackgroundRaised),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(CHANNEL_COL_WIDTH))
        Row(
            modifier = Modifier
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
                    modifier = Modifier
                        .width((slice * PX_PER_MIN).dp)
                        .fillMaxHeight()
                        .border(0.5.dp, YancoPalette.BorderSubtle),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = label,
                        color = YancoPalette.TextMuted,
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
    onProgrammeAction: (EpgProgramme) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(YancoPalette.BackgroundRaised),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sticky channel cell — stays visible even as the timeline scrolls.
        ChannelCell(
            channel = channel,
            onClick = onPlayChannel,
        )

        // Programme lane. We manually position each programme with a leading
        // Spacer-ish gap, then a clickable block sized to its duration. Any
        // trailing empty space after the last programme is left blank.
        Row(
            modifier = Modifier
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

@Composable
private fun ChannelCell(channel: EpgGuideChannel, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) YancoPalette.BackgroundHover else YancoPalette.BackgroundRaised
    val border = if (focused) YancoPalette.FocusRing else YancoPalette.BorderSubtle

    Row(
        modifier = Modifier
            .width(CHANNEL_COL_WIDTH)
            .fillMaxHeight()
            .background(bg)
            .border(0.5.dp, border)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(YancoPalette.BackgroundDeep),
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
            color = YancoPalette.TextPrimary,
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
    val bg = if (focused) YancoPalette.BackgroundHover else YancoPalette.BackgroundDeep
    val border = if (focused) YancoPalette.FocusRing else YancoPalette.BorderSubtle

    Column(
        modifier = Modifier
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
            color = YancoPalette.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatHourMinute(programme.startTime),
            color = YancoPalette.TextMuted,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun GuideEmptyState(text: String, modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(YancoPalette.BackgroundDeep),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = YancoPalette.TextMuted)
    }
}

@Composable
private fun ProgrammeActionDialog(
    channel: EpgGuideChannel,
    programme: EpgProgramme,
    nowSeconds: Long,
    isReminderSet: Boolean,
    onWatch: () -> Unit,
    onSetReminder: () -> Unit,
    onCancelReminder: () -> Unit,
    onDismiss: () -> Unit,
) {
    // "Future" means the programme hasn't started yet. Setting a reminder on
    // something already live is pointless — the user should just press Watch.
    val isFuture = programme.startTime > nowSeconds

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = YancoPalette.BackgroundRaised,
        title = {
            Text(text = programme.title, color = YancoPalette.TextPrimary)
        },
        text = {
            Column {
                Text(
                    text = channel.name,
                    color = YancoPalette.TextMuted,
                    fontSize = 13.sp,
                )
                Text(
                    text = "${formatHourMinute(programme.startTime)} – ${formatHourMinute(programme.endTime)}",
                    color = YancoPalette.TextMuted,
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onWatch) {
                Text(text = "Watch channel", color = YancoPalette.Accent)
            }
        },
        dismissButton = {
            if (isFuture) {
                if (isReminderSet) {
                    TextButton(onClick = onCancelReminder) {
                        Text(text = "Cancel reminder", color = YancoPalette.TextPrimary)
                    }
                } else {
                    TextButton(onClick = onSetReminder) {
                        Text(text = "Set reminder", color = YancoPalette.TextPrimary)
                    }
                }
            }
        },
    )
}

/** Formats a unix-second timestamp in the device's local HH:mm form. */
private fun formatHourMinute(unixSeconds: Long): String {
    val calendar = java.util.Calendar.getInstance().apply {
        timeInMillis = unixSeconds * 1000L
    }
    val h = calendar.get(java.util.Calendar.HOUR_OF_DAY)
    val m = calendar.get(java.util.Calendar.MINUTE)
    return buildString {
        if (h < 10) append('0'); append(h); append(':')
        if (m < 10) append('0'); append(m)
    }
}
