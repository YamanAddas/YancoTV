package com.yancotv.android.ui.theme

import androidx.annotation.StringRes
import com.yancotv.android.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stable identifiers for the built-in themes. Persisted as the [name]
 * string so renaming an entry is an explicit migration, not silent
 * corruption. Order here is the order the picker renders.
 */
enum class ThemeId(@StringRes val labelRes: Int) {
    FrostedEmerald(R.string.th_frosted_emerald),
    MidnightSapphire(R.string.th_midnight_sapphire),
    WarmAmber(R.string.th_warm_amber),
    Monochrome(R.string.th_monochrome),
    ;

    companion object {
        fun fromKey(key: String?): ThemeId = values().firstOrNull { it.name == key } ?: FrostedEmerald
    }
}

/**
 * MK.16.3 — accent overlay picker. `MATCH_THEME` keeps each base palette's
 * own accent (the previous behaviour); the rest swap the accent block
 * onto whatever base theme is active.
 */
enum class AccentId(@StringRes val labelRes: Int) {
    MATCH_THEME(R.string.ac_match_theme),
    EMERALD(R.string.ac_emerald),
    SAPPHIRE(R.string.ac_sapphire),
    AMBER(R.string.ac_amber),
    RED(R.string.ac_red),
    ;

    companion object {
        fun fromKey(key: String?): AccentId = values().firstOrNull { it.name == key } ?: MATCH_THEME
    }
}

/**
 * Single source of truth for the active theme + accent overlay. The
 * `palette` flow recomputes whenever either flips so the UI recomposes
 * in a single pass.
 */
class ThemeController {
    private val _themeId = MutableStateFlow(ThemeId.FrostedEmerald)
    val themeId: StateFlow<ThemeId> = _themeId.asStateFlow()

    private val _accentId = MutableStateFlow(AccentId.MATCH_THEME)
    val accentId: StateFlow<AccentId> = _accentId.asStateFlow()

    fun setTheme(id: ThemeId) {
        _themeId.value = id
    }

    fun setAccent(id: AccentId) {
        _accentId.value = id
    }

    fun paletteFor(id: ThemeId): YancoPalette = when (id) {
        ThemeId.FrostedEmerald -> FrostedEmerald
        ThemeId.MidnightSapphire -> MidnightSapphire
        ThemeId.WarmAmber -> WarmAmber
        ThemeId.Monochrome -> Monochrome
    }

    /** Resolves the base palette + accent overlay into the final palette
     *  every UI surface reads. */
    fun resolved(themeId: ThemeId, accentId: AccentId): YancoPalette {
        val base = paletteFor(themeId)
        val bundle = bundleFor(accentId) ?: return base
        return base.applyAccent(bundle)
    }

    private fun bundleFor(id: AccentId): AccentBundle? = when (id) {
        AccentId.MATCH_THEME -> null
        AccentId.EMERALD -> EmeraldAccent
        AccentId.SAPPHIRE -> SapphireAccent
        AccentId.AMBER -> AmberAccent
        AccentId.RED -> RedAccent
    }
}
