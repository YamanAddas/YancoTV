package com.yancotv.android.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.R
import com.yancotv.shared.recording.RecordingStatus
import com.yancotv.shared.recording.RecordingsRepository

/**
 * In-app "REC" indicator pinned to the top-right of the player view.
 *
 * Why this exists: Fire TV deliberately doesn't surface foreground-service
 * notifications over fullscreen video (Amazon's own docs say "informational
 * in nature and do not interrupt the current foreground activity"). With
 * IMPORTANCE_LOW the recording's system notification is correctly posted
 * but invisible to the user. So we render the same status in-app.
 *
 * The composable observes [RecordingsRepository.allFlow] and only renders
 * when at least one row matches:
 *   - status = RECORDING
 *   - content_id = the currently playing channel id
 *
 * That way the indicator says "you are recording THIS channel" rather
 * than the more confusing "you have some recording in flight". For
 * record-from-EPG / record-while-watching-another-channel flows the
 * notification (and the future RecordingsScreen) carries that info.
 */
@Composable
fun RecordingIndicator(controller: PlaybackController, recordings: RecordingsRepository) {
    val currentItem by controller.currentItem.collectAsState()
    val rows by remember { recordings.allFlow() }.collectAsState(initial = emptyList())

    val activeForCurrent =
        rows.firstOrNull { row ->
            row.status == RecordingStatus.RECORDING && row.contentId == currentItem?.id
        }
    if (activeForCurrent == null) return

    // Slow pulse on the dot — same idiom every camera + DVR uses to
    // communicate "live recording". 1.4 s cycle keeps it noticeable
    // without becoming an attention sink.
    val transition = rememberInfiniteTransition(label = "recording-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec =
        infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recording-pulse-alpha",
    )

    val recordingDesc = stringResource(R.string.ri_recording_in_progress)

    Row(
        modifier =
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color(0xCC000000))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            // MK.31.13 — resolved above the chain; semantics{} is not composable scope.
            .semantics { contentDescription = recordingDesc },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier =
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(0xFFE53935))
                .alpha(pulse),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.rc_st_rec),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
    }
}
