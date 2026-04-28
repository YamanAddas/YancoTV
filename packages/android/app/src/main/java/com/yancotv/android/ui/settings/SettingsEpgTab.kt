package com.yancotv.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.types.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * EPG tab — diagnostics + URL editor (via [GuideSyncPanel]) plus the
 * MK.15.1 / 15.2 display prefs (days back/forward, visible timeline)
 * and MK.15.7 source priority. Re-skinned onto [SettingsSection] /
 * [SettingsRow] / [SettingsSlider] so the type scale matches the rest
 * of Settings.
 */
@UnstableApi
@Composable
fun SettingsEpgTab(
    modifier: Modifier = Modifier,
    prefs: AppPreferences = koinInject(),
    sources: SourceRepository = koinInject(),
) {
    val epg by prefs.epgFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val allSources by sources.allFlow().collectAsState(initial = emptyList())
    val orderedSources =
        remember(allSources) { allSources.sortedByDescending { it.epgPriority } }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 32.dp),
    ) {
        // Sync panel keeps its own card chrome — handed in as-is so the
        // diagnostics summary at the top of the tab matches what
        // appears on Home.
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            GuideSyncPanel(
                compact = false,
                onRefreshed = { /* Settings doesn't re-query guide data */ },
            )
        }

        SettingsSection(
            title = "Guide window",
            sub = "How far back catch-up is fetched and how far forward upcoming programmes load.",
        ) {
            SettingsRow(
                label = "Days back",
                hint = "Catch-up window. Higher values increase the EPG payload pulled at every refresh.",
                content = {
                    SettingsSlider(
                        value = epg.daysBack,
                        range = 0..14,
                        unit = " d",
                        presets = listOf(0, 1, 3, 7),
                        onValueChange = { v -> scope.launch { prefs.setEpgDaysBack(v) } },
                    )
                },
            )
            SettingsRowSpacer()
            SettingsRow(
                label = "Days forward",
                hint = "Upcoming-programme window — applies to the guide and reminders.",
                content = {
                    SettingsSlider(
                        value = epg.daysForward,
                        range = 1..14,
                        unit = " d",
                        presets = listOf(1, 3, 7, 14),
                        onValueChange = { v -> scope.launch { prefs.setEpgDaysForward(v) } },
                    )
                },
            )
        }

        SettingsSection(
            title = "Timeline density",
            sub = "How many minutes of the timeline are visible at once in the guide grid.",
        ) {
            SettingsRow(
                label = "Visible window",
                content = {
                    SettingsChipRow(
                        options = EpgPrefs.TIMELINE_PRESETS.map { "$it min" },
                        selected = "${epg.timelineMinutes} min",
                        onSelect = { selection ->
                            val minutes = selection.removeSuffix(" min").toIntOrNull() ?: return@SettingsChipRow
                            scope.launch { prefs.setEpgTimelineMinutes(minutes) }
                        },
                    )
                },
            )
        }

        if (orderedSources.isNotEmpty()) {
            SettingsSection(
                title = "Source priority",
                sub = "When two sources provide programmes for the same channel, the higher-priority source wins. Reorder with the arrows.",
            ) {
                orderedSources.forEachIndexed { idx, src ->
                    if (idx > 0) SettingsRowSpacer()
                    EpgPriorityRow(
                        source = src,
                        canMoveUp = idx > 0,
                        canMoveDown = idx < orderedSources.lastIndex,
                        onMoveUp = {
                            val above = orderedSources[idx - 1]
                            val a = src.epgPriority
                            val b = above.epgPriority
                            val newSelf = if (a == b) b + 1 else b
                            val newAbove = if (a == b) b else a
                            scope.launch(Dispatchers.IO) {
                                sources.setEpgPriority(src.id, newSelf)
                                sources.setEpgPriority(above.id, newAbove)
                            }
                        },
                        onMoveDown = {
                            val below = orderedSources[idx + 1]
                            val a = src.epgPriority
                            val b = below.epgPriority
                            val newSelf = if (a == b) b - 1 else b
                            val newBelow = if (a == b) b else a
                            scope.launch(Dispatchers.IO) {
                                sources.setEpgPriority(src.id, newSelf)
                                sources.setEpgPriority(below.id, newBelow)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpgPriorityRow(
    source: Source,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    SettingsRow(
        label = source.name,
        kicker = "PRIORITY ${source.epgPriority}",
        right = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canMoveUp) {
                    SettingsChip(label = "↑", selected = false, onClick = onMoveUp)
                }
                if (canMoveDown) {
                    SettingsChip(label = "↓", selected = false, onClick = onMoveDown)
                }
                if (!canMoveUp && !canMoveDown) {
                    Text(
                        text = "Only source",
                        color = LocalYancoPalette.current.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    )
}
