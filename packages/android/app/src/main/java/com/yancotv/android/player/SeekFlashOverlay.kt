package com.yancotv.android.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow

/**
 * MK.25.A.2 — transient seek-feedback flash.
 *
 * Renders a `+10s` / `-10s` badge on the right or left edge of the
 * screen when the user presses the seek keys. Multi-press coalesces:
 * three RIGHT presses inside the 600 ms window show `+30s` (not three
 * sequential `+10s`) so a TV remote keypress flurry doesn't thrash
 * the visual.
 *
 * This composable is read-only — the activity owns the
 * [seekFlashFlow] state and updates it synchronously in the seek key
 * handler. The overlay decides which edge based on sign:
 *   - positive → right edge, "+Ns"
 *   - negative → left edge, "-Ns" (sign rendered, not "−Ns")
 *   - zero    → hidden
 *
 * Intentionally does NOT pull focus, doesn't show the dock, and
 * doesn't interfere with chrome / buffering / error overlays.
 */
@Composable
fun SeekFlashOverlay(seekFlashFlow: StateFlow<Int>) {
    val seconds by seekFlashFlow.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = seconds != 0,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier =
            Modifier
                .align(if (seconds >= 0) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 64.dp),
        ) {
            FlashBadge(seconds = seconds)
        }
    }
}

@Composable
private fun FlashBadge(seconds: Int) {
    Row(
        modifier =
        Modifier
            .clip(RoundedCornerShape(36.dp))
            .background(Color(0xCC0A1410))
            .padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (seconds > 0) "▶▶  +${seconds}s" else "◂◂  ${seconds}s",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
        )
    }
}
