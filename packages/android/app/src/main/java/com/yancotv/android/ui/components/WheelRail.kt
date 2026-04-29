package com.yancotv.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 3D "wheel" transform for a card inside a horizontally-scrolling rail.
 *
 * The math runs inside [graphicsLayer] so the transform reads [listState]'s
 * layoutInfo on every frame without forcing recomposition — the card is
 * composed once, then the GPU applies a new matrix each scroll tick.
 *
 * Transform shape: map each card's viewport-center distance to a normalized
 * [-1..1] where 0 = dead centre. Apply a Y-axis rotation (tilts side cards
 * toward the camera), scale-down (far cards read as further away), and an
 * alpha fade (reinforces depth).
 *
 * Defaults are intentionally restrained (38° / 0.82 / 0.6) because these
 * transforms continue to apply to the focused card during the centre-snap
 * animation — too-aggressive values make the focus indicator appear to
 * warp into place. Callers can turn the dial up on rails that never need
 * to show a focus rim mid-animation.
 *
 * Pivot: lerps from mid-card at centre to the inner edge at the rim so the
 * wheel reads as curving around the viewer *without* the pivot flipping
 * sign at zero-crossing (which would pop the card a few px as it passes
 * centre during a scroll).
 */
fun Modifier.wheelItemTransform(
    listState: LazyListState,
    index: Int,
    maxRotationDegrees: Float = 38f,
    minScale: Float = 0.82f,
    minAlpha: Float = 0.6f,
): Modifier = this.graphicsLayer {
    val layoutInfo = listState.layoutInfo
    val viewportWidth = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
    if (viewportWidth <= 0f) return@graphicsLayer
    val viewportCenter = layoutInfo.viewportStartOffset + viewportWidth / 2f
    val itemInfo =
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            ?: return@graphicsLayer
    val itemCenter = itemInfo.offset + itemInfo.size / 2f
    val halfViewport = viewportWidth / 2f
    val normalized = ((itemCenter - viewportCenter) / halfViewport).coerceIn(-1f, 1f)
    val absN = abs(normalized)

    cameraDistance = 14f * density
    rotationY = normalized * maxRotationDegrees
    val s = 1f - (1f - minScale) * absN
    scaleX = s
    scaleY = s
    alpha = 1f - (1f - minAlpha) * absN
    // Pivot lerps from 0.5 (centre) at normalized=0 to the inner edge
    // (0 or 1) at |normalized|=1. No sign-flip pop at zero-crossing.
    val innerEdge = if (normalized < 0f) 1f else 0f
    transformOrigin =
        TransformOrigin(
            pivotFractionX = 0.5f + (innerEdge - 0.5f) * absN,
            pivotFractionY = 0.5f,
        )
}

/**
 * [BringIntoViewSpec] that asks the nearest scroll container to centre the
 * requesting item in the viewport instead of the default "just barely
 * visible" behaviour. When a focusable card gains focus, Compose calls
 * bringIntoView on its scrollable ancestor; providing this spec above a
 * [LazyRow] makes that single focus-triggered scroll do the centre-snap.
 *
 * The only scroll driver must be focus-triggered bringIntoView, or a
 * second scroll from [LazyListState.animateScrollToItem] will race with
 * this one.
 */
@OptIn(ExperimentalFoundationApi::class)
private val CenterBringIntoViewSpec =
    object : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            // offset is the item's start position within the container (post
            // contentPadding). To centre, we want the item's centre to land at
            // containerSize / 2. Return the delta the container should scroll.
            val target = (containerSize - size) / 2f
            return offset - target
        }
    }

/**
 * A [LazyRow] that renders like a horizontal wheel: the focused card sits
 * dead-centre and neighbours curve away via [wheelItemTransform]. Pads the
 * viewport so the first/last item can reach the centre slot.
 *
 * Single scroll driver: the wrapped [LazyRow] sees a [CenterBringIntoViewSpec]
 * via [LocalBringIntoViewSpec], so Compose's focus system centres items on
 * its own. Callers must NOT additionally call [LazyListState.animateScrollToItem]
 * from a `LaunchedEffect(focusedIndex)` — that would race with bringIntoView
 * and produce stacked animations.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelRow(
    itemWidth: Dp,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(24.dp),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    verticalPadding: Dp = 0.dp,
    minSidePadding: Dp = 24.dp,
    content: LazyListScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val sidePad = ((maxWidth - itemWidth) / 2).coerceAtLeast(minSidePadding)
        CompositionLocalProvider(LocalBringIntoViewSpec provides CenterBringIntoViewSpec) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding =
                PaddingValues(
                    start = sidePad,
                    end = sidePad,
                    top = verticalPadding,
                    bottom = verticalPadding,
                ),
                horizontalArrangement = horizontalArrangement,
                verticalAlignment = verticalAlignment,
                content = content,
            )
        }
    }
}
