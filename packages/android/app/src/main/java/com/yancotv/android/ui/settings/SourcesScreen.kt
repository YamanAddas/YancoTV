package com.yancotv.android.ui.settings

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.sources.SourceSyncCoordinator
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoIcons
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
 * Sources tab — Verdant Frost redesign (rev. 4).
 *
 * Architecture is **one container card with a list inside**, not "many
 * cards stacked." The container has a header (kicker + title + ADD
 * SOURCE) at top, then a hairline-divided list below. Each list row is
 * a single horizontal Row whose meta (name big, type+count caption) sits
 * left, status chip in the middle, and two compact action buttons on
 * the right.
 *
 * **Focus model — what was broken in rev. 3:**
 * - The row Row had `.clickable(onClick = no-op)` which made the row
 *   itself a focus target. That ate D-pad RIGHT presses on the row and
 *   starved the inner SYNC / DELETE buttons. The user couldn't
 *   navigate between Sync and Delete because focus never reached them.
 *
 * Rev. 4 fix:
 * - The row is a pure visual container. NOT focusable, NOT clickable.
 * - Only SYNC and DELETE inside each row are focusable (via the
 *   SettingsOutlinedButton / SettingsDangerButton wrappers).
 * - The row uses `onFocusChanged { hasFocus }` to detect when ANY
 *   descendant has focus, and lights up its background + border when
 *   it does.
 * - D-pad RIGHT now moves SYNC → DELETE within the same row (they're
 *   horizontal siblings). UP / DOWN moves between rows (closest button
 *   in same x). LEFT from SYNC goes back out to the sidebar.
 *
 * **Information hierarchy fix:**
 * - Source name is 18sp Bold (was 15sp SemiBold) — the dominant
 *   element on each row. The point of a sources list is reading the
 *   names; everything else (icon, type/count, chip, actions) is
 *   secondary.
 * - The meta caption (`Xtream · 12.3k items`) and the status chip read
 *   as supporting context, not competing widgets.
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

    val active by coordinator.state.collectAsState()

    // Tick once per second so the sync banner's elapsed counter updates
    // in place. No tick when no sync is running.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(active != null) {
        if (active == null) return@LaunchedEffect
        while (true) {
            tick = System.currentTimeMillis()
            delay(1000)
        }
    }

    suspend fun refresh() {
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching { repo.getAll() }
                    .onFailure { Log.w("Yanco", "SourcesScreen.refresh failed: ${it.message}", it) }
                    .getOrElse { return@withContext null }
            } ?: return
        sources.clear()
        sources.addAll(loaded)
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(active?.sourceId) {
        if (active == null) refresh()
    }

    val palette = LocalYancoPalette.current

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        active?.let { state ->
            val elapsed =
                ((tick.coerceAtLeast(state.startedAtMs) - state.startedAtMs) / 1000)
                    .coerceAtLeast(0)
            SyncBanner(
                sourceName = state.sourceName,
                progress = state.progress,
                elapsedSec = elapsed,
                onCancel = { coordinator.cancel() },
            )
        }

        // Single container card holds the header + the list. Reads as
        // ONE list of sources, not 12 stacked cards.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.BackgroundRaised.copy(alpha = 0.55f))
                    .border(1.dp, palette.PanelBorder, RoundedCornerShape(20.dp)),
        ) {
            ListHeader(
                count = sources.size,
                onAddClick = { showAdd = true },
            )
            HairLine(palette = palette)

            if (sources.isEmpty()) {
                EmptyState(
                    onAddClick = { showAdd = true },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    val last = sources.size - 1
                    items(sources, key = { it.id }) { source ->
                        val isSyncing = active?.sourceId == source.id
                        val isAnotherSyncing = active != null && !isSyncing
                        SourceListRow(
                            source = source,
                            isSyncing = isSyncing,
                            isAnotherSyncing = isAnotherSyncing,
                            palette = palette,
                            onSync = { coordinator.start(source.id, source.name) },
                            onDelete = {
                                if (active?.sourceId == source.id) return@SourceListRow
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        runCatching { repo.removeSource(source.id) }
                                            .onFailure {
                                                Log.w(
                                                    "Yanco",
                                                    "SourcesScreen.removeSource(${source.id}) failed: ${it.message}",
                                                    it,
                                                )
                                            }
                                    }
                                    refresh()
                                }
                            },
                        )
                        if (sources.indexOf(source) != last) {
                            HairLine(palette = palette, indent = 24.dp)
                        }
                    }
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
                    val result =
                        runCatching {
                            withTimeout(15_000L) {
                                withContext(Dispatchers.IO) { repo.addSource(input) }
                            }
                        }
                    addSaving = false
                    result
                        .onSuccess {
                            showAdd = false
                            addError = null
                            refresh()
                        }.onFailure { t ->
                            addError =
                                when (t) {
                                    is TimeoutCancellationException ->
                                        "Save timed out after 15s — DB or Keystore is stuck. Restart the app and try again; logcat (adb logcat -s Yanco:*) shows which step stalled."
                                    else ->
                                        t.message?.takeIf { it.isNotBlank() }
                                            ?: t::class.simpleName
                                            ?: "Unknown error"
                                }
                        }
                }
            },
        )
    }
}

/**
 * Header for the single container card. Lives at the top of the
 * Sources panel, NOT a separate card — keeps the visual "one list,
 * one identity" instead of stacking multiple framed sections.
 */
@Composable
private fun ListHeader(
    count: Int,
    onAddClick: () -> Unit,
) {
    val palette = LocalYancoPalette.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "YOUR SOURCES",
                color = palette.Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (count == 0) "No sources yet" else "Playlists & providers",
                    color = palette.TextPrimary,
                    fontSize = 22.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                )
                if (count > 0) {
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = "$count",
                        color = palette.Accent,
                        fontSize = 22.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp,
                    )
                }
            }
        }
        SettingsAccentButton(onClick = onAddClick) {
            Text(text = "ADD SOURCE")
        }
    }
}

/**
 * Active-sync banner. Lives ABOVE the container card so a running sync
 * is impossible to miss but doesn't crowd the list itself. Cancel
 * action lives here, not on the per-row card — single source of truth.
 */
@Composable
private fun SyncBanner(
    sourceName: String,
    progress: SyncProgress,
    elapsedSec: Long,
    onCancel: () -> Unit,
) {
    val palette = LocalYancoPalette.current
    val message = phaseLabel(sourceName, progress, elapsedSec)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.Accent.copy(alpha = 0.10f))
                .border(1.dp, palette.Accent.copy(alpha = 0.32f), RoundedCornerShape(14.dp))
                .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(palette.Accent),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "SYNC IN PROGRESS",
                color = palette.Accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = message,
                color = palette.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SettingsOutlinedButton(onClick = onCancel, size = ButtonSize.Compact) {
            Text("CANCEL")
        }
    }
}

@Composable
private fun EmptyState(
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalYancoPalette.current
    Column(
        modifier = modifier.padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.Accent.copy(alpha = 0.12f))
                    .border(1.dp, palette.Accent.copy(alpha = 0.32f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = YancoIcons.Link,
                contentDescription = null,
                tint = palette.Accent,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = "No sources configured",
            color = palette.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
        )
        Text(
            text = "Add an Xtream login, M3U URL, M3U file, or Stalker portal to start streaming.",
            color = palette.TextMuted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        SettingsAccentButton(onClick = onAddClick) {
            Text(text = "ADD SOURCE")
        }
    }
}

/**
 * One row in the sources list — redesigned (rev. 5).
 *
 * Layout: `[live-dot] [name + sub-line] [SYNC] [DELETE]`. The 44dp type
 * icon and the verbose READY chip are gone — replaced by a single 10dp
 * status dot whose colour encodes the state (green = healthy, amber =
 * stale / never synced, red = error). The name is the dominant element;
 * the sub-line consolidates type + item count + time-until-next-sync
 * into one muted caption so the row reads in a single sweep.
 *
 * **Focus model:**
 * - Row is NOT focusable. Only SYNC + DELETE buttons are.
 * - `onFocusChanged` lights the row when any descendant has focus.
 * - LEFT from SYNC escapes via `leftExitsTo(activeTabFocus)` — same
 *   contract every Settings row uses, so D-pad LEFT always returns to
 *   the inner sidebar's active tab.
 * - RIGHT cycles SYNC → DELETE within the row.
 *
 * **Wrap fix:** both buttons set `maxLines = 1` + `softWrap = false`
 * on their Text so DELETE never breaks across lines (the user's "DEL /
 * ET / E" complaint).
 */
@Composable
private fun SourceListRow(
    source: Source,
    isSyncing: Boolean,
    isAnotherSyncing: Boolean,
    palette: YancoPalette,
    onSync: () -> Unit,
    onDelete: () -> Unit,
) {
    var hasFocus by remember { mutableStateOf(false) }
    val activeTabFocus = LocalActiveSettingsTabFocus.current

    val targetScale = if (hasFocus) 1.005f else 1.0f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 200),
        label = "rowScale",
    )

    val rowBg =
        when {
            hasFocus -> palette.Accent.copy(alpha = 0.10f)
            isSyncing -> palette.Accent.copy(alpha = 0.05f)
            else -> Color.Transparent
        }
    val rowBorder =
        when {
            hasFocus -> palette.FocusRing
            else -> Color.Transparent
        }

    val status = remember(source, isSyncing) { computeRowStatus(source, isSyncing) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged { hasFocus = it.hasFocus }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = if (hasFocus) 12.dp else 0.dp,
                    shape = RoundedCornerShape(0.dp),
                    ambientColor = palette.AccentGlow,
                    spotColor = palette.AccentGlow,
                )
                .background(rowBg)
                .border(
                    width = if (hasFocus) 1.5.dp else 0.dp,
                    color = rowBorder,
                )
                .leftExitsTo(activeTabFocus)
                .padding(start = 22.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LiveDot(color = status.dotColor(palette), pulsing = isSyncing)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = source.name.ifBlank { "Untitled source" },
                color = palette.TextPrimary,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status.subLine(source),
                color = status.subColor(palette),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isSyncing) {
            // Show a compact "syncing" chip in place of the action
            // buttons so the user can't double-trigger a sync.
            StatusChip(
                text = "SYNCING",
                accent = palette.Accent,
                bg = palette.Accent.copy(alpha = 0.18f),
            )
        } else {
            // The ONLY focusable nodes in the row. D-pad RIGHT cycles
            // SYNC → DELETE; UP / DOWN moves to the corresponding
            // button in the next row.
            SettingsOutlinedButton(
                onClick = onSync,
                enabled = !isAnotherSyncing,
                size = ButtonSize.Compact,
            ) {
                Text(text = "SYNC", maxLines = 1, softWrap = false)
            }
            SettingsDangerButton(
                onClick = onDelete,
                size = ButtonSize.Compact,
            ) {
                Text(text = "DELETE", maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Status of a single source row — drives the dot colour and sub-line text. */
private enum class RowStatus { Syncing, Ready, Stale, NeverSynced, Error }

private fun RowStatus.dotColor(palette: YancoPalette): Color =
    when (this) {
        RowStatus.Syncing, RowStatus.Ready -> palette.Accent
        RowStatus.Stale, RowStatus.NeverSynced -> palette.Premium
        RowStatus.Error -> palette.Error
    }

private fun RowStatus.subColor(palette: YancoPalette): Color =
    when (this) {
        RowStatus.Error -> palette.Error.copy(alpha = 0.85f)
        else -> palette.TextMuted
    }

private fun RowStatus.subLine(source: Source): String {
    val type = typeLabel(source.type)
    val items = formatItemCount(source.channelCount)
    val timing =
        when (this) {
            RowStatus.Syncing -> "syncing now"
            RowStatus.Ready -> nextSyncSuffix(source)
            RowStatus.Stale -> "stale · sync to refresh"
            RowStatus.NeverSynced -> "never synced"
            RowStatus.Error -> source.lastSyncError?.take(48) ?: "last sync failed"
        }
    return "$type · $items · $timing"
}

private fun computeRowStatus(source: Source, isSyncing: Boolean): RowStatus {
    if (isSyncing) return RowStatus.Syncing
    if (source.lastSyncError != null) return RowStatus.Error
    val last = source.lastSynced ?: return RowStatus.NeverSynced
    val intervalMs = source.autoSyncInterval.coerceAtLeast(1) * 60L * 60L * 1000L
    val ageMs = System.currentTimeMillis() - last
    return if (ageMs > intervalMs) RowStatus.Stale else RowStatus.Ready
}

private fun nextSyncSuffix(source: Source): String {
    val last = source.lastSynced ?: return "never synced"
    val intervalMs = source.autoSyncInterval.coerceAtLeast(1) * 60L * 60L * 1000L
    val nextMs = last + intervalMs
    val remaining = nextMs - System.currentTimeMillis()
    if (remaining <= 0L) return "due now"
    val totalMin = remaining / 60_000L
    return when {
        totalMin < 60 -> "refresh in ${totalMin}m"
        totalMin < 24 * 60 -> {
            val h = totalMin / 60
            val m = totalMin % 60
            if (m == 0L) "refresh in ${h}h" else "refresh in ${h}h ${m}m"
        }
        else -> {
            val d = totalMin / (24 * 60)
            val h = (totalMin % (24 * 60)) / 60
            if (h == 0L) "refresh in ${d}d" else "refresh in ${d}d ${h}h"
        }
    }
}

/** Small status dot. Pulses softly while syncing. 10dp diameter at the
 *  far left of every row — the single "is this alive?" indicator that
 *  replaces the previous 44dp type icon + READY chip combo. */
@Composable
private fun LiveDot(
    color: Color,
    pulsing: Boolean,
) {
    val alpha by animateFloatAsState(
        targetValue = if (pulsing) 0.55f else 1.0f,
        animationSpec = tween(durationMillis = 600),
        label = "liveDotAlpha",
    )
    Box(
        modifier =
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
                .shadow(
                    elevation = 6.dp,
                    shape = CircleShape,
                    ambientColor = color,
                    spotColor = color,
                )
                .graphicsLayer { this.alpha = alpha },
    )
}

@Composable
private fun StatusChip(
    text: String,
    accent: Color,
    bg: Color,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.0.sp,
        )
    }
}

@Composable
private fun HairLine(
    palette: YancoPalette,
    indent: androidx.compose.ui.unit.Dp = 0.dp,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = indent)
                .height(1.dp)
                .background(palette.BorderSubtle),
    )
}

private fun typeLabel(type: SourceType): String =
    when (type) {
        SourceType.XTREAM -> "Xtream"
        SourceType.M3U_URL -> "M3U URL"
        SourceType.M3U_FILE -> "M3U File"
        SourceType.STALKER -> "Stalker"
    }

private fun formatItemCount(n: Int): String =
    when {
        n == 0 -> "no items yet"
        n == 1 -> "1 item"
        n < 1_000 -> "$n items"
        n < 1_000_000 -> "%.1fk items".format(n / 1000.0)
        else -> "%.1fM items".format(n / 1_000_000.0)
    }

private fun phaseLabel(
    name: String,
    p: SyncProgress,
    elapsedSec: Long = 0,
): String {
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
