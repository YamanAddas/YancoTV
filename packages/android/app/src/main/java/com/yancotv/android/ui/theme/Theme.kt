package com.yancotv.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cinematic dark palette — obsidian surfaces + refined mint accent. Evolved
// from the first-pass "admin utility" greens toward a streaming-app feel:
// deeper blacks for contrast, a single brand accent used sparingly, ambient
// glow reserved for focus and highlights.
object YancoPalette {
    // Base canvases. BackgroundDeep is near-black with a trace of cool green
    // so the mint accent harmonises without sitting on pure grey.
    val BackgroundDeep = Color(0xFF06090B)
    val BackgroundRaised = Color(0xFF0D1216)
    val BackgroundHover = Color(0xFF17232A)
    val BackgroundElevated = Color(0xFF1B2A33)

    // Hairline borders. Subtle by default, PanelBorder for stronger divides.
    val BorderSubtle = Color(0xFF1F2D34)
    val PanelBorder = Color(0xFF2A3B44)

    // Text ramp — warm off-white primary, desaturated cool secondary,
    // dedicated muted tone for tertiary metadata so rails don't shout.
    val TextPrimary = Color(0xFFECF4F1)
    val TextSecondary = Color(0xFFB8C7C2)
    val TextMuted = Color(0xFF7A8E88)
    val TextFaint = Color(0xFF4C5B57)

    // Brand accent. The single signature colour; used for the logo mark,
    // focus ring, selected-state highlights, progress fills. Everything
    // else is neutral so this pops.
    val Accent = Color(0xFF3DE5A8)
    val AccentSoft = Color(0xFF8EFFD0)
    val AccentDeep = Color(0xFF188F66)
    val AccentGlow = Color(0xFF5CFFBD)

    // Muted accent for lower-emphasis chips (HD badge, disabled buttons).
    // Sits between AccentDeep and the panel borders so it reads as
    // "accent family" without competing with the primary focus ring.
    val AccentMuted = Color(0xFF2F8B6A)

    // Warm amber for "live" indicators / recording dots so it reads as
    // "signal" not "brand accent". Gold for 4K / premium badges.
    val Live = Color(0xFFFF5E57)
    val Premium = Color(0xFFD7B36A)

    // Feedback.
    val Error = Color(0xFFFF6B6B)
    val FocusRing = Color(0xFF6BFFC8)

    // Semi-transparent layers derived once so composables don't hand-roll.
    val Scrim = Color(0xCC050709)
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
