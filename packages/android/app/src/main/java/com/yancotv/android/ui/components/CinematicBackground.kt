package com.yancotv.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import com.yancotv.android.ui.theme.YancoPalette

/**
 * Ambient app-wide background. A radial wash plus a subtle vertical
 * gradient so the shell doesn't feel like a flat fill. The accent tint is
 * kept very low (≤6%) — it has to read as atmosphere, not brand noise.
 */
@Composable
fun CinematicBackground(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(YancoPalette.BackgroundDeep)
                .background(
                    Brush.radialGradient(
                        colors =
                            listOf(
                                YancoPalette.Accent.copy(alpha = 0.05f),
                                YancoPalette.BackgroundDeep.copy(alpha = 0f),
                            ),
                        center = Offset(260f, 220f),
                        radius = 1400f,
                    ),
                ).background(
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                YancoPalette.BackgroundDeep.copy(alpha = 0f),
                                YancoPalette.BackgroundDeep.copy(alpha = 0.35f),
                            ),
                    ),
                ),
    )
}
