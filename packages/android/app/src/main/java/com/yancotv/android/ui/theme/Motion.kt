package com.yancotv.android.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Audit catch — Honour the system "Remove animations" accessibility
 * preference. The existing focus springs, elevation lifts, chip / tab
 * scale animations all ran unconditionally; users on Settings →
 * Accessibility → "Remove animations" (or Developer Options →
 * Animation scale: Off) still saw every spring. Asymmetric with
 * fontScale, which IS honoured (Theme.kt:54-60).
 *
 * Pattern: provide a `LocalReduceMotion` CompositionLocal at YancoTheme
 * entry; animation sites read it and switch their `spring(...)` to
 * `snap()` when true. Sites that haven't been migrated default to the
 * `false` value, so the rollout can land one composable at a time
 * without changing visible behaviour for the others.
 *
 * Read-once at composition entry: Android exposes the scale via
 * `Settings.Global.TRANSITION_ANIMATION_SCALE` and the related
 * `ANIMATOR_DURATION_SCALE`. A user toggling reduce-motion mid-session
 * doesn't re-render until the screen rebuilds — acceptable trade for
 * not needing a ContentObserver. Both scales == 0f means animations
 * are off platform-wide.
 */
val LocalReduceMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * Snapshot the system reduce-motion flag at composition entry.
 *
 * Returns true when EITHER `TRANSITION_ANIMATION_SCALE` or
 * `ANIMATOR_DURATION_SCALE` is 0f. Reading is wrapped in try/catch so
 * a stripped-down OS (e.g. emulator missing the global) doesn't crash
 * the theme — defaults to false (motion enabled).
 */
@Composable
fun rememberSystemReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val transition =
            runCatching {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.TRANSITION_ANIMATION_SCALE,
                    1f,
                )
            }.getOrDefault(1f)
        val animator =
            runCatching {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                )
            }.getOrDefault(1f)
        transition == 0f || animator == 0f
    }
}
