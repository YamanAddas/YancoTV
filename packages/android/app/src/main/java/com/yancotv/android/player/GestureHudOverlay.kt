package com.yancotv.android.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.yancotv.android.ui.theme.LocalYancoPalette
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

/**
 * What the gesture HUD is currently showing. `null` means nothing — the
 * overlay renders an empty Box and costs nothing, matching the
 * eager-inflate-and-stay-quiet pattern used by [SeekFlashOverlay].
 */
sealed interface GestureHud {
    /** 0f..1f. */
    data class Level(val gesture: PlayerGesture, val value: Float) : GestureHud

    /** Signed offset from the position the drag started at. */
    data class Seek(val offsetMs: Long) : GestureHud
}

/**
 * MK.11.2 — feedback for the phone player's swipe controls.
 *
 * Deliberately text-and-bar rather than icons: this project forbids emoji in
 * UI text (unreliable cross-device rendering), and the labels are already
 * localized strings, so a Turkish or Arabic user reads words rather than
 * guessing at a glyph.
 */
@Composable
fun GestureHudOverlay(hudFlow: StateFlow<GestureHud?>) {
    val hud by hudFlow.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = hud != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            when (val state = hud) {
                is GestureHud.Level -> LevelBadge(state)
                is GestureHud.Seek -> SeekBadge(state)
                null -> Box(modifier = Modifier)
            }
        }
    }
}

@Composable
private fun LevelBadge(state: GestureHud.Level) {
    val label =
        when (state.gesture) {
            PlayerGesture.BRIGHTNESS -> "Brightness"
            PlayerGesture.VOLUME -> "Volume"
            else -> ""
        }
    HudSurface {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                text = "${(state.value * 100).roundToInt()}%",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Box(
                modifier =
                Modifier
                    .width(160.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.25f)),
            ) {
                Box(
                    modifier =
                    Modifier
                        .fillMaxWidth(state.value.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(LocalYancoPalette.current.Accent),
                )
            }
        }
    }
}

@Composable
private fun SeekBadge(state: GestureHud.Seek) {
    val seconds = abs(state.offsetMs) / 1000L
    val sign = if (state.offsetMs >= 0) "+" else "-"
    HudSurface {
        Text(
            text = "$sign${seconds}s",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun HudSurface(content: @Composable () -> Unit) {
    Box(
        modifier =
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 26.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
