package com.yancotv.android.ui.shell

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.yancotv.android.R
import com.yancotv.android.sources.SourceSyncCoordinator
import com.yancotv.android.sync.EpgSyncReason
import com.yancotv.android.sync.EpgSyncWorker
import com.yancotv.android.ui.components.ButtonSize
import com.yancotv.android.ui.components.YancoPrimaryButton
import com.yancotv.android.ui.components.YancoSecondaryButton
import com.yancotv.android.ui.focus.snapToTopNearStart
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.epg.EpgStats
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.types.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Manual EPG refresh + diagnostics panel.
 *
 * Two render modes:
 *  - `compact = false` — fills the empty-guide state with prominent CTA.
 *  - `compact = true` — thin header strip above the populated grid.
 *
 * Reads [EpgStats] + active sources; observes the `epg-sync-oneshot`
 * WorkManager entry so a running refresh animates live. Offers:
 *  - Refresh EPG now — enqueues [EpgSyncWorker.enqueueOnce].
 *  - Re-sync sources — walks every active source through the existing
 *    [SourceSyncCoordinator], which auto-adopts the M3U `url-tvg` / Xtream
 *    `xmltv.php` EPG URL on success. Fixes catalogs added before EPG
 *    support landed, or when the provider rotates its URL.
 *
 * When the refresh work completes, invokes [onRefreshed] so the calling
 * screen can re-query the guide data.
 */
@Composable
fun GuideSyncPanel(
    compact: Boolean,
    onRefreshed: () -> Unit,
    modifier: Modifier = Modifier,
    epg: EpgRepository = koinInject(),
    sourceRepo: SourceRepository = koinInject(),
    coordinator: SourceSyncCoordinator = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stats by remember { mutableStateOf<EpgStats?>(null) }
    var sources by remember { mutableStateOf<List<Source>>(emptyList()) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var savedGlobalUrl by remember { mutableStateOf<String?>(null) }
    // MK.28.4 (MB-258) — saveable, mirrors SettingsEpgTab.
    var globalUrlDraft by rememberSaveable { mutableStateOf("") }
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(reloadTick) {
        val snapshot =
            withContext(Dispatchers.IO) {
                Snapshot(
                    stats = runCatching { epg.getStats() }.getOrNull(),
                    sources = runCatching { sourceRepo.getAll() }.getOrElse { emptyList() },
                    globalUrl = runCatching { epg.getGlobalEpgUrl() }.getOrNull(),
                    lastError = runCatching { epg.getLastError() }.getOrNull(),
                )
            }
        stats = snapshot.stats
        sources = snapshot.sources
        savedGlobalUrl = snapshot.globalUrl
        lastError = snapshot.lastError
        // Only overwrite the draft when it's untouched (matches previously-saved
        // value or empty). Otherwise we'd stomp a half-typed URL every reload.
        if (globalUrlDraft.isBlank() || globalUrlDraft == (snapshot.globalUrl ?: "")) {
            globalUrlDraft = snapshot.globalUrl.orEmpty()
        }
    }

    val work = rememberEpgWorkFlow(context).collectAsState(initial = emptyList()).value
    val running = work.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    // Prefer the persisted last-error (survives process restart + carries the
    // actual per-source message from EpgRepository.refresh). Fall back to the
    // WorkManager failure payload when the worker itself crashed outside the
    // refresh try/catch.
    val workerError = work.firstOrNull { it.state == WorkInfo.State.FAILED }?.outputData?.getString(EpgSyncWorker.KEY_ERROR)
    val displayError = lastError ?: workerError
    val coordinatorState = coordinator.state.collectAsState().value

    // Bump reloadTick + notify the parent after a real running→idle edge, so
    // the stats + source rows repopulate without the user having to navigate
    // away and back. Gating both transitions behind a "was running" flag is
    // required — without it the effect fires on first composition (key is
    // already "not running"), which bounces the parent between loading and
    // ready and creates a remount loop.
    val epgWasRunning = remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (running) {
            epgWasRunning.value = true
        } else if (epgWasRunning.value) {
            epgWasRunning.value = false
            reloadTick++
            onRefreshed()
        }
    }
    val syncWasRunning = remember { mutableStateOf(false) }
    LaunchedEffect(coordinatorState?.sourceId) {
        if (coordinatorState != null) {
            syncWasRunning.value = true
        } else if (syncWasRunning.value) {
            syncWasRunning.value = false
            reloadTick++
            onRefreshed()
        }
    }

    val activeSources = sources.filter { it.isActive }
    val withEpg = activeSources.count { !it.epgUrl.isNullOrBlank() }
    val brokenSources = activeSources.filter { !it.lastSyncError.isNullOrBlank() }
    val lastRefreshed = stats?.lastRefreshedAt
    val programmes = stats?.programmeCount ?: 0L
    val channels = stats?.channelCount ?: 0L

    val syncing = coordinatorState != null
    val busyLabel =
        when {
            running -> stringResource(R.string.gs_refreshing)
            syncing -> stringResource(
                R.string.gs_resyncing,
                coordinatorState?.sourceName ?: stringResource(R.string.gs_generic_source),
            )
            else -> null
        }

    val doRefreshEpg = { EpgSyncWorker.enqueueOnce(context, EpgSyncReason.USER) }
    val doResyncSources = {
        scope.launch {
            for (src in activeSources) {
                // Sequential — SourceSyncCoordinator enforces single-slot anyway.
                while (coordinator.state.value != null) delay(400)
                coordinator.start(src.id, src.name)
            }
        }
        Unit
    }

    if (compact) {
        Row(
            modifier =
            modifier
                .fillMaxWidth()
                .background(LocalYancoPalette.current.BackgroundRaised)
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // MB-339 — the one case that genuinely cannot be a single
                    // plural: two counts governing two different nouns, and a
                    // plural selects on one quantity. Split, then joined.
                    text =
                    stringResource(
                        R.string.gs_counts_join,
                        pluralStringResource(
                            R.plurals.gs_counts_programmes,
                            programmes.toInt(),
                            programmes,
                        ),
                        pluralStringResource(R.plurals.gs_counts_channels, channels.toInt(), channels),
                    ),
                    color = LocalYancoPalette.current.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = busyLabel
                        ?: subtitleFor(context, lastRefreshed, activeSources.size, withEpg, displayError),
                    color = if (displayError != null && !running) LocalYancoPalette.current.Error else LocalYancoPalette.current.TextMuted,
                    fontSize = 12.sp,
                    // MK.28.8 (MB-279) — live region so TalkBack announces
                    // refresh started / finished / failed transitions.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (running || syncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = LocalYancoPalette.current.Accent,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            // MB-395 — shared button family, not raw M3: an M3 button that
            // disables itself on its own click (`running` flips here) falls
            // out of the focus system and TV focus escapes to the app
            // sidebar. The Yanco family stays focusable while disabled.
            YancoSecondaryButton(
                onClick = { doRefreshEpg() },
                enabled = !running && !syncing,
                size = ButtonSize.Compact,
            ) {
                Text(stringResource(R.string.gs_refresh_epg))
            }
        }
        return
    }

    Box(
        modifier =
        modifier
            .fillMaxWidth()
            .background(LocalYancoPalette.current.BackgroundDeep)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        // MB-336 — this card was a fixed-height centred Column with NO
        // scroll: its content sums to ~400dp on a ~460dp pane, so opening
        // the keyboard on the EPG-URL field (second-to-last block) left the
        // field with nowhere to scroll to and no way to reach it. Scrolling
        // the card also makes the diagnostics list reachable when a user has
        // several broken sources listed.
        val panelScroll = rememberScrollState()
        Column(
            modifier =
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(LocalYancoPalette.current.BackgroundRaised)
                .verticalScroll(panelScroll)
                .snapToTopNearStart(panelScroll)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(
                    if (programmes == 0L) R.string.gs_no_guide_data else R.string.gs_diagnostics,
                ),
                color = LocalYancoPalette.current.TextPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitleFor(context, lastRefreshed, activeSources.size, withEpg, displayError),
                color = if (displayError != null && !running) LocalYancoPalette.current.Error else LocalYancoPalette.current.TextMuted,
                fontSize = 14.sp,
                // MK.28.8 (MB-279) — live region so TalkBack announces
                // refresh started / finished / failed transitions.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (busyLabel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = LocalYancoPalette.current.Accent,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(busyLabel, color = LocalYancoPalette.current.TextPrimary, fontSize = 14.sp)
                }
            }
            if (activeSources.isEmpty()) {
                Text(
                    text = stringResource(R.string.gs_add_source_hint),
                    color = LocalYancoPalette.current.TextMuted,
                    fontSize = 12.sp,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (src in activeSources) {
                        val hasEpg = !src.epgUrl.isNullOrBlank()
                        val err = src.lastSyncError
                        Text(
                            text =
                            buildString {
                                append(
                                    pluralStringResource(
                                        R.plurals.gs_src_line,
                                        src.channelCount,
                                        src.name,
                                        src.channelCount,
                                    ),
                                )
                                append(
                                    stringResource(
                                        if (hasEpg) R.string.gs_epg_url_set else R.string.gs_no_epg_url,
                                    ),
                                )
                                // err is provider text; only the label is localized.
                                if (!err.isNullOrBlank()) {
                                    append(stringResource(R.string.gs_src_err, err))
                                }
                            },
                            color = if (!err.isNullOrBlank() || !hasEpg) LocalYancoPalette.current.Error else LocalYancoPalette.current.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // MB-395 — Yanco family for both: these disable themselves on
                // click (`running`/`syncing` flip), which on raw M3 buttons
                // dropped TV focus onto the app sidebar.
                YancoPrimaryButton(
                    onClick = { doRefreshEpg() },
                    enabled = !running && !syncing && activeSources.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.gs_refresh_epg_now))
                }
                YancoSecondaryButton(
                    onClick = { doResyncSources() },
                    enabled = !running && !syncing && activeSources.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.gs_resync_sources))
                }
            }
            if (brokenSources.isNotEmpty() && !running && !syncing) {
                Text(
                    text =
                    pluralStringResource(
                        R.plurals.gs_broken_sources,
                        brokenSources.size,
                        brokenSources.size,
                    ),
                    color = LocalYancoPalette.current.Error,
                    fontSize = 12.sp,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.gs_add_override_epg),
                color = LocalYancoPalette.current.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text =
                stringResource(R.string.gs_override_help),
                color = LocalYancoPalette.current.TextMuted,
                fontSize = 12.sp,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Click-to-edit (MB-117): D-pad CENTER on the field opens
                // the keyboard; D-pad navigation alone doesn't, so the user
                // can scroll past the row without the IME jumping in.
                //
                // Bare mode: tighter layout — label sits inline with the
                // hint text, then the field strip below. The previous card
                // mode wrapped the field in its own background + padding,
                // making the input feel small and boxy next to the wide
                // Save button.
                com.yancotv.android.ui.settings.SettingsClickToEditField(
                    label = stringResource(R.string.epg_url),
                    value = globalUrlDraft,
                    onValueChange = { globalUrlDraft = it },
                    hint = "https://example.com/xmltv.xml",
                    bare = true,
                    modifier = Modifier.weight(1f),
                )
                // MB-395 — Yanco family: this button disables the moment the
                // save commits (draft == saved), which on raw M3 dropped TV
                // focus out of the panel right after pressing SAVE.
                YancoPrimaryButton(
                    onClick = {
                        scope.launch {
                            val cleaned = globalUrlDraft.trim().ifBlank { null }
                            val ok =
                                withContext(Dispatchers.IO) {
                                    runCatching { epg.setGlobalEpgUrl(cleaned) }
                                        .onFailure { Log.w("Yanco", "GuideSyncPanel.setGlobalEpgUrl failed: ${it.message}", it) }
                                        .isSuccess
                                }
                            if (!ok) return@launch
                            savedGlobalUrl = cleaned
                            // Kick a refresh right after save so the user sees
                            // immediate feedback. KEEP dedups if one's already
                            // in flight.
                            EpgSyncWorker.enqueueOnce(context, EpgSyncReason.USER)
                        }
                    },
                    enabled = globalUrlDraft.trim() != (savedGlobalUrl ?: ""),
                ) {
                    Text(
                        if (globalUrlDraft.isBlank() && savedGlobalUrl != null) {
                            stringResource(R.string.common_clear)
                        } else {
                            stringResource(R.string.common_save)
                        },
                    )
                }
            }
            // MK.31.11 — captured into a local because savedGlobalUrl is a
            // delegated property, so it cannot smart-cast to the non-null Any
            // that stringResource's vararg wants.
            val currentGlobalUrl = savedGlobalUrl
            if (currentGlobalUrl != null) {
                Text(
                    text = stringResource(R.string.gs_current, currentGlobalUrl),
                    color = LocalYancoPalette.current.TextMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private data class Snapshot(val stats: EpgStats?, val sources: List<Source>, val globalUrl: String?, val lastError: String?)

// MK.31.22 — takes a Context: plain function, so stringResource is unavailable.
private fun subtitleFor(ctx: android.content.Context, lastRefreshedMs: Long?, totalSources: Int, withEpg: Int, lastError: String?): String {
    val refreshed =
        when (lastRefreshedMs) {
            null -> ctx.getString(R.string.gs_never_refreshed)
            else ->
                ctx.getString(R.string.gs_last_refreshed, formatRelative(ctx, lastRefreshedMs))
        }
    val srcPart =
        when (totalSources) {
            0 -> ctx.getString(R.string.gs_no_active_sources)
            // Selector is the total, which is the noun the sentence agrees with.
            else ->
                ctx.resources.getQuantityString(
                    R.plurals.gs_sources_with_epg,
                    totalSources,
                    withEpg,
                    totalSources,
                )
        }
    // lastError is provider text; only the label around it is localized.
    val tail = if (lastError != null) ctx.getString(R.string.gs_last_error, lastError) else ""
    return ctx.getString(R.string.gs_subtitle, refreshed, srcPart, tail)
}

private fun formatRelative(ctx: android.content.Context, epochMs: Long): String {
    val diff = (System.currentTimeMillis() - epochMs).coerceAtLeast(0L)
    val min = diff / 60_000L
    val hr = diff / 3_600_000L
    val days = diff / 86_400_000L
    return when {
        min < 1L -> ctx.getString(R.string.rel_just_now_lower)
        min < 60L -> ctx.getString(R.string.rel_min_ago, min)
        hr < 24L -> ctx.getString(R.string.rel_hour_ago, hr)
        else -> ctx.getString(R.string.rel_day_ago, days)
    }
}

@Composable
private fun rememberEpgWorkFlow(context: android.content.Context): Flow<List<WorkInfo>> = remember {
    WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("epg-sync-oneshot")
}
