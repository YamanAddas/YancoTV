package com.yancotv.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.yancotv.android.sources.SourceSyncCoordinator
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.sources.SyncProgress
import com.yancotv.shared.types.Source
import com.yancotv.shared.types.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.compose.koinInject

/**
 * Sources management. MK.6-minimal: list existing sources, add a new Xtream
 * or M3U URL entry, kick off a sync, delete.
 *
 * Heavy work (add/sync/delete) runs on [Dispatchers.IO] to keep the TV
 * input loop responsive; the list refreshes after each mutation.
 */
@Composable
fun SourcesScreen(
    repo: SourceRepository = koinInject(),
    coordinator: SourceSyncCoordinator = koinInject(),
) {
    val sources = remember { mutableStateListOf<Source>() }
    var showAdd by remember { mutableStateOf(false) }
    var addSaving by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Active sync state lives in the app-scoped coordinator, not a composable
    // scope — so navigating away from Settings no longer kills the sync, and
    // coming back re-binds to the live progress instead of an empty screen.
    val active by coordinator.state.collectAsState()

    // Tick once a second so "Listing categories (12s)" updates in place. No
    // tick when no sync is running — cheap idle cost.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(active != null) {
        if (active == null) return@LaunchedEffect
        while (true) {
            tick = System.currentTimeMillis()
            delay(1000)
        }
    }

    suspend fun refresh() {
        val loaded = withContext(Dispatchers.IO) { repo.getAll() }
        sources.clear()
        sources.addAll(loaded)
    }

    LaunchedEffect(Unit) { refresh() }
    // Refresh the list when a sync completes (active transitions to null).
    LaunchedEffect(active?.sourceId) {
        if (active == null) refresh()
    }

    val syncMessage = active?.let { a ->
        val elapsed = ((tick.coerceAtLeast(a.startedAtMs) - a.startedAtMs) / 1000).coerceAtLeast(0)
        phaseLabel(a.sourceName, a.progress, elapsedSec = elapsed)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Sources", color = YancoPalette.TextPrimary)
            ActionButton(label = "Add source", onClick = { showAdd = true })
        }

        syncMessage?.let {
            Text(text = it, color = YancoPalette.TextMuted)
        }

        if (sources.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No sources yet. Add an Xtream or M3U URL to start.",
                    color = YancoPalette.TextMuted,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sources, key = { it.id }) { source ->
                    val isSyncing = active?.sourceId == source.id
                    SourceRow(
                        source = source,
                        isSyncing = isSyncing,
                        onSync = {
                            // Coordinator enforces single-sync; navigating away
                            // no longer stops the work in progress.
                            coordinator.start(source.id, source.name)
                        },
                        onCancel = { coordinator.cancel() },
                        onDelete = {
                            if (active?.sourceId == source.id) return@SourceRow
                            scope.launch {
                                withContext(Dispatchers.IO) { repo.removeSource(source.id) }
                                refresh()
                            }
                        },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddSourceDialog(
            saving = addSaving,
            saveError = addError,
            onDismiss = {
                if (!addSaving) {
                    showAdd = false
                    addError = null
                }
            },
            onSubmit = { input ->
                scope.launch {
                    addSaving = true
                    addError = null
                    // 15s ceiling so a Keystore or SQLite stall shows as a
                    // real error instead of a forever-"Saving…" spinner. Most
                    // addSource() runs finish in <100ms; anything >15s is a
                    // hang worth surfacing.
                    val result = runCatching {
                        withTimeout(15_000L) {
                            withContext(Dispatchers.IO) { repo.addSource(input) }
                        }
                    }
                    addSaving = false
                    result.onSuccess {
                        showAdd = false
                        addError = null
                        refresh()
                    }.onFailure { t ->
                        addError = when (t) {
                            is TimeoutCancellationException ->
                                "Save timed out after 15s — DB or Keystore is stuck. Restart the app and try again; logcat (adb logcat -s Yanco:*) shows which step stalled."
                            else -> t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName ?: "Unknown error"
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun SourceRow(
    source: Source,
    isSyncing: Boolean,
    onSync: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(YancoPalette.BackgroundRaised)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.6f)) {
            Text(text = source.name, color = YancoPalette.TextPrimary)
            Text(
                text = "${typeLabel(source.type)} · ${source.channelCount} items",
                color = YancoPalette.TextMuted,
            )
        }
        Spacer(Modifier.height(0.dp))
        if (isSyncing) {
            ActionButton(label = "Cancel", onClick = onCancel)
        } else {
            ActionButton(label = "Sync", onClick = onSync)
            ActionButton(label = "Delete", onClick = onDelete)
        }
    }
}

@Composable
fun ActionButton(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = if (focused) YancoPalette.Accent else YancoPalette.BackgroundHover
    val border = if (focused) YancoPalette.FocusRing else YancoPalette.BorderSubtle
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text = label, color = YancoPalette.TextPrimary)
    }
}

private fun typeLabel(type: SourceType): String = when (type) {
    SourceType.XTREAM -> "Xtream"
    SourceType.M3U_URL -> "M3U URL"
    SourceType.M3U_FILE -> "M3U File"
    SourceType.STALKER -> "Stalker"
}

private fun phaseLabel(name: String, p: SyncProgress, elapsedSec: Long = 0): String {
    // Surface `p.message` during FETCHING and WRITING so the user sees which
    // sub-step is live ("Authenticating" / "Live categories" / "Movie
    // categories" / "Series categories" / "Live channels" / "Movies" /
    // "Series") instead of a flat "fetching…" that sits there for up to 90s
    // per retry with zero feedback. Elapsed seconds appended so the user can
    // tell a long-but-progressing fetch from a genuine stall.
    val suffix = p.message?.takeIf { it.isNotBlank() }
    val elapsed = if (elapsedSec > 0) " (${elapsedSec}s)" else ""
    return when (p.phase) {
        SyncProgress.Phase.FETCHING ->
            if (suffix != null) "$name — $suffix…$elapsed" else "$name — fetching…$elapsed"
        SyncProgress.Phase.PARSING -> "$name — parsing…$elapsed"
        SyncProgress.Phase.CLASSIFYING -> "$name — classifying…$elapsed"
        SyncProgress.Phase.WRITING -> {
            val base = if (suffix != null) "$name — $suffix" else "$name — writing"
            if (p.total > 0) "$base ${p.current}/${p.total}$elapsed" else "$base (${p.current})$elapsed"
        }
        SyncProgress.Phase.DONE -> "$name — synced (${p.total})"
        SyncProgress.Phase.ERROR -> "$name — error: ${p.message ?: "unknown"}"
    }
}
