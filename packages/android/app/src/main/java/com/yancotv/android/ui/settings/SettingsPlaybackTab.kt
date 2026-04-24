package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
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
                    ResizeChip(
                        label = mode.displayName,
                        selected = snapshot.resizeMode == mode,
                        onClick = {
                            scope.launch { prefs.setResizeMode(mode) }
                        },
                    )
                }
            }
        }

        ToggleRow(
            label = "Auto-play next episode",
            description = "When a series episode ends, automatically continue to the next episode in the same season.",
            checked = snapshot.autoPlayNext,
            onCheckedChange = { scope.launch { prefs.setAutoPlayNext(it) } },
        )

        // ── Default audio + subtitle language ──
        LangField(
            label = "Preferred audio language",
            description = "Two- or three-letter ISO 639 code (e.g. en, eng, fre). Applied when a stream ships multiple audio tracks.",
            value = snapshot.audioLanguage,
            onCommit = { scope.launch { prefs.setAudioLanguage(it) } },
        )
        LangField(
            label = "Preferred subtitle language",
            description = "ISO 639 code. Blank = subtitles off by default.",
            value = snapshot.subtitleLanguage,
            onCommit = { scope.launch { prefs.setSubtitleLanguage(it) } },
        )
    }
}

@Composable
private fun ResizeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) LocalYancoPalette.current.Accent.copy(alpha = 0.22f) else LocalYancoPalette.current.BackgroundDeep
    val fg = if (selected) LocalYancoPalette.current.TextPrimary else LocalYancoPalette.current.TextMuted
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(bg)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = fg, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun LangField(
    label: String,
    description: String,
    value: String,
    onCommit: (String) -> Unit,
) {
    // Local draft so typing doesn't round-trip the DB per keystroke. Commit
    // on every non-identity change — the DB write is cheap, and commit-on-
    // blur semantics don't work well without explicit focus tracking on TV.
    var draft by remember(value) { mutableStateOf(value) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(LocalYancoPalette.current.BackgroundRaised)
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = label, color = LocalYancoPalette.current.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(text = description, color = LocalYancoPalette.current.TextMuted, fontSize = 11.sp)
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it.take(6).lowercase()
                onCommit(draft)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            textStyle = TextStyle(color = LocalYancoPalette.current.TextPrimary, fontSize = 13.sp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedTextColor = LocalYancoPalette.current.TextPrimary,
                    unfocusedTextColor = LocalYancoPalette.current.TextPrimary,
                    focusedBorderColor = LocalYancoPalette.current.Accent,
                    unfocusedBorderColor = LocalYancoPalette.current.BackgroundHover,
                    cursorColor = LocalYancoPalette.current.Accent,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(LocalYancoPalette.current.BackgroundRaised)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = LocalYancoPalette.current.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = description, color = LocalYancoPalette.current.TextMuted, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = LocalYancoPalette.current.Accent,
                    checkedTrackColor = LocalYancoPalette.current.Accent.copy(alpha = 0.4f),
                    uncheckedThumbColor = LocalYancoPalette.current.TextMuted,
                    uncheckedTrackColor = LocalYancoPalette.current.BackgroundHover,
                ),
        )
    }
}

// Small convenience — .collectAsState() returns State<T>, `by` delegates
// to its `.value` via the `getValue` import at the top.
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateValue(): androidx.compose.runtime.State<T> = collectAsState()
