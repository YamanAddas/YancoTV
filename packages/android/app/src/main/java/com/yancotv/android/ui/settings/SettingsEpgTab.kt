package com.yancotv.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
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
 * EPG tab — hosts diagnostics + URL editor (via [GuideSyncPanel]) plus
 * MK.15.1 / 15.2 display prefs (days back/forward, visible timeline).
 *
 * Controls are deliberately TV-focusable:
 *   - Days are stepped via −/+ buttons (Material3 [androidx.compose.material3.Slider]
 *     doesn't honour D-pad LEFT/RIGHT cleanly on Fire TV).
 *   - Timeline span uses [SettingsChip] which is the same focus surface
 *     the rest of the settings screens use, so the focus ring renders
 *     consistently and CENTER picks the chip.
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

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(text = "Guide window")

            DaysStepper(
                label = "Days back (catch-up)",
                value = epg.daysBack,
                range = 0..14,
                onChange = { v -> scope.launch { prefs.setEpgDaysBack(v) } },
            )
            DaysStepper(
                label = "Days forward (upcoming)",
                value = epg.daysForward,
                range = 1..14,
                onChange = { v -> scope.launch { prefs.setEpgDaysForward(v) } },
            )

            SectionHeader(text = "Timeline density")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EpgPrefs.TIMELINE_PRESETS.forEach { minutes ->
                    SettingsChip(
                        label = "$minutes min",
                        selected = epg.timelineMinutes == minutes,
                        onClick = { scope.launch { prefs.setEpgTimelineMinutes(minutes) } },
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
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/**
 * Two-button stepper. CENTER on −/+ steps the value by 1 within
 * [range]; D-pad LEFT/RIGHT moves focus between the buttons. Replaces
 * Material3 Slider, which on Fire TV silently swallows CENTER.
 */
@Composable
private fun DaysStepper(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 14.sp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsChip(
                label = "−",
                selected = false,
                onClick = {
                    if (value > range.first) onChange(value - 1)
                },
            )
            Text(
                text = "$value day${if (value == 1) "" else "s"}",
                color = LocalYancoPalette.current.Accent,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            SettingsChip(
                label = "+",
                selected = false,
                onClick = {
                    if (value < range.last) onChange(value + 1)
                },
            )
            Text(
                text = "(${range.first}–${range.last})",
                color = LocalYancoPalette.current.TextMuted,
                fontSize = 11.sp,
            )
        }
    }
}
