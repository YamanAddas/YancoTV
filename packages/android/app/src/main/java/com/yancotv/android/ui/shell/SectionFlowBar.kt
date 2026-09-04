package com.yancotv.android.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yancotv.android.R
import com.yancotv.android.ui.nav.AppSection
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.android.ui.theme.YancoType
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The phone's bottom navigation, as one continuous thing rather than five
 * separate buttons.
 *
 * ### The idea
 *
 * One accent hexagon that **travels**. It does not appear under whichever tab
 * you tapped and vanish from the last — it slides from where it was to where
 * you sent it, and the bar reacts on the way: every icon lifts, brightens and
 * grows as the hexagon nears, and settles again as it passes. The bar behaves
 * like a continuum with a moving point of attention rather than a row of
 * independent lamps.
 *
 * And because it is a continuum, a **finger can drag along it**. The hexagon
 * follows, the wave follows the hexagon, and the destination commits on
 * release — passing over Movies on the way to Series must not load Movies.
 * Tapping is the same animation with the finger skipping the middle.
 *
 * ### Restraint
 *
 * One accent colour, the hexagon the rest of the shell is built from, and a
 * spring in the same register as everything else. Machined, not bouncy: this is
 * a navigation bar someone uses forty times an hour, and anything springier
 * wears out fast. `Spring.DampingRatioNoBouncy` is deliberate — an overshoot
 * here reads as sloppiness, not delight.
 *
 * ### Why this is phone-only
 *
 * It is a touch surface: the drag is the whole point and a D-pad cannot perform
 * it. TV and tablet keep `AppSidebar`, which the shell chooses via
 * `ShellMetrics.usesSidebar`. The two never coexist.
 */
@Composable
fun SectionFlowBar(current: AppSection, onSelect: (AppSection) -> Unit, onOpenOverflow: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalYancoPalette.current
    val items = AppSection.compactPrimary
    // The overflow occupies a slot in the flow like any other destination. It
    // opens a sheet rather than a screen, but it must not feel bolted on the end.
    val slots = items.size + 1

    // MK.37.H.2 — an overflow destination parks the indicator on the More
    // slot, not wherever it happened to be.
    //
    // `indexOf` returns -1 for Guide / Recordings / Search / Settings because
    // none of them is in the bar, and the old `coerceAtLeast(0)` turned that
    // into Home. The `LaunchedEffect` below then declined to move because
    // `items.contains(current)` was false, so the marker simply stayed where it
    // last was — sitting under whichever tab you pressed before More, while the
    // screen showed Settings. The bar was pointing at the wrong place.
    val overflowSlot = items.size
    val selectedIndex =
        if (current in AppSection.compactOverflow) {
            overflowSlot.toFloat()
        } else {
            items.indexOf(current).coerceAtLeast(0).toFloat()
        }
    // Continuous position of the indicator, in slot indices. Fractional while a
    // finger is dragging, which is what lets the whole row respond rather than
    // two cells swapping states.
    val focus = remember { Animatable(selectedIndex) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Keeps the indicator honest when the section changes from somewhere else —
    // the overflow sheet, a deep link, the parental gate bouncing off Settings.
    LaunchedEffect(current) {
        // No `items.contains` guard: an overflow destination has a slot too —
        // the More one — and refusing to move for it is what left the marker
        // stranded on the previous tab.
        if (!dragging) focus.animateTo(selectedIndex, travel())
    }

    Box(
        modifier =
        modifier
            .fillMaxWidth()
            // The bar owns the bottom inset. The content above it is inset for
            // the top and sides only, so the bed can run under the gesture bar
            // rather than floating above a stripe of background.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSidesBottomHorizontal))
            .background(
                Brush.verticalGradient(
                    listOf(
                        palette.BackgroundRaised.copy(alpha = 0.92f),
                        palette.BackgroundDeep.copy(alpha = 0.96f),
                    ),
                ),
            ),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().height(BarHeight).padding(horizontal = Space.md),
        ) {
            val slotWidth = maxWidth / slots
            val slotPx = with(LocalDensity.current) { slotWidth.toPx() }
            // MB-416 - the Row below and the indicator's offset are both
            // layout-direction aware, so on an Arabic phone the bar draws
            // right-to-left. Pointer input is not: position.x is a distance
            // from the physical left edge either way. Two of the three agreed
            // and the third did not, so every tab activated its mirror.
            val barRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

            fun commit(index: Int) {
                if (index >= items.size) {
                    onOpenOverflow()
                    // Travel to the More slot and STAY there while the sheet is
                    // open. It used to bounce straight back, which read as the
                    // press being rejected — and it was the only visible
                    // response at all while the sheet was going unrendered
                    // (MK.37.H.1). If the viewer dismisses without choosing,
                    // the `LaunchedEffect` above returns it to the real section.
                    scope.launch { focus.animateTo(index.toFloat(), travel()) }
                } else {
                    scope.launch { focus.animateTo(index.toFloat(), travel()) }
                    onSelect(items[index])
                }
            }

            Box(
                modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(slotPx, items, current, barRtl) {
                        detectTapGestures { offset ->
                            commit(flowBarSlotAt(offset.x, slotPx, slots, barRtl))
                        }
                    }
                    .pointerInput(slotPx, items, current, barRtl) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = {
                                dragging = false
                                commit(focus.value.roundToInt().coerceIn(0, slots - 1))
                            },
                            onDragCancel = {
                                dragging = false
                                scope.launch { focus.animateTo(selectedIndex, travel()) }
                            },
                        ) { change, _ ->
                            val target =
                                flowBarDragTarget(change.position.x, slotPx, slots, barRtl)
                                    .coerceIn(0f, (slots - 1).toFloat())
                            scope.launch { focus.snapTo(target) }
                        }
                    },
            ) {
                // ── the travelling hexagon ──
                Box(
                    modifier =
                    Modifier
                        .offset(x = slotWidth * focus.value + Space.xs / 2)
                        .width(slotWidth - Space.xs)
                        .height(IndicatorHeight)
                        .align(Alignment.CenterStart)
                        .clip(YancoShapes.HexCapsule)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    palette.Accent.copy(alpha = 0.30f),
                                    palette.AccentDeep.copy(alpha = 0.16f),
                                ),
                            ),
                        ),
                )

                Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    items.forEachIndexed { index, item ->
                        FlowCell(
                            icon = iconFor(item),
                            label = stringResource(item.labelRes),
                            nearness = nearness(index.toFloat(), focus.value),
                            selected = item == current,
                            modifier = Modifier.width(slotWidth),
                        )
                    }
                    FlowCell(
                        icon = com.yancotv.android.ui.theme.YancoIcons.More,
                        label = stringResource(R.string.section_more),
                        nearness = nearness(items.size.toFloat(), focus.value),
                        selected = current in AppSection.compactOverflow,
                        modifier = Modifier.width(slotWidth),
                    )
                }
            }
        }
    }
}

/** 1 directly under the indicator, 0 a slot away and beyond. */
private fun nearness(index: Float, focus: Float): Float = (1f - abs(index - focus)).coerceIn(0f, 1f)

private fun travel() = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/**
 * One destination, styled by how near the indicator is.
 *
 * `nearness` is continuous, so during a drag the whole row responds rather than
 * two cells swapping states at a threshold.
 */
@Composable
private fun FlowCell(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, nearness: Float, selected: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalYancoPalette.current
    Column(
        modifier =
        modifier
            .offset(y = (-2 * nearness).dp)
            // One spoken label per destination. The cells are drawn, not
            // Material components, so without this the bar is silent to
            // TalkBack — the same rule the orb needed in MB-280.
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = lerp(palette.TextMuted, palette.Accent, nearness),
            modifier = Modifier.scale(1f + nearness * 0.16f),
        )
        Text(
            text = label,
            style = if (selected) YancoType.CaptionStrong else YancoType.Caption,
            color = lerp(palette.TextMuted, palette.Accent, nearness),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val BarHeight = 58.dp
private val IndicatorHeight = 46.dp

/** The bottom + horizontal sides of the safe area, which is all this bar insets for. */
private val WindowInsetsSidesBottomHorizontal =
    androidx.compose.foundation.layout.WindowInsetsSides.Bottom +
        androidx.compose.foundation.layout.WindowInsetsSides.Horizontal

/**
 * The four destinations the bar cannot carry.
 *
 * A plain sheet rather than the hexagon language: this is a list you read once
 * and dismiss, not a surface you live in, and giving it the same travelling
 * indicator would imply it is part of the same continuum when it is not.
 */
@Composable
fun SectionOverflowSheet(current: AppSection, onSelect: (AppSection) -> Unit) {
    val palette = LocalYancoPalette.current
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .background(palette.BackgroundRaised)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSidesBottomHorizontal))
            .padding(horizontal = Space.xl, vertical = Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = stringResource(R.string.section_more).uppercase(),
            style = YancoType.Overline,
            color = palette.Accent,
            modifier = Modifier.padding(bottom = Space.sm),
        )
        AppSection.compactOverflow.forEach { item ->
            val isCurrent = item == current
            val label = stringResource(item.labelRes)
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isCurrent) palette.Accent.copy(alpha = 0.14f) else palette.BackgroundRaised)
                    .pointerInput(item) { detectTapGestures { onSelect(item) } }
                    .padding(horizontal = Space.lg)
                    .semantics { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Icon(
                    imageVector = iconFor(item),
                    contentDescription = null,
                    tint = if (isCurrent) palette.Accent else palette.TextSecondary,
                )
                Text(
                    text = label,
                    style = YancoType.Label,
                    color = if (isCurrent) palette.Accent else palette.TextSecondary,
                )
            }
        }
    }
}
