package com.yancotv.android.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.theme.YancoPalette

/**
 * Player-options bottom sheet (MK.12a.1 scaffold).
 *
 * Hosts the audio / subtitle / speed / aspect pickers from MK.12a.2–12a.5
 * and the sleep timer from MK.12b. Opened via KEYCODE_MENU on the remote
 * or long-press CENTER; dismissed by BACK. Playback is not paused while
 * the sheet is up — the video underneath keeps running.
 *
 * This file ships the container only: surface, shape, dismiss row, focus
 * seed. The picker rows themselves light up as 12a.2+ lands, each adding
 * one [OptionRow] that reads from `controller.player` / `prefs` and writes
 * through on selection.
 */
@Composable
fun PlayerOptionsSheet(onDismiss: () -> Unit) {
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // Seed focus into the sheet so D-pad input drives it; without this,
        // focus would stay on the (invisible) PlayerView under the sheet.
        runCatching { firstRowFocus.requestFocus() }
    }
    // Translucent scrim over the video so the sheet reads clearly without
    // fully blacking out the stream — users still see what's playing.
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(YancoPalette.BackgroundRaised)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "PLAYER OPTIONS",
                color = YancoPalette.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            // Scaffold rows — each becomes functional in a follow-up commit:
            // 12a.2 Audio, 12a.3 Subtitles, 12a.4 Speed, 12a.5 Aspect,
            // 12b.1 Sleep timer. Until then they're disabled-looking and
            // tap-to-dismiss so the user isn't trapped if they wandered in.
            OptionRow(
                label = "Audio track",
                value = "Coming in MK.12a.2",
                focusRequester = firstRowFocus,
                onClick = onDismiss,
            )
            OptionRow(
                label = "Subtitles",
                value = "Coming in MK.12a.3",
                onClick = onDismiss,
            )
            OptionRow(
                label = "Playback speed",
                value = "Coming in MK.12a.4",
                onClick = onDismiss,
            )
            OptionRow(
                label = "Aspect ratio",
                value = "Coming in MK.12a.5",
                onClick = onDismiss,
            )
            OptionRow(
                label = "Sleep timer",
                value = "Coming in MK.12b.1",
                onClick = onDismiss,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "BACK to close",
                color = YancoPalette.TextMuted,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val rowModifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) YancoPalette.BackgroundElevated else Color.Transparent,
            ).let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .semantics { contentDescription = "$label. $value" }
            .padding(horizontal = 14.dp, vertical = 14.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = YancoPalette.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(12.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = value,
                color = YancoPalette.TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}
