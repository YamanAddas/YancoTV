package com.yancotv.android.ui.settings

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.R
import com.yancotv.android.sources.SourceSyncCoordinator
import com.yancotv.android.sources.syncDetailText
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
fun SourcesScreen(repo: SourceRepository = koinInject(), coordinator: SourceSyncCoordinator = koinInject()) {
    val sources = remember { mutableStateListOf<Source>() }
    // MK.28.4 (MB-257) — saveable so the add-source dialog survives the SAF
    // picker round-trip / background kill together with its form fields.
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var addSaving by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    // When non-null, the detail pane replaces the list view. Persisted via
    // rememberSaveable so process death / activity recreate doesn't bounce
    // the user out of the source they were editing.
    var selectedSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // MK.31.21 — both are assigned from inside coroutines/`onFailure`
    // lambdas, which are not composable scope.
    val saveTimeoutMsg = stringResource(R.string.sr_save_timeout)
    val unknownErrorMsg = stringResource(R.string.common_unknown_error)

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

    // Detail mode: replace the list view with the per-source detail
    // surface. BACK / LEFT inside SourceDetailScreen calls onBack() to
    // close the detail and return here.
    val openDetailId = selectedSourceId
    if (openDetailId != null) {
        // Re-fetch on every refresh — but if the source has been deleted,
        // bail back to the list automatically.
        val openSourceExists = sources.any { it.id == openDetailId }
        LaunchedEffect(openSourceExists) {
            if (!openSourceExists) selectedSourceId = null
        }
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 80.dp),
        ) {
            SourceDetailScreen(
                sourceId = openDetailId,
                repo = repo,
                coordinator = coordinator,
                onBack = {
                    selectedSourceId = null
                    scope.launch { refresh() }
                },
            )
        }
        return
    }

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 80.dp),
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
        // ONE list of sources, not 12 stacked cards. Each row is a
        // clickable card that opens [SourceDetailScreen] — no inline
        // SYNC / DELETE buttons (they cramped the metadata into a tiny
        // strip on Fire TV; the detail pane has the room for them).
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
                        SourceListRow(
                            source = source,
                            isSyncing = isSyncing,
                            palette = palette,
                            onClick = { selectedSourceId = source.id },
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
                        .onSuccess { saved ->
                            showAdd = false
                            addError = null
                            refresh()
                            coordinator.start(saved.id, saved.name)
                            // MK.32.2 — Confirm the action. The dialog
                            // dismisses + the list updates, but the user
                            // doesn't know the sync started until rows
                            // start appearing minutes later.
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.sr_added_syncing, saved.name),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }.onFailure { t ->
                            // Audit catch — was telling end-users to run
                            // `adb logcat -s Yanco:*`, which a Fire TV
                            // remote user obviously can't. Friendlier
                            // user-facing copy + redact raw t.message
                            // (could include internals) and cap to ~140
                            // chars for the generic else branch.
                            android.util.Log.e(
                                "Yanco",
                                "AddSource failed (adb logcat -s Yanco:* shows the failing step): ${t.message}",
                                t,
                            )
                            addError =
                                when (t) {
                                    is TimeoutCancellationException -> saveTimeoutMsg
                                    else -> {
                                        val raw = t.message?.takeIf { it.isNotBlank() }
                                            ?: t::class.simpleName
                                            ?: unknownErrorMsg
                                        com.yancotv.shared.http.redactCredentials(raw).take(140)
                                    }
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
private fun ListHeader(count: Int, onAddClick: () -> Unit) {
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
            // MB-300 — the count used to be a sibling Text in a Row next to
            // the title. Because the title is unweighted and measured first,
            // it consumed the whole budget and the count was handed
            // maxWidth = 0: the number rendered zero-width, so the user could
            // never actually see how many sources they had. Folding it into
            // the overline both fixes that and buys the title the full width.
            Text(
                text = if (count > 0) {
                    stringResource(R.string.sr_your_sources_count, count)
                } else {
                    stringResource(R.string.sr_your_sources)
                },
                color = palette.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            // MB-300 — was 22sp with no maxLines/overflow inside a weighted
            // column sharing a Row with the unweighted ADD SOURCE button.
            // Measured, it wrapped from font scale 0.686x upward: it has
            // never fit on this screen at ANY shipped preset, including 90%.
            // 20sp + a 2-line clamp + ellipsis means it renders on one line
            // at 100% and breaks at a word boundary (never mid-glyph) at 125%.
            Text(
                text = stringResource(
                    if (count == 0) R.string.sr_no_sources_yet else R.string.sr_playlists_providers,
                ),
                color = palette.TextPrimary,
                fontSize = 19.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SettingsAccentButton(onClick = onAddClick) {
            Text(text = stringResource(R.string.src_add_source_btn))
        }
    }
}

/**
 * Active-sync banner. Lives ABOVE the container card so a running sync
 * is impossible to miss but doesn't crowd the list itself. Cancel
 * action lives here, not on the per-row card — single source of truth.
 */
@Composable
private fun SyncBanner(sourceName: String, progress: SyncProgress, elapsedSec: Long, onCancel: () -> Unit) {
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
                text = stringResource(R.string.src_sync_in_progress),
                color = palette.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = message,
                color = palette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SettingsOutlinedButton(onClick = onCancel, size = ButtonSize.Compact) {
            Text(stringResource(R.string.common_cancel_caps))
        }
    }
}

@Composable
private fun EmptyState(onAddClick: () -> Unit, modifier: Modifier = Modifier) {
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
            text = stringResource(R.string.src_none_configured),
            color = palette.TextPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
        )
        Text(
            text = stringResource(R.string.src_none_configured_sub),
            color = palette.TextMuted,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
        SettingsAccentButton(onClick = onAddClick) {
            Text(text = stringResource(R.string.src_add_source_btn))
        }
    }
}

/**
 * One row in the sources list — redesigned (rev. 6).
 *
 * Each row is a single focusable card: `[live-dot] [name + sub-line]
 * [chevron]`. The previous SYNC + DELETE buttons were cramming the
 * source name and metadata into a tiny strip; activating the row now
 * opens [SourceDetailScreen] which has all the room it needs for full
 * info + edit form + Sync / Delete actions.
 *
 * **Focus model:**
 * - The row IS focusable (Row.clickable creates the focus target).
 * - LEFT from anywhere on the row escapes via `startExitsTo(activeTabFocus)`.
 * - RIGHT / CENTER on the row activates [onClick] which navigates into
 *   the detail surface.
 */
@Composable
private fun SourceListRow(source: Source, isSyncing: Boolean, palette: YancoPalette, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val activeTabFocus = LocalActiveSettingsTabFocus.current

    val targetScale = if (focused) 1.01f else 1.0f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 200),
        label = "rowScale",
    )

    val rowBg =
        when {
            focused -> palette.Accent.copy(alpha = 0.12f)
            isSyncing -> palette.Accent.copy(alpha = 0.06f)
            else -> Color.Transparent
        }
    val rowBorder =
        when {
            focused -> palette.FocusRing
            else -> Color.Transparent
        }

    val status = remember(source, isSyncing) { computeRowStatus(source, isSyncing) }

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = if (focused) 14.dp else 0.dp,
                shape = RoundedCornerShape(0.dp),
                ambientColor = palette.AccentGlow,
                spotColor = palette.AccentGlow,
            )
            .background(rowBg)
            .border(
                width = if (focused) 1.5.dp else 0.dp,
                color = rowBorder,
            )
            .startExitsTo(activeTabFocus)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LiveDot(color = status.dotColor(palette), pulsing = isSyncing)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = source.name.ifBlank { stringResource(R.string.src_untitled) },
                color = palette.TextPrimary,
                fontSize = 19.sp,
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
            StatusChip(
                text = stringResource(R.string.src_syncing),
                accent = palette.Accent,
                bg = palette.Accent.copy(alpha = 0.18f),
            )
        } else {
            // Chevron affordance — visual hint that activating the row
            // opens a detail surface, mirroring the system Settings
            // pattern (TiviMate, Android Settings).
            Text(
                text = "›",
                color = if (focused) palette.Accent else palette.TextMuted,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

/** Status of a single source row — drives the dot colour and sub-line text.
 *  Shared with [SourceDetailScreen] so the dot/health vocabulary is identical
 *  across the list and detail surfaces. */
internal enum class RowStatus { Syncing, Ready, Stale, NeverSynced, Error }

internal fun RowStatus.dotColor(palette: YancoPalette): Color = when (this) {
    RowStatus.Syncing, RowStatus.Ready -> palette.Accent
    RowStatus.Stale, RowStatus.NeverSynced -> palette.Premium
    RowStatus.Error -> palette.Error
}

internal fun RowStatus.subColor(palette: YancoPalette): Color = when (this) {
    RowStatus.Error -> palette.Error.copy(alpha = 0.85f)
    else -> palette.TextMuted
}

// MK.31.16 — @Composable. Flagged as outstanding in MK.31.6 and MK.31.9: the
// whole sub-line cluster was plain functions, so extracting the strings needed
// this conversion first. Every caller is already in composable scope (the row's
// Text, and the detail screen's kicker for typeLabel), so this costs nothing.
@Composable
internal fun RowStatus.subLine(source: Source): String {
    val type = typeLabel(source.type)
    val items = formatItemCount(source.channelCount)
    val timing =
        when (this) {
            RowStatus.Syncing -> stringResource(R.string.sl_syncing_now)
            RowStatus.Ready -> nextSyncSuffix(source)
            RowStatus.Stale -> stringResource(R.string.sl_stale)
            RowStatus.NeverSynced -> stringResource(R.string.sl_never_synced)
            RowStatus.Error -> source.lastSyncError?.take(48) ?: stringResource(R.string.sl_last_sync_failed)
        }
    // MK.30.3 — account expiry, when the provider told us one. Appended
    // rather than replacing a segment: "when does this stop working" is
    // orthogonal to sync health, and a source can be perfectly synced and
    // about to lapse. Omitted entirely when unknown (every m3u source, and
    // any xtream source not yet re-synced) so the common row stays 3 parts.
    val expiry =
        formatSourceExpiry(
            source.expiresAt,
            System.currentTimeMillis(),
            expiryStrings(LocalContext.current),
        )?.compact
    return listOfNotNull(type, items, timing, expiry).joinToString(" · ")
}

internal fun computeRowStatus(source: Source, isSyncing: Boolean): RowStatus {
    if (isSyncing) return RowStatus.Syncing
    if (source.lastSyncError != null) return RowStatus.Error
    val last = source.lastSynced ?: return RowStatus.NeverSynced
    val intervalMs = source.autoSyncInterval.coerceAtLeast(1) * 60L * 60L * 1000L
    val ageMs = System.currentTimeMillis() - last
    return if (ageMs > intervalMs) RowStatus.Stale else RowStatus.Ready
}

@Composable
private fun nextSyncSuffix(source: Source): String {
    val last = source.lastSynced ?: return stringResource(R.string.sl_never_synced)
    val intervalMs = source.autoSyncInterval.coerceAtLeast(1) * 60L * 60L * 1000L
    val nextMs = last + intervalMs
    val remaining = nextMs - System.currentTimeMillis()
    if (remaining <= 0L) return stringResource(R.string.sl_due_now)
    val totalMin = remaining / 60_000L
    return when {
        totalMin < 60 -> stringResource(R.string.sl_refresh_in_m, totalMin)
        totalMin < 24 * 60 -> {
            val h = totalMin / 60
            val m = totalMin % 60
            if (m == 0L) {
                stringResource(R.string.sl_refresh_in_h, h)
            } else {
                stringResource(R.string.sl_refresh_in_hm, h, m)
            }
        }
        else -> {
            val d = totalMin / (24 * 60)
            val h = (totalMin % (24 * 60)) / 60
            if (h == 0L) {
                stringResource(R.string.sl_refresh_in_d, d)
            } else {
                stringResource(R.string.sl_refresh_in_dh, d, h)
            }
        }
    }
}

/** Small status dot. Pulses softly while syncing. 10dp diameter at the
 *  far left of every row — the single "is this alive?" indicator that
 *  replaces the previous 44dp type icon + READY chip combo. */
@Composable
private fun LiveDot(color: Color, pulsing: Boolean) {
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
private fun StatusChip(text: String, accent: Color, bg: Color) {
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
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.0.sp,
        )
    }
}

@Composable
private fun HairLine(palette: YancoPalette, indent: androidx.compose.ui.unit.Dp = 0.dp) {
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .height(1.dp)
            .background(palette.BorderSubtle),
    )
}

// Reuses the add-source dialog keys rather than duplicating them — one
// spelling of each provider type across the app.
@Composable
internal fun typeLabel(type: SourceType): String = when (type) {
    SourceType.XTREAM -> stringResource(R.string.add_type_xtream)
    SourceType.M3U_URL -> stringResource(R.string.add_type_m3u_url)
    SourceType.M3U_FILE -> stringResource(R.string.add_type_m3u_file)
    SourceType.STALKER -> stringResource(R.string.add_type_stalker)
}

// The k / M abbreviations pass the already-formatted number as a string arg:
// the decimal separator is locale-dependent (1.2k vs 1,2k) and %.1f inside a
// translated resource would not get one per language.
@Composable
private fun formatItemCount(n: Int): String = when {
    n == 0 -> stringResource(R.string.sl_no_items)
    n == 1 -> stringResource(R.string.sl_one_item)
    n < 1_000 -> stringResource(R.string.sl_n_items, n)
    n < 1_000_000 -> stringResource(R.string.sl_k_items, "%.1f".format(n / 1000.0))
    else -> stringResource(R.string.sl_m_items, "%.1f".format(n / 1_000_000.0))
}

// MK.31.16 — the sync banner. `p.message` comes from packages/shared and is
// still English (KMP cannot reach Android resources); the FRAME around it is
// localized here, so an Arabic user sees Arabic phase text and an English
// provider detail rather than an entirely English line. Localizing the shared
// messages themselves needs a typed progress result and is tracked separately.
@Composable
private fun phaseLabel(name: String, p: SyncProgress, elapsedSec: Long = 0): String {
    val suffix = syncDetailText(p.detail)?.takeIf { it.isNotBlank() }
    val elapsed = if (elapsedSec > 0) stringResource(R.string.sp_elapsed, elapsedSec) else ""
    return when (p.phase) {
        SyncProgress.Phase.FETCHING ->
            if (suffix != null) {
                stringResource(R.string.sp_message, name, suffix, elapsed)
            } else {
                stringResource(R.string.sp_fetching, name, elapsed)
            }
        SyncProgress.Phase.PARSING -> stringResource(R.string.sp_parsing, name, elapsed)
        SyncProgress.Phase.CLASSIFYING -> stringResource(R.string.sp_classifying, name, elapsed)
        SyncProgress.Phase.WRITING -> {
            val base =
                if (suffix != null) {
                    stringResource(R.string.sp_writing_msg, name, suffix)
                } else {
                    stringResource(R.string.sp_writing, name)
                }
            if (p.total > 0) {
                stringResource(R.string.sp_progress, base, p.current, p.total, elapsed)
            } else {
                stringResource(R.string.sp_progress_open, base, p.current, elapsed)
            }
        }
        SyncProgress.Phase.DONE -> stringResource(R.string.sp_done, name, p.total)
        SyncProgress.Phase.ERROR ->
            stringResource(R.string.sp_error, name, suffix ?: stringResource(R.string.sp_unknown))
    }
}
