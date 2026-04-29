package com.yancotv.android.player.zap

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.theme.LocalYancoPalette

/**
 * MK.10.4 — bottom-left numeric channel-jump badge.
 *
 * Shows "CH 503_" (with a blinking-style caret implied by trailing
 * underscore) while the user is mid-entry. Slides up + fades on appear,
 * fades on dismiss. Lightweight — no blur, no per-frame redraw outside
 * the digit text itself.
 *
 * Intentionally narrow: this composable owns no commit logic and no
 * timer. PlayerActivity drives both — it pushes digits into the state
 * via `pushDigit`, schedules its own `Handler.postDelayed` for the
 * idle-commit timer, and calls `consume()` / `clear()` when committing
 * or cancelling. That keeps the Compose surface dumb and the activity
 * the single owner of the lifecycle.
 */
@Composable
fun ChannelZapOverlay(state: ChannelZapNumericState) {
    val visible by state.visible.collectAsState()
    val digits by state.digits.collectAsState()
    val palette = LocalYancoPalette.current

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
    ) {
        Column(
            modifier =
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(palette.BackgroundElevated)
                .border(
                    width = 1.dp,
                    color = palette.Accent,
                    shape = RoundedCornerShape(12.dp),
                ).padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            Text(
                text = "CHANNEL",
                color = palette.TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
            )
            Text(
                text = digits.ifBlank { " " } + "_",
                color = palette.Accent,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
