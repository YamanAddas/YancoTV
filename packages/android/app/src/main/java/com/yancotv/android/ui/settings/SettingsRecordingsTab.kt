package com.yancotv.android.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.yancotv.android.R
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.RecordingStorageMode
import com.yancotv.android.ui.components.YancoSecondaryButton
import com.yancotv.android.ui.theme.LocalYancoPalette
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Stage 3.1 / MK.14.2-storage (audit-revised) — recording-storage tab.
 *
 * Three storage modes (radio-style picker), each D-pad focusable:
 *
 *   1. **Public folder (default)** — `Movies/YancoTV/`. Recordings
 *      survive uninstall. On API 29+ uses MediaStore (zero permission).
 *      On API ≤28 (Fire OS 7) needs `WRITE_EXTERNAL_STORAGE`; first-time
 *      selection triggers the runtime grant prompt.
 *   2. **App-private** — `getExternalFilesDir(MOVIES)/yanco-recordings/`.
 *      Zero permission, every API. Wiped on uninstall (Android contract
 *      for app-specific external dirs).
 *   3. **Custom folder (advanced)** — SAF tree URI grant via
 *      `ACTION_OPEN_DOCUMENT_TREE`. The system picker UX is
 *      OEM-dependent (Fire TV's stock DocumentsUI has known D-pad
 *      reachability bugs); the modes above are designed so the user
 *      never has to touch the system picker for the common case.
 *
 * Switching modes is reversible — picking a different mode just updates
 * the pref. Existing recordings continue to play (their `file_path`
 * column already points at where the bytes live). New recordings land
 * under the newly-selected mode.
 */
@Composable
fun SettingsRecordingsTab(modifier: Modifier = Modifier, prefs: AppPreferences = koinInject()) {
    val palette = LocalYancoPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recordingPrefs by prefs.recordingFlow.collectAsState()

    // Flips when the WRITE_EXTERNAL_STORAGE permission grant lands so the
    // composable re-reads the granted state. The OS grant doesn't fire a
    // pref-flow change; we use this state hash to bust the cache.
    var permissionEpoch by remember { mutableStateOf(0) }

    // SAF folder picker. Returns a content:// tree URI; we take
    // persistable read+write permission so the choice survives reboots,
    // then store the URI and switch to CUSTOM_SAF mode.
    val safFolderPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { treeUri: Uri? ->
            if (treeUri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                scope.launch {
                    prefs.setRecordingFolderUri(treeUri.toString())
                    prefs.setRecordingStorageMode(RecordingStorageMode.CUSTOM_SAF)
                }
            }
        }

    // WRITE_EXTERNAL_STORAGE runtime grant — only ever requested on API
    // ≤28 when the user first selects Public mode. The manifest declares
    // it with `maxSdkVersion="28"` so it doesn't even appear on modern
    // OS install consent screens.
    val storagePermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted: Boolean ->
            permissionEpoch++
            if (granted) {
                scope.launch { prefs.setRecordingStorageMode(RecordingStorageMode.PUBLIC_MEDIA_STORE) }
            }
            // On deny we leave the current mode untouched — no toast
            // flicker, the radio just stays on whatever it was. The
            // Public row re-renders with a "Tap to grant permission"
            // hint via [needsLegacyPermission] below.
        }

    // Recompute on permission epoch so the public-row hint re-evaluates
    // after a grant lands.
    val needsLegacyPermission =
        remember(permissionEpoch) {
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !hasLegacyStoragePermission(context)
        }

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.rec_title),
            color = palette.TextPrimary,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
        )

        // ── Storage mode picker card ─────────────────────────────
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.BackgroundRaised)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.rec_storage_location),
                color = palette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                "Pick where new recordings save. You can change this " +
                    "any time — past recordings keep playing from wherever they were saved.",
                color = palette.TextMuted,
                fontSize = 12.sp,
            )

            // Public folder (recommended)
            ModeRow(
                title = stringResource(R.string.rec_public_folder),
                badge = "RECOMMENDED",
                subtitle = stringResource(R.string.rec_public_folder_sub, PUBLIC_DIR_NAME),
                detail =
                if (needsLegacyPermission && recordingPrefs.storageMode != RecordingStorageMode.PUBLIC_MEDIA_STORE) {
                    "Tap to grant storage permission and switch."
                } else if (needsLegacyPermission) {
                    "Storage permission needed — tap to grant."
                } else {
                    publicFolderResolvedPath()
                },
                selected = recordingPrefs.storageMode == RecordingStorageMode.PUBLIC_MEDIA_STORE,
                onSelect = {
                    if (needsLegacyPermission) {
                        // Trigger runtime grant. On grant, the launcher's
                        // callback above flips storageMode to public.
                        storagePermissionLauncher.launch(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        )
                    } else {
                        scope.launch {
                            prefs.setRecordingStorageMode(RecordingStorageMode.PUBLIC_MEDIA_STORE)
                        }
                    }
                },
            )

            // App-private
            ModeRow(
                title = stringResource(R.string.rec_app_private),
                badge = null,
                subtitle = stringResource(R.string.rec_app_private_sub),
                detail = appPrivateResolvedPath(context),
                selected = recordingPrefs.storageMode == RecordingStorageMode.APP_PRIVATE,
                onSelect = {
                    scope.launch {
                        prefs.setRecordingStorageMode(RecordingStorageMode.APP_PRIVATE)
                    }
                },
            )

            // Custom (advanced)
            ModeRow(
                title = stringResource(R.string.rec_custom_folder),
                badge = "ADVANCED",
                subtitle =
                if (recordingPrefs.folderUri != null) {
                    "Pick a different folder anytime"
                } else {
                    "Pick any folder using your TV's system picker"
                },
                detail =
                recordingPrefs.folderUri?.let { uriString ->
                    runCatching { Uri.parse(uriString) }
                        .getOrNull()
                        ?.let { friendlyTreeUriPath(it) }
                        ?: uriString
                } ?: "No folder picked yet — tap to choose.",
                selected = recordingPrefs.storageMode == RecordingStorageMode.CUSTOM_SAF,
                onSelect = {
                    // Selecting Custom always launches the system picker —
                    // both for first pick and for changing the folder.
                    // The picker callback updates both the URI and the
                    // mode atomically.
                    val initial = primaryStorageInitialUri()
                    safFolderPicker.launch(initial)
                },
            )

            // Reset button — only when CUSTOM_SAF is selected so we have
            // somewhere to reset FROM. Other modes are already "default".
            if (recordingPrefs.storageMode == RecordingStorageMode.CUSTOM_SAF &&
                recordingPrefs.folderUri != null
            ) {
                YancoSecondaryButton(
                    onClick = {
                        // Release SAF permission and clear the URI. Mode
                        // stays CUSTOM_SAF — the row will offer "pick a
                        // folder" again. (We don't auto-switch back to
                        // Public because that's a different decision the
                        // user should make explicitly.)
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    val uri = Uri.parse(recordingPrefs.folderUri)
                                    context.contentResolver
                                        .releasePersistableUriPermission(
                                            uri,
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                        )
                                }
                            }
                            prefs.setRecordingFolderUri(null)
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.rec_release_folder))
                }
            }
        }

        // ── Browse-your-recordings tip ──────────────────────────
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.BackgroundRaised)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.rec_browse),
                color = palette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                "Open the Recordings tab in the sidebar to play, delete, and inspect " +
                    "any recording you've made. New recordings show up there immediately " +
                    "as they start.",
                color = palette.TextMuted,
                fontSize = 12.sp,
            )
        }

        // ── Picker caveats ──────────────────────────────────────
        // Only shown when CUSTOM_SAF is the active mode — the audit
        // recommendation was to not surface this for users on the
        // smooth-default (Public) path.
        if (recordingPrefs.storageMode == RecordingStorageMode.CUSTOM_SAF) {
            Column(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.BackgroundRaised)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.rec_picker_hint),
                    color = palette.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                    "Stock Fire TV's folder picker has known D-pad bugs (the Select / " +
                        "hamburger / tab buttons can be unreachable from the remote). It's a " +
                        "Fire OS limitation, not a YancoTV bug. Fix: install Files by Google — " +
                        "Fire TV will route the picker through it. Or switch to Public folder " +
                        "above; recordings save without ever using the system picker.",
                    color = palette.TextMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * One radio-style row in the storage-mode picker. Whole row is a single
 * focusable D-pad target — CENTER selects (which may then trigger a
 * permission grant launcher or a SAF picker).
 */
@Composable
private fun ModeRow(title: String, badge: String?, subtitle: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)

    val borderColor =
        when {
            focused -> palette.Accent
            selected -> palette.Accent
            else -> palette.PanelBorder
        }
    val bg =
        when {
            focused -> palette.BackgroundElevated
            selected -> palette.BackgroundElevated
            else -> palette.BackgroundDeep.copy(alpha = 0.4f)
        }

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(
                width = if (focused || selected) 2.dp else 1.dp,
                color = borderColor,
                shape = shape,
            )
            .focusable(interactionSource = interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onSelect,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Radio dot
        Box(
            modifier = Modifier.padding(top = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .border(
                        width = 2.dp,
                        color = if (selected) palette.Accent else palette.PanelBorder,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        modifier =
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(palette.Accent),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    color = palette.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (badge != null) {
                    Text(
                        text = badge,
                        color = palette.Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        modifier =
                        Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(palette.Accent.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = subtitle,
                color = palette.TextMuted,
                fontSize = 12.sp,
            )
            Text(
                text = detail,
                color = palette.TextMuted.copy(alpha = 0.7f),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Build an initial-URI hint for the SAF folder picker that points at
 * the primary external storage volume's root. On Fire TV the stock
 * DocumentsUI's default "Recent" view is empty + un-navigable; this
 * hint at least scrolls the picker to a folder that exists.
 */
private fun primaryStorageInitialUri(): Uri? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    return runCatching {
        DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:",
        )
    }.getOrNull()
}

/**
 * Produce a human-readable summary of a SAF tree URI:
 * `content://com.android.externalstorage.documents/tree/primary%3AMovies%2FYancoTV`
 * → `Movies/YancoTV`. Used in the Custom-mode row's detail line.
 */
private fun friendlyTreeUriPath(uri: Uri): String {
    val docId =
        runCatching { uri.lastPathSegment }.getOrNull()
            ?: return uri.toString()
    val parts = docId.split(":", limit = 2)
    val volume = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
    val path = parts.getOrNull(1).orEmpty()
    val decodedPath = runCatching { Uri.decode(path) }.getOrDefault(path)
    return when {
        volume == null || volume == "primary" -> "/$decodedPath".trimEnd('/').ifBlank { "/" }
        else -> "$volume:/$decodedPath".trimEnd('/')
    }
}

/**
 * What the Public-mode row shows below the title — different copy on
 * API 29+ (zero permission, MediaStore-managed) vs API ≤28 (legacy
 * direct path requiring `WRITE_EXTERNAL_STORAGE`).
 */
private fun publicFolderResolvedPath(): String {
    val moviesPart = "${Environment.DIRECTORY_MOVIES}/$PUBLIC_DIR_NAME"
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "/storage/emulated/0/$moviesPart (managed by Android Media)"
    } else {
        "/storage/emulated/0/$moviesPart"
    }
}

private fun appPrivateResolvedPath(context: android.content.Context): String {
    val baseDir =
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "Movies")
    val dir = File(baseDir, "yanco-recordings")
    // Trim the leading "/storage/emulated/0" prefix so the user sees a
    // cleaner relative path. Real path stays intact behind the scenes.
    return dir.absolutePath.replaceFirst(Regex("^/storage/emulated/\\d+"), "")
}

private fun hasLegacyStoragePermission(context: android.content.Context): Boolean = ContextCompat.checkSelfPermission(
    context,
    Manifest.permission.WRITE_EXTERNAL_STORAGE,
) == PackageManager.PERMISSION_GRANTED

/** Mirrors `RecordingStorageResolver.PUBLIC_DIR_NAME`; kept in sync by code review. */
private const val PUBLIC_DIR_NAME = "YancoTV"
