package com.yancotv.android.ui.shell

import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.R
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.player.PlayerLauncher
import com.yancotv.android.recording.RecordingService
import com.yancotv.android.recording.schedule.RecordingScheduleScheduler
import com.yancotv.android.ui.components.ButtonSize
import com.yancotv.android.ui.components.ConfirmDangerDialog
import com.yancotv.android.ui.components.YancoPrimaryButton
import com.yancotv.android.ui.components.YancoSecondaryButton
import com.yancotv.android.ui.focus.ProvideFocusScrollSpec
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.recording.RecordingEntry
import com.yancotv.shared.recording.RecordingScheduleEntry
import com.yancotv.shared.recording.RecordingScheduleRepository
import com.yancotv.shared.recording.RecordingScheduleState
import com.yancotv.shared.recording.RecordingStatus
import com.yancotv.shared.recording.RecordingsRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Stage 3.1 / MK.14.5 — sidebar Recordings catalog.
 *
 * Shows every row in the recordings table newest-first; each row
 * surfaces title, recorded date, duration, file size, and status.
 *
 *   - Tap a *completed* row → plays the local file via the existing
 *     `PlaybackController` + `PlayerLauncher` pipeline. The
 *     synthesized [ContentItem]'s id is prefixed [PlaybackController.LOCAL_RECORDING_ID_PREFIX]
 *     so persistResumePoint skips this play (no `content` row exists
 *     to FK against).
 *   - Tap an *in-flight* row → no-op (the row is being written; the
 *     in-app REC indicator + player options sheet handle stop).
 *   - Tap a *failed/cancelled* row → renders an explanation but no
 *     playback (partial files might still be playable but for v1.0
 *     we keep the action surface tight).
 *   - Long-press / RIGHT on any row → delete (DB row + on-disk file).
 *
 * Storage management (cap slider, manual eviction) lives in
 * Settings → Recordings; this screen is the catalog view.
 */
@UnstableApi
@Composable
fun RecordingsScreen(
    isTv: Boolean,
    recordings: RecordingsRepository = koinInject(),
    controller: PlaybackController = koinInject(),
    schedules: RecordingScheduleRepository = koinInject(),
    scheduler: RecordingScheduleScheduler = koinInject(),
) {
    val palette = LocalYancoPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // MB-335 — deletes stage into this and fire only from the dialog.
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    pendingDelete?.let { p ->
        ConfirmDangerDialog(
            title = stringResource(p.titleRes),
            body = if (p.bodyArg != null) {
                stringResource(p.bodyRes, p.bodyArg)
            } else {
                stringResource(p.bodyRes)
            },
            confirmLabel = stringResource(R.string.dlg_delete_cta),
            onConfirm = {
                pendingDelete = null
                p.action()
            },
            onDismiss = { pendingDelete = null },
        )
    }

    // Reactive lists — flip immediately when a recording or schedule
    // changes from any surface (RecordingService writes, schedule
    // create/cancel, alarm fires, boot reconciliation).
    //
    // Audit catch — wrap both flows with .catch{emit(empty)} so a
    // corrupted state-enum / malformed file_path row doesn't crash
    // the screen. Recordings persistence has a deferred-fix history
    // (MB-212) — exactly the surface where a stray row could exist.
    val rows by remember {
        recordings.allFlow().catch { t ->
            android.util.Log.w("Yanco", "RecordingsScreen recordings flow failed: ${t.message}", t)
            emit(emptyList())
        }
    }.collectAsState(initial = emptyList())
    val allSchedules by remember {
        schedules.allFlow().catch { t ->
            android.util.Log.w("Yanco", "RecordingsScreen schedules flow failed: ${t.message}", t)
            emit(emptyList())
        }
    }.collectAsState(initial = emptyList())

    // MK.14.3 — "Upcoming" view shows non-terminal schedules ordered
    // soonest-first. Terminal-state rows (COMPLETED / CANCELLED /
    // MISSED / FAILED) live in the "History" tail below the active
    // recordings so the user can review what fired (and what didn't)
    // without burying the actively-recording row.
    val upcoming =
        remember(allSchedules) {
            allSchedules
                .filter { !it.state.isTerminal() }
                .sortedBy { it.scheduledStart }
        }
    val historySchedules =
        remember(allSchedules) {
            allSchedules
                .filter { it.state.isTerminal() }
                .sortedByDescending { it.updatedAt }
                .take(20) // keep the tail trimmed to avoid scrolling forever
        }

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.rec_title),
                color = palette.TextPrimary,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (rows.isEmpty()) "" else stringResource(R.string.rs_count_suffix, rows.size),
                color = palette.TextMuted,
                fontSize = 14.sp,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (rows.isEmpty() && allSchedules.isEmpty()) {
            EmptyRecordingsState(palette)
        } else {
            ProvideFocusScrollSpec {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (upcoming.isNotEmpty()) {
                        item("upcoming-header") { SectionHeader(stringResource(R.string.rc_hdr_upcoming, upcoming.size), palette) }
                        items(upcoming, key = { "upc-${it.id}" }) { schedule ->
                            UpcomingScheduleRow(
                                entry = schedule,
                                onCancel = {
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { scheduler.cancel(schedule.id) }
                                    }
                                },
                            )
                        }
                        item("upcoming-spacer") { Spacer(modifier = Modifier.height(12.dp)) }
                    }
                    if (rows.isNotEmpty()) {
                        item("recordings-header") { SectionHeader(stringResource(R.string.rec_title), palette) }
                        items(rows, key = { "rec-${it.id}" }) { row ->
                            RecordingRow(
                                entry = row,
                                onPlay = { playRecording(controller, context, row) },
                                onStop = { RecordingService.stop(context, row.id) },
                                onDelete = {
                                    pendingDelete = PendingDelete(
                                        titleRes = R.string.dlg_delete_recording_title,
                                        bodyArg = row.title,
                                        bodyRes = R.string.dlg_delete_recording_body,
                                    ) {
                                        scope.launch(Dispatchers.IO) {
                                            runCatching { deleteRecording(context, recordings, row) }
                                        }
                                    }
                                },
                            )
                        }
                    }
                    if (historySchedules.isNotEmpty()) {
                        item("history-spacer") { Spacer(modifier = Modifier.height(12.dp)) }
                        item("history-header") { SectionHeader(stringResource(R.string.rc_hdr_history), palette) }
                        items(historySchedules, key = { "hist-${it.id}" }) { schedule ->
                            // Pair the schedule with its recording row.
                            // Note: schedule.recordingId is intentionally
                            // never written (recordingScheduleReceiver
                            // sidesteps an FK-timing bug by deriving the
                            // recordId deterministically from the schedule
                            // id — see RecordingScheduleScheduler
                            // .recordIdForSchedule). So fall back to that
                            // derivation when the column is null. Lookup on
                            // the already-collected `rows` list — cheap and
                            // keeps the row composable free of repo plumbing.
                            val derivedRecId =
                                remember(schedule.id, schedule.recordingId) {
                                    schedule.recordingId
                                        ?: RecordingScheduleScheduler.recordIdForSchedule(schedule.id)
                                }
                            val linked =
                                remember(derivedRecId, rows) {
                                    rows.firstOrNull { it.id == derivedRecId }
                                }
                            // Play only when the linked row is actually
                            // playable. FAILED / CANCELLED rows have no usable
                            // file (or 0 bytes) — same gating as RecordingRow.
                            // RECORDING is in flight; not listed in history
                            // anyway since the schedule's still firing.
                            val playable =
                                linked?.takeIf {
                                    it.status == com.yancotv.shared.recording.RecordingStatus.COMPLETED
                                }
                            HistoryScheduleRow(
                                entry = schedule,
                                linkedRecording = linked,
                                onPlay =
                                if (playable != null) {
                                    { playRecording(controller, context, playable) }
                                } else {
                                    null
                                },
                                onDelete = {
                                    // Delete the schedule row; if a linked
                                    // recording exists, delete it too (file +
                                    // DB row) so "Done" entries don't strand
                                    // a phantom recording on disk. Cancelled /
                                    // failed / missed entries have no
                                    // recording to clean up.
                                    pendingDelete = PendingDelete(
                                        titleRes = R.string.dlg_delete_history_title,
                                        bodyArg = null,
                                        bodyRes = R.string.dlg_delete_history_body,
                                    ) {
                                        scope.launch(Dispatchers.IO) {
                                            if (linked != null) {
                                                runCatching { deleteRecording(context, recordings, linked) }
                                            }
                                            runCatching { schedules.deleteById(schedule.id) }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, palette: com.yancotv.android.ui.theme.YancoPalette) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        color = palette.TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun EmptyRecordingsState(palette: com.yancotv.android.ui.theme.YancoPalette) {
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .padding(top = 40.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.rc_none_yet),
                color = palette.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text =
                stringResource(R.string.rc_none_yet_body),
                color = palette.TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}

/**
 * Per-row UX, action buttons by status:
 *   - RECORDING → Stop (primary) + Delete
 *   - COMPLETED → Play (primary) + Delete
 *   - FAILED / CANCELLED → Delete only (the row already shows the
 *     reason; replaying a failed recording isn't a v1.0 feature)
 *
 * The row container is **not** clickable — actions live exclusively
 * in the buttons. Bug feedback from MK.14.5: a "whole row activates
 * play" with a separate Delete button is confusing because users
 * can't predict which gesture goes where, and on TV D-pad CENTER
 * could land either action depending on focus. Two explicit buttons
 * with their own focus targets is unambiguous.
 */
@Composable
private fun RecordingRow(entry: RecordingEntry, onPlay: () -> Unit, onStop: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalYancoPalette.current
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.BackgroundRaised)
            .border(width = 1.dp, color = palette.PanelBorder, shape = shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                color = palette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.metaLine(LocalContext.current),
                color = palette.TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.error?.takeIf { it.isNotBlank() }?.let { reason ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.rc_error, reason),
                    color = palette.Error,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        StatusBadge(entry.status, palette)
        Spacer(modifier = Modifier.width(12.dp))
        when (entry.status) {
            RecordingStatus.RECORDING -> {
                YancoPrimaryButton(onClick = onStop, size = ButtonSize.Compact, translucent = true) {
                    Text(text = stringResource(R.string.common_stop))
                }
                Spacer(modifier = Modifier.width(8.dp))
                YancoSecondaryButton(onClick = onDelete, size = ButtonSize.Compact) {
                    Text(text = stringResource(R.string.common_delete))
                }
            }
            RecordingStatus.COMPLETED, RecordingStatus.CANCELLED -> {
                // Manually-stopped recordings are partial but valid — the
                // recorder flushes the sink on cancel, so the bytes already
                // written are a well-formed prefix that ExoPlayer plays
                // happily. Surface Play next to Delete just like a completed
                // row. FAILED stays Delete-only because a 0-byte file or one
                // killed mid-write isn't something we want to surface as
                // playable.
                YancoPrimaryButton(onClick = onPlay, size = ButtonSize.Compact, translucent = true) {
                    Text(text = stringResource(R.string.common_play))
                }
                Spacer(modifier = Modifier.width(8.dp))
                YancoSecondaryButton(onClick = onDelete, size = ButtonSize.Compact) {
                    Text(text = stringResource(R.string.common_delete))
                }
            }
            RecordingStatus.FAILED -> {
                YancoSecondaryButton(onClick = onDelete, size = ButtonSize.Compact) {
                    Text(text = stringResource(R.string.common_delete))
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: RecordingStatus, palette: com.yancotv.android.ui.theme.YancoPalette) {
    val (label, fg, bg) =
        when (status) {
            RecordingStatus.RECORDING -> Triple(stringResource(R.string.rc_st_rec), palette.BackgroundDeep, palette.Live)
            RecordingStatus.COMPLETED -> Triple(stringResource(R.string.rc_st_saved), palette.BackgroundDeep, palette.Accent)
            RecordingStatus.FAILED -> Triple(stringResource(R.string.rc_st_failed), palette.BackgroundDeep, palette.Error)
            RecordingStatus.CANCELLED -> Triple(stringResource(R.string.rc_st_stopped), palette.TextMuted, palette.BackgroundElevated)
        }
    Box(
        modifier =
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
    }
}

/**
 * MK.14.3 — non-terminal scheduled recording row. Shown in the
 * "Upcoming" section. Cancel is a single visible action (matching
 * RecordingRow's two-button discipline — D-pad finds the action
 * unambiguously). FIRING schedules show "Stop" instead of "Cancel"
 * so the user understands they're stopping an in-flight recording,
 * not just unscheduling something pending.
 */
@Composable
private fun UpcomingScheduleRow(entry: RecordingScheduleEntry, onCancel: () -> Unit) {
    val palette = LocalYancoPalette.current
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.BackgroundRaised)
            .border(width = 1.dp, color = palette.PanelBorder, shape = shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                color = palette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.upcomingMetaLine(LocalContext.current),
                color = palette.TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        ScheduleStateBadge(kind = ScheduleStateBadgeKind.fromState(entry.state), palette = palette)
        Spacer(modifier = Modifier.width(12.dp))
        YancoSecondaryButton(onClick = onCancel, size = ButtonSize.Compact) {
            Text(
                text = if (entry.state == RecordingScheduleState.FIRING) stringResource(R.string.common_stop) else stringResource(R.string.common_cancel),
            )
        }
    }
}

/**
 * MK.14.3 — terminal-state scheduled recording row, no actions.
 * Compact — the user wants to see what fired (or didn't) without
 * the visual weight of the upcoming/recordings rows.
 */
@Composable
private fun HistoryScheduleRow(
    entry: RecordingScheduleEntry,
    linkedRecording: com.yancotv.shared.recording.RecordingEntry?,
    onPlay: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val palette = LocalYancoPalette.current
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.BackgroundDeep.copy(alpha = 0.4f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                color = palette.TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.historyMetaLine(LocalContext.current),
                color = palette.TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        // Promote a COMPLETED schedule with no recording row behind it
        // to the "No file" badge. Tells the user honestly that the
        // alarm fired but nothing was captured (storage permission
        // gap, app process killed during the recording window, or the
        // file was already deleted). Without this the row reads "Done"
        // beside a Delete-only action — confusing.
        val effectiveState =
            if (entry.state == RecordingScheduleState.COMPLETED && linkedRecording == null) {
                ScheduleStateBadgeKind.NO_FILE
            } else {
                ScheduleStateBadgeKind.fromState(entry.state)
            }
        ScheduleStateBadge(kind = effectiveState, palette = palette)
        if (onPlay != null && linkedRecording != null) {
            Spacer(modifier = Modifier.width(8.dp))
            YancoPrimaryButton(onClick = onPlay, size = ButtonSize.Compact, translucent = true) {
                Text(text = stringResource(R.string.common_play))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        YancoSecondaryButton(onClick = onDelete, size = ButtonSize.Compact) {
            Text(text = stringResource(R.string.common_delete))
        }
    }
}

/** UI-side badge variants. Mirrors [RecordingScheduleState] plus a synthetic
 *  NO_FILE for completed schedules whose recording row is missing. */
private enum class ScheduleStateBadgeKind {
    SCHEDULED,
    FIRING,
    COMPLETED,
    NO_FILE,
    FAILED,
    CANCELLED,
    MISSED,
    ;

    companion object {
        fun fromState(state: RecordingScheduleState): ScheduleStateBadgeKind = when (state) {
            RecordingScheduleState.SCHEDULED, RecordingScheduleState.ARMED -> SCHEDULED
            RecordingScheduleState.FIRING -> FIRING
            RecordingScheduleState.COMPLETED -> COMPLETED
            RecordingScheduleState.FAILED -> FAILED
            RecordingScheduleState.CANCELLED -> CANCELLED
            RecordingScheduleState.MISSED -> MISSED
        }
    }
}

@Composable
private fun ScheduleStateBadge(kind: ScheduleStateBadgeKind, palette: com.yancotv.android.ui.theme.YancoPalette) {
    val (label, fg, bg) =
        when (kind) {
            ScheduleStateBadgeKind.SCHEDULED ->
                Triple(stringResource(R.string.rc_st_scheduled), palette.BackgroundDeep, palette.Accent)
            ScheduleStateBadgeKind.FIRING ->
                Triple(stringResource(R.string.rc_st_rec), palette.BackgroundDeep, palette.Live)
            ScheduleStateBadgeKind.COMPLETED ->
                Triple(stringResource(R.string.rc_st_done), palette.BackgroundDeep, palette.Accent)
            ScheduleStateBadgeKind.NO_FILE ->
                Triple(stringResource(R.string.rc_st_no_file), palette.BackgroundDeep, palette.Error)
            ScheduleStateBadgeKind.FAILED ->
                Triple(stringResource(R.string.rc_st_failed), palette.BackgroundDeep, palette.Error)
            ScheduleStateBadgeKind.CANCELLED ->
                Triple(stringResource(R.string.rc_st_cancelled), palette.TextMuted, palette.BackgroundElevated)
            ScheduleStateBadgeKind.MISSED ->
                Triple(stringResource(R.string.rc_st_missed), palette.BackgroundDeep, palette.Error)
        }
    Box(
        modifier =
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
    }
}

// MK.31.22 — Context for the unit strings, and Locale.getDefault() for the
// date: Locale.US meant an Arabic UI still rendered "Mar 3" with Latin digits
// while every sibling line on the same screen used the app locale.
private fun RecordingEntry.metaLine(ctx: android.content.Context): String {
    val date = SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(startedAt))
    val durationStr =
        durationSeconds?.let { secs ->
            val h = secs / 3600
            val m = (secs % 3600) / 60
            if (h > 0) {
                ctx.getString(R.string.rs_dur_hm, h, m)
            } else {
                ctx.getString(R.string.rs_dur_m, m)
            }
        }
    val sizeStr =
        fileSizeBytes?.let { bytes ->
            when {
                bytes >= 1024L * 1024L * 1024L ->
                    ctx.getString(
                        R.string.rs_size_gb,
                        "%.1f".format(Locale.getDefault(), bytes / 1024.0 / 1024.0 / 1024.0),
                    )
                bytes >= 1024L * 1024L -> ctx.getString(R.string.rs_size_mb, bytes / 1024L / 1024L)
                else -> ctx.getString(R.string.rs_size_kb, bytes / 1024L)
            }
        }
    val parts = listOfNotNull(date, durationStr, sizeStr, format?.name)
    return parts.joinToString(" · ")
}

/**
 * Build a synthetic [ContentItem] for the recording and push it through
 * the existing [PlaybackController.play] path. The id prefix tells the
 * controller to skip resume-point persistence (no `content` row exists
 * to FK against — see PlaybackController.persistResumePoint).
 */
@UnstableApi
private fun playRecording(controller: PlaybackController, context: Context, entry: RecordingEntry) {
    val streamUrl =
        // Both absolute file paths and content:// URIs are supported.
        // For File paths we prepend file:// so ExoPlayer's data-source
        // factory routes correctly; content:// passes through unchanged.
        if (entry.filePath.startsWith("content://")) {
            entry.filePath
        } else {
            Uri.fromFile(File(entry.filePath)).toString()
        }
    val synthetic =
        ContentItem(
            id = "${PlaybackController.LOCAL_RECORDING_ID_PREFIX}${entry.id}",
            sourceId = "_recording_local",
            type = ContentType.MOVIE,
            title = entry.title,
            cleanTitle = entry.title,
            groupName = null,
            streamUrl = streamUrl,
            logoUrl = null,
            tvgId = null,
            metadataJson = null,
            sortOrder = 0,
            createdAt = entry.startedAt,
        )
    if (controller.currentId != synthetic.id) {
        controller.play(listOf(synthetic), 0)
    }
    PlayerLauncher.launch(context)
}

/**
 * Delete the recording's row + on-disk file. SAF-backed recordings are
 * resolved via DocumentFile.fromSingleUri; default-path recordings via
 * java.io.File. Best effort — a missing file isn't a fatal error
 * (the row still goes away).
 */
private suspend fun deleteRecording(context: Context, recordings: RecordingsRepository, entry: RecordingEntry) {
    withContext(Dispatchers.IO) {
        runCatching {
            if (entry.filePath.startsWith("content://")) {
                val uri = Uri.parse(entry.filePath)
                DocumentFile.fromSingleUri(context, uri)?.delete()
            } else {
                File(entry.filePath).delete()
            }
        }
        runCatching { recordings.deleteById(entry.id) }
    }
}

/**
 * MK.14.3 — meta line for an upcoming/active schedule:
 * "Tonight 8:00 PM · in 3 hours" / "Recording · ends 9:45 PM" / etc.
 */
private fun RecordingScheduleEntry.upcomingMetaLine(ctx: android.content.Context): String {
    val nowMs = System.currentTimeMillis()
    val timeFmt = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
    val startStr = timeFmt.format(Date(scheduledStart))
    return when (state) {
        RecordingScheduleState.FIRING -> {
            val endTimeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            ctx.getString(R.string.rs_recording_ends, endTimeFmt.format(Date(scheduledEnd)))
        }
        else -> {
            val deltaMs = scheduledStart - nowMs
            val relative =
                when {
                    deltaMs < 0L -> ctx.getString(R.string.rs_starting_now)
                    deltaMs < 60L * 60_000L ->
                        ctx.getString(R.string.rs_in_min, deltaMs / 60_000L)
                    deltaMs < 24L * 60L * 60_000L ->
                        ctx.getString(R.string.rs_in_hours, deltaMs / (60L * 60_000L))
                    else ->
                        ctx.getString(R.string.rs_in_days, deltaMs / (24L * 60L * 60_000L))
                }
            ctx.getString(R.string.rs_meta_join, startStr, relative)
        }
    }
}

/**
 * MK.14.3 — meta line for a terminal-state schedule:
 * "Yesterday 8:00 PM · device was off" / "Tomorrow 9 PM · cancelled" / etc.
 */
private fun RecordingScheduleEntry.historyMetaLine(ctx: android.content.Context): String {
    val timeFmt = SimpleDateFormat("MMM d · h:mm a", Locale.getDefault())
    val startStr = timeFmt.format(Date(scheduledStart))
    val reason =
        error?.takeIf { it.isNotBlank() }?.let { friendlyReason(ctx, it) }
    return if (reason != null) ctx.getString(R.string.rs_meta_join, startStr, reason) else startStr
}

// The four keys are the DB's own reason codes, not display text — they must
// stay literal. Only the right-hand side is localized; the else branch
// prettifies an unrecognised code and cannot be.
private fun friendlyReason(ctx: android.content.Context, rawReason: String): String = when (rawReason) {
    "device_offline" -> ctx.getString(R.string.rs_reason_device_offline)
    "concurrent_recording_active" -> ctx.getString(R.string.rs_reason_concurrent)
    // MB-337 — the fail-closed skip. Kept distinct from the concurrent case
    // so a broken database is not disguised as ordinary cap contention.
    "recording_state_unreadable" -> ctx.getString(R.string.rs_reason_state_unreadable)
    "orphaned_by_app_kill" -> ctx.getString(R.string.rs_reason_orphaned)
    "channel_deleted" -> ctx.getString(R.string.rs_reason_channel_deleted)
    else -> rawReason.replace('_', ' ')
}

/**
 * MB-335 — a staged deletion awaiting the user's confirmation.
 *
 * [bodyArg] is the recording title interpolated into [bodyRes]; null for the
 * history variant, whose body takes no argument. [action] is the exact block
 * that used to run directly in the row's onDelete.
 */
private data class PendingDelete(
    @androidx.annotation.StringRes val titleRes: Int,
    val bodyArg: String?,
    @androidx.annotation.StringRes val bodyRes: Int,
    val action: () -> Unit,
)
