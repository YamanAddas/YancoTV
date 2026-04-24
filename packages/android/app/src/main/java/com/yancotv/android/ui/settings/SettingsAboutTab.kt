package com.yancotv.android.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.theme.YancoPalette

/**
 * About tab — version, milestone, and short build info. Everything here
 * is derived at runtime from the package manager so a bumped version
 * number in `build.gradle.kts` flows through without a code change.
 */
@Composable
fun SettingsAboutTab(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val info = remember { buildInfo(ctx) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "YancoTV",
            color = YancoPalette.TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "IPTV for Android TV + Fire TV + phone",
            color = YancoPalette.TextMuted,
            fontSize = 13.sp,
        )

        Spacer(modifier = Modifier.width(1.dp))

        InfoRow(label = "Version", value = info.version)
        InfoRow(label = "Build", value = info.versionCode.toString())
        InfoRow(label = "Package", value = info.packageName)
        InfoRow(label = "Milestone", value = CURRENT_MILESTONE)

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "Credits",
            color = YancoPalette.Accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text =
                "Built with Media3 ExoPlayer, SQLDelight, Ktor, Coil, and Jetpack Compose. " +
                    "Shared business logic (parsers, clients, classifier, EPG) lives in packages/shared via Kotlin Multiplatform — the iOS app in MK.iOS.* will consume the same code.",
            color = YancoPalette.TextMuted,
            fontSize = 12.sp,
        )
    }
}

private const val CURRENT_MILESTONE = "MK.8.6 Settings shell (in progress)"

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

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(YancoPalette.BackgroundRaised)
                .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = YancoPalette.TextMuted,
            fontSize = 12.sp,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value,
            color = YancoPalette.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
