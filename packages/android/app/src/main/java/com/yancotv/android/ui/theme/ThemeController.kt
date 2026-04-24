package com.yancotv.android.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stable identifiers for the built-in themes. Persisted as the `name`
 * string so renaming an entry is a migration, not a silent corruption.
 *
 * Only `FrostedEmerald` is wired today (MK.16.1 = refactor only). The
 * remaining three land in MK.16.2 with the Appearance picker.
 */
enum class ThemeId {
    FrostedEmerald,
    // MidnightSapphire,  // MK.16.2
    // WarmAmber,         // MK.16.2
    // Monochrome,        // MK.16.2
}

/**
 * Single source of truth for the currently-active theme. [YancoTheme]
 * collects [themeId], resolves the palette via [paletteFor], and pushes
 * it through [LocalYancoPalette] — so the whole UI recomposes to a new
 * palette without restart.
 *
 * Koin-registered as a singleton in `AppModules`. Pref persistence wires
 * in MK.16.2 (AppPreferences gains a `themeIdFlow`); until then cold
 * launch always starts on [ThemeId.FrostedEmerald], matching the
 * pre-refactor behaviour.
 *
 * [setTheme] is safe to call from any thread — `MutableStateFlow.value=`
 * is atomic.
 */
class ThemeController {
    private val _themeId = MutableStateFlow(ThemeId.FrostedEmerald)
    val themeId: StateFlow<ThemeId> = _themeId.asStateFlow()

    fun setTheme(id: ThemeId) {
        _themeId.value = id
    }

    /**
     * Resolves a [ThemeId] to its [YancoPalette]. Pure function — safe
     * to call from a composable without wrapping; the `when` is
     * exhaustive so adding a new [ThemeId] is a compile-time nudge to
     * add the matching palette.
     */
    fun paletteFor(id: ThemeId): YancoPalette =
        when (id) {
            ThemeId.FrostedEmerald -> FrostedEmerald
        }
}
