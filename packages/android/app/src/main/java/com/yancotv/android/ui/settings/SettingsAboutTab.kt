package com.yancotv.android.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.R
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.update.UpdateCheckWorker
import com.yancotv.android.update.UpdateInstaller
import com.yancotv.android.update.UpdateRepository
import com.yancotv.shared.update.UpdateInfo
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * About tab — logo + version with an arabesque display face, then a
 * Stage 5.2 Updates section (auto-check toggle, last-checked, "Check
 * now" button + an "update available" banner when the worker has
 * found a newer version).
 *
 * MK.21+ redesign 2026-04-28: replaced the gradient "Y" placeholder
 * + "YancoTV" wordmark + tagline trio with the actual `ic_logo`
 * drawable resized to the same hero footprint, and moved the version
 * label into an arabesque (Cinzel Decorative) face for a more
 * deliberate identity than the default sans.
 */
@Composable
fun SettingsAboutTab(
    modifier: Modifier = Modifier,
    prefs: AppPreferences = koinInject(),
    updateRepo: UpdateRepository = koinInject(),
    installer: UpdateInstaller = koinInject(),
) {
    val ctx = LocalContext.current
    val info = remember { buildInfo(ctx) }
    val scroll = rememberScrollState()
    val palette = LocalYancoPalette.current

    val updatePrefs by prefs.updatePrefsFlow.collectAsState()
    val updateInfo by updateRepo.info.collectAsState()
    val installerState by installer.state.collectAsState()
    val scope = rememberCoroutineScope()
    val arabesque = remember { FontFamily(Font(R.font.arabesque_display, FontWeight.Bold)) }

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 80.dp),
        // dpadVerticalScroll deliberately NOT applied here — once the
        // tab has focusable rows (Updates toggle + Check-now button +
        // future control rows), Compose's natural focus traversal
        // drives scrolling automatically. Re-adding it would consume
        // D-pad UP/DOWN before focus could reach the controls, leaving
        // the user unable to hit them. The original MB-116 wrap was
        // for a fully-read-only tab — that's no longer this tab.
    ) {
        // ───── Logo (above the Version block, centered horizontally) ─────
        Image(
            painter = painterResource(id = R.drawable.ic_logo),
            contentDescription = "YancoTV logo",
            contentScale = ContentScale.Fit,
            modifier =
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(140.dp)
                .clip(RoundedCornerShape(20.dp))
                .padding(bottom = 12.dp),
        )

        // ───── Version row + tagline ─────
        // No section header — the row IS the version. "v 1.0.0" is the
        // aspirational target we'll ship under at feature-complete +
        // stable; the "Preview" kicker on the right tells the user
        // this is a pre-release build so the number doesn't read as a
        // promise. Actual BuildConfig version surfaces in Diagnostics
        // at the bottom for bug-report context.
        VersionRow(text = "v 1.0.0", kicker = "Preview", arabesque = arabesque)
        Text(
            text = "IPTV for Android TV, Fire TV, and phone.",
            color = palette.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
        )

        // ───── Updates (Stage 5.2.2 + 5.2.3) ─────
        // When BuildConfig.UPDATE_ENDPOINT is empty (no `update.endpoint`
        // in local.properties — the default for dev builds) the checker
        // is an unconditional no-op. Surface that explicitly instead of
        // showing a "Check now" button that does nothing + a "Last
        // checked: Just now" timestamp that lies. UpdateRepository
        // mirrors this state from UpdateChecker.isConfigured.
        val isConfigured = updateRepo.isConfigured
        val updatesSub =
            if (isConfigured) {
                "YancoTV will tell you when a new version is ready."
            } else {
                "Update checks aren't wired for this build (no release endpoint configured)."
            }
        val updatesRight: (@Composable () -> Unit)? =
            if (isConfigured) {
                {
                    SettingsOutlinedButton(
                        onClick = { UpdateCheckWorker.enqueueOnce(ctx) },
                        size = ButtonSize.Compact,
                    ) {
                        Text(text = "Check now")
                    }
                }
            } else {
                null
            }
        SettingsSection(
            title = "Updates",
            sub = updatesSub,
            right = updatesRight,
        ) {
            if (!isConfigured) {
                // Nothing else makes sense here — the toggle would be a
                // dead control, the timestamp would be permanently
                // "Never" or stale. Leave the section visible so the
                // user knows the feature exists; the sub copy explains
                // why the controls are absent.
                return@SettingsSection
            }
            SettingsToggleRow(
                label = "Check for updates automatically",
                description = "Once a day, quietly in the background.",
                checked = updatePrefs.autoCheckEnabled,
                onCheckedChange = { enabled ->
                    scope.launch {
                        prefs.setAutoUpdateCheckEnabled(enabled)
                        if (enabled) {
                            UpdateCheckWorker.schedulePeriodic(ctx)
                        } else {
                            UpdateCheckWorker.cancelPeriodic(ctx)
                        }
                    }
                },
            )
            SettingsRowSpacer()
            SettingsRow(
                label = "Last checked",
                right = { ValueText(formatLastChecked(updatePrefs.lastCheckedAt)) },
            )
            updateInfo?.let { uinfo ->
                SettingsRowSpacer()
                UpdateAvailableBanner(
                    info = uinfo,
                    installerState = installerState,
                    onDownload = { installer.download(uinfo) },
                    onCancel = { installer.cancel() },
                    onInstall = { apk ->
                        // launchInstall returns false when the system "install
                        // unknown apps" permission isn't granted yet — it
                        // also routes the user to settings, so the UI can
                        // surface a hint without opening anything itself.
                        installer.launchInstall(apk)
                    },
                    onRetry = {
                        installer.reset()
                        installer.download(uinfo)
                    },
                    onOpenReleasePage = {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(uinfo.downloadUrl),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }
        }

        // ───── About YancoTV ─────
        // Replaces the previous "Credits" section, which was a developer
        // architecture lecture (mentioning packages/shared, milestone
        // codes, etc — none of which mean anything to users). One human
        // sentence about what the app does + who it's for.
        SettingsSection(
            title = "About YancoTV",
            sub =
            "An IPTV client for Android TV, Fire TV, and phones. Bring your M3U or Xtream playlist; " +
                "we handle the EPG, recordings, favourites, multi-list, and smart category grouping.",
        ) {}

        // ───── Diagnostics ─────
        // Renamed from "Build" — these are debugging fields, not user
        // identity. Combined version + build into one row, dropped the
        // useless "Package: com.yancotv.android" row, added a Device
        // row that's actually useful when filing a bug report. Each
        // row is readOnlyFocusable so D-pad walks the whole tab top-
        // to-bottom and the verticalScroll's bringIntoView pulls each
        // focused row into the safety-margin gap inherited from
        // SettingsScreen (MK.21.8) — focus + scroll integrated, not
        // bolted together.
        SettingsSection(
            title = "Diagnostics",
            sub = "Useful when reporting a bug.",
        ) {
            SettingsRow(
                label = "Build",
                readOnlyFocusable = true,
                right = { ValueText("${info.version} (build ${info.versionCode})") },
            )
            SettingsRowSpacer()
            SettingsRow(
                label = "Device",
                readOnlyFocusable = true,
                right = { ValueText("${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}") },
            )
        }
    }
}

/**
 * Custom focusable version row — visually matches `SettingsRow` but
 * lets the label render in the arabesque face. Used only on the
 * About hero's Version block. Drives D-pad traversal + bringIntoView
 * scroll the same way every other read-only-focusable row does.
 */
@Composable
private fun VersionRow(text: String, kicker: String? = null, arabesque: FontFamily) {
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.BackgroundRaised.copy(alpha = if (focused) 0.65f else 0.5f))
            .border(
                width = if (focused) 1.5.dp else 1.dp,
                color = if (focused) palette.FocusRing else palette.BorderSubtle,
                shape = shape,
            )
            .focusable(interactionSource = interaction)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = palette.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = arabesque,
            letterSpacing = 0.4.sp,
            modifier = Modifier.weight(1f),
        )
        if (kicker != null) {
            // Soft tag on the right — "Preview" / "Pre-release" so the
            // aspirational v1.0.0 doesn't read as a shipping promise.
            // Uses the same SettingsKicker primitive other tabs use for
            // status pills so the visual language is consistent.
            SettingsKicker(text = kicker)
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
    )
}

/**
 * Stage 5.2.3 — banner that surfaces whenever the [UpdateRepository]
 * has a known new version. Drives off [UpdateInstaller.State] so the
 * call-to-action morphs as the user steps through the flow:
 *
 *   - Idle               → "Download update" (+ "Open release page")
 *   - Downloading(pct)   → progress bar + "Cancel"
 *   - ReadyToInstall     → "Install now" (+ "Open release page")
 *   - Failed(reason)     → reason text + "Retry" (+ "Open release page")
 *
 * The release-page button stays available in every state as a fallback
 * — if our download host is down, or the user prefers their browser,
 * they can sideload manually.
 */
@Composable
private fun UpdateAvailableBanner(
    info: UpdateInfo,
    installerState: UpdateInstaller.State,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: (java.io.File) -> Unit,
    onRetry: () -> Unit,
    onOpenReleasePage: () -> Unit,
) {
    val palette = LocalYancoPalette.current
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.Accent.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column {
            Text(
                text = "New version available: ${info.versionName}",
                color = palette.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val notes = info.releaseNotes
            if (!notes.isNullOrBlank()) {
                Text(
                    text = notes,
                    color = palette.TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            when (val s = installerState) {
                UpdateInstaller.State.Idle -> {
                    Text(
                        text = "Download and install in place, or open the release page in a browser.",
                        color = palette.TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                    )
                    Row {
                        SettingsOutlinedButton(
                            onClick = onDownload,
                            size = ButtonSize.Compact,
                        ) {
                            Text(text = "Download update")
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        SettingsOutlinedButton(
                            onClick = onOpenReleasePage,
                            size = ButtonSize.Compact,
                        ) {
                            Text(text = "Open release page")
                        }
                    }
                }
                is UpdateInstaller.State.Downloading -> {
                    Text(
                        text = "Downloading… ${s.percent}%",
                        color = palette.TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                    )
                    LinearProgressBar(percent = s.percent)
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsOutlinedButton(
                        onClick = onCancel,
                        size = ButtonSize.Compact,
                    ) {
                        Text(text = "Cancel")
                    }
                }
                is UpdateInstaller.State.ReadyToInstall -> {
                    Text(
                        text = "Download complete. Tap Install to apply the update — Android may ask you to allow installs from this app the first time.",
                        color = palette.TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                    )
                    Row {
                        SettingsOutlinedButton(
                            onClick = { onInstall(s.apkFile) },
                            size = ButtonSize.Compact,
                        ) {
                            Text(text = "Install now")
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        SettingsOutlinedButton(
                            onClick = onOpenReleasePage,
                            size = ButtonSize.Compact,
                        ) {
                            Text(text = "Open release page")
                        }
                    }
                }
                is UpdateInstaller.State.Failed -> {
                    Text(
                        text = "Download failed: ${s.reason}",
                        color = palette.TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                    )
                    Row {
                        SettingsOutlinedButton(
                            onClick = onRetry,
                            size = ButtonSize.Compact,
                        ) {
                            Text(text = "Retry")
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        SettingsOutlinedButton(
                            onClick = onOpenReleasePage,
                            size = ButtonSize.Compact,
                        ) {
                            Text(text = "Open release page")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinearProgressBar(percent: Int) {
    val palette = LocalYancoPalette.current
    val pct = percent.coerceIn(0, 100)
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(palette.BorderSubtle.copy(alpha = 0.6f)),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxWidth(fraction = pct / 100f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(palette.Accent),
        )
    }
}

private fun formatLastChecked(millis: Long?): String {
    if (millis == null) return "Never"
    val deltaMs = System.currentTimeMillis() - millis
    if (deltaMs < 0L) return "Just now"
    val mins = deltaMs / 60_000L
    return when {
        mins < 1L -> "Just now"
        mins < 60L -> "$mins min ago"
        mins < 24L * 60L -> "${mins / 60L} hr ago"
        else -> "${mins / (24L * 60L)} d ago"
    }
}

private data class BuildInfo(val version: String, val versionCode: Long, val packageName: String)

private fun buildInfo(ctx: Context): BuildInfo {
    val pm = ctx.packageManager
    val pkg = ctx.packageName
    return try {
        val info = pm.getPackageInfo(pkg, 0)

        @Suppress("DEPRECATION")
        val code: Long =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
        BuildInfo(
            version = info.versionName ?: "?",
            versionCode = code,
            packageName = pkg,
        )
    } catch (_: PackageManager.NameNotFoundException) {
        BuildInfo(version = "?", versionCode = 0L, packageName = pkg)
    }
}
