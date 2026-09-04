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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.R
import com.yancotv.android.locale.localeNumber
import com.yancotv.android.sources.SourceSyncCoordinator
import com.yancotv.android.ui.components.ConfirmDangerDialog
import com.yancotv.android.ui.focus.placedFocus
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import com.yancotv.android.ui.focus.snapToTopNearStart
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
    val ctx = LocalContext.current
    // MK.31.21 — assigned from suspend functions, not composable scope.
    val notFoundMsg = stringResource(R.string.sd_not_found)
    val unknownErrorMsg = stringResource(R.string.common_unknown_error)
    // MB-335 — DELETE must never fire on a single press. See
    // ConfirmDangerDialog for the incident that mandated this.
    var confirmDelete by remember { mutableStateOf(false) }

    // Editable buffers — separate from the persisted Source so the user
    // can type freely; SAVE commits them via SourceRepository.updateSource.
    var nameField by rememberSaveable { mutableStateOf("") }
    var urlField by rememberSaveable { mutableStateOf("") }
    var usernameField by rememberSaveable { mutableStateOf("") }
    var passwordField by rememberSaveable { mutableStateOf("") }
    var epgUrlField by rememberSaveable { mutableStateOf("") }
    var referrerField by rememberSaveable { mutableStateOf("") }
    var userAgentField by rememberSaveable { mutableStateOf("") }
    // MK.28.4 (MB-259) — dirty is saveable together with the field buffers,
    // and seeding is one-shot per source: loadSource used to overwrite all
    // seven restored buffers with DB values on every recreation, silently
    // reverting unsaved edits while dirty=false hid the loss.
    var dirty by rememberSaveable { mutableStateOf(false) }
    var seeded by rememberSaveable(sourceId) { mutableStateOf(false) }
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
            loadError = notFoundMsg
            return
        }
        source = loaded
        // Seed editable fields with current persisted values — ONCE per
        // source (MB-259). On a recreation the rememberSaveable buffers
        // already hold the user's in-progress edits; re-seeding would
        // clobber them. Decrypted username/password come from
        // xtreamCredentials() for Xtream sources; M3U-URL has no
        // credentials, Stalker has MAC.
        if (seeded) return
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
        seeded = true
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

    // MK.30.6 — hoisted so snapToTopNearStart can see the same state.
    val tabScroll = rememberScrollState()

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .verticalScroll(tabScroll)
            .snapToTopNearStart(tabScroll),
    ) {
        DetailHero(
            source = current,
            palette = palette,
            onBack = onBack,
            backAnchorModifier = Modifier.placedFocus(backButtonAnchor),
        )

        Spacer(modifier = Modifier.height(8.dp))

        SettingsSection(
            title = stringResource(R.string.sd_sec_status),
            sub = stringResource(R.string.sd_sec_status_sub),
        ) {
            val status = remember(current) { computeRowStatus(current, isSyncing = false) }
            // Every row in this section is read-only but [readOnlyFocusable]
            // so D-pad can stop on each one. Without that, DOWN from the
            // back button would skip the entire Status section and land on
            // the Connection form's first text field, never scrolling these
            // facts into view.
            SettingsRow(
                label = stringResource(R.string.sd_health),
                kicker = statusKicker(status),
                right = {
                    Text(
                        text = healthSummary(status),
                        color = status.subColor(palette),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                readOnlyFocusable = true,
            )
            SettingsRowSpacer()
            SettingsRow(
                label = stringResource(R.string.sd_refresh_in),
                kicker = stringResource(R.string.sd_kicker_autosync),
                right = { ValueText(formatRefreshIn(ctx, current)) },
                readOnlyFocusable = true,
            )
            SettingsRowSpacer()
            SettingsRow(
                label = stringResource(R.string.sd_last_synced),
                right = { ValueText(formatLastSynced(ctx, current.lastSynced)) },
                readOnlyFocusable = true,
            )
            SettingsRowSpacer()
            SettingsRow(
                label = stringResource(R.string.sd_live_channels),
                right = { ValueText(localeNumber(current.channelCount)) },
                readOnlyFocusable = true,
            )
            // MK.30.3 — account expiry. Only rendered when the provider
            // actually reported one: m3u playlists carry no account metadata,
            // so a permanent "Unknown" row there would look like a defect.
            formatSourceExpiry(
                current.expiresAt,
                System.currentTimeMillis(),
                expiryStrings(ctx),
            )?.let { expiry ->
                SettingsRowSpacer()
                SettingsRow(
                    label = stringResource(R.string.sd_subscription),
                    kicker = if (expiry.urgency == ExpiryUrgency.Later) null else "EXPIRES",
                    right = {
                        Text(
                            text = expiry.full,
                            color =
                            when (expiry.urgency) {
                                ExpiryUrgency.Expired -> palette.Error
                                ExpiryUrgency.Soon -> palette.Premium
                                ExpiryUrgency.Later -> palette.TextSecondary
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    readOnlyFocusable = true,
                )
            }
            current.lastSyncError?.let { err ->
                SettingsRowSpacer()
                SettingsRow(
                    label = stringResource(R.string.sd_last_error),
                    kicker = stringResource(R.string.sd_kicker_error),
                    hint = err,
                    readOnlyFocusable = true,
                )
            }
        }

        SettingsSection(
            title = stringResource(R.string.sd_sec_sync),
            sub = stringResource(R.string.sd_sec_sync_sub),
        ) {
            SettingsToggleRow(
                label = stringResource(R.string.sd_autosync_on_start),
                description =
                stringResource(R.string.sd_autosync_help),
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
            title = stringResource(R.string.sd_sec_connection),
            sub = stringResource(R.string.sd_sec_connection_sub),
        ) {
            SettingsClickToEditField(
                label = stringResource(R.string.sd_display_name),
                value = nameField,
                onValueChange = {
                    nameField = it
                    dirty = true
                },
                hint = stringResource(R.string.sd_name_hint),
            )
            if (current.type != SourceType.M3U_FILE) {
                SettingsRowSpacer()
                SettingsClickToEditField(
                    label = serverFieldLabel(current.type),
                    description = stringResource(R.string.sd_url_desc),
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
                    label = stringResource(R.string.sd_file_path),
                    hint = stringResource(R.string.sd_file_path_hint),
                    right = {
                        Text(
                            text = current.filePath?.takeLast(40) ?: "—",
                            color = palette.TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
            if (current.type == SourceType.XTREAM) {
                SettingsRowSpacer()
                SettingsClickToEditField(
                    label = stringResource(R.string.add_username),
                    value = usernameField,
                    onValueChange = {
                        usernameField = it
                        dirty = true
                    },
                    hint = stringResource(R.string.sd_username_hint),
                    keyboardType = KeyboardType.Ascii,
                )
                SettingsRowSpacer()
                SettingsClickToEditField(
                    label = stringResource(R.string.add_password),
                    description = stringResource(R.string.sd_password_desc),
                    value = passwordField,
                    onValueChange = {
                        passwordField = it
                        dirty = true
                    },
                    hint = stringResource(R.string.sd_password_hint),
                    transformation = PasswordVisualTransformation(),
                    keyboardType = KeyboardType.Password,
                )
            }
            SettingsRowSpacer()
            SettingsClickToEditField(
                label = stringResource(R.string.sd_epg_url),
                description = stringResource(R.string.sd_epg_url_desc),
                value = epgUrlField,
                onValueChange = {
                    epgUrlField = it
                    dirty = true
                },
                hint = "https://example.com/epg.xml.gz",
            )
            SettingsRowSpacer()
            SettingsClickToEditField(
                label = stringResource(R.string.sd_ua),
                description = stringResource(R.string.sd_ua_desc),
                value = userAgentField,
                onValueChange = {
                    userAgentField = it
                    dirty = true
                },
                hint = "VLC/3.0.20 LibVLC/3.0.20",
            )
            SettingsRowSpacer()
            SettingsClickToEditField(
                label = stringResource(R.string.sd_referer),
                description = stringResource(R.string.sd_referer_desc),
                value = referrerField,
                onValueChange = {
                    referrerField = it
                    dirty = true
                },
                hint = "https://example.com",
            )
        }

        SettingsSection(
            title = stringResource(R.string.sd_sec_actions),
            sub = stringResource(R.string.sd_sec_actions_sub),
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
                    .startExitsTo(LocalActiveSettingsTabFocus.current),
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
                                    saveError = t.message ?: t::class.simpleName ?: unknownErrorMsg
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
                    Text(text = if (saving) "SAVING…" else "SAVE", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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
                    Text(text = stringResource(R.string.sd_btn_sync), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
                SettingsDangerButton(
                    onClick = { confirmDelete = true },
                    size = ButtonSize.Compact,
                ) {
                    Text(text = stringResource(R.string.sd_btn_delete), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
            if (confirmDelete) {
                ConfirmDangerDialog(
                    title = stringResource(R.string.dlg_delete_source_title, current.name),
                    body = stringResource(R.string.dlg_delete_source_body),
                    confirmLabel = stringResource(R.string.dlg_delete_cta),
                    onConfirm = {
                        confirmDelete = false
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
                    onDismiss = { confirmDelete = false },
                )
            }
            saveError?.let { err ->
                SettingsRowSpacer()
                Text(
                    text = stringResource(R.string.sd_save_failed, err),
                    color = palette.Error,
                    fontSize = 12.sp,
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
            SettingsKicker(text = stringResource(R.string.sd_kicker_source, typeLabel(source.type).uppercase()), accent = true)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = source.name.ifBlank { stringResource(R.string.sd_untitled) },
                color = palette.TextPrimary,
                fontSize = 23.sp,
                lineHeight = 29.sp,
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
            Text(text = stringResource(R.string.sd_btn_back), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ValueText(value: String) {
    Text(
        text = value,
        color = LocalYancoPalette.current.TextPrimary,
        fontSize = 14.sp,
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
            Text(text = stringResource(R.string.sd_back_to_list), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun statusKicker(status: RowStatus): String = when (status) {
    RowStatus.Syncing -> stringResource(R.string.st_kicker_syncing)
    RowStatus.Ready -> stringResource(R.string.st_kicker_ready)
    RowStatus.Stale -> stringResource(R.string.st_kicker_stale)
    RowStatus.NeverSynced -> stringResource(R.string.st_kicker_new)
    RowStatus.Error -> stringResource(R.string.st_kicker_error)
}

@Composable
private fun healthSummary(status: RowStatus): String = when (status) {
    RowStatus.Syncing -> stringResource(R.string.st_syncing_now)
    RowStatus.Ready -> stringResource(R.string.st_healthy)
    RowStatus.Stale -> stringResource(R.string.st_stale_sync)
    RowStatus.NeverSynced -> stringResource(R.string.st_never_synced)
    RowStatus.Error -> stringResource(R.string.st_last_sync_failed)
}

private fun formatRefreshIn(ctx: android.content.Context, source: Source): String {
    // The em-dash placeholders stay literal — they are notation, not copy.
    if (source.lastSyncError != null) return "—"
    val last = source.lastSynced ?: return "—"
    val intervalMs = source.autoSyncInterval.coerceAtLeast(1) * 60L * 60L * 1000L
    val remaining = (last + intervalMs) - System.currentTimeMillis()
    if (remaining <= 0L) return ctx.getString(R.string.sd_due_now)
    val totalMin = remaining / 60_000L
    return when {
        totalMin < 60 -> ctx.getString(R.string.dur_m, totalMin)
        totalMin < 24 * 60 -> {
            val h = totalMin / 60
            val m = totalMin % 60
            if (m == 0L) {
                ctx.getString(R.string.dur_h, h)
            } else {
                ctx.getString(R.string.sd_dur_hm, h, m)
            }
        }
        else -> {
            val d = totalMin / (24 * 60)
            val h = (totalMin % (24 * 60)) / 60
            if (h == 0L) {
                ctx.getString(R.string.dur_d, d)
            } else {
                ctx.getString(R.string.sd_dur_dh, d, h)
            }
        }
    }
}

@Composable
private fun serverFieldLabel(type: SourceType): String = when (type) {
    SourceType.XTREAM -> stringResource(R.string.sd_server_url)
    SourceType.M3U_URL -> stringResource(R.string.sd_playlist_url)
    SourceType.STALKER -> stringResource(R.string.sd_portal_url)
    SourceType.M3U_FILE -> stringResource(R.string.sd_file_path)
}

private fun formatLastSynced(ctx: android.content.Context, ms: Long?): String {
    if (ms == null) return ctx.getString(R.string.common_never)
    val ageMs = System.currentTimeMillis() - ms
    val totalMin = ageMs / 60_000L
    return when {
        totalMin < 1 -> ctx.getString(R.string.sd_moments_ago)
        totalMin < 60 -> ctx.getString(R.string.sd_min_ago, totalMin)
        totalMin < 24 * 60 -> ctx.getString(R.string.rel_hour_ago, totalMin / 60)
        else -> ctx.getString(R.string.rel_day_ago, totalMin / (24 * 60))
    }
}
