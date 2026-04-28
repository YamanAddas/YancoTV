package com.yancotv.android.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.yancotv.android.R
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.ui.focus.dpadVerticalScroll
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.update.UpdateCheckWorker
import com.yancotv.android.update.UpdateRepository
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
) {
    val ctx = LocalContext.current
    val info = remember { buildInfo(ctx) }
    val scroll = rememberScrollState()
    val palette = LocalYancoPalette.current

    val updatePrefs by prefs.updatePrefsFlow.collectAsState()
    val updateInfo by updateRepo.info.collectAsState()
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

        // ───── Version block — aspirational v1.0.0 ─────
        // Hardcoded "v 1.0.0" — the target stability number we'll ship
        // under once the app is feature-complete. Actual BuildConfig
        // versionName / versionCode still appear in Build section
        // below for diagnostics.
        //
        // Custom focusable row instead of `SettingsRow` because we need
        // the arabesque face on the left-aligned label, and SettingsRow
        // hardcodes the label font. Visual match (rounded corners,
        // background tint, focus border) so it reads as part of the
        // same primitives family. `readOnlyFocusable = true`-equivalent:
        // the Box itself is focusable so D-pad traversal walks through
        // it, and the parent verticalScroll's bringIntoView pulls it
        // into the safety-margin gap on focus.
        SettingsSection(title = "Version") {
            VersionRow(text = "v 1.0.0", arabesque = arabesque)
        }

        // ───── Updates (Stage 5.2.2) ─────
        SettingsSection(
            title = "Updates",
            sub = "Periodic check against the configured release endpoint. Disable to skip the 24-hour poll; \"Check now\" runs immediately regardless.",
            right = {
                SettingsOutlinedButton(
                    onClick = { UpdateCheckWorker.enqueueOnce(ctx) },
                    size = ButtonSize.Compact,
                ) {
                    Text(text = "Check now")
                }
            },
        ) {
            SettingsToggleRow(
                label = "Auto-check for updates",
                description = "Polls the release endpoint every 24 hours. Honors the system network policy (skips on metered if you've set the OS-wide preference).",
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
                            text = "New version available: ${uinfo.versionName}",
                            color = palette.Accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val notes = uinfo.releaseNotes
                        if (!notes.isNullOrBlank()) {
                            Text(
                                text = notes,
                                color = palette.TextSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Text(
                            text = "Install flow lands in a follow-up update.",
                            color = palette.TextMuted,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        // ───── Build metadata ─────
        // `readOnlyFocusable = true` on every read-only row makes them
        // participate in D-pad traversal. Without this the read-only
        // section was unreachable: D-pad DOWN from the Updates toggle
        // (the last focusable above) had nothing focusable to land on
        // here, so the user never saw the Build / Credits content even
        // though it was rendered just below the viewport. Integrating
        // focus + scroll into ONE thing — every row is focusable, the
        // verticalScroll's bringIntoView pulls each focused row into
        // view with the safety margin, and the natural top-to-bottom
        // focus order matches the natural top-to-bottom reading order.
        SettingsSection(title = "Build") {
            SettingsRow(
                label = "Version",
                readOnlyFocusable = true,
                right = { ValueText(info.version) },
            )
            SettingsRowSpacer()
            SettingsRow(
                label = "Build",
                readOnlyFocusable = true,
                right = { ValueText(info.versionCode.toString()) },
            )
            SettingsRowSpacer()
            SettingsRow(
                label = "Package",
                readOnlyFocusable = true,
                right = { ValueText(info.packageName) },
            )
        }

        SettingsSection(
            title = "Credits",
            sub = "Built with Media3 ExoPlayer, SQLDelight, Ktor, Coil, and Jetpack Compose. Shared business logic (parsers, clients, classifier, EPG) lives in packages/shared via Kotlin Multiplatform — the iOS app in MK.iOS.* will consume the same code.",
        ) {
            // Empty section body. The `sub` line above carries the
            // content; an additional read-only-focusable spacer here
            // gives D-pad somewhere to land at the very bottom so the
            // scroll comes to rest WITH the safety-margin gap below
            // the Credits text.
            SettingsRow(
                label = "Open-source licenses",
                hint = "Bundled at app/src/main/res/raw — review for full notices.",
                readOnlyFocusable = true,
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
private fun VersionRow(
    text: String,
    arabesque: FontFamily,
) {
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
        )
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

private data class BuildInfo(
    val version: String,
    val versionCode: Long,
    val packageName: String,
)

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
