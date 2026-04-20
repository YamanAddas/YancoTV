package com.yancotv.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PhoneDarkScheme = darkColorScheme(
    primary = Color(0xFF9FC9FF),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF10161D),
    onPrimary = Color.Black,
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
)

private val TvDarkScheme = androidx.tv.material3.darkColorScheme(
    primary = Color(0xFF9FC9FF),
    background = Color(0xFF0B0F14),
    surface = Color(0xFF10161D),
    onPrimary = Color.Black,
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3),
)

/**
 * Single theme wrapper. Branches to the TV Material3 theme when on a leanback
 * device so focus-aware surfaces pick up the right palette, otherwise uses the
 * phone Material3 theme. Real typography/shape scales land in MK.4.
 */
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
