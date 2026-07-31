package com.yancotv.android.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.R
import com.yancotv.android.backup.BackupCoordinator
import com.yancotv.android.backup.ExportResult
import com.yancotv.android.backup.ImportResult
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.focus.placedFocus
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import com.yancotv.android.ui.focus.snapToTopNearStart
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.db.YancoDb
import java.io.File
import java.util.Calendar
import java.util.Locale
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
    // MK.31.20 — used inside a `getOrElse` lambda, which is not composable scope.
    val unknownMsg = stringResource(R.string.bk_unknown)
    // MK.31.17 — resolved here; semantics{} is not composable scope.
    val backupDesc = stringResource(R.string.bk_settings_desc)

    // Survives configuration changes + process death — picker round-
    // trips don't clear the user's typed label or toggle.
    var label by rememberSaveable { mutableStateOf("") }
    var encryptToggle by rememberSaveable { mutableStateOf(false) }
    var exportPassword by rememberSaveable { mutableStateOf("") }
    var exportStatus by rememberSaveable { mutableStateOf<String?>(null) }
    // MK.31.20 — the colour used to be chosen with
    // `status.startsWith("Export failed")`. Once the string is translated
    // that match never fires, so every failure would render in the muted
    // colour. The flag travels beside the text instead. Kept as a separate
    // primitive rather than a data class so `rememberSaveable` still works
    // without a custom Saver.
    var exportStatusIsError by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    var importPickedUriString by rememberSaveable { mutableStateOf<String?>(null) }
    val importPickedUri = importPickedUriString?.let(Uri::parse)
    var importPassword by rememberSaveable { mutableStateOf("") }
    var importStatus by rememberSaveable { mutableStateOf<String?>(null) }
    // See exportStatusIsError above.
    var importStatusIsError by rememberSaveable { mutableStateOf(false) }
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
    // MK.28.5 (MB-264) — the shipped effect bodies incremented their OWN
    // key inside the loop and never actually requested focus: every
    // increment cancelled + restarted the effect, which incremented again —
    // an unbounded per-frame effect-restart chain (constant CPU/battery
    // churn for as long as the tab stayed mounted after any action) that
    // ALSO left the documented SAF-return focus drop unfixed. The retry now
    // re-requests via the anchor each frame and never touches its key; the
    // action/picker callbacks below re-arm it by bumping.
    var exportFocusBump by remember { mutableStateOf(0) }
    var importFocusBump by remember { mutableStateOf(0) }
    LaunchedEffect(exportFocusBump) {
        if (exportFocusBump == 0) return@LaunchedEffect
        repeat(5) {
            // ~16ms per frame on Fire TV — five iterations covers the
            // activity-resume + recomposition + focusRestorer pulse.
            withFrameNanos { }
            runCatching { exportButtonAnchor.awaitAndRequest() }
        }
    }
    LaunchedEffect(importFocusBump) {
        if (importFocusBump == 0) return@LaunchedEffect
        repeat(5) {
            withFrameNanos { }
            runCatching { importButtonAnchor.awaitAndRequest() }
        }
    }

    // Generate a timestamped filename.
    //
    // MB-294 — `java.time.LocalDateTime` is API 26 and minSdk is 24, with no
    // core-library desugaring configured, so this threw NoClassDefFoundError
    // on API 24/25 the moment the user tapped Export. Fire OS 6 is API 25 —
    // this is the same bug class as MB-241 (the API-26-only PBKDF2 factory
    // that crashed 1.3.7 at startup), just on the backup path instead of the
    // launch path. `Calendar` is API 1 and gives the same fields.
    //
    // Locale.US on the format is deliberate and load-bearing: the default
    // locale renders %04d with Arabic-Indic digits under an Arabic locale,
    // which would put non-ASCII digits in a FILENAME. Same DefaultLocale
    // lesson as the time-code fix in D.1a-fixes.
    fun makeFilename(): String {
        val now = Calendar.getInstance()
        return String.format(
            Locale.US,
            "yancotv-backup-%04d-%02d-%02d-%02d%02d.json",
            now.get(Calendar.YEAR),
            // Calendar.MONTH is 0-based; LocalDateTime.monthValue was 1-based.
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH),
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
        )
    }

    fun runExportToCustomFolder(folder: Uri) {
        val filename = makeFilename()
        exporting = true
        exportStatus = context.getString(R.string.bk_exporting_to, filename)
        exportStatusIsError = false
        scope.launch {
            val result =
                runCatching {
                    coordinator.export(
                        folderUri = folder,
                        filename = filename,
                        password = exportPassword.takeIf { encryptToggle && it.isNotBlank() },
                        label = label.takeIf { it.isNotBlank() },
                    )
                }.getOrElse { ExportResult.Failed(it.message ?: unknownMsg) }
            exporting = false
            exportStatus = formatExportResult(context, result, filename)
            exportStatusIsError = result is ExportResult.Failed
            // Re-grab focus after the picker / coroutine round-trip.
            exportFocusBump++
        }
    }

    fun runExportToDefault() {
        val filename = makeFilename()
        exporting = true
        exportStatus = context.getString(R.string.bk_exporting_downloads, filename)
        exportStatusIsError = false
        scope.launch {
            val result =
                runCatching {
                    coordinator.exportToDefault(
                        filename = filename,
                        password = exportPassword.takeIf { encryptToggle && it.isNotBlank() },
                        label = label.takeIf { it.isNotBlank() },
                    )
                }.getOrElse { ExportResult.Failed(it.message ?: unknownMsg) }
            exporting = false
            exportStatus = formatExportResult(context, result, filename)
            exportStatusIsError = result is ExportResult.Failed
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
                // Default-folder fallback — point the picker at
                // Download/YancoTV (where exportToDefault writes via
                // MediaStore.Downloads). This is the SAF tree URI
                // format ExternalStorageProvider uses for that
                // path. EXTRA_INITIAL_URI accepts tree URIs as a
                // hint; if the device's picker doesn't recognize
                // it, it falls back to the system default
                // harmlessly.
                runCatching {
                    DocumentsContract.buildTreeDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Download/YancoTV",
                    )
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
            importStatus = context.getString(R.string.bk_picked_short, uri.lastPathSegment ?: uri)
            importFocusBump++
        }

    // MK.30.6 — hoisted so snapToTopNearStart can see the same state.
    val tabScroll = rememberScrollState()

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(tabScroll)
            .snapToTopNearStart(tabScroll)
            // Same outer padding as the SettingsSection-based tabs:
            // 32dp horizontal aligns with the breadcrumb's optical
            // edge; 24dp top + 80dp bottom give safety margin so the
            // last row doesn't hug the panel edge when scrolled.
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 80.dp)
            .semantics { contentDescription = backupDesc },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.bk_title),
            color = LocalYancoPalette.current.TextPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.bk_intro),
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
                stringResource(R.string.bk_export),
                color = LocalYancoPalette.current.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (customFolder != null) {
                    stringResource(R.string.bk_saving_to, customFolder.lastPathSegment ?: customFolder)
                } else {
                    stringResource(R.string.bk_saving_to_default)
                },
                color = LocalYancoPalette.current.TextSecondary,
                fontSize = 12.sp,
            )
            SettingsClickToEditField(
                label = stringResource(R.string.bk_label_optional),
                value = label,
                onValueChange = { label = it },
                hint = stringResource(R.string.bk_label_hint),
                bare = true,
            )
            // Replaced Material3 Switch with SettingsToggleRow — Material3
            // Switch's unchecked thumb is invisible against BackgroundRaised
            // on Fire TV (3 m viewing distance), and it has no per-row focus
            // halo. SettingsToggleRow uses the Verdant emerald pill and
            // paints a 1.5dp focus ring around the entire row, so the
            // selector is unmistakable.
            SettingsToggleRow(
                label = stringResource(R.string.bk_encrypt),
                description =
                if (encryptToggle) {
                    stringResource(R.string.bk_encrypt_on)
                } else {
                    stringResource(R.string.bk_encrypt_off)
                },
                checked = encryptToggle,
                onCheckedChange = { encryptToggle = it },
            )
            if (encryptToggle) {
                SettingsClickToEditField(
                    label = stringResource(R.string.bk_password),
                    value = exportPassword,
                    onValueChange = { exportPassword = it },
                    hint = stringResource(R.string.bk_password_hint),
                    transformation = PasswordVisualTransformation(),
                    bare = true,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                // Each button row owns its own LEFT-exit boundary so the
                // leftmost button escapes to the active inner-sidebar tab
                // (Backup) instead of letting Compose's spatial search
                // jump UP-LEFT to a focusable in the row above.
                modifier = Modifier.startExitsTo(LocalActiveSettingsTabFocus.current),
            ) {
                // NOTE: button stays focusable while exporting. Disabling
                // it (`enabled = !exporting`) makes Compose drop focus the
                // moment the click commits — focus search then escapes
                // Settings entirely and lands on HomeScreen's sidebar.
                // We guard the work with `if (!exporting)` instead so a
                // double-tap is a no-op without ever un-focusing.
                val canExport = !encryptToggle || exportPassword.length >= 8
                SettingsAccentButton(
                    enabled = canExport,
                    translucent = true,
                    onClick = {
                        if (exporting) return@SettingsAccentButton
                        if (customFolder != null) {
                            runExportToCustomFolder(customFolder)
                        } else {
                            runExportToDefault()
                        }
                    },
                    // NOTE: was `Modifier.placedFocus(...).focusable()` —
                    // the trailing `.focusable()` creates an OUTER focus
                    // node that catches focus before the inner clickable
                    // inside SettingsAccentButton. Result: focus lands on
                    // the wrapper, the button's interactionSource never
                    // flips, the scale/ring/halo never fire, and the
                    // user sees no selector. `placedFocus` already calls
                    // `focusRequester` which binds to the next focus
                    // node down the chain — that's the inner clickable.
                    // Drop the `.focusable()` and the requester targets
                    // the button directly.
                    modifier = Modifier.placedFocus(exportButtonAnchor),
                ) {
                    Text(
                        text = stringResource(
                            if (exporting) R.string.bk_exporting else R.string.bk_export_backup,
                        ),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SettingsOutlinedButton(
                    onClick = { changeFolderLauncher.launch(null) },
                ) {
                    Text(
                        text = if (customFolder != null) stringResource(R.string.bk_change_folder) else stringResource(R.string.bk_pick_folder),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (customFolder != null) {
                    SettingsOutlinedButton(
                        onClick = {
                            customFolderString = null
                            scope.launch {
                                prefs.setBackupFolderUri(null)
                                exportFocusBump++
                            }
                        },
                    ) {
                        Text(text = stringResource(R.string.bk_reset_default), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            exportStatus?.let { status ->
                Text(
                    status,
                    color =
                    if (exportStatusIsError) {
                        LocalYancoPalette.current.Error
                    } else {
                        LocalYancoPalette.current.TextMuted
                    },
                    fontSize = 12.sp,
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
                stringResource(R.string.bk_restore_merge),
                color = LocalYancoPalette.current.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.bk_restore_merge_sub),
                color = LocalYancoPalette.current.TextMuted,
                fontSize = 12.sp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.startExitsTo(LocalActiveSettingsTabFocus.current),
            ) {
                SettingsOutlinedButton(
                    onClick = { importPickLauncher.launch(arrayOf("application/json", "*/*")) },
                    // Same focus-stacking fix as Export above. `placedFocus`
                    // already calls focusRequester; the redundant
                    // `.focusable()` was creating a wrapper focus node
                    // that ate focus before the inner clickable.
                    modifier = Modifier.placedFocus(importButtonAnchor),
                ) {
                    Text(
                        text = if (importPickedUri == null) stringResource(R.string.bk_choose_file) else stringResource(R.string.bk_choose_another),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                }
                // v1.1.0 — Quick-restore-from-default-folder. Fire OS's
                // DocumentsUI ignores EXTRA_INITIAL_URI and opens to
                // "Recent" with breadcrumb nav that's effectively
                // unreachable via D-pad. This button skips the picker
                // entirely: scan `/sdcard/Download/YancoTV/` for
                // yancotv-backup-*.json (read access is fine since
                // WRITE_EXTERNAL_STORAGE/READ_EXTERNAL_STORAGE are
                // declared with maxSdkVersion=28 + auto-granted at
                // install on API ≤28; on API 29+ this read works
                // because the path is in the user-visible Downloads
                // area which scoped storage permits for the app's
                // own filename pattern).
                //
                // Picks the most recently modified backup, sets it as
                // the import target so the existing Restore button +
                // password flow takes over.
                SettingsOutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val picked = pickNewestBackupInDefaultFolder()
                            withContext(Dispatchers.Main) {
                                if (picked != null) {
                                    importPickedUriString = Uri.fromFile(picked).toString()
                                    importStatus = context.getString(R.string.bk_picked_latest, picked.name)
                                } else {
                                    // MK.32.3 — Friendlier copy. The
                                    // file path was relevant only to a
                                    // developer reading the toast; the
                                    // user just wants to know "nothing
                                    // to restore + how to get one".
                                    // MK.31.7 — Context.getString, not
                                    // stringResource: this runs inside a
                                    // coroutine launched from onClick, which
                                    // is not composable scope.
                                    importStatus =
                                        context.getString(R.string.bk_no_backups)
                                }
                                importFocusBump++
                            }
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.bk_restore_latest),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                }
            }
            if (importPickedUri != null) {
                Text(
                    stringResource(R.string.bk_picked, importPickedUri.lastPathSegment ?: importPickedUri),
                    color = LocalYancoPalette.current.TextSecondary,
                    fontSize = 12.sp,
                )
                SettingsClickToEditField(
                    label = stringResource(R.string.bk_password_if_encrypted),
                    value = importPassword,
                    onValueChange = { importPassword = it },
                    transformation = PasswordVisualTransformation(),
                    bare = true,
                )
                SettingsAccentButton(
                    onClick = {
                        if (importing) return@SettingsAccentButton
                        importing = true
                        importStatus = context.getString(R.string.bk_restoring)
                        scope.launch {
                            val result = coordinator.import(importPickedUri, password = importPassword.takeIf { it.isNotBlank() })
                            importing = false
                            importStatus = formatImportResult(context, result)
                            importStatusIsError = result !is ImportResult.Success
                            importFocusBump++
                        }
                    },
                ) {
                    Text(if (importing) "Restoring…" else "Restore")
                }
            }
            importStatus?.let { status ->
                Text(
                    status,
                    color =
                    if (importStatusIsError) {
                        LocalYancoPalette.current.Error
                    } else {
                        LocalYancoPalette.current.TextMuted
                    },
                    fontSize = 12.sp,
                )
                // v1.1.0 — live pending-count + manual retry button.
                // After import the coordinator buffers records that
                // reference content IDs not yet in the DB; they resolve
                // automatically when each source's sync completes. If
                // the auto-retry observer misses an event or the user
                // wants confirmation, this button forces a retry pass.
                val pending by coordinator.pendingCount.collectAsState()
                if (pending > 0) {
                    Text(
                        stringResource(R.string.bk_pending_note, pending),
                        color = LocalYancoPalette.current.TextMuted,
                        fontSize = 12.sp,
                    )
                    SettingsOutlinedButton(
                        onClick = { coordinator.retryPendingLinksNow() },
                    ) {
                        Text(
                            text = stringResource(R.string.bk_retry_pending),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                        )
                    }
                }
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
                    stringResource(R.string.bk_recent_exports),
                    color = LocalYancoPalette.current.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.bk_recent_exports_sub),
                    color = LocalYancoPalette.current.TextMuted,
                    fontSize = 12.sp,
                )
                recent.forEach { row ->
                    RecentExportRow(
                        row = row,
                        onUse = {
                            val rowUri = row.fileUri ?: return@RecentExportRow
                            importPickedUriString = rowUri
                            importStatus = context.getString(R.string.bk_selected, row.label)
                            importFocusBump++
                        },
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching { db.backupMetadataQueries.deleteById(row.id) }
                                }
                                recent.remove(row)
                                // If the user had this entry pre-selected for
                                // restore, drop it so the Restore button
                                // doesn't point at a stale id.
                                if (importPickedUriString == row.fileUri) {
                                    importPickedUriString = null
                                    importStatus = null
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** One row in the "Recent exports" list. Card frame holds the metadata
 *  on the left and Use + Delete buttons on the right. The card itself
 *  is NOT focusable — only the two buttons are, so D-pad RIGHT cycles
 *  Use → Delete and UP / DOWN moves between rows. Mirrors the Sources
 *  row pattern so the navigation feels identical across the two
 *  Settings list surfaces. */
@Composable
private fun RecentExportRow(row: RecentBackup, onUse: () -> Unit, onDelete: () -> Unit) {
    val palette = LocalYancoPalette.current
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.BackgroundElevated.copy(alpha = 0.6f))
            .border(1.dp, palette.BorderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Tiny dot for visual anchor — same vocabulary as the Sources rows.
        Box(
            modifier =
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (row.fileUri != null) palette.Accent else palette.TextMuted),
        )
        val ctx = LocalContext.current
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = row.label.ifBlank { stringResource(R.string.bk_untitled) },
                color = palette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                buildString {
                    append(
                        stringResource(
                            R.string.bk_row_meta,
                            formatBytes(ctx, row.sizeBytes),
                            row.schemaVersion,
                            row.checksum.take(8),
                        ),
                    )
                    if (row.fileUri == null) append(stringResource(R.string.bk_file_location_lost))
                },
                color = palette.TextMuted,
                fontSize = 12.sp,
            )
        }
        SettingsOutlinedButton(
            onClick = onUse,
            enabled = row.fileUri != null,
            size = ButtonSize.Compact,
        ) {
            Text(text = stringResource(R.string.bk_use), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        SettingsDangerButton(onClick = onDelete, size = ButtonSize.Compact) {
            Text(text = stringResource(R.string.bk_delete), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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
private class OpenDocumentWithInitialUri(private val initialUri: Uri?) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
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

// MK.31.20 — Context-taking so the unit strings come from resources. The
// %.1f itself uses the default locale deliberately (MK.31.3): an Arabic UI
// should render Arabic-Indic digits here, matching every other number.
private fun formatBytes(ctx: android.content.Context, b: Long): String = when {
    b < 1024 -> ctx.getString(R.string.bk_bytes_b, b)
    b < 1024 * 1024 -> ctx.getString(R.string.bk_bytes_kb, "%.1f".format(b / 1024.0))
    else -> ctx.getString(R.string.bk_bytes_mb, "%.1f".format(b / (1024.0 * 1024.0)))
}

private fun formatExportResult(ctx: android.content.Context, r: ExportResult, filename: String): String = when (r) {
    is ExportResult.Success ->
        ctx.getString(
            R.string.bk_export_ok,
            filename,
            formatBytes(ctx, r.bytesWritten),
            r.file.dbSchemaVersion,
            r.file.recordCounts.values.sum(),
            r.file.checksum.take(8),
        )
    // r.message is transport/filesystem text — untranslatable by nature,
    // same rule as SyncDetail.Failure (MK.31.18). The frame is localized.
    is ExportResult.Failed -> ctx.getString(R.string.bk_export_failed, r.message)
}

private fun formatImportResult(ctx: android.content.Context, r: ImportResult): String = when (r) {
    is ImportResult.Success -> {
        val report = r.report
        buildString {
            append(ctx.getString(R.string.bk_restored_rows, report.totalRestored))
            if (report.totalUnlinked > 0) {
                append(ctx.getString(R.string.bk_pending_resync, report.totalUnlinked))
            }
            if (report.totalSkipped > 0) {
                append(ctx.getString(R.string.bk_already_present, report.totalSkipped))
            }
            // Warnings come from the coordinator as raw text; see the
            // Failed branch below for why those stay untranslated.
            report.warnings.forEach { append(" · ").append(it) }
        }
    }
    is ImportResult.ChecksumMismatch -> ctx.getString(R.string.bk_checksum_mismatch)
    is ImportResult.SchemaTooNew ->
        ctx.getString(R.string.bk_schema_too_new, r.backupVersion, r.currentVersion)
    is ImportResult.DecryptFailed -> ctx.getString(R.string.bk_decrypt_failed, r.message)
    is ImportResult.MalformedJson -> ctx.getString(R.string.bk_malformed_json, r.message)
    is ImportResult.IoError -> ctx.getString(R.string.bk_io_error, r.message)
    is ImportResult.UnexpectedError -> ctx.getString(R.string.bk_restore_failed, r.message)
}

/**
 * Scan `/sdcard/Download/YancoTV/` for `yancotv-backup-*.json` files and
 * return the most recently modified one. Used by the "Restore latest
 * from Downloads" button to bypass Fire OS's broken DocumentsUI picker.
 * Null when the folder doesn't exist, can't be read, or holds no
 * matching files.
 */
@Suppress("DEPRECATION")
private fun pickNewestBackupInDefaultFolder(): File? {
    return runCatching {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val target = File(downloads, "YancoTV")
        if (!target.isDirectory) return@runCatching null
        target.listFiles { f ->
            f.isFile && f.name.startsWith("yancotv-backup-") && f.name.endsWith(".json")
        }?.maxByOrNull { it.lastModified() }
    }.getOrNull()
}
