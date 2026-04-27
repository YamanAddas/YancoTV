package com.yancotv.android.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.yancotv.android.ui.focus.placedFocus
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.backup.BackupCoordinator
import com.yancotv.android.backup.ExportResult
import com.yancotv.android.backup.ImportResult
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.db.YancoDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * MK.19.8.3 — Backup tab.
 *
 * Default export path writes to the public Downloads folder
 * (`Download/YancoTV/yancotv-backup-…json`) via MediaStore on API 29+
 * or direct file write on API ≤28. The user can override with a SAF
 * folder pick that persists across sessions.
 *
 * SAF picker uses `OPEN_DOCUMENT_TREE` (folder picker) — Fire TV's
 * `CREATE_DOCUMENT` (file picker with Save) traps focus on the Save
 * button so D-pad users can't actually save.
 *
 * Form state uses `rememberSaveable` so picker round-trips don't
 * clobber the user's typed label / password.
 */
@Composable
fun SettingsBackupTab(
    modifier: Modifier = Modifier,
    coordinator: BackupCoordinator = koinInject(),
    db: YancoDb = koinInject(),
    prefs: AppPreferences = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Survives configuration changes + process death — picker round-
    // trips don't clear the user's typed label or toggle.
    var label by rememberSaveable { mutableStateOf("") }
    var encryptToggle by rememberSaveable { mutableStateOf(false) }
    var exportPassword by rememberSaveable { mutableStateOf("") }
    var exportStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    var importPickedUriString by rememberSaveable { mutableStateOf<String?>(null) }
    val importPickedUri = importPickedUriString?.let(Uri::parse)
    var importPassword by rememberSaveable { mutableStateOf("") }
    var importStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    // Persisted SAF backup folder URI (null → use MediaStore default).
    var customFolderString by rememberSaveable { mutableStateOf(prefs.readBackupFolderUri()) }
    val customFolder = customFolderString?.let(Uri::parse)

    // Focus anchors — when an SAF picker returns, Compose puts focus
    // wherever it lands by default (often on the sidebar root). Use
    // PlacedFocusAnchor (project's race-free focus primitive) so the
    // request waits for the button's onPlaced callback before firing
    // — bare FocusRequester.requestFocus() races the activity-resume
    // recomposition and silently no-ops on Fire TV.
    val exportButtonAnchor = rememberPlacedFocusAnchor()
    val importButtonAnchor = rememberPlacedFocusAnchor()

    // Bump counters drive the focus-retry LaunchedEffects below. Bumping
    // after every async action / picker callback re-arms the effect; the
    // effect waits several frames so the sidebar's `focusRestorer()` (in
    // SettingsScreen) finishes its activity-resume work BEFORE we
    // re-request focus to the Backup button — otherwise the sidebar
    // restorer grabs focus a beat after our requestFocus() lands and
    // the user sees focus pop back to the sidebar.
    //
    // Single-shot anchor.awaitAndRequest() (the previous approach)
    // races the sidebar's restoration; this variant retries across
    // 5 frames spread over ~80ms which beats the restorer reliably
    // on Fire TV. Tested against the export / pick-folder / pick-
    // file / clear-folder paths.
    var exportFocusBump by remember { mutableStateOf(0) }
    var importFocusBump by remember { mutableStateOf(0) }
    LaunchedEffect(exportFocusBump) {
        if (exportFocusBump == 0) return@LaunchedEffect
        repeat(5) {
            // ~16ms per frame on Fire TV — five iterations covers the
            // activity-resume + recomposition + focusRestorer pulse.
            withFrameNanos { }
            exportFocusBump++
        }
    }
    LaunchedEffect(importFocusBump) {
        if (importFocusBump == 0) return@LaunchedEffect
        repeat(5) {
            withFrameNanos { }
            importFocusBump++
        }
    }

    // Generate a timestamped filename.
    fun makeFilename(): String {
        val now = java.time.LocalDateTime.now()
        return "yancotv-backup-%04d-%02d-%02d-%02d%02d.json".format(
            now.year,
            now.monthValue,
            now.dayOfMonth,
            now.hour,
            now.minute,
        )
    }

    fun runExportToCustomFolder(folder: Uri) {
        val filename = makeFilename()
        exporting = true
        exportStatus = "Exporting to $filename…"
        scope.launch {
            val result =
                runCatching {
                    coordinator.export(
                        folderUri = folder,
                        filename = filename,
                        password = exportPassword.takeIf { encryptToggle && it.isNotBlank() },
                        label = label.takeIf { it.isNotBlank() },
                    )
                }.getOrElse { ExportResult.Failed(it.message ?: "unknown") }
            exporting = false
            exportStatus = formatExportResult(result, filename)
            // Re-grab focus after the picker / coroutine round-trip.
            exportFocusBump++
        }
    }

    fun runExportToDefault() {
        val filename = makeFilename()
        exporting = true
        exportStatus = "Exporting $filename to Downloads/YancoTV…"
        scope.launch {
            val result =
                runCatching {
                    coordinator.exportToDefault(
                        filename = filename,
                        password = exportPassword.takeIf { encryptToggle && it.isNotBlank() },
                        label = label.takeIf { it.isNotBlank() },
                    )
                }.getOrElse { ExportResult.Failed(it.message ?: "unknown") }
            exporting = false
            exportStatus = formatExportResult(result, filename)
            exportFocusBump++
        }
    }

    val changeFolderLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { treeUri: Uri? ->
            if (treeUri == null) {
                exportFocusBump++
                return@rememberLauncherForActivityResult
            }
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            customFolderString = treeUri.toString()
            scope.launch {
                prefs.setBackupFolderUri(treeUri.toString())
            }
            // Immediately export to the freshly-picked folder — that's
            // what the user just confirmed.
            runExportToCustomFolder(treeUri)
        }

    // Initial URI for the import picker — opens to wherever the user
    // last saved a backup so they don't have to navigate from scratch.
    // Priority: persisted custom-folder SAF tree → most recent
    // BackupMetadata.file_uri → null (system default).
    var initialImportUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(customFolderString) {
        initialImportUri =
            customFolder ?: withContext(Dispatchers.IO) {
                runCatching {
                    db.backupMetadataQueries.selectLatest().executeAsOneOrNull()?.file_uri?.let(Uri::parse)
                }.getOrNull()
            }
    }

    val importPickLauncher =
        rememberLauncherForActivityResult(
            contract = OpenDocumentWithInitialUri(initialImportUri),
        ) { uri: Uri? ->
            if (uri == null) {
                importFocusBump++
                return@rememberLauncherForActivityResult
            }
            importPickedUriString = uri.toString()
            importStatus = "Picked ${uri.lastPathSegment ?: uri}"
            importFocusBump++
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .semantics { contentDescription = "Backup settings" },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Backup & restore",
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Export sources, favorites, history, recordings, and settings to a single JSON file. " +
                "Restore on this device or another. Credentials are saved in plaintext unless you enable password encryption.",
            color = LocalYancoPalette.current.TextMuted,
            fontSize = 12.sp,
        )

        // ───── Export ─────
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(LocalYancoPalette.current.BackgroundRaised)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "EXPORT",
                color = LocalYancoPalette.current.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (customFolder != null) {
                    "Saving to: ${customFolder.lastPathSegment ?: customFolder}"
                } else {
                    "Saving to: Downloads/YancoTV (default)"
                },
                color = LocalYancoPalette.current.TextSecondary,
                fontSize = 11.sp,
            )
            SettingsClickToEditField(
                label = "Label (optional)",
                value = label,
                onValueChange = { label = it },
                hint = "e.g. \"Before reinstall\" or \"Living-room TV\"",
                bare = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Switch(
                    checked = encryptToggle,
                    onCheckedChange = { encryptToggle = it },
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = LocalYancoPalette.current.Accent,
                            checkedTrackColor = LocalYancoPalette.current.Accent.copy(alpha = 0.4f),
                        ),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Encrypt with password",
                        color = LocalYancoPalette.current.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (encryptToggle) {
                            "Source credentials will be re-encrypted under your password (PBKDF2 + AES/GCM). You'll need this password to restore."
                        } else {
                            "Source credentials will be in PLAINTEXT in the file. Don't share or upload this file."
                        },
                        color = LocalYancoPalette.current.TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
            if (encryptToggle) {
                SettingsClickToEditField(
                    label = "Password",
                    value = exportPassword,
                    onValueChange = { exportPassword = it },
                    hint = "8+ characters",
                    transformation = PasswordVisualTransformation(),
                    bare = true,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !exporting && (!encryptToggle || exportPassword.length >= 8),
                    onClick = {
                        if (customFolder != null) {
                            runExportToCustomFolder(customFolder)
                        } else {
                            runExportToDefault()
                        }
                    },
                    modifier = Modifier.placedFocus(exportButtonAnchor).focusable(),
                    colors = ButtonDefaults.buttonColors(containerColor = LocalYancoPalette.current.Accent),
                ) {
                    Text(if (exporting) "Exporting…" else "Export backup")
                }
                OutlinedButton(
                    onClick = { changeFolderLauncher.launch(null) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalYancoPalette.current.TextPrimary),
                ) {
                    Text(if (customFolder != null) "Change folder…" else "Pick folder…")
                }
                if (customFolder != null) {
                    OutlinedButton(
                        onClick = {
                            customFolderString = null
                            scope.launch {
                                prefs.setBackupFolderUri(null)
                                exportFocusBump++
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalYancoPalette.current.TextMuted),
                    ) {
                        Text("Reset to default")
                    }
                }
            }
            exportStatus?.let { status ->
                Text(
                    status,
                    color =
                        if (status.startsWith("Export failed")) {
                            LocalYancoPalette.current.Error
                        } else {
                            LocalYancoPalette.current.TextMuted
                        },
                    fontSize = 11.sp,
                )
            }
        }

        // ───── Import ─────
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(LocalYancoPalette.current.BackgroundRaised)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "RESTORE (MERGE MODE)",
                color = LocalYancoPalette.current.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Existing sources, favorites, and history are NOT deleted. New rows are added; conflicting source rows are skipped (your local credentials win). After restore, your sources will resync — favorites and history may take a moment to relink.",
                color = LocalYancoPalette.current.TextMuted,
                fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { importPickLauncher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier.placedFocus(importButtonAnchor).focusable(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalYancoPalette.current.TextPrimary),
                ) {
                    Text(if (importPickedUri == null) "Choose backup file…" else "Choose another file…")
                }
            }
            if (importPickedUri != null) {
                Text(
                    "Picked: ${importPickedUri.lastPathSegment ?: importPickedUri}",
                    color = LocalYancoPalette.current.TextSecondary,
                    fontSize = 11.sp,
                )
                SettingsClickToEditField(
                    label = "Password (only if file is encrypted)",
                    value = importPassword,
                    onValueChange = { importPassword = it },
                    transformation = PasswordVisualTransformation(),
                    bare = true,
                )
                Button(
                    enabled = !importing,
                    onClick = {
                        importing = true
                        importStatus = "Restoring…"
                        scope.launch {
                            val result = coordinator.import(importPickedUri, password = importPassword.takeIf { it.isNotBlank() })
                            importing = false
                            importStatus = formatImportResult(result)
                            importFocusBump++
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LocalYancoPalette.current.Accent),
                ) {
                    Text(if (importing) "Restoring…" else "Restore")
                }
            }
            importStatus?.let { status ->
                Text(
                    status,
                    color =
                        if (status.startsWith("Restore failed") || status.startsWith("Couldn't") || status.startsWith("Wrong")) {
                            LocalYancoPalette.current.Error
                        } else {
                            LocalYancoPalette.current.TextMuted
                        },
                    fontSize = 11.sp,
                )
            }
        }

        // ───── Recent backups (MK.19.8.5) ─────
        val recent = remember { mutableStateListOf<RecentBackup>() }
        LaunchedEffect(exportStatus) {
            withContext(Dispatchers.IO) {
                val rows =
                    runCatching {
                        db.backupMetadataQueries.selectAll().executeAsList().take(3)
                    }.getOrElse { emptyList() }
                recent.clear()
                recent.addAll(
                    rows.map {
                        RecentBackup(
                            id = it.id,
                            label = it.label,
                            createdAt = it.created_at,
                            sizeBytes = it.size_bytes,
                            schemaVersion = it.schema_version,
                            checksum = it.checksum,
                            fileUri = it.file_uri,
                        )
                    },
                )
            }
        }
        if (recent.isNotEmpty()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(LocalYancoPalette.current.BackgroundRaised)
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "RECENT EXPORTS",
                    color = LocalYancoPalette.current.Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                recent.forEach { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            row.label,
                            color = LocalYancoPalette.current.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "${formatBytes(row.sizeBytes)} · schema v${row.schemaVersion} · sha256 ${row.checksum.take(8)}… · ${row.fileUri ?: "(uri lost)"}",
                            color = LocalYancoPalette.current.TextMuted,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * MK.19.8.3 (UX fix #2) — `ActivityResultContracts.OpenDocument`
 * doesn't expose the standard `EXTRA_INITIAL_URI` knob, so the SAF
 * picker always opens to the system default ("Recent" on most
 * Android skins). This subclass adds the extra so we can land the
 * picker on the user's last backup folder. Falls back to the system
 * default when [initialUri] is null or the extra isn't supported.
 *
 * EXTRA_INITIAL_URI was added in API 26. Older devices ignore the
 * extra harmlessly — picker opens to default and the user navigates.
 */
private class OpenDocumentWithInitialUri(
    private val initialUri: Uri?,
) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(
        context: Context,
        input: Array<String>,
    ): Intent {
        val intent = super.createIntent(context, input)
        if (initialUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
        return intent
    }
}

private data class RecentBackup(
    val id: String,
    val label: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val schemaVersion: Long,
    val checksum: String,
    val fileUri: String?,
)

private fun formatBytes(b: Long): String =
    when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "%.1f MB".format(b / (1024.0 * 1024.0))
    }

private fun formatExportResult(r: ExportResult, filename: String): String =
    when (r) {
        is ExportResult.Success ->
            "Exported $filename · ${formatBytes(r.bytesWritten)} · schema v${r.file.dbSchemaVersion} · " +
                "${r.file.recordCounts.values.sum()} records · checksum ${r.file.checksum.take(8)}…"
        is ExportResult.Failed -> "Export failed: ${r.message}"
    }

private fun formatImportResult(r: ImportResult): String =
    when (r) {
        is ImportResult.Success -> {
            val report = r.report
            buildString {
                append("Restored ")
                append(report.totalRestored)
                append(" rows")
                if (report.totalUnlinked > 0) {
                    append(" · ")
                    append(report.totalUnlinked)
                    append(" pending source resync")
                }
                if (report.totalSkipped > 0) {
                    append(" · ")
                    append(report.totalSkipped)
                    append(" already present (skipped)")
                }
                report.warnings.forEach { append(" · ").append(it) }
            }
        }
        is ImportResult.ChecksumMismatch -> "Restore failed: file is corrupted (checksum mismatch)."
        is ImportResult.SchemaTooNew -> "Restore failed: backup is for schema v${r.backupVersion} but app is at v${r.currentVersion}. Update the app first."
        is ImportResult.DecryptFailed -> "Wrong password (or file is corrupted): ${r.message}"
        is ImportResult.MalformedJson -> "Couldn't read the file: ${r.message}"
        is ImportResult.IoError -> "Couldn't open the file: ${r.message}"
        is ImportResult.UnexpectedError -> "Restore failed: ${r.message}"
    }
