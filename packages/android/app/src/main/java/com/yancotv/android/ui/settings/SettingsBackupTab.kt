package com.yancotv.android.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.backup.BackupCoordinator
import com.yancotv.android.backup.ExportResult
import com.yancotv.android.backup.ImportResult
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.db.YancoDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * MK.19.8.3 — Backup tab. Two sections:
 *
 *  - **Export** — optional label, optional password, SAF Save dialog
 *    (`ACTION_CREATE_DOCUMENT`) → JSON file written via
 *    [BackupCoordinator]. A `BackupMetadata` row is persisted on every
 *    success so the "Recent backups" list below stays in sync.
 *
 *  - **Import** — SAF Open dialog (`ACTION_OPEN_DOCUMENT`) → password
 *    field (only used when the file is encrypted) → restore via
 *    [BackupCoordinator] → restore report. Merge mode only in v1
 *    (per the active-queue decision).
 *
 *  - **Recent backups** (MK.19.8.5) — last 3 `BackupMetadata` rows,
 *    useful for finding a previous export's URI.
 */
@Composable
fun SettingsBackupTab(
    modifier: Modifier = Modifier,
    coordinator: BackupCoordinator = koinInject(),
    db: YancoDb = koinInject(),
) {
    val scope = rememberCoroutineScope()

    var label by remember { mutableStateOf("") }
    var encryptToggle by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    var importPickedUri by remember { mutableStateOf<Uri?>(null) }
    var importPassword by remember { mutableStateOf("") }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            exporting = true
            exportStatus = "Exporting…"
            scope.launch {
                val result =
                    runCatching {
                        coordinator.export(
                            destination = uri,
                            password = exportPassword.takeIf { encryptToggle && it.isNotBlank() },
                            label = label.takeIf { it.isNotBlank() },
                        )
                    }.getOrElse { ExportResult.Failed(it.message ?: "unknown") }
                exporting = false
                exportStatus =
                    when (result) {
                        is ExportResult.Success ->
                            "Exported ${formatBytes(result.bytesWritten)} — schema v${result.file.dbSchemaVersion}, " +
                                "${result.file.recordCounts.values.sum()} records, " +
                                "checksum ${result.file.checksum.take(8)}…"
                        is ExportResult.Failed -> "Export failed: ${result.message}"
                    }
            }
        }

    val importPickLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            importPickedUri = uri
            importStatus = "Picked ${uri.lastPathSegment ?: uri}"
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
                        // Default filename includes a UTC-ish timestamp
                        // so successive exports don't collide if the
                        // user keeps clicking the same folder.
                        val now = java.time.LocalDateTime.now()
                        val stamp =
                            "%04d-%02d-%02d-%02d%02d".format(
                                now.year,
                                now.monthValue,
                                now.dayOfMonth,
                                now.hour,
                                now.minute,
                            )
                        exportLauncher.launch("yancotv-backup-$stamp.json")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LocalYancoPalette.current.Accent),
                ) {
                    Text(if (exporting) "Exporting…" else "Export backup…")
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
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalYancoPalette.current.TextPrimary),
                ) {
                    Text(if (importPickedUri == null) "Choose backup file…" else "Choose another file…")
                }
            }
            if (importPickedUri != null) {
                Text(
                    "Picked: ${importPickedUri?.lastPathSegment ?: importPickedUri}",
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
                            val pickedUri = importPickedUri ?: return@launch
                            val result = coordinator.import(pickedUri, password = importPassword.takeIf { it.isNotBlank() })
                            importing = false
                            importStatus = formatImportResult(result)
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
            // Re-read on every export-status change so the row appears
            // immediately after a successful export.
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
