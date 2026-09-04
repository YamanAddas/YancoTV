package com.yancotv.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yancotv.android.R
import com.yancotv.android.ui.theme.LocalShellMetrics
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.android.ui.theme.YancoType
import com.yancotv.shared.types.EpgGuideChannel
import com.yancotv.shared.types.EpgProgramme

/**
 * The guide, for a window too narrow to be a timeline.
 *
 * ### Why a list rather than a squeezed grid
 *
 * A guide grid is channels down and time across, and it needs width: at the
 * shipped density a phone in portrait has room for **about ninety minutes of one
 * channel**. That is not a guide, it is a very wide list with most of it
 * off-screen and a horizontal scrollbar standing in for the information.
 *
 * So a tall window gets the same facts arranged for its shape: one row per
 * channel, what is on now with how far through it is, and what is next. The
 * timeline is what does not fit; "what is on" is the part people came for, and
 * it fits.
 *
 * ### What is deliberately not here
 *
 * The grid's per-programme affordances — tapping a *future* block to set a
 * reminder, tapping a *past* one for catch-up — have no target in a list,
 * because a list shows one programme per channel rather than an evening of
 * them. A row here plays the channel. Reminders and catch-up stay reachable
 * from the grid on a wide window and from the channel's own detail page;
 * inventing a long-press menu for them belongs in its own slice, not smuggled
 * into a layout change.
 */
@Composable
fun GuideList(
    channels: List<EpgGuideChannel>,
    nowSeconds: Long,
    listState: LazyListState,
    onPlay: (EpgGuideChannel, EpgProgramme?) -> Unit,
    onChannelLongPress: (EpgGuideChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalShellMetrics.current
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = metrics.pageInset, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        items(channels, key = { it.id }) { channel ->
            GuideListRow(
                channel = channel,
                nowSeconds = nowSeconds,
                onPlay = { onPlay(channel, channel.nowProgramme(nowSeconds)) },
                onLongPress = { onChannelLongPress(channel) },
            )
        }
    }
}

/** Currently-airing programme, or null when the guide does not cover now. */
internal fun EpgGuideChannel.nowProgramme(nowSeconds: Long): EpgProgramme? =
    programmes.firstOrNull { nowSeconds in it.startTime until it.endTime }

/** The one after [nowProgramme], by start time. */
internal fun EpgGuideChannel.nextProgramme(nowSeconds: Long): EpgProgramme? =
    programmes.filter { it.startTime > nowSeconds }.minByOrNull { it.startTime }

/**
 * How far through the current programme we are, 0..1.
 *
 * Guards a zero or negative span: a provider that ships `end == start` would
 * otherwise divide by zero, and the guide is full of provider data nobody
 * validated.
 */
internal fun programmeProgress(programme: EpgProgramme, nowSeconds: Long): Float {
    val span = programme.endTime - programme.startTime
    if (span <= 0L) return 0f
    return ((nowSeconds - programme.startTime).toFloat() / span.toFloat()).coerceIn(0f, 1f)
}

@Composable
private fun GuideListRow(
    channel: EpgGuideChannel,
    nowSeconds: Long,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
) {
    val palette = LocalYancoPalette.current
    val now = channel.nowProgramme(nowSeconds)
    val next = channel.nextProgramme(nowSeconds)

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(YancoShapes.CutCornerCardSmall)
            .background(palette.BackgroundRaised.copy(alpha = 0.5f))
            .clickable(onClick = onPlay)
            .padding(Space.md)
            .semantics {
                contentDescription = channel.name + (now?.let { ". " + it.title } ?: "")
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        // ── the channel ──
        Row(
            modifier = Modifier.width(ChannelColumn),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            if (!channel.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logoUrl,
                    // Named by the text beside it; announcing the logo too
                    // would say the channel's name twice.
                    contentDescription = null,
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(4.dp)),
                )
            }
            Text(
                text = channel.name,
                style = YancoType.Caption,
                color = palette.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ── what is on ──
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            if (now == null) {
                Text(
                    text = stringResource(R.string.gl_no_guide),
                    style = YancoType.Caption,
                    color = palette.TextMuted,
                )
            } else {
                Text(
                    text = now.title,
                    style = YancoType.LabelStrong,
                    color = palette.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    // Programme titles run long and a truncated one often loses
                    // the episode or match that identifies it.
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                ProgressBar(progress = programmeProgress(now, nowSeconds))
                if (next != null) {
                    Text(
                        text = stringResource(R.string.cf_up_next, next.title),
                        style = YancoType.Caption,
                        color = palette.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float) {
    val palette = LocalYancoPalette.current
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(palette.BorderSubtle),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxWidth(progress)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.Accent),
        )
    }
}

/** Width of the channel column — logo plus two lines of name. */
private val ChannelColumn = 108.dp
