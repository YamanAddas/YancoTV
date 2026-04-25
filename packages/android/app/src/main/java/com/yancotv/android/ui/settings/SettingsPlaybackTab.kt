package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.ResizeMode
import com.yancotv.android.ui.theme.LocalYancoPalette
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Playback preferences — resize mode, auto-play next, default audio +
 * subtitle language. Every toggle writes through [AppPreferences] which
 * backs the `settings` table, so state survives process restart and
 * every read site (PlaybackController, PlayerActivity) sees the same
 * value.
 *
 * A few of the toggles' *effects* need follow-up wiring in the
 * consumers (PlaybackController honours some of them only after its
 * next play call; PlayerView.resizeMode reads on attach). That is
 * deliberate — this screen is the single source of truth, and the
 * downstream integration lands per-consumer in their own patches.
 */
@Composable
fun SettingsPlaybackTab(
    modifier: Modifier = Modifier,
    prefs: AppPreferences = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val snapshot by prefs.playbackFlow.collectAsStateValue()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Playback",
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )

        // ── Resize mode ──
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(LocalYancoPalette.current.BackgroundRaised)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Video resize",
                color = LocalYancoPalette.current.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "How the stream's frame is mapped to the player's area. Fit preserves aspect ratio with letterboxing; Fill stretches to the screen edges; Zoom crops to fill without letterboxing.",
                color = LocalYancoPalette.current.TextMuted,
                fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in ResizeMode.values()) {
                    SettingsChip(
                        label = mode.displayName,
                        selected = snapshot.resizeMode == mode,
                        onClick = {
                            scope.launch { prefs.setResizeMode(mode) }
                        },
                    )
                }
            }
        }

        // MB-107a: shared focus-aware row, see [SettingsToggleRow].
        SettingsToggleRow(
            label = "Auto-play next episode",
            description = "When a series episode ends, automatically continue to the next episode in the same season.",
            checked = snapshot.autoPlayNext,
            onCheckedChange = { scope.launch { prefs.setAutoPlayNext(it) } },
        )

        // ── Default audio + subtitle language ──
        // MB-117: SettingsClickToEditField — IME only on explicit OK press.
        // The lowercase + 6-char cap lives in the onValueChange shim.
        SettingsClickToEditField(
            label = "Preferred audio language",
            description = "Two- or three-letter ISO 639 code (e.g. en, eng, fre). Applied when a stream ships multiple audio tracks.",
            value = snapshot.audioLanguage,
            onValueChange = { scope.launch { prefs.setAudioLanguage(it.take(6).lowercase()) } },
            keyboardType = KeyboardType.Ascii,
        )
        SettingsClickToEditField(
            label = "Preferred subtitle language",
            description = "ISO 639 code. Blank = subtitles off by default.",
            value = snapshot.subtitleLanguage,
            onValueChange = { scope.launch { prefs.setSubtitleLanguage(it.take(6).lowercase()) } },
            keyboardType = KeyboardType.Ascii,
        )
    }
}

// Small convenience — .collectAsState() returns State<T>, `by` delegates
// to its `.value` via the `getValue` import at the top.
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateValue(): androidx.compose.runtime.State<T> = collectAsState()
