package com.yancotv.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette ported from the desktop Tailwind theme (`src/renderer/styles/global.css`).
// Kept as a single source so the phone + TV themes stay visually identical.
object YancoPalette {
    val BackgroundDeep = Color(0xFF0B0F14)
    val BackgroundRaised = Color(0xFF10161D)
    val BackgroundHover = Color(0xFF162029)
    val BorderSubtle = Color(0xFF1C2732)
    val TextPrimary = Color(0xFFE6EDF3)
    val TextMuted = Color(0xFF8B97A7)
    val Accent = Color(0xFF9FC9FF)
    val AccentMuted = Color(0xFF4F7FBF)
    val Error = Color(0xFFFF6B6B)
    val FocusRing = Color(0xFF9FC9FF)
}

private val PhoneDarkScheme = darkColorScheme(
    primary = YancoPalette.Accent,
    onPrimary = Color.Black,
    secondary = YancoPalette.AccentMuted,
    background = YancoPalette.BackgroundDeep,
    surface = YancoPalette.BackgroundRaised,
    surfaceVariant = YancoPalette.BackgroundHover,
    onBackground = YancoPalette.TextPrimary,
    onSurface = YancoPalette.TextPrimary,
    onSurfaceVariant = YancoPalette.TextMuted,
    outline = YancoPalette.BorderSubtle,
    error = YancoPalette.Error,
)

private val TvDarkScheme = androidx.tv.material3.darkColorScheme(
    primary = YancoPalette.Accent,
    onPrimary = Color.Black,
    secondary = YancoPalette.AccentMuted,
    background = YancoPalette.BackgroundDeep,
    surface = YancoPalette.BackgroundRaised,
    surfaceVariant = YancoPalette.BackgroundHover,
    onBackground = YancoPalette.TextPrimary,
    onSurface = YancoPalette.TextPrimary,
    onSurfaceVariant = YancoPalette.TextMuted,
    border = YancoPalette.BorderSubtle,
    error = YancoPalette.Error,
)

@Composable
fun YancoTheme(isTv: Boolean, content: @Composable () -> Unit) {
    if (isTv) {
        androidx.tv.material3.MaterialTheme(
            colorScheme = TvDarkScheme,
            content = content,
        )
    } else {
        androidx.compose.material3.MaterialTheme(
            colorScheme = PhoneDarkScheme,
            content = content,
        )
    }
}
