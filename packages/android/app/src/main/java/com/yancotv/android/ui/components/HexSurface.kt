package com.yancotv.android.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.yancotv.android.ui.theme.YancoPalette

/**
 * Layered hex-inspired surface. Every tile, chip, and button in the shell
 * pipes through this primitive so the visual language stays coherent — a
 * shell frame clipped to the chosen [shape] with an inner content layer
 * inset by [bevelInset] dp, producing a subtle bevelled "frame around the
 * content" read instead of a single flat border.
 *
 * Focus treatment mirrors [focusStyle] but pulled inside here so the three
 * visual signals (scale lift, tinted wash, accent rim) stay registered to
 * the bevelled shell — a separate focus border on the outer shell plus the
 * inner hex shape means the user reads "lit frame" not "lit rectangle".
 *
 * Depth recipe:
 *   - outer shell: subtle top-down gradient, tinted accent shadow on focus
 *   - inner panel: flat fill (or accent wash on focus) to read as the
 *     "recessed" surface behind the frame
 *   - inner rim: 1dp accent stroke on focus, giving the bevel a lit edge
 *
 * Sizing: the caller provides the outer dimensions via [modifier]. The
 * inner panel uses the remaining space after [bevelInset]. Content inside
 * [content] should not set its own background — let the panel surface read
 * through.
 */
@Composable
fun HexSurface(
    shape: Shape,
    focused: Boolean,
    modifier: Modifier = Modifier,
    bevelInset: androidx.compose.ui.unit.Dp = 3.dp,
    shellGradient: Brush = Brush.verticalGradient(
        colors = listOf(
            YancoPalette.BackgroundElevated.copy(alpha = 0.75f),
            YancoPalette.BackgroundRaised.copy(alpha = 0.70f),
        ),
    ),
    focusedShellGradient: Brush = Brush.verticalGradient(
        colors = listOf(
            YancoPalette.AccentGlow.copy(alpha = 0.55f),
            YancoPalette.Accent.copy(alpha = 0.35f),
            YancoPalette.AccentDeep.copy(alpha = 0.30f),
        ),
    ),
    innerFill: Color = YancoPalette.BackgroundDeep.copy(alpha = 0.78f),
    focusedInnerFill: Color = YancoPalette.Accent.copy(alpha = 0.14f),
    liftScale: Float = 1.035f,
    raised: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (focused) liftScale else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 420f),
        label = "hex-scale",
    )
    val elevation by animateDpAsState(
        targetValue = if (focused && raised) 16.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
        label = "hex-elev",
    )
    val shellBorder by animateDpAsState(
        targetValue = if (focused) 1.5.dp else 1.dp,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
        label = "hex-shell-bw",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = YancoPalette.Accent,
                spotColor = YancoPalette.Accent,
            )
            // Outer shell — the "frame"
            .clip(shape)
            .background(if (focused) focusedShellGradient else shellGradient)
            .border(
                width = shellBorder,
                color = if (focused) YancoPalette.FocusRing else YancoPalette.PanelBorder,
                shape = shape,
            )
            .padding(bevelInset),
    ) {
        // Inner content panel — the "recessed" surface. Also clipped to the
        // same shape so the bevelled edge is preserved inside the frame.
        Box(
            modifier = Modifier
                .clip(shape)
                .background(if (focused) focusedInnerFill else innerFill)
                .border(
                    width = 1.dp,
                    color = if (focused) YancoPalette.Accent.copy(alpha = 0.55f) else Color.Transparent,
                    shape = shape,
                ),
            content = content,
        )
    }
}
