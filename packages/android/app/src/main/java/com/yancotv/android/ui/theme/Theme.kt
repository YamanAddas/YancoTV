package com.yancotv.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Concept A — "Frosted Glass Emerald". Deep green-black canvases with a
// single saturated emerald accent. Tuned in Claude Design (2026-04-23) to
// match the hex-cut card vocabulary and the 3D coverflow Live TV view.
// Color references: design CSS vars in YancoTV+Concept A.html.
object YancoPalette {
    // Base canvases. BackgroundDeep is the near-black floor; everything
    // else sits above it. Each step adds a touch more green saturation so
    // panels read as lit by the accent without changing hue.
    val BackgroundDeep = Color(0xFF050A08)
    val BackgroundRaised = Color(0xFF0A1410)
    val BackgroundHover = Color(0xFF0F1C17)
    val BackgroundElevated = Color(0xFF14251F)

    // Hairline borders. Translucent so the green-black canvas bleeds through.
    val BorderSubtle = Color(0x14FFFFFF)
    val PanelBorder = Color(0x1FFFFFFF)

    // Text ramp — ivory primary, desaturated mint secondary, faded forest
    // muted, near-floor faint for footer-style metadata.
    val TextPrimary = Color(0xFFF0FFF6)
    val TextSecondary = Color(0xFFA7B8AF)
    val TextMuted = Color(0xFF5F7068)
    val TextFaint = Color(0xFF3A4A43)

    // Brand accent — saturated emerald, primary signal everywhere. Deep
    // partner sits one step warmer/darker for gradients (top→bottom).
    val Accent = Color(0xFF00E28A)
    val AccentSoft = Color(0xFF66F0B5)
    val AccentDeep = Color(0xFF00B872)
    val AccentGlow = Color(0xFF66F0B5)

    // Muted accent — for lower-emphasis chips and idle progress fills.
    val AccentMuted = Color(0xFF1C7A55)

    // Warm red for live/recording dots; gold for 4K/premium badges.
    val Live = Color(0xFFFF3B3B)
    val Premium = Color(0xFFD7B36A)

    // Feedback.
    val Error = Color(0xFFFF6B6B)
    val FocusRing = Color(0xFF66F0B5)

    // Semi-transparent layers — derived once so composables don't hand-roll.
    val Scrim = Color(0xCC050A08)
    val Veil = Color(0x66000000)
}

private val PhoneDarkScheme = darkColorScheme(
    primary = YancoPalette.Accent,
    onPrimary = Color.Black,
    secondary = YancoPalette.AccentDeep,
    background = YancoPalette.BackgroundDeep,
    surface = YancoPalette.BackgroundRaised,
    surfaceVariant = YancoPalette.BackgroundHover,
    onBackground = YancoPalette.TextPrimary,
    onSurface = YancoPalette.TextPrimary,
    onSurfaceVariant = YancoPalette.TextSecondary,
    outline = YancoPalette.BorderSubtle,
    error = YancoPalette.Error,
)

private val TvDarkScheme = androidx.tv.material3.darkColorScheme(
    primary = YancoPalette.Accent,
    onPrimary = Color.Black,
    secondary = YancoPalette.AccentDeep,
    background = YancoPalette.BackgroundDeep,
    surface = YancoPalette.BackgroundRaised,
    surfaceVariant = YancoPalette.BackgroundHover,
    onBackground = YancoPalette.TextPrimary,
    onSurface = YancoPalette.TextPrimary,
    onSurfaceVariant = YancoPalette.TextSecondary,
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
