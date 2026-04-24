package com.yancotv.android.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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
    bevelInset: androidx.compose.ui.unit.Dp = 0.dp,
    shellGradient: Brush = Brush.verticalGradient(
        colors = listOf(
            YancoPalette.BackgroundElevated.copy(alpha = 0.78f),
            YancoPalette.BackgroundRaised.copy(alpha = 0.72f),
        ),
    ),
    focusedShellGradient: Brush = Brush.verticalGradient(
        colors = listOf(
            YancoPalette.Accent,
            YancoPalette.AccentDeep,
        ),
    ),
    innerFill: Color = YancoPalette.BackgroundDeep.copy(alpha = 0.78f),
    focusedInnerFill: Color = YancoPalette.Accent.copy(alpha = 0.14f),
    liftScale: Float = 1.06f,
    liftDp: androidx.compose.ui.unit.Dp = 10.dp,
    raised: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (focused) liftScale else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f),
        label = "hex-scale",
    )
    val translatePx = with(LocalDensity.current) { liftDp.toPx() }
    val translate by animateFloatAsState(
        targetValue = if (focused) -translatePx else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f),
        label = "hex-translate",
    )
    val elevation by animateDpAsState(
        targetValue = if (focused && raised) 28.dp else 6.dp,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
        label = "hex-elev",
    )
    val shellBorder by animateDpAsState(
        targetValue = if (focused) 2.dp else 1.dp,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
        label = "hex-shell-bw",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = translate
            }
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = if (focused) YancoPalette.Accent else Color.Black,
                spotColor = if (focused) YancoPalette.Accent else Color.Black,
            )
            // Outer shell — the "frame". On focus it becomes a saturated
            // emerald gradient ring; idle it's a subtle elevated surface.
            .clip(shape)
            .background(if (focused) focusedShellGradient else shellGradient)
            .border(
                width = shellBorder,
                color = if (focused) YancoPalette.FocusRing else YancoPalette.PanelBorder,
                shape = shape,
            )
            .padding(bevelInset),
    ) {
        // Inner content panel — the "recessed" surface. Clipped to the same
        // shape so the bevelled edge is preserved inside the frame.
        Box(
            modifier = Modifier
                .clip(shape)
                .background(if (focused) focusedInnerFill else innerFill)
                .border(
                    width = 1.dp,
                    color = if (focused) YancoPalette.Accent.copy(alpha = 0.55f) else Color.Transparent,
                    shape = shape,
                ),
        ) {
            content()
            // Specular top facet — single hairline of warm white that traces
            // the top edge to make the hex read as machined metal under light.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = if (focused) 0.55f else 0.18f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }
    }
}
