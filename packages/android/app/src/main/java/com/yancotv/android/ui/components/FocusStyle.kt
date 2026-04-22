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
    unfocusedBg: Color = YancoPalette.BackgroundRaised,
    focusedBg: Color = YancoPalette.BackgroundHover,
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
    val shape = RoundedCornerShape(radius)
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
