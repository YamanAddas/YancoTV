package com.yancotv.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.yancotv.android.prefs.AppPreferences
import org.koin.compose.koinInject

/**
 * Top-level theme wrapper. Everything the app renders lives inside a
 * single [YancoTheme] scope at `MainActivity.setContent { ... }`.
 *
 * Responsibilities:
 *   1. Read the active [ThemeId] from [ThemeController] and map it to
 *      a [YancoPalette].
 *   2. Push the palette through [LocalYancoPalette] so every surface
 *      downstream can read colours via `LocalYancoPalette.current.*`.
 *   3. Build Material3 / androidx.tv.material3 `colorScheme`s from the
 *      same palette so stock Material widgets (TextField, Button) match
 *      the theme.
 *
 * Palette-derived schemes move *inside* the composable on purpose: the
 * previous `object`-based palette could live at module scope, but a
 * `data class` instance has to be resolved per-composition so the
 * colourScheme rebuilds when the user switches theme (MK.16.2).
 * [remember] keyed on the palette keeps the allocation cheap.
 *
 * The `ChannelSurfOverlay` + `PlayerOptionsSheet` `ComposeView`s in
 * `PlayerActivity` intentionally do NOT wrap in `YancoTheme` today;
 * they'll fall back to [FrostedEmerald] via the `LocalYancoPalette`
 * default, which matches their pre-refactor rendering. Wrapping those
 * overlays lands with MK.16.2 when runtime theme switching ships.
 */
@Composable
fun YancoTheme(
    isTv: Boolean,
    content: @Composable () -> Unit,
) {
    val themeController: ThemeController = koinInject()
    val themeId by themeController.themeId.collectAsState()
    val accentId by themeController.accentId.collectAsState()
    val palette =
        remember(themeId, accentId) { themeController.resolved(themeId, accentId) }

    // MK.16.4 — font scale via LocalDensity override. Multiplies the
    // ambient density's fontScale; physical density (dp sizing) is
    // untouched so layouts don't drift, only sp-sized text rescales.
    val prefs: AppPreferences = koinInject()
    val appearance by prefs.appearanceFlow.collectAsState()
    val baseDensity = LocalDensity.current
    val scaledDensity =
        remember(baseDensity, appearance.fontScalePercent) {
            Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * appearance.fontScale,
            )
        }

    CompositionLocalProvider(
        LocalYancoPalette provides palette,
        LocalDensity provides scaledDensity,
    ) {
        if (isTv) {
            val scheme = remember(palette) { tvColorScheme(palette) }
            androidx.tv.material3.MaterialTheme(
                colorScheme = scheme,
                content = content,
            )
        } else {
            val scheme = remember(palette) { phoneColorScheme(palette) }
            androidx.compose.material3.MaterialTheme(
                colorScheme = scheme,
                content = content,
            )
        }
    }
}

/**
 * Material3 (phone) `colorScheme` derived from a [YancoPalette]. Only
 * the slots we actually rely on are mapped — stock Material widgets
 * pick up our palette automatically; untouched slots fall back to the
 * Material dark defaults and that's fine.
 */
private fun phoneColorScheme(p: YancoPalette) =
    darkColorScheme(
        primary = p.Accent,
        onPrimary = Color.Black,
        secondary = p.AccentDeep,
        background = p.BackgroundDeep,
        surface = p.BackgroundRaised,
        surfaceVariant = p.BackgroundHover,
        onBackground = p.TextPrimary,
        onSurface = p.TextPrimary,
        onSurfaceVariant = p.TextSecondary,
        outline = p.BorderSubtle,
        error = p.Error,
    )

/**
 * androidx.tv.material3 `colorScheme` — same mapping as [phoneColorScheme]
 * but with the TV-specific `border` slot instead of Material3's `outline`.
 */
private fun tvColorScheme(p: YancoPalette) =
    androidx.tv.material3.darkColorScheme(
        primary = p.Accent,
        onPrimary = Color.Black,
        secondary = p.AccentDeep,
        background = p.BackgroundDeep,
        surface = p.BackgroundRaised,
        surfaceVariant = p.BackgroundHover,
        onBackground = p.TextPrimary,
        onSurface = p.TextPrimary,
        onSurfaceVariant = p.TextSecondary,
        border = p.BorderSubtle,
        error = p.Error,
    )
