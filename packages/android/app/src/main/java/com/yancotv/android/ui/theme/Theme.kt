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
/**
 * MB-298 — TV type multiplier. The user's report was "the font size to be
 * better optimized"; on a TV that means bigger.
 *
 * Measured on the canonical Fire TV Stick (AFTMM): 1920x1080 at densityDpi
 * 320 gives a 960x540 dp viewport, so 1sp is 2 physical pixels. At a ~3 m
 * viewing distance an 11sp caption subtends roughly 10 arcmin, well under
 * the ~16 arcmin floor for comfortable sustained reading — which is why the
 * small text in Settings, the Guide and the poster rails reads as cramped
 * from the couch.
 *
 * Why here rather than in [YancoType]: only ~96 text call sites use the type
 * scale, while ~209 raw `fontSize = N.sp` literals across ~35 files bypass it
 * entirely (Settings, Guide, Recordings, Sources and the player menus are
 * essentially all hardcoded). Migrating those is days of mechanical churn
 * with a real chance of typos; multiplying `fontScale` at the density seam
 * lifts every one of them at once. It scales `sp` only — every `dp` layout
 * dimension is byte-identical, so nothing moves or re-flows.
 *
 * Phone is deliberately untouched (multiplier 1f): it is viewed at ~30 cm and
 * is already correctly sized.
 */
private const val TV_TYPE_SCALE = 1.30f

@Composable
fun YancoTheme(isTv: Boolean, content: @Composable () -> Unit) {
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
        remember(baseDensity, appearance.fontScalePercent, isTv) {
            Density(
                density = baseDensity.density,
                // MB-298 — TV type multiplier, applied at the same seam as
                // the user's font-scale preference so the two compose
                // instead of fighting.
                fontScale = baseDensity.fontScale * appearance.fontScale *
                    if (isTv) TV_TYPE_SCALE else 1f,
            )
        }

    // Audit catch — snapshot reduce-motion at theme entry; sites that
    // honour the preference (FocusStyle for now) read via
    // LocalReduceMotion.current.
    val reduceMotion = rememberSystemReduceMotion()
    CompositionLocalProvider(
        LocalYancoPalette provides palette,
        LocalDensity provides scaledDensity,
        LocalReduceMotion provides reduceMotion,
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
private fun phoneColorScheme(p: YancoPalette) = darkColorScheme(
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
private fun tvColorScheme(p: YancoPalette) = androidx.tv.material3.darkColorScheme(
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
