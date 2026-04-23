package com.yancotv.android.ui.shell

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.yancotv.android.sources.SourceSyncCoordinator
import com.yancotv.android.sync.EpgSyncWorker
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.epg.EpgStats
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.types.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    var globalUrlDraft by remember { mutableStateOf("") }
    var reloadTick by remember { mutableStateOf(0) }

    LaunchedEffect(reloadTick) {
        val snapshot = withContext(Dispatchers.IO) {
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
    val busyLabel = when {
        running -> "Refreshing EPG…"
        syncing -> "Re-syncing ${coordinatorState?.sourceName ?: "source"}…"
        else -> null
    }

    val doRefreshEpg = { EpgSyncWorker.enqueueOnce(context) }
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
            modifier = modifier
                .fillMaxWidth()
                .background(YancoPalette.BackgroundRaised)
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$programmes programmes · $channels channels",
                    color = YancoPalette.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = busyLabel ?: subtitleFor(lastRefreshed, activeSources.size, withEpg, displayError),
                    color = if (displayError != null && !running) YancoPalette.Error else YancoPalette.TextMuted,
                    fontSize = 11.sp,
                )
            }
            if (running || syncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = YancoPalette.Accent,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            OutlinedButton(
                onClick = { doRefreshEpg() },
                enabled = !running && !syncing,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = YancoPalette.Accent),
            ) {
                Text("Refresh EPG", fontSize = 12.sp)
            }
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(YancoPalette.BackgroundDeep)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(YancoPalette.BackgroundRaised)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (programmes == 0L) "No guide data yet" else "Guide diagnostics",
                color = YancoPalette.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitleFor(lastRefreshed, activeSources.size, withEpg, displayError),
                color = if (displayError != null && !running) YancoPalette.Error else YancoPalette.TextMuted,
                fontSize = 13.sp,
            )
            if (busyLabel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = YancoPalette.Accent,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(busyLabel, color = YancoPalette.TextPrimary, fontSize = 13.sp)
                }
            }
            if (activeSources.isEmpty()) {
                Text(
                    text = "Add a source in Settings → Sources to get started.",
                    color = YancoPalette.TextMuted,
                    fontSize = 12.sp,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (src in activeSources) {
                        val hasEpg = !src.epgUrl.isNullOrBlank()
                        val err = src.lastSyncError
                        Text(
                            text = buildString {
                                append("• ${src.name}  ·  ${src.channelCount} channels  ·  ")
                                append(if (hasEpg) "EPG URL set" else "no EPG URL")
                                if (!err.isNullOrBlank()) append("  ·  err: $err")
                            },
                            color = if (!err.isNullOrBlank() || !hasEpg) YancoPalette.Error else YancoPalette.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { doRefreshEpg() },
                    enabled = !running && !syncing && activeSources.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = YancoPalette.Accent),
                ) {
                    Text("Refresh EPG now")
                }
                OutlinedButton(
                    onClick = { doResyncSources() },
                    enabled = !running && !syncing && activeSources.isNotEmpty(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = YancoPalette.TextPrimary),
                ) {
                    Text("Re-sync sources")
                }
            }
            if (brokenSources.isNotEmpty() && !running && !syncing) {
                Text(
                    text = "${brokenSources.size} source(s) last failed. Re-sync them to retry.",
                    color = YancoPalette.Error,
                    fontSize = 11.sp,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "ADD / OVERRIDE EPG URL",
                color = YancoPalette.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Paste an XMLTV URL to load EPG from. Used in addition to per-source URLs — useful when a provider's feed is broken or you want a better schedule.",
                color = YancoPalette.TextMuted,
                fontSize = 11.sp,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = globalUrlDraft,
                    onValueChange = { globalUrlDraft = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("https://example.com/xmltv.xml", fontSize = 12.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = YancoPalette.TextPrimary,
                        fontSize = 13.sp,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = YancoPalette.TextPrimary,
                        unfocusedTextColor = YancoPalette.TextPrimary,
                        focusedBorderColor = YancoPalette.Accent,
                        unfocusedBorderColor = YancoPalette.BackgroundHover,
                        cursorColor = YancoPalette.Accent,
                    ),
                )
                Button(
                    onClick = {
                        scope.launch {
                            val cleaned = globalUrlDraft.trim().ifBlank { null }
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { epg.setGlobalEpgUrl(cleaned) }
                                    .onFailure { Log.w("Yanco", "GuideSyncPanel.setGlobalEpgUrl failed: ${it.message}", it) }
                                    .isSuccess
                            }
                            if (!ok) return@launch
                            savedGlobalUrl = cleaned
                            // Kick a refresh right after save so the user sees
                            // immediate feedback. KEEP dedups if one's already
                            // in flight.
                            EpgSyncWorker.enqueueOnce(context)
                        }
                    },
                    enabled = globalUrlDraft.trim() != (savedGlobalUrl ?: ""),
                    colors = ButtonDefaults.buttonColors(containerColor = YancoPalette.Accent),
                ) {
                    Text(if (globalUrlDraft.isBlank() && savedGlobalUrl != null) "Clear" else "Save")
                }
            }
            if (savedGlobalUrl != null) {
                Text(
                    text = "Current: ${savedGlobalUrl}",
                    color = YancoPalette.TextMuted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

private data class Snapshot(
    val stats: EpgStats?,
    val sources: List<Source>,
    val globalUrl: String?,
    val lastError: String?,
)

private fun subtitleFor(lastRefreshedMs: Long?, totalSources: Int, withEpg: Int, lastError: String?): String {
    val refreshed = when (lastRefreshedMs) {
        null -> "never refreshed"
        else -> "last refreshed ${formatRelative(lastRefreshedMs)}"
    }
    val srcPart = when (totalSources) {
        0 -> "no active sources"
        else -> "$withEpg of $totalSources source(s) have an EPG URL"
    }
    val tail = if (lastError != null) " · last error: $lastError" else ""
    return "$refreshed · $srcPart$tail"
}

private fun formatRelative(epochMs: Long): String {
    val diff = (System.currentTimeMillis() - epochMs).coerceAtLeast(0L)
    val min = diff / 60_000L
    val hr = diff / 3_600_000L
    val days = diff / 86_400_000L
    return when {
        min < 1L -> "just now"
        min < 60L -> "${min}m ago"
        hr < 24L -> "${hr}h ago"
        else -> "${days}d ago"
    }
}

@Composable
private fun rememberEpgWorkFlow(context: android.content.Context): Flow<List<WorkInfo>> = remember {
    WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("epg-sync-oneshot")
}
