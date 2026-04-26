package com.yancotv.android.ui.settings

import android.content.Intent
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.theme.LocalYancoPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

/**
 * Stage 3.1 / MK.14.2-storage — recordings storage configuration.
 *
 * Shows the current storage location for new recordings and lets the
 * user pick a different folder via the system folder picker (SAF).
 *
 * Two states:
 *   - **Default** — recordings land in app-private external storage
 *     (`/sdcard/Android/data/com.yancotv.android/files/Movies/yanco-recordings/`).
 *     Works with zero permissions. Hidden from most file managers.
 *   - **Custom (SAF)** — the user picked a folder via the system
 *     picker. Recordings land there as `<title> - <timestamp> - <id>.ts`,
 *     visible in any file manager that opens the chosen tree.
 *
 * Cap / quota / retention controls are deferred to Stage 5; this tab
 * is just storage location for v1.0.
 *
 * The "browse all recordings" link will land here when MK.14.5 ships
 * the sidebar Recordings destination — tracked but not present yet.
 */
@Composable
fun SettingsRecordingsTab(
    modifier: Modifier = Modifier,
    prefs: AppPreferences = koinInject(),
) {
    val palette = LocalYancoPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recordingPrefs by prefs.recordingFlow.collectAsState()

    // Storage Access Framework folder picker. Returns a content:// tree
    // URI; we take persistable read+write permission so the choice
    // survives reboots, then store the URI in prefs. Cancelling the
    // picker yields null — we leave the previous choice intact.
    val folderPicker =
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
                scope.launch { prefs.setRecordingFolderUri(treeUri.toString()) }
            }
        }

    // Computed display: the friendly path or URI summary that's shown
    // in the "Current folder" card.
    val folderSummary =
        remember(recordingPrefs.folderUri) {
            recordingPrefs.folderUri?.let { uriString ->
                runCatching { Uri.parse(uriString) }
                    .getOrNull()
                    ?.let { uri -> friendlyTreeUriPath(uri) }
                    ?: uriString
            } ?: defaultFolderPath(context).let { dir ->
                // Trim the leading "/storage/emulated/0" prefix on AFTDCT31 so the
                // user sees a cleaner relative path. Real path stays intact behind
                // the scenes.
                dir.absolutePath.replaceFirst(Regex("^/storage/emulated/\\d+"), "")
            }
        }

    val isCustom = recordingPrefs.folderUri != null
    val locationLabel = if (isCustom) "Custom folder" else "Default (app-private)"

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Recordings",
            color = palette.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )

        // ── Current folder card ──────────────────────────────────
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
                text = "Storage location",
                color = palette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    if (isCustom) {
                        "Custom folder you picked. Recordings appear in the chosen folder " +
                            "and survive uninstalling the app."
                    } else {
                        "Default app-private folder. Easy to set up — no permissions needed — but " +
                            "the recordings disappear if you uninstall the app, and most file " +
                            "managers can't see them."
                    },
                color = palette.TextMuted,
                fontSize = 11.sp,
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.BackgroundElevated)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = locationLabel,
                        color = palette.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = folderSummary,
                        color = palette.TextMuted,
                        fontSize = 10.sp,
                        maxLines = 2,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusableSettingsButton(
                    label = if (isCustom) "Change folder" else "Pick a folder",
                    primary = true,
                    onClick = {
                        // Hint the picker to start at primary external
                        // storage (Internal storage on most devices). On
                        // Fire TV the bare DocumentsUI defaults to a
                        // "Recent" view that's empty + un-navigable; this
                        // hint at least scrolls to a real folder. Falls
                        // back to null on devices where the hint isn't
                        // supported.
                        val initial = primaryStorageInitialUri()
                        folderPicker.launch(initial)
                    },
                )
                if (isCustom) {
                    FocusableSettingsButton(
                        label = "Reset to default",
                        primary = false,
                        onClick = {
                            // Release the persistable permission first, then
                            // clear the pref. Order matters: if we clear pref
                            // and then crash, we'd leak the URI permission.
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
                    )
                }
            }
        }

        // ── Heads-up about recordings list ───────────────────────
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
                text = "Browse your recordings",
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
                fontSize = 11.sp,
            )
        }

        // ── Fire TV picker limitation note ───────────────────────
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
                text = "Picker not working on your TV?",
                color = palette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    "Stock Fire TV's folder picker can come up empty and unresponsive — " +
                        "it's a Fire OS limitation, not a YancoTV bug. The default folder above " +
                        "still works fine; recordings save there with no setup. To make the " +
                        "picker usable, install Files by Google (or any file-manager app) — " +
                        "Fire TV will route the picker through it.",
                color = palette.TextMuted,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * Build an initial-URI hint for the SAF folder picker that points at
 * the primary external storage volume's root. On Fire TV the stock
 * DocumentsUI's default "Recent" view is empty + un-navigable; this
 * hint at least scrolls the picker to a folder that exists.
 *
 * Returns null on API levels where DocumentsContract.buildDocumentUri
 * isn't available — the launcher then opens at the picker's default
 * (which is fine on phones / Google TV).
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
 * Produce a human-readable summary of a SAF tree URI. The full URI is
 * `content://com.android.externalstorage.documents/tree/primary%3AMovies%2FYancoTV`;
 * we extract the right side of the last `:` and percent-decode it so
 * the user sees `Movies/YancoTV` rather than the wire format.
 */
private fun friendlyTreeUriPath(uri: Uri): String {
    val docId = runCatching {
        uri.lastPathSegment ?: return@runCatching null
    }.getOrNull() ?: return uri.toString()
    val parts = docId.split(":", limit = 2)
    val volume = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
    val path = parts.getOrNull(1).orEmpty()
    val decodedPath = runCatching { Uri.decode(path) }.getOrDefault(path)
    return when {
        volume == null || volume == "primary" -> "/$decodedPath".trimEnd('/').ifBlank { "/" }
        else -> "$volume:/$decodedPath".trimEnd('/')
    }
}

private fun defaultFolderPath(context: android.content.Context): File {
    val baseDir =
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "Movies")
    return File(baseDir, "yanco-recordings")
}

@Composable
private fun FocusableSettingsButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val palette = LocalYancoPalette.current
    val shape = RoundedCornerShape(8.dp)
    val borderColor =
        when {
            focused -> palette.Accent
            primary -> palette.Accent
            else -> palette.PanelBorder
        }
    val bg =
        when {
            focused -> palette.Accent
            primary -> palette.BackgroundElevated
            else -> palette.BackgroundElevated
        }
    val fg =
        when {
            focused -> palette.BackgroundDeep
            primary -> palette.Accent
            else -> palette.TextPrimary
        }
    Row(
        modifier =
            Modifier
                .clip(shape)
                .background(bg)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = borderColor,
                    shape = shape,
                )
                // TV D-pad needs the explicit focusable; same pattern
                // every clickable in this codebase that needs to take
                // focus on Fire TV.
                .focusable(interactionSource = interaction)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
