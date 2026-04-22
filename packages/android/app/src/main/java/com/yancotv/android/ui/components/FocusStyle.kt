package com.yancotv.android.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yancotv.android.ui.theme.YancoPalette

/**
 * Focus visual style — the one place in the app that decides how "this row
 * is selected on TV" looks. Every focusable surface pulls from here so the
 * shell feels coherent.
 *
 * The treatment stacks three signals:
 *   1. A small spring-animated scale lift (1 → 1.035) so the user's eye
 *      tracks focus across the 10-ft viewing distance even without a
 *      colour change.
 *   2. A 2dp accent border ring (FocusRing, a bright mint) replacing the
 *      subtle hairline. Contrast is what carries at distance, not saturation.
 *   3. An ambient shadow tinted with the accent so the card reads "lit"
 *      rather than merely outlined. Shadow is painted before scale so the
 *      clipping doesn't chop it.
 *
 * Unfocused surfaces keep their subtle hairline border at unchanged scale —
 * no layout thrash when focus lands.
 */
@Composable
fun Modifier.focusStyle(
    focused: Boolean,
    radius: Dp,
    liftScale: Float = 1.035f,
    raised: Boolean = true,
    unfocusedBorder: Color = YancoPalette.BorderSubtle,
    focusedBorder: Color = YancoPalette.FocusRing,
    // Translucent unfocused surface so the cinematic hero / preview shows
    // through the rail and chip strip — TiviMate-style shell where the
    // container frames the video, it doesn't cover it.
    unfocusedBg: Color = YancoPalette.BackgroundRaised.copy(alpha = 0.55f),
    // Focus state reads as a tinted accent wash rather than a neutral
    // slate fill so the selector is clearly "colored" at 10 ft. The alpha
    // lets the backdrop bleed through so the focused card still feels
    // part of the scene.
    focusedBg: Color = YancoPalette.Accent.copy(alpha = 0.22f),
): Modifier = this.focusStyle(
    focused = focused,
    shape = RoundedCornerShape(radius),
    liftScale = liftScale,
    raised = raised,
    unfocusedBorder = unfocusedBorder,
    focusedBorder = focusedBorder,
    unfocusedBg = unfocusedBg,
    focusedBg = focusedBg,
)

/**
 * Shape-aware overload. Hex-frame tiles, cut-corner cards, and bevelled
 * chips use this so the focus ring + tinted wash follow the angular
 * silhouette instead of a silent rounded rectangle underneath.
 */
@Composable
fun Modifier.focusStyle(
    focused: Boolean,
    shape: Shape,
    liftScale: Float = 1.035f,
    raised: Boolean = true,
    unfocusedBorder: Color = YancoPalette.BorderSubtle,
    focusedBorder: Color = YancoPalette.FocusRing,
    unfocusedBg: Color = YancoPalette.BackgroundRaised.copy(alpha = 0.55f),
    focusedBg: Color = YancoPalette.Accent.copy(alpha = 0.22f),
): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (focused) liftScale else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 420f),
        label = "focus-scale",
    )
    val elevation by animateDpAsState(
        targetValue = if (focused && raised) 14.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
        label = "focus-elev",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused) 2.dp else 1.dp,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 600f),
        label = "focus-bw",
    )
    return this
        .scale(scale)
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = YancoPalette.Accent,
            spotColor = YancoPalette.Accent,
        )
        .clip(shape)
        .background(if (focused) focusedBg else unfocusedBg)
        .border(borderWidth, if (focused) focusedBorder else unfocusedBorder, shape)
}
