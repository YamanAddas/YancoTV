package com.yancotv.android.ui.theme

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stable identifiers for the built-in themes. Persisted as the [name]
 * string so renaming an entry is an explicit migration, not silent
 * corruption. Order here is the order the picker renders.
 */
enum class ThemeId(val displayName: String) {
    FrostedEmerald("Frosted Emerald"),
    MidnightSapphire("Midnight Sapphire"),
    WarmAmber("Warm Amber"),
    Monochrome("Monochrome"),
    ;

    companion object {
        fun fromKey(key: String?): ThemeId =
            values().firstOrNull { it.name == key } ?: FrostedEmerald
    }
}

/**
 * Single source of truth for the currently-active theme. [YancoTheme]
 * collects [themeId], resolves the palette via [paletteFor], and pushes
 * it through [LocalYancoPalette] — so the whole UI recomposes to a new
 * palette without restart.
 *
 * Koin-registered as a singleton in `AppModules`. MK.16.2 wires
 * `AppPreferences.setThemeId` so cold launch restores the user's pick.
 */
class ThemeController {
    private val _themeId = MutableStateFlow(ThemeId.FrostedEmerald)
    val themeId: StateFlow<ThemeId> = _themeId.asStateFlow()

    fun setTheme(id: ThemeId) {
        _themeId.value = id
    }

    fun paletteFor(id: ThemeId): YancoPalette =
        when (id) {
            ThemeId.FrostedEmerald -> FrostedEmerald
            ThemeId.MidnightSapphire -> MidnightSapphire
            ThemeId.WarmAmber -> WarmAmber
            ThemeId.Monochrome -> Monochrome
        }
}
