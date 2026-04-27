package com.yancotv.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Runtime-swappable palette. Was `object YancoPalette` through MK.8;
 * MK.16.1 promoted it to a [data class] so the UI can recompose to a
 * different palette without restart (MK.16.2 wires the picker).
 *
 * All 21 tokens are named + documented on the class for IDE-completion
 * friendliness — downstream code reads `LocalYancoPalette.current.Accent`
 * and gets the field + its KDoc without having to open this file.
 *
 * Field names intentionally preserved from the old `object` so the
 * refactor is a mechanical prefix swap at every call site.
 */
@Immutable
data class YancoPalette(
    // Base canvases. BackgroundDeep is the near-black floor; everything
    // else sits above it. Each step adds a touch more colour saturation so
    // panels read as lit by the accent without changing hue.
    val BackgroundDeep: Color,
    val BackgroundRaised: Color,
    val BackgroundHover: Color,
    val BackgroundElevated: Color,
    // Hairline borders. Translucent so the canvas bleeds through.
    val BorderSubtle: Color,
    val PanelBorder: Color,
    // Text ramp — primary / secondary / muted / faint.
    val TextPrimary: Color,
    val TextSecondary: Color,
    val TextMuted: Color,
    val TextFaint: Color,
    // Brand accent + partners for gradients / idle progress fills.
    val Accent: Color,
    val AccentSoft: Color,
    val AccentDeep: Color,
    val AccentGlow: Color,
    val AccentMuted: Color,
    // Status accents.
    val Live: Color,
    val Premium: Color,
    val Error: Color,
    val FocusRing: Color,
    // Semi-transparent layers.
    val Scrim: Color,
    val Veil: Color,
)

/**
 * Concept A — "Frosted Glass Emerald". Deep green-black canvases with a
 * single saturated emerald accent. Tuned in Claude Design (2026-04-23)
 * to match the hex-cut card vocabulary and the 3D coverflow Live TV
 * view. Colour references: design CSS vars in `YancoTV+Concept A.html`.
 *
 * This is the MK.16.1 baseline + the default value for any composable
 * that renders without a [YancoTheme] wrapper (e.g. the pre-existing
 * `ChannelSurfOverlay` / `PlayerOptionsSheet` `ComposeView`s in
 * `PlayerActivity` — wrapping those lands with MK.16.2).
 */
val FrostedEmerald =
    YancoPalette(
        BackgroundDeep = Color(0xFF050A08),
        BackgroundRaised = Color(0xFF0A1410),
        BackgroundHover = Color(0xFF0F1C17),
        BackgroundElevated = Color(0xFF14251F),
        BorderSubtle = Color(0x14FFFFFF),
        PanelBorder = Color(0x1FFFFFFF),
        TextPrimary = Color(0xFFF0FFF6),
        TextSecondary = Color(0xFFA7B8AF),
        TextMuted = Color(0xFF5F7068),
        TextFaint = Color(0xFF3A4A43),
        Accent = Color(0xFF00E28A),
        AccentSoft = Color(0xFF66F0B5),
        AccentDeep = Color(0xFF00B872),
        AccentGlow = Color(0xFF66F0B5),
        AccentMuted = Color(0xFF1C7A55),
        Live = Color(0xFFFF3B3B),
        Premium = Color(0xFFD7B36A),
        Error = Color(0xFFFF6B6B),
        FocusRing = Color(0xFF66F0B5),
        Scrim = Color(0xCC050A08),
        Veil = Color(0x66000000),
    )

/**
 * Palette scope for the current composition. Every UI surface reads
 * colours through `LocalYancoPalette.current.X` — `YancoTheme` provides
 * the active palette from [ThemeController], and the default
 * ([FrostedEmerald]) handles the rare case of a composable rendered
 * outside a `YancoTheme` wrapper.
 *
 * `staticCompositionLocalOf` is correct here: reads vastly outnumber
 * writes (theme swap), and a swap legitimately wants every reader to
 * recompose — the tracking overhead of `compositionLocalOf` would be
 * pure waste.
 */
val LocalYancoPalette = staticCompositionLocalOf { FrostedEmerald }

/**
 * MK.16.2 — "Midnight Sapphire". Cool blue-black canvases with a
 * saturated cobalt accent. Reads more "TV-broadcast classic" than
 * Emerald's tech-startup tone; pairs well with sports / news streams.
 */
val MidnightSapphire =
    YancoPalette(
        BackgroundDeep = Color(0xFF050810),
        BackgroundRaised = Color(0xFF0A1020),
        BackgroundHover = Color(0xFF0F1730),
        BackgroundElevated = Color(0xFF14203F),
        BorderSubtle = Color(0x14FFFFFF),
        PanelBorder = Color(0x1FFFFFFF),
        TextPrimary = Color(0xFFEDF3FF),
        TextSecondary = Color(0xFFA7B3CC),
        TextMuted = Color(0xFF5F6B85),
        TextFaint = Color(0xFF3A4358),
        Accent = Color(0xFF4A8CFF),
        AccentSoft = Color(0xFF8FB6FF),
        AccentDeep = Color(0xFF2A6BD8),
        AccentGlow = Color(0xFF8FB6FF),
        AccentMuted = Color(0xFF1F3F7A),
        Live = Color(0xFFFF3B3B),
        Premium = Color(0xFFD7B36A),
        Error = Color(0xFFFF6B6B),
        FocusRing = Color(0xFF8FB6FF),
        Scrim = Color(0xCC050810),
        Veil = Color(0x66000000),
    )

/**
 * MK.16.2 — "Warm Amber". Charcoal canvases warmed slightly toward
 * brown, paired with a saturated amber accent. Easier on the eyes
 * for long-form viewing in dim rooms; closest analogue is f.lux's
 * warm-night palette adapted for a 10-foot UI.
 */
val WarmAmber =
    YancoPalette(
        BackgroundDeep = Color(0xFF0E0907),
        BackgroundRaised = Color(0xFF18110D),
        BackgroundHover = Color(0xFF231811),
        BackgroundElevated = Color(0xFF2E1F15),
        BorderSubtle = Color(0x14FFFFFF),
        PanelBorder = Color(0x1FFFFFFF),
        TextPrimary = Color(0xFFFFF3E5),
        TextSecondary = Color(0xFFC9B5A0),
        TextMuted = Color(0xFF7A6A5C),
        TextFaint = Color(0xFF4A3F35),
        Accent = Color(0xFFFFB14A),
        AccentSoft = Color(0xFFFFD18F),
        AccentDeep = Color(0xFFE08826),
        AccentGlow = Color(0xFFFFD18F),
        AccentMuted = Color(0xFF7A4A1F),
        Live = Color(0xFFFF3B3B),
        Premium = Color(0xFFD7B36A),
        Error = Color(0xFFFF6B6B),
        FocusRing = Color(0xFFFFD18F),
        Scrim = Color(0xCC0E0907),
        Veil = Color(0x66000000),
    )

/**
 * MK.16.2 — "Monochrome". No colour accent — neutral grayscale ramp
 * with a near-white focus ring. For users who find saturated accents
 * distracting; also a useful sanity check that no UI relies on hue
 * to convey state (everything must still be legible here).
 */
val Monochrome =
    YancoPalette(
        BackgroundDeep = Color(0xFF080808),
        BackgroundRaised = Color(0xFF121212),
        BackgroundHover = Color(0xFF1C1C1C),
        BackgroundElevated = Color(0xFF262626),
        BorderSubtle = Color(0x14FFFFFF),
        PanelBorder = Color(0x1FFFFFFF),
        TextPrimary = Color(0xFFF5F5F5),
        TextSecondary = Color(0xFFB8B8B8),
        TextMuted = Color(0xFF6E6E6E),
        TextFaint = Color(0xFF424242),
        Accent = Color(0xFFE0E0E0),
        AccentSoft = Color(0xFFFFFFFF),
        AccentDeep = Color(0xFFB0B0B0),
        AccentGlow = Color(0xFFFFFFFF),
        AccentMuted = Color(0xFF555555),
        Live = Color(0xFFFF6B6B),
        Premium = Color(0xFFD7B36A),
        Error = Color(0xFFFF6B6B),
        FocusRing = Color(0xFFFFFFFF),
        Scrim = Color(0xCC080808),
        Veil = Color(0x66000000),
    )
