package com.yancotv.android.ui.shell

import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.player.PlayerLauncher
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.recording.RecordingEntry
import com.yancotv.shared.recording.RecordingStatus
import com.yancotv.shared.recording.RecordingsRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
) {
    val palette = LocalYancoPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Reactive list — flips immediately when a recording starts, stops,
    // fails, or gets deleted from any surface.
    val rows by remember { recordings.allFlow() }.collectAsState(initial = emptyList())

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Recordings",
                color = palette.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (rows.isEmpty()) "" else "· ${rows.size}",
                color = palette.TextMuted,
                fontSize = 14.sp,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (rows.isEmpty()) {
            EmptyRecordingsState(palette)
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(rows, key = { it.id }) { row ->
                    RecordingRow(
                        entry = row,
                        onPlay = {
                            if (row.status == RecordingStatus.COMPLETED) {
                                playRecording(controller, context, row)
                            }
                        },
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                runCatching { deleteRecording(context, recordings, row) }
                            }
                        },
                    )
                }
            }
        }
    }
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
                text = "No recordings yet",
                color = palette.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text =
                    "Open any live channel, press MENU, and pick \"Record this channel\" " +
                        "to start. Recordings appear here as soon as they begin.",
                color = palette.TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun RecordingRow(
    entry: RecordingEntry,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (focused) palette.BackgroundElevated else palette.BackgroundRaised)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) palette.Accent else palette.PanelBorder,
                    shape = shape,
                )
                // Stage 3.1 / MK.14.5 bug fix: TV D-pad needs explicit
                // `.focusable()` paired with the same MutableInteractionSource
                // as the clickable. Modifier.clickable alone reliably
                // gets focus on phone but not on Fire TV — same pattern
                // as FavoritesScreen rows (working since MK.13.x).
                .focusable(interactionSource = interaction)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onPlay,
                )
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
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.metaLine(),
                color = palette.TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
            )
            entry.error?.takeIf { it.isNotBlank() }?.let { reason ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: $reason",
                    color = palette.Error,
                    fontSize = 10.sp,
                    maxLines = 2,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        StatusBadge(entry.status, palette)
        Spacer(modifier = Modifier.width(8.dp))
        FocusableInlineButton(label = "Delete", onClick = onDelete)
    }
}

@Composable
private fun StatusBadge(
    status: RecordingStatus,
    palette: com.yancotv.android.ui.theme.YancoPalette,
) {
    val (label, fg, bg) =
        when (status) {
            RecordingStatus.RECORDING -> Triple("REC", palette.BackgroundDeep, palette.Live)
            RecordingStatus.COMPLETED -> Triple("Saved", palette.BackgroundDeep, palette.Accent)
            RecordingStatus.FAILED -> Triple("Failed", palette.BackgroundDeep, palette.Error)
            RecordingStatus.CANCELLED -> Triple("Stopped", palette.TextMuted, palette.BackgroundElevated)
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
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
    }
}

@Composable
private fun FocusableInlineButton(
    label: String,
    onClick: () -> Unit,
) {
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier =
            Modifier
                .clip(shape)
                .background(if (focused) palette.Accent else palette.BackgroundElevated)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) palette.Accent else palette.PanelBorder,
                    shape = shape,
                )
                // Same pattern as the row above — TV D-pad RIGHT lands
                // here from the row, OK fires onClick.
                .focusable(interactionSource = interaction)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (focused) palette.BackgroundDeep else palette.TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun RecordingEntry.metaLine(): String {
    val date = SimpleDateFormat("MMM d · HH:mm", Locale.US).format(Date(startedAt))
    val durationStr =
        durationSeconds?.let { secs ->
            val h = secs / 3600
            val m = (secs % 3600) / 60
            if (h > 0) "${h}h ${m}m" else "${m}m"
        }
    val sizeStr =
        fileSizeBytes?.let { bytes ->
            when {
                bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(Locale.US, bytes / 1024.0 / 1024.0 / 1024.0)
                bytes >= 1024L * 1024L -> "${bytes / 1024L / 1024L} MB"
                else -> "${bytes / 1024L} KB"
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
private fun playRecording(
    controller: PlaybackController,
    context: Context,
    entry: RecordingEntry,
) {
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
private suspend fun deleteRecording(
    context: Context,
    recordings: RecordingsRepository,
    entry: RecordingEntry,
) {
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
