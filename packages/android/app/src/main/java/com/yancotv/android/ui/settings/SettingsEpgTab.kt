package com.yancotv.android.ui.settings

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.yancotv.android.R
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.EpgPrefs
import com.yancotv.android.sources.SourceSyncCoordinator
import com.yancotv.android.sync.EpgSyncReason
import com.yancotv.android.sync.EpgSyncWorker
import com.yancotv.android.ui.focus.snapToTopNearStart
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.epg.EpgStats
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.types.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * EPG tab — guide diagnostics, optional override URL, display prefs
 * (days back/forward, visible timeline) and per-source priority.
 *
 * The previous version embedded `GuideSyncPanel(compact = false)` at
 * the top — that panel was designed for the home screen's empty-guide
 * state, so it rendered as a centered card on a `BackgroundDeep`
 * wrapper that read as a "weird vertical empty pill" inside the
 * Settings panel chrome. This rewrite inlines the same data + actions
 * as proper [SettingsSection] / [SettingsRow] rows so the tab matches
 * the rest of Settings.
 */
@UnstableApi
@Composable
fun SettingsEpgTab(
    modifier: Modifier = Modifier,
    prefs: AppPreferences = koinInject(),
    sources: SourceRepository = koinInject(),
    epg: EpgRepository = koinInject(),
    coordinator: SourceSyncCoordinator = koinInject(),
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val epgState by prefs.epgFlow.collectAsState()
    // Audit catch — protect against a corrupted source row crashing
    // the EPG tab (lower-impact than Home but consistent with rule 7).
    val allSources by remember {
        sources.allFlow().catch { t ->
            android.util.Log.w("Yanco", "SettingsEpgTab sources flow failed: ${t.message}", t)
            emit(emptyList())
        }
    }.collectAsState(initial = emptyList())
    val orderedSources =
        remember(allSources) { allSources.sortedByDescending { it.epgPriority } }
    val activeSources = remember(allSources) { allSources.filter { it.isActive } }
    val withEpg = activeSources.count { !it.epgUrl.isNullOrBlank() }

    // Diagnostics + override URL state — pulled in directly instead of
    // reading via GuideSyncPanel, so the rendering can match the rest of
    // Settings (no nested centered-card chrome).
    var stats by remember { mutableStateOf<EpgStats?>(null) }
    var savedGlobalUrl by remember { mutableStateOf<String?>(null) }
    // MK.28.4 (MB-258) — saveable: long XMLTV URLs are exactly what users
    // app-switch to copy; plain remember lost the draft to background kill.
    var globalUrlDraft by rememberSaveable { mutableStateOf("") }
    var lastError by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(reloadTick) {
        val snapshot =
            withContext(Dispatchers.IO) {
                Triple(
                    runCatching { epg.getStats() }.getOrNull(),
                    runCatching { epg.getGlobalEpgUrl() }.getOrNull(),
                    runCatching { epg.getLastError() }.getOrNull(),
                )
            }
        stats = snapshot.first
        savedGlobalUrl = snapshot.second
        lastError = snapshot.third
        if (globalUrlDraft.isBlank() || globalUrlDraft == (snapshot.second ?: "")) {
            globalUrlDraft = snapshot.second.orEmpty()
        }
    }

    val work = rememberEpgWorkFlow(ctx).collectAsState(initial = emptyList()).value
    val running = work.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    val workerError = work.firstOrNull { it.state == WorkInfo.State.FAILED }
        ?.outputData?.getString(EpgSyncWorker.KEY_ERROR)
    val displayError = lastError ?: workerError
    val coordinatorState = coordinator.state.collectAsState().value
    val syncing = coordinatorState != null

    // Live reload of stats once a refresh transitions running → idle, so the
    // "Last refreshed" / "Programmes" rows update without leaving the tab.
    val epgWasRunning = remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (running) {
            epgWasRunning.value = true
        } else if (epgWasRunning.value) {
            epgWasRunning.value = false
            reloadTick++
        }
    }
    val syncWasRunning = remember { mutableStateOf(false) }
    LaunchedEffect(coordinatorState?.sourceId) {
        if (coordinatorState != null) {
            syncWasRunning.value = true
        } else if (syncWasRunning.value) {
            syncWasRunning.value = false
            reloadTick++
        }
    }

    // MK.30.6 — hoisted so snapToTopNearStart can see the same state.
    val tabScroll = rememberScrollState()

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(tabScroll)
            .snapToTopNearStart(tabScroll)
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 80.dp),
    ) {
        SettingsSection(
            title = stringResource(R.string.epg_sec_diag),
            sub = stringResource(R.string.epg_sec_diag_sub),
        ) {
            SettingsRow(
                label = stringResource(R.string.epg_last_refreshed),
                readOnlyFocusable = true,
                right = { ValueText(formatLastRefreshed(stats?.lastRefreshedAt)) },
            )
            SettingsRowSpacer()
            SettingsRow(
                label = stringResource(R.string.epg_programmes_loaded),
                readOnlyFocusable = true,
                right = { ValueText(formatCount(stats?.programmeCount ?: 0L)) },
            )
            SettingsRowSpacer()
            SettingsRow(
                label = stringResource(R.string.epg_channels_with_epg),
                readOnlyFocusable = true,
                right = { ValueText("$withEpg of ${activeSources.size}") },
            )
            if (displayError != null) {
                SettingsRowSpacer()
                SettingsRow(
                    label = stringResource(R.string.epg_last_error),
                    hint = displayError,
                    readOnlyFocusable = true,
                    right = {
                        Text(
                            text = stringResource(R.string.epg_kicker_error),
                            color = LocalYancoPalette.current.Error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp,
                        )
                    },
                )
            }
            SettingsRowSpacer()
            SettingsRow(
                label = stringResource(R.string.epg_refresh_actions),
                hint =
                when {
                    running -> "Refreshing EPG…"
                    syncing -> "Re-syncing ${coordinatorState?.sourceName ?: "source"}…"
                    activeSources.isEmpty() -> "Add a source first — Settings → Sources."
                    else -> "REFRESH pulls a fresh EPG payload. RE-SYNC re-walks every active source so a rotated EPG URL is auto-adopted."
                },
                content = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsAccentButton(
                            onClick = { EpgSyncWorker.enqueueOnce(ctx, EpgSyncReason.USER) },
                            enabled = !running && !syncing && activeSources.isNotEmpty(),
                            size = ButtonSize.Compact,
                            translucent = true,
                        ) {
                            Text(text = stringResource(R.string.epg_btn_refresh), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                        SettingsOutlinedButton(
                            onClick = {
                                scope.launch {
                                    for (src in activeSources) {
                                        while (coordinator.state.value != null) delay(400)
                                        coordinator.start(src.id, src.name)
                                    }
                                }
                            },
                            enabled = !running && !syncing && activeSources.isNotEmpty(),
                            size = ButtonSize.Compact,
                        ) {
                            Text(text = stringResource(R.string.epg_btn_resync), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                        if (running || syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = LocalYancoPalette.current.Accent,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                },
            )
        }

        SettingsSection(
            title = stringResource(R.string.epg_sec_override),
            sub = stringResource(R.string.epg_sec_override_sub),
        ) {
            SettingsRow(
                label = stringResource(R.string.epg_url),
                hint = savedGlobalUrl?.let { "Current: $it" },
                content = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingsClickToEditField(
                            label = stringResource(R.string.epg_url),
                            value = globalUrlDraft,
                            onValueChange = { globalUrlDraft = it },
                            hint = "https://example.com/xmltv.xml",
                            bare = true,
                            modifier = Modifier.fillMaxWidth(0.7f),
                        )
                        SettingsAccentButton(
                            onClick = {
                                scope.launch {
                                    val cleaned = globalUrlDraft.trim().ifBlank { null }
                                    val ok =
                                        withContext(Dispatchers.IO) {
                                            runCatching { epg.setGlobalEpgUrl(cleaned) }
                                                .onFailure {
                                                    Log.w(
                                                        "Yanco",
                                                        "SettingsEpgTab.setGlobalEpgUrl failed: ${it.message}",
                                                        it,
                                                    )
                                                }
                                                .isSuccess
                                        }
                                    if (!ok) return@launch
                                    savedGlobalUrl = cleaned
                                    EpgSyncWorker.enqueueOnce(ctx, EpgSyncReason.USER)
                                    reloadTick++
                                }
                            },
                            enabled = globalUrlDraft.trim() != (savedGlobalUrl ?: ""),
                            size = ButtonSize.Compact,
                            translucent = true,
                        ) {
                            Text(
                                text =
                                if (globalUrlDraft.isBlank() && savedGlobalUrl != null) {
                                    "CLEAR"
                                } else {
                                    "SAVE"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false,
                            )
                        }
                    }
                },
            )
        }

        SettingsSection(
            title = stringResource(R.string.epg_sec_window),
            sub = stringResource(R.string.epg_sec_window_sub),
        ) {
            SettingsRow(
                label = stringResource(R.string.epg_days_back),
                hint = stringResource(R.string.epg_days_back_hint),
                content = {
                    SettingsSlider(
                        value = epgState.daysBack,
                        range = 0..14,
                        unit = " d",
                        presets = listOf(0, 1, 3, 7),
                        onValueChange = { v -> scope.launch { prefs.setEpgDaysBack(v) } },
                    )
                },
            )
            SettingsRowSpacer()
            SettingsRow(
                label = stringResource(R.string.epg_days_forward),
                hint = stringResource(R.string.epg_days_forward_hint),
                content = {
                    SettingsSlider(
                        value = epgState.daysForward,
                        range = 1..14,
                        unit = " d",
                        presets = listOf(1, 3, 7, 14),
                        onValueChange = { v -> scope.launch { prefs.setEpgDaysForward(v) } },
                    )
                },
            )
        }

        SettingsSection(
            title = stringResource(R.string.epg_sec_density),
            sub = stringResource(R.string.epg_sec_density_sub),
        ) {
            SettingsRow(
                label = stringResource(R.string.epg_visible_window),
                content = {
                    SettingsChipRow(
                        options = EpgPrefs.TIMELINE_PRESETS.map { "$it min" },
                        selected = "${epgState.timelineMinutes} min",
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
                title = stringResource(R.string.epg_sec_priority),
                sub = stringResource(R.string.epg_sec_priority_sub),
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
private fun ValueText(value: String) {
    Text(
        text = value,
        color = LocalYancoPalette.current.TextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun EpgPriorityRow(source: Source, canMoveUp: Boolean, canMoveDown: Boolean, onMoveUp: () -> Unit, onMoveDown: () -> Unit) {
    SettingsRow(
        label = source.name,
        kicker = stringResource(R.string.epg_priority_kicker, source.epgPriority),
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
                        text = stringResource(R.string.epg_only_source),
                        color = LocalYancoPalette.current.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    )
}

private fun formatLastRefreshed(epochMs: Long?): String {
    if (epochMs == null) return "Never"
    val diff = (System.currentTimeMillis() - epochMs).coerceAtLeast(0L)
    val min = diff / 60_000L
    val hr = diff / 3_600_000L
    val days = diff / 86_400_000L
    return when {
        min < 1L -> "Just now"
        min < 60L -> "${min}m ago"
        hr < 24L -> "${hr}h ago"
        else -> "${days}d ago"
    }
}

private fun formatCount(n: Long): String = when {
    n < 1_000L -> n.toString()
    n < 10_000L -> String.format(java.util.Locale.US, "%.1fk", n / 1000.0)
    else -> "${n / 1000L}k"
}

@Composable
private fun rememberEpgWorkFlow(context: android.content.Context): Flow<List<WorkInfo>> = remember {
    WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("epg-sync-oneshot")
}
