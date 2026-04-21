package com.yancotv.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.content.QualityBadge

/**
 * Small pill row. Colours mirror the desktop palette: 4K/UHD gold, FHD
 * accent blue, HDR warm, codecs muted. Badges deduplicate upstream via
 * [QualityBadge.parse] so we never render the same chip twice.
 */
@Composable
fun QualityChips(badges: List<QualityBadge>, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        badges.forEach { Chip(it) }
    }
}

@Composable
private fun Chip(badge: QualityBadge) {
    val (bg, fg) = colorsFor(badge)
    Text(
        text = badge.label,
        color = fg,
        fontSize = 10.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

private fun colorsFor(badge: QualityBadge): Pair<Color, Color> = when (badge) {
    QualityBadge.UHD_8K, QualityBadge.UHD_4K -> Color(0xFFD4A74A) to Color.Black
    QualityBadge.FHD -> YancoPalette.Accent to Color.Black
    QualityBadge.HD -> YancoPalette.AccentMuted to YancoPalette.TextPrimary
    QualityBadge.SD -> Color(0xFF4A4A4A) to YancoPalette.TextPrimary
    QualityBadge.HDR -> Color(0xFFFF8A4A) to Color.Black
    QualityBadge.HEVC, QualityBadge.H264 -> YancoPalette.BackgroundHover to YancoPalette.TextMuted
}
