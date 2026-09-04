package com.yancotv.android.ui.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yancotv.android.R
import com.yancotv.android.ui.theme.LocalShellMetrics
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.YancoType
import kotlin.math.abs

/** One category row: the provider's name and how many titles are in it. */
data class CategoryEntry(val name: String, val count: Int)

/**
 * The category surface for a phone held upright: a single line you can pull
 * down into a grid.
 *
 * ### What it replaces
 *
 * `CategoryRail` is a 240 dp standing column — 60% of a phone's portrait width,
 * so portrait cannot have it. A horizontal strip alone is not the answer
 * either: a real account here ships **855 live categories**, and a strip shows
 * four at a time with no sense of how far the rest run.
 *
 * Pulled open it becomes a grid, where a flick covers rows rather than columns,
 * and where you scrolled to stays put while the sheet is open.
 *
 * ### Three detents, not two
 *
 * "Just the strip", "enough to browse without losing the content behind", and
 * "the whole list". A free height would let it rest halfway through a row.
 *
 * ### Both layers stay mounted
 *
 * The strip and the grid are always in the tree and cross-fade on how far open
 * the drawer is. Choosing between them with an `if` cost one of two things on
 * the iOS original: deciding on the *live* height rebuilt the subtree
 * mid-gesture and the drag snagged, and deciding on the *settled* height left
 * the categories missing for the whole length of the pull. Moving only opacity
 * has neither problem, because nothing is created or destroyed while the finger
 * is down — and the grid is lazy, so collapsed it only builds the row that fits.
 */
@Composable
fun CategoryDrawer(
    entries: List<CategoryEntry>,
    totalCount: Int,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalYancoPalette.current
    val metrics = LocalShellMetrics.current
    val density = LocalDensity.current

    val detents = remember(metrics.windowHeight) { Detents(metrics.windowHeight) }
    var restHeight by remember(detents) { mutableStateOf(detents.collapsed) }
    // Live drag offset in px, applied on top of `restHeight`. Kept separate so
    // the settle can animate while the drag itself does not — a spring on the
    // finger-tracking half adds lag to every pull.
    var dragPx by remember { mutableFloatStateOf(0f) }

    val settled by animateDpAsState(restHeight, spring(dampingRatio = 0.88f), label = "drawerHeight")
    val height =
        remember(settled, dragPx) {
            val raw = settled + with(density) { dragPx.toDp() }
            // Past either end the drawer still follows at a quarter speed, so
            // the limit reads as a limit rather than as the gesture having
            // stopped working.
            when {
                raw < detents.collapsed -> detents.collapsed - (detents.collapsed - raw) * 0.25f
                raw > detents.full -> detents.full + (raw - detents.full) * 0.25f
                else -> raw
            }
        }

    val openRatio =
        ((height - detents.collapsed) / (detents.half - detents.collapsed))
            .coerceIn(0f, 1f)
    val isOpen = openRatio > 0.5f

    fun settleTo(target: Dp) {
        restHeight = target
        dragPx = 0f
    }

    fun toggle() = settleTo(if (isOpen) detents.collapsed else detents.half)

    /**
     * Settle on the nearest detent to where the finger let go.
     *
     * @param guardSideways refuse a drag that is more horizontal than vertical.
     *   Only where something scrolls sideways underneath — the collapsed strip.
     *   Applying it to the grabber too was the other half of the iOS snag: a
     *   real thumb wanders, and every frame where the sideways component
     *   happened to win was dropped, so the drawer froze and then jumped.
     */
    fun endDrag() {
        val projected = settled + with(density) { dragPx.toDp() }
        settleTo(detents.all.minBy { abs((it - projected).value) })
    }

    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds(),
    ) {
        // ── the header: name, current selection, chevron ──
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { toggle() } }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { endDrag() },
                        onDragCancel = { dragPx = 0f },
                    ) { _, delta -> dragPx += delta }
                }
                .padding(horizontal = metrics.pageInset, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.cd_categories).uppercase(),
                style = YancoType.Overline,
                color = palette.TextMuted,
            )
            Spacer(Modifier.width(Space.sm))
            Spacer(Modifier.weight(1f))
            if (selected != ALL_GROUPS && selected != FAVORITES_GROUP) {
                Text(
                    text = selected,
                    style = YancoType.Caption,
                    color = palette.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
                Spacer(Modifier.width(Space.sm))
            }
            Icon(
                imageVector = YancoIcons.ChevronDown,
                contentDescription = null,
                tint = palette.TextMuted,
                modifier = Modifier.size(16.dp).rotate(if (isOpen) 180f else 0f),
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // ── open: a grid ──
            if (openRatio > 0f) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.fillMaxSize().alpha(openRatio),
                    contentPadding =
                    androidx.compose.foundation.layout.PaddingValues(
                        start = metrics.pageInset,
                        end = metrics.pageInset,
                        bottom = Space.lg,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    item { PinnedTile(R.string.cd_favorites, YancoIcons.Favorites, null, selected == FAVORITES_GROUP) { onSelect(FAVORITES_GROUP) } }
                    item { PinnedTile(R.string.cd_all, YancoIcons.Grid, totalCount, selected == ALL_GROUPS) { onSelect(ALL_GROUPS) } }
                    items(entries, key = { it.name }) { entry ->
                        CategoryTile(entry.name, entry.count, selected == entry.name) { onSelect(entry.name) }
                    }
                }
            }

            // ── collapsed: one line ──
            //
            // The whole strip drags, not only the header — reaching for a
            // 46x18 grabber is not how anyone opens a drawer. Safe because the
            // strip scrolls *horizontally*, so a vertical drag has nothing to
            // collide with, and the gesture refuses anything more sideways
            // than down.
            if (openRatio < 1f) {
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(StripHeight)
                        .alpha(1f - openRatio)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = { endDrag() },
                                onDragCancel = { dragPx = 0f },
                            ) { _, delta -> dragPx += delta }
                        }
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = metrics.pageInset),
                    horizontalArrangement = Arrangement.spacedBy(Space.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PinnedTile(R.string.cd_favorites, YancoIcons.Favorites, null, selected == FAVORITES_GROUP, strip = true) { onSelect(FAVORITES_GROUP) }
                    PinnedTile(R.string.cd_all, YancoIcons.Grid, totalCount, selected == ALL_GROUPS, strip = true) { onSelect(ALL_GROUPS) }
                    entries.forEach { entry ->
                        CategoryTile(entry.name, entry.count, selected == entry.name, strip = true) { onSelect(entry.name) }
                    }
                }
            }
        }
    }

    // ── the grabber, on the bottom border ──
    //
    // The one control that works in both directions from either state,
    // including closing — with the gesture on the header alone there is
    // nothing to pull *up* once the grid has the rest of the screen.
    Box(
        modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.BorderSubtle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
            Modifier
                // A generous invisible target around the drawn one: 46x18 on a
                // border is not something a thumb finds reliably.
                .padding(horizontal = Space.xl, vertical = Space.sm)
                .pointerInput(Unit) { detectTapGestures { toggle() } }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { endDrag() },
                        onDragCancel = { dragPx = 0f },
                    ) { _, delta -> dragPx += delta }
                }
                .semantics {
                    contentDescription = if (isOpen) "Collapse categories" else "Expand categories"
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                Modifier
                    .width(46.dp)
                    .height(18.dp)
                    .background(palette.BackgroundRaised, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(2) {
                        Box(Modifier.width(22.dp).height(1.5.dp).background(palette.TextMuted, RoundedCornerShape(1.dp)))
                    }
                }
            }
        }
    }
}

/**
 * A category, as typography rather than as a container.
 *
 * The name **is** the object: full contrast, given the whole width. The count
 * sits small and muted underneath — findable when wanted, never read past.
 * There is no fill and no border; selection is an accent rule down the leading
 * edge and the name in accent colour, which is the least ink that can carry a
 * state. The bevelled accent-washed chip this replaces was rejected outright.
 */
@Composable
private fun CategoryTile(
    name: String,
    count: Int?,
    isSelected: Boolean,
    strip: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalYancoPalette.current
    Row(
        modifier =
        Modifier
            .then(if (strip) Modifier.widthIn(min = 120.dp) else Modifier.fillMaxWidth())
            .pointerInput(name) { detectTapGestures { onClick() } }
            .semantics { contentDescription = name },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The selection rule is measured by the text beside it, not by the row
        // it sits in: as a plain sibling it had a width and no height, so while
        // the drawer was being dragged it ran the drawer's full height.
        Box(
            modifier =
            Modifier
                .width(2.dp)
                .height(if (count != null) 40.dp else 24.dp)
                .background(if (isSelected) palette.Accent else Color.Transparent),
        )
        Spacer(Modifier.width(Space.sm))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = name,
                style = if (isSelected) YancoType.LabelStrong else YancoType.Label,
                color = if (isSelected) palette.Accent else palette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (count != null) {
                Text(
                    text = formatCount(count),
                    style = YancoType.Caption,
                    color = palette.TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PinnedTile(
    labelRes: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int?,
    isSelected: Boolean,
    strip: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalYancoPalette.current
    val label = stringResource(labelRes)
    Row(
        modifier =
        Modifier
            .then(if (strip) Modifier.widthIn(min = 120.dp) else Modifier.fillMaxWidth())
            .pointerInput(labelRes) { detectTapGestures { onClick() } }
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
            Modifier
                .width(2.dp)
                .height(if (count != null) 40.dp else 24.dp)
                .background(if (isSelected) palette.Accent else Color.Transparent),
        )
        Spacer(Modifier.width(Space.sm))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) palette.Accent else palette.TextPrimary,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = label,
                    style = if (isSelected) YancoType.LabelStrong else YancoType.Label,
                    color = if (isSelected) palette.Accent else palette.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (count != null) {
                Text(
                    text = formatCount(count),
                    style = YancoType.Caption,
                    color = palette.TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Grouped thousands, in the reader's own locale.
 *
 * `String.format` with an explicit locale rather than `toString()`: 53,141 is
 * far easier to size up at a glance than 53141, and an Arabic reader should get
 * Arabic-Indic digits like the rest of the UI. Same rule the D.1a lint pass
 * applied to time codes.
 */
private fun formatCount(count: Int): String = String.format(java.util.Locale.getDefault(), "%,d", count)

/**
 * Where the drawer is allowed to settle, for a given window.
 *
 * **Proportional, unlike the port this comes from.** iOS hard-codes 114 / 300 /
 * 560, which works because iPhones are all roughly 844 pt tall. Android is not:
 * on a 568 dp phone a fixed 560 dp drawer is **99% of the screen** and buries
 * the content it is supposed to be filtering. Half and Full are fractions of the
 * window with clamps at both ends, so a small phone keeps a usable strip of grid
 * underneath and a tablet does not get a drawer stuck at a third of its height.
 *
 * Collapsed stays absolute: it is sized by what it must *show* — the header
 * (~32), one tile of name-over-count (~48) and the grabber on the border (~18) —
 * not by what the window can spare.
 */
private class Detents(windowHeight: Dp) {
    val collapsed: Dp = 114.dp
    val half: Dp = (windowHeight * 0.40f).coerceIn(260.dp, 380.dp)
    val full: Dp = (windowHeight * 0.72f).coerceIn(380.dp, 640.dp)

    val all: List<Dp> get() = listOf(collapsed, half, full)
}

private val StripHeight = 56.dp
