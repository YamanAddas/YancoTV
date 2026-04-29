package com.yancotv.android.ui.settings

import android.util.Log
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.sources.SourceSyncCoordinator
import com.yancotv.android.ui.focus.placedFocus
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.types.Source
import com.yancotv.shared.types.SourceType
import com.yancotv.shared.types.UpdateSourceInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Source detail screen — opens when the user activates a row in the
 * Sources list. Replaces the inline SYNC / DELETE buttons that were
 * cramming the row.
 *
 * Surfaces: name + status hero, info section (read-only metrics),
 * connection section (URL / username / password / EPG URL / UA / referer
 * — editable click-to-edit fields), and an actions row (Sync now,
 * Delete). Each tab inside Settings is a focusGroup-wrapped scroll
 * container; this screen reuses the SettingsSection / SettingsRow
 * primitives so the type scale + focus chrome match every other tab.
 *
 * BACK / LEFT escapes back to the source list via [onBack]. The Sources
 * screen owns the `selectedSourceId` state — this composable is purely
 * a view + edit form.
 */
@Composable
fun SourceDetailScreen(sourceId: String, repo: SourceRepository, coordinator: SourceSyncCoordinator, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Source?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Editable buffers — separate from the persisted Source so the user
    // can type freely; SAVE commits them via SourceRepository.updateSource.
    var nameField by rememberSaveable { mutableStateOf("") }
    var urlField by rememberSaveable { mutableStateOf("") }
    var usernameField by rememberSaveable { mutableStateOf("") }
    var passwordField by rememberSaveable { mutableStateOf("") }
    var epgUrlField by rememberSaveable { mutableStateOf("") }
    var referrerField by rememberSaveable { mutableStateOf("") }
    var userAgentField by rememberSaveable { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    suspend fun loadSource() {
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching { repo.getById(sourceId) }
                    .onFailure { Log.w("Yanco", "SourceDetail.load failed: ${it.message}", it) }
                    .getOrNull()
            }
        if (loaded == null) {
            loadError = "Source not found. It may have been deleted."
            return
        }
        source = loaded
        // Seed editable fields with current persisted values. Decrypted
        // username/password come from xtreamCredentials() for Xtream
        // sources; M3U-URL has no credentials, Stalker has MAC.
        nameField = loaded.name
        urlField = loaded.url.orEmpty()
        epgUrlField = loaded.epgUrl.orEmpty()
        referrerField = loaded.referer.orEmpty()
        userAgentField = loaded.userAgent.orEmpty()
        if (loaded.type == SourceType.XTREAM) {
            val creds =
                withContext(Dispatchers.IO) {
                    runCatching { repo.xtreamCredentials(sourceId) }.getOrNull()
                }
            usernameField = creds?.username.orEmpty()
            passwordField = creds?.password.orEmpty()
        }
        dirty = false
    }

    LaunchedEffect(sourceId) { loadSource() }

    BackHandler(onBack = onBack)

    val palette = LocalYancoPalette.current

    // When the user activates a row in the list, the row's clickable
    // unmounts and Compose's focus manager loses its anchor — without
    // this, focus falls back to the first focusable in the global tree
    // (the main app sidebar) on the way down. PlacedFocusAnchor waits
    // for the back button's `onPlaced` callback before requesting
    // focus — race-free across the recompose pulse.
    val backButtonAnchor = rememberPlacedFocusAnchor()
    LaunchedEffect(sourceId) { backButtonAnchor.awaitAndRequest() }

    if (loadError != null) {
        ErrorPane(message = loadError!!, onBack = onBack)
        return
    }
    val current = source ?: return

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        DetailHero(
            source = current,
            palette = palette,
            onBack = onBack,
            backAnchorModifier = Modifier.placedFocus(backButtonAnchor),
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsSection(
            title = "Status",
            sub = "Last sync result + when this source will refresh next.",
        ) {
            val status = remember(current) { computeRowStatus(current, isSyncing = false) }
            // Every row in this section is read-only but [readOnlyFocusable]
            // so D-pad can stop on each one. Without that, DOWN from the
            // back button would skip the entire Status section and land on
            // the Connection form's first text field, never scrolling these
            // facts into view.
            SettingsRow(
                label = "Health",
                kicker = statusKicker(status),
                right = {
                    Text(
                        text = healthSummary(status),
                        color = status.subColor(palette),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                readOnlyFocusable = true,
            )
            SettingsRowSpacer()
            SettingsRow(
                label = "Refresh in",
                kicker = "AUTO-SYNC",
                right = { ValueText(formatRefreshIn(current)) },
                readOnlyFocusable = true,
            )
            SettingsRowSpacer()
            SettingsRow(
                label = "Last synced",
                right = { ValueText(formatLastSynced(current.lastSynced)) },
                readOnlyFocusable = true,
            )
            SettingsRowSpacer()
            SettingsRow(
                label = "Live channels",
                right = { ValueText("${current.channelCount}") },
                readOnlyFocusable = true,
            )
            current.lastSyncError?.let { err ->
                SettingsRowSpacer()
                SettingsRow(
                    label = "Last error",
                    kicker = "ERROR",
                    hint = err,
                    readOnlyFocusable = true,
                )
            }
        }

        SettingsSection(
            title = "Sync",
            sub = "How aggressively this source refreshes its catalog.",
        ) {
            SettingsToggleRow(
                label = "Auto-sync on app start",
                description =
                "When on, this source kicks off a background refresh every time you open YancoTV. Off keeps catalog reads fast on launch and only syncs when you press Sync manually or the auto-sync interval elapses.",
                checked = current.autoSyncOnStart,
                onCheckedChange = { enabled ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { repo.setAutoSyncOnStart(current.id, enabled) }
                                .onFailure {
                                    Log.w(
                                        "Yanco",
                                        "SourceDetail.setAutoSyncOnStart(${current.id}) failed: ${it.message}",
                                        it,
                                    )
                                }
                        }
                        loadSource()
                    }
                },
            )
        }

        SettingsSection(
            title = "Connection",
            sub = "Editable. Save changes when you're done; the next sync uses the new values.",
        ) {
            SettingsClickToEditField(
                label = "Display name",
                value = nameField,
                onValueChange = {
                    nameField = it
                    dirty = true
                },
                hint = "e.g. \"Living-room TV\"",
            )
            if (current.type != SourceType.M3U_FILE) {
                SettingsRowSpacer()
                SettingsClickToEditField(
                    label = serverFieldLabel(current.type),
                    description = "Base URL the playlist or panel is served from. Include the http(s):// scheme.",
                    value = urlField,
                    onValueChange = {
                        urlField = it
                        dirty = true
                    },
                    hint = "https://example.com:8080",
                )
            } else {
                SettingsRowSpacer()
                SettingsRow(
                    label = "File path",
                    hint = "Local M3U files can't be edited from here. Re-add the file from disk to swap it.",
                    right = {
                        Text(
                            text = current.filePath?.takeLast(40) ?: "—",
                            color = palette.TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
            if (current.type == SourceType.XTREAM) {
                SettingsRowSpacer()
                SettingsClickToEditField(
                    label = "Username",
                    value = usernameField,
                    onValueChange = {
                        usernameField = it
                        dirty = true
                    },
                    hint = "your Xtream username",
                    keyboardType = KeyboardType.Ascii,
                )
                SettingsRowSpacer()
                SettingsClickToEditField(
                    label = "Password",
                    description = "Stored encrypted in the Android Keystore. Leave blank to keep the existing password.",
                    value = passwordField,
                    onValueChange = {
                        passwordField = it
                        dirty = true
                    },
                    hint = "tap to enter a new password",
                    transformation = PasswordVisualTransformation(),
                    keyboardType = KeyboardType.Password,
                )
            }
            SettingsRowSpacer()
            SettingsClickToEditField(
                label = "EPG URL (optional)",
                description = "Override the source's bundled EPG with a custom XMLTV URL.",
                value = epgUrlField,
                onValueChange = {
                    epgUrlField = it
                    dirty = true
                },
                hint = "https://example.com/epg.xml.gz",
            )
            SettingsRowSpacer()
            SettingsClickToEditField(
                label = "User-Agent (optional)",
                description = "Per-source override. Falls back to the global default in Network settings.",
                value = userAgentField,
                onValueChange = {
                    userAgentField = it
                    dirty = true
                },
                hint = "VLC/3.0.20 LibVLC/3.0.20",
            )
            SettingsRowSpacer()
            SettingsClickToEditField(
                label = "Referer (optional)",
                description = "Some providers gate streams by HTTP Referer.",
                value = referrerField,
                onValueChange = {
                    referrerField = it
                    dirty = true
                },
                hint = "https://example.com",
            )
        }

        SettingsSection(
            title = "Actions",
            sub = "Sync forces a refresh; Delete removes this source and its catalog from local storage.",
        ) {
            // Action buttons are Compact size with short labels (SAVE /
            // SYNC / DELETE) so all three fit in a single row at every
            // viewport width without DELETE wrapping. The button row
            // owns its own LEFT-exit boundary so D-pad LEFT from any
            // button escapes to the active inner-sidebar tab.
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .leftExitsTo(LocalActiveSettingsTabFocus.current),
            ) {
                SettingsAccentButton(
                    onClick = {
                        if (saving || !dirty) return@SettingsAccentButton
                        saving = true
                        saveError = null
                        scope.launch {
                            val input =
                                UpdateSourceInput(
                                    id = current.id,
                                    name = nameField.takeIf { it.isNotBlank() && it != current.name },
                                    url = urlField.takeIf { it != current.url.orEmpty() },
                                    username =
                                    usernameField.takeIf {
                                        current.type == SourceType.XTREAM && it.isNotBlank()
                                    },
                                    password =
                                    passwordField.takeIf {
                                        current.type == SourceType.XTREAM && it.isNotBlank()
                                    },
                                    epgUrl = epgUrlField.takeIf { it != current.epgUrl.orEmpty() },
                                    userAgent = userAgentField.takeIf { it != current.userAgent.orEmpty() },
                                    referer = referrerField.takeIf { it != current.referer.orEmpty() },
                                )
                            val result =
                                runCatching {
                                    withContext(Dispatchers.IO) { repo.updateSource(input) }
                                }
                            saving = false
                            result
                                .onSuccess {
                                    saveError = null
                                    dirty = false
                                    loadSource()
                                }
                                .onFailure { t ->
                                    saveError = t.message ?: t::class.simpleName ?: "Unknown error"
                                    Log.w(
                                        "Yanco",
                                        "SourceDetail.save(${current.id}) failed: ${t.message}",
                                        t,
                                    )
                                }
                        }
                    },
                    enabled = dirty && !saving,
                    translucent = true,
                    size = ButtonSize.Compact,
                ) {
                    Text(text = if (saving) "SAVING…" else "SAVE", maxLines = 1, softWrap = false)
                }
                SettingsOutlinedButton(
                    onClick = {
                        scope.launch {
                            coordinator.start(current.id, current.name)
                            onBack()
                        }
                    },
                    size = ButtonSize.Compact,
                ) {
                    Text(text = "SYNC", maxLines = 1, softWrap = false)
                }
                SettingsDangerButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { repo.removeSource(current.id) }
                                    .onFailure {
                                        Log.w(
                                            "Yanco",
                                            "SourceDetail.delete(${current.id}) failed: ${it.message}",
                                            it,
                                        )
                                    }
                            }
                            onBack()
                        }
                    },
                    size = ButtonSize.Compact,
                ) {
                    Text(text = "DELETE", maxLines = 1, softWrap = false)
                }
            }
            saveError?.let { err ->
                SettingsRowSpacer()
                Text(
                    text = "Save failed: $err",
                    color = palette.Error,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** Top hero with the Source name, status dot and a back affordance.
 *  Reuses the Sources-list dot vocabulary so the user sees the same
 *  "alive" indicator across the list and detail surfaces. */
@Composable
private fun DetailHero(source: Source, palette: YancoPalette, onBack: () -> Unit, backAnchorModifier: Modifier) {
    val status = remember(source) { computeRowStatus(source, isSyncing = false) }
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.BackgroundElevated.copy(alpha = 0.55f))
            .border(1.dp, palette.PanelBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier =
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(status.dotColor(palette)),
        )
        Column(modifier = Modifier.weight(1f)) {
            SettingsKicker(text = "${typeLabel(source.type).uppercase()} · SOURCE", accent = true)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = source.name.ifBlank { "Untitled source" },
                color = palette.TextPrimary,
                fontSize = 24.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SettingsOutlinedButton(
            onClick = onBack,
            size = ButtonSize.Compact,
            modifier = backAnchorModifier,
        ) {
            Text(text = "BACK", maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun ValueText(value: String) {
    Text(
        text = value,
        color = LocalYancoPalette.current.TextPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ErrorPane(message: String, onBack: () -> Unit) {
    val palette = LocalYancoPalette.current
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            color = palette.TextSecondary,
            fontSize = 14.sp,
        )
        SettingsOutlinedButton(onClick = onBack) {
            Text(text = "Back to list", maxLines = 1, softWrap = false)
        }
    }
}

private fun statusKicker(status: RowStatus): String = when (status) {
    RowStatus.Syncing -> "SYNCING"
    RowStatus.Ready -> "READY"
    RowStatus.Stale -> "STALE"
    RowStatus.NeverSynced -> "NEW"
    RowStatus.Error -> "ERROR"
}

private fun healthSummary(status: RowStatus): String = when (status) {
    RowStatus.Syncing -> "Syncing now"
    RowStatus.Ready -> "Healthy"
    RowStatus.Stale -> "Stale — sync to refresh"
    RowStatus.NeverSynced -> "Never synced"
    RowStatus.Error -> "Last sync failed"
}

private fun formatRefreshIn(source: Source): String {
    if (source.lastSyncError != null) return "—"
    val last = source.lastSynced ?: return "—"
    val intervalMs = source.autoSyncInterval.coerceAtLeast(1) * 60L * 60L * 1000L
    val remaining = (last + intervalMs) - System.currentTimeMillis()
    if (remaining <= 0L) return "Due now"
    val totalMin = remaining / 60_000L
    return when {
        totalMin < 60 -> "${totalMin}m"
        totalMin < 24 * 60 -> {
            val h = totalMin / 60
            val m = totalMin % 60
            if (m == 0L) "${h}h" else "${h}h ${m}m"
        }
        else -> {
            val d = totalMin / (24 * 60)
            val h = (totalMin % (24 * 60)) / 60
            if (h == 0L) "${d}d" else "${d}d ${h}h"
        }
    }
}

private fun serverFieldLabel(type: SourceType): String = when (type) {
    SourceType.XTREAM -> "Server URL"
    SourceType.M3U_URL -> "Playlist URL"
    SourceType.STALKER -> "Portal URL"
    SourceType.M3U_FILE -> "File path"
}

private fun formatLastSynced(ms: Long?): String {
    if (ms == null) return "Never"
    val ageMs = System.currentTimeMillis() - ms
    val totalMin = ageMs / 60_000L
    return when {
        totalMin < 1 -> "Moments ago"
        totalMin < 60 -> "$totalMin min ago"
        totalMin < 24 * 60 -> {
            val h = totalMin / 60
            "$h h ago"
        }
        else -> {
            val d = totalMin / (24 * 60)
            "$d d ago"
        }
    }
}
