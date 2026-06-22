package com.yancotv.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.YancoType
import com.yancotv.shared.history.WatchProgress

/**
 * MK.28.2 — Shared watch-state visual primitives.
 *
 * Hoisted out of HomeContent so the Netflix-style "where I left off on
 * every icon" experience can be applied uniformly across the app:
 *   - Home Continue Watching / Favorites / Recently Added rails
 *   - Browse coverflow orbs (Live / Movies / Series wheels)
 *   - Favorites screen rows
 *   - Search result rows
 *   - Series detail's per-episode list
 *
 * Visual primitives are stateless. Callers wrap them in a Box and align
 * them per surface (corner badge, bottom stripe). The higher-level
 * helpers below ([formatResumeLabel]) keep the call sites tight.
 */

/**
 * Gradient progress bar across the bottom edge of a tile. 4 dp tall, full
 * width, fills `progress` (0..1) of its width. Backdrop sits at 60%
 * alpha so even 0%-progress reads as a deliberate UI element, not a
 * partial draw.
 *
 * Pass a value from [WatchProgress.ratio] for VOD tiles, or the EPG
 * elapsed/duration ratio for live On-Now tiles. Identical surface in
 * both cases.
 */
@Composable
fun ProgressStripe(progress: Float, modifier: Modifier = Modifier) {
    // Brush rebuilds only on palette swap (MK.16.1 pattern), not every
    // recompose. The Brush itself doesn't depend on `progress` — only the
    // child Box width does, and that's already a stable lambda.
    val pal = LocalYancoPalette.current
    val tileProgressBrush =
        remember(pal) {
            Brush.horizontalGradient(
                colors = listOf(pal.AccentDeep, pal.Accent, pal.AccentGlow),
            )
        }
    Box(
        modifier =
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(pal.BackgroundDeep.copy(alpha = 0.6f)),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(tileProgressBrush),
        )
    }
}

/**
 * Top-corner pill that surfaces the user's resume affordance. The label
 * is pre-computed by the caller (use [formatResumeLabel]); the badge
 * itself is purely visual so callers can override the label for the
 * Continue Watching rail's case-by-case copy ("Resume" vs "12m left" vs
 * "Watched again").
 */
@Composable
fun ResumeBadge(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier =
        modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.75f))
            .padding(horizontal = Space.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(
            imageVector = YancoIcons.Play,
            contentDescription = null,
            tint = LocalYancoPalette.current.Accent,
            modifier = Modifier.size(10.dp),
        )
        Text(
            text = label,
            color = LocalYancoPalette.current.TextPrimary,
            style = YancoType.Caption,
        )
    }
}

/**
 * Top-corner pill marking a fully-watched tile (≥95% per
 * [WatchProgress.isFinished]). Mirrors [ResumeBadge]'s shape so the two
 * read as variants of the same affordance. Renders a check glyph + the
 * word "Watched" so non-color cues remain on-screen for accessibility.
 */
@Composable
fun WatchedCheckBadge(modifier: Modifier = Modifier) {
    Row(
        modifier =
        modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.75f))
            .padding(horizontal = Space.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(
            imageVector = YancoIcons.Check,
            contentDescription = null,
            tint = LocalYancoPalette.current.Accent,
            modifier = Modifier.size(10.dp),
        )
        Text(
            text = "Watched",
            color = LocalYancoPalette.current.TextPrimary,
            style = YancoType.Caption,
        )
    }
}

/**
 * Format the "12m left" / "Resume" / "Watched" label that goes inside a
 * [ResumeBadge]. Callers who only need a per-tile label can use this
 * directly — the badge composable doesn't depend on [WatchProgress] so
 * it stays reusable for sites that already have their own copy
 * (e.g. continue-watching hero's "12m left • pick up where you stopped").
 *
 * Returns "Watched" for finished rows so callers that wrap in a single
 * badge slot (no separate WatchedCheckBadge) get sensible copy.
 */
fun formatResumeLabel(progress: WatchProgress): String {
    if (progress.isFinished()) return "Watched"
    val remaining = progress.remainingSeconds() ?: return "Resume"
    val minutes = (remaining / 60).toInt().coerceAtLeast(1)
    return "${minutes}m left"
}
