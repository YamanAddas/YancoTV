package com.yancotv.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.EpgPrefs
import com.yancotv.android.ui.shell.GuideSyncPanel
import com.yancotv.android.ui.theme.LocalYancoPalette
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * EPG tab — hosts the detailed diagnostics + refresh + URL editor UI plus
 * MK.15.1 / 15.2 display prefs (days back/forward, visible timeline span).
 */
@UnstableApi
@Composable
fun SettingsEpgTab(
    modifier: Modifier = Modifier,
    prefs: AppPreferences = koinInject(),
) {
    val epg by prefs.epgFlow.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
    ) {
        GuideSyncPanel(
            compact = false,
            onRefreshed = { /* Settings doesn't re-query guide data */ },
        )

        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            SectionHeader(text = "Guide window")
            DaysSlider(
                label = "Days back (catch-up)",
                value = epg.daysBack,
                range = 0..14,
                onChange = { v -> scope.launch { prefs.setEpgDaysBack(v) } },
            )
            DaysSlider(
                label = "Days forward (upcoming)",
                value = epg.daysForward,
                range = 1..14,
                onChange = { v -> scope.launch { prefs.setEpgDaysForward(v) } },
            )

            SectionHeader(text = "Timeline density")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EpgPrefs.TIMELINE_PRESETS.forEach { minutes ->
                    val selected = epg.timelineMinutes == minutes
                    FilterChip(
                        selected = selected,
                        onClick = { scope.launch { prefs.setEpgTimelineMinutes(minutes) } },
                        label = { Text(text = "$minutes min") },
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LocalYancoPalette.current.Accent,
                                selectedLabelColor = LocalYancoPalette.current.BackgroundDeep,
                            ),
                        shape = RoundedCornerShape(6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        color = LocalYancoPalette.current.TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun DaysSlider(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
            Text(
                text = label,
                color = LocalYancoPalette.current.TextPrimary,
                fontSize = 14.sp,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(
                text = "$value day${if (value == 1) "" else "s"}",
                color = LocalYancoPalette.current.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            colors =
                SliderDefaults.colors(
                    thumbColor = LocalYancoPalette.current.Accent,
                    activeTrackColor = LocalYancoPalette.current.Accent,
                ),
        )
    }
}
