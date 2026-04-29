package com.yancotv.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.types.NowNext

/**
 * Single-line "now playing" indicator under the channel title.
 *  - Shows the current programme title and a thin progress bar.
 *  - If nothing is airing right now (gap), shows the next programme's title
 *    prefixed with "Next: ".
 *  - Renders nothing if the channel has no EPG data (caller should skip it).
 */
@Composable
fun NowNextLine(nowNext: NowNext?, nowSeconds: Long, modifier: Modifier = Modifier) {
    if (nowNext == null) return
    val now = nowNext.now
    val next = nowNext.next

    val (label, progress) =
        when {
            now != null -> {
                val dur = (now.endTime - now.startTime).coerceAtLeast(1L)
                val elapsed = (nowSeconds - now.startTime).coerceIn(0L, dur)
                now.title to elapsed.toFloat() / dur.toFloat()
            }
            next != null -> "Next: ${next.title}" to 0f
            else -> return
        }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = if (now != null) LocalYancoPalette.current.Accent else LocalYancoPalette.current.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (now != null) {
            Box(
                modifier =
                Modifier
                    .padding(top = 3.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(LocalYancoPalette.current.BorderSubtle),
            ) {
                Box(
                    modifier =
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(LocalYancoPalette.current.Accent),
                )
            }
        }
    }
}
