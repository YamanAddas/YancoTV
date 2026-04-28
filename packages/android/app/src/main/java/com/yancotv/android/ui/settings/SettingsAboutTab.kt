package com.yancotv.android.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.focus.dpadVerticalScroll
import com.yancotv.android.ui.theme.LocalYancoPalette

/**
 * About tab — version + build hero, then run-of-the-mill metadata rows
 * and credits. Re-skinned onto the [SettingsSection] / [SettingsRow]
 * primitives so the type scale matches the rest of Settings.
 */
@Composable
fun SettingsAboutTab(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val info = remember { buildInfo(ctx) }
    val scroll = rememberScrollState()
    val palette = LocalYancoPalette.current

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                // MB-116: focusable + D-pad UP/DOWN drive scroll. Without
                // this the read-only About body has no focusable child, so
                // the focus from the sub-sidebar lands on ContentPane's
                // sibling FocusableSpacer and D-pad arrows do nothing.
                .dpadVerticalScroll(scroll)
                .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 32.dp),
    ) {
        SettingsSection(title = "Version") {
            // Hero card — gradient Y tile + wordmark + version + build.
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.BackgroundRaised.copy(alpha = 0.55f))
                        .padding(horizontal = 24.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(palette.Accent, palette.AccentDeep),
                                ),
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Y",
                        color = palette.BackgroundDeep,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "YancoTV",
                        color = palette.TextPrimary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.4).sp,
                    )
                    Text(
                        text = "IPTV for Android TV · Fire TV · phone",
                        color = palette.TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsKicker(text = "VERSION ${info.version}", accent = true)
                        Text(
                            text = "·",
                            color = palette.TextMuted,
                            fontSize = 11.sp,
                        )
                        SettingsKicker(text = "BUILD ${info.versionCode}")
                    }
                }
            }
        }

        SettingsSection(title = "Build") {
            SettingsRow(label = "Version", right = { ValueText(info.version) })
            SettingsRowSpacer()
            SettingsRow(label = "Build", right = { ValueText(info.versionCode.toString()) })
            SettingsRowSpacer()
            SettingsRow(label = "Package", right = { ValueText(info.packageName) })
            SettingsRowSpacer()
            SettingsRow(label = "Milestone", right = { ValueText(CURRENT_MILESTONE) })
        }

        SettingsSection(
            title = "Credits",
            sub = "Built with Media3 ExoPlayer, SQLDelight, Ktor, Coil, and Jetpack Compose. Shared business logic (parsers, clients, classifier, EPG) lives in packages/shared via Kotlin Multiplatform — the iOS app in MK.iOS.* will consume the same code.",
        ) {}
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

private const val CURRENT_MILESTONE = "MK.8.6 Settings shell"

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
