package com.yancotv.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout sizes derived from the space actually available.
 *
 * ### Why this exists
 *
 * [ShellDim] carries one number per structural dimension and every one of them
 * was calibrated against a **960x540 dp Fire TV viewport**. `isTv` gates
 * behaviour but nothing gates *structure*, so a phone renders the television's
 * layout at the television's sizes: a 92 dp rail is 24% of a phone's width, and
 * `heroHeight = 330` is 61% of a TV screen but swallows the fold on a tall one.
 *
 * The values here are computed from the **content lane** — the width left after
 * the navigation rail, not the width of the screen — so the same code lays out a
 * 320 dp phone, a 1,100 dp tablet column, and everything between.
 *
 * ### The rule that must not be duplicated
 *
 * The iOS port spells the sidebar/panel/grid decision out three times, in
 * `RootShell`, `CoverflowScreen` and `GuideScreen`, and a comment in each asking
 * the reader to keep them in step. That is a drift surface. Here the decisions
 * are properties of this object — [usesSidebar], [usesCoverflow] — so two
 * screens cannot disagree about the shape of the window they are in.
 *
 * ### Nothing reads this yet
 *
 * MK.37.A introduces the layer and the rotation unlock and wires **neither** to
 * an existing screen, so the television renders byte-for-byte what it rendered
 * before. Screens adopt it one at a time in MK.37.B onward, each with its own
 * TV pass — the alternative is one commit that changes every surface at once on
 * the one form factor that is already shipping.
 */
@Immutable
data class ShellMetrics(
    /** Full window width, before any rail is subtracted. */
    val windowWidth: Dp,
    /** Full window height. */
    val windowHeight: Dp,
    /** Width available to content, excluding the navigation rail. */
    val lane: Dp,
) {
    /**
     * True when vertical space is the scarce axis — a phone held sideways.
     *
     * 480 dp is the boundary Android itself uses for "short" screens, and a
     * phone in landscape lands around 360-420 dp of height.
     */
    val shortViewport: Boolean get() = isShortViewport(windowHeight)

    /** True when the window is too narrow for a standing rail plus content. */
    val narrowViewport: Boolean get() = isNarrowViewport(windowWidth)

    /**
     * Side rail, or a bottom bar.
     *
     * **Which dimension is scarce decides the navigation, not the width alone.**
     *
     * A mainstream phone in landscape is ~869 dp across, so the width test alone
     * already gives it the rail. The second clause is for the *small* phone: at
     * 568x320 it is narrow **and** short, and a width-only rule would spend
     * ~140 dp of a 320 dp-tall window on a brand bar plus a tab bar — 44% of the
     * screen on chrome, with the hero clipped behind it. Landscape has width to
     * spare and no height, which is the shape the rail was drawn for.
     *
     * (The iOS port needs this clause far more than Android does: UIKit reports
     * an iPhone in landscape as *compact* width regardless of its actual size,
     * so a width-only rule there fails on every phone rather than only the small
     * ones. Android reports real dp and does not have that trap — verified in
     * `ShellMetricsTest`, where the 869 dp case asserts `narrowViewport` is
     * false.)
     */
    val usesSidebar: Boolean get() = usesSidebarFor(windowWidth, windowHeight)

    /**
     * Preview-over-wheel, or a grid.
     *
     * The coverflow composition assumes a lane wider than it is tall: artwork
     * beside its metadata, wheel as a band along the bottom. Give it a *tall*
     * lane and the proportions invert — the preview pane gets height for content
     * that does not need it and spends the surplus on voids. So the composition
     * follows the shape of the lane rather than the device: wide and short gets
     * the television layout, tall gets the grid. A tablet in portrait is a tall
     * lane too, which is why this is not an `isPhone` test.
     */
    val usesCoverflow: Boolean get() = lane >= windowHeight * 0.95f

    /**
     * Gutter between the page edge and content. Proportional so a small phone
     * does not lose a quarter of its width to margins, clamped so a wide screen
     * does not float its content in the middle of an ocean.
     */
    val pageInset: Dp get() = (lane * 0.05f).coerceIn(Space.lg, Space.page)

    /** Space between tiles in a rail. */
    val tileGap: Dp get() = if (lane < 500.dp) Space.md else Space.lg

    /**
     * Width of a rail tile. Scales with the lane up to a ceiling: past about
     * 530 dp a wider screen should show *more* tiles rather than bigger ones,
     * which is how a shelf is meant to work.
     */
    val tileWidth: Dp get() = (lane * 0.45f).coerceIn(150.dp, ShellDim.posterTile)

    /** Rail height: the tile's 16:9 art plus its two lines of label. */
    val railHeight: Dp get() = tileWidth * 9f / 16f + if (shortViewport) 72.dp else 88.dp

    /**
     * Home hero. A phone in landscape has roughly 390 dp of height in total, so
     * the portrait hero would be four fifths of the screen on its own.
     */
    val heroHeight: Dp get() =
        if (shortViewport) {
            (windowHeight * 0.50f).coerceIn(150.dp, 240.dp)
        } else {
            (lane * 0.82f).coerceIn(220.dp, 380.dp)
        }

    /**
     * Width of the vertical CATEGORIES column.
     *
     * [ShellDim.categoriesPanelWidth] (240) is the television number and the
     * ceiling here. A phone in landscape has far less to spend: on a 667 dp-wide
     * viewport, 92 of rail plus 240 of panel leaves the preview and its orbs
     * 335 dp — narrower than the panel and the rail together.
     */
    val categoryPanel: Dp get() = (lane * 0.30f).coerceIn(168.dp, ShellDim.categoriesPanelWidth)

    /** Grid tile for the portrait browse grid, Favorites and Search results. */
    val gridTileWidth: Dp get() = (lane * 0.40f).coerceIn(140.dp, 220.dp)

    /** Detail-page backdrop height, as a fraction of what the window can spare. */
    val detailHeroHeight: Dp get() =
        if (shortViewport) {
            (windowHeight * 0.55f).coerceIn(180.dp, 300.dp)
        } else {
            (windowHeight * 0.38f).coerceIn(220.dp, ShellDim.heroHeight)
        }
}

// ───── The window-shape rules ─────
//
// Free functions rather than members because [rememberShellMetrics] has to
// answer "is there a rail?" *before* it can subtract one to get the lane.
// Inlining the expression there instead would put the rule in two places, which
// is the drift this whole file exists to remove.

internal fun isShortViewport(height: Dp): Boolean = height < 480.dp

internal fun isNarrowViewport(width: Dp): Boolean = width < 600.dp

internal fun usesSidebarFor(width: Dp, height: Dp): Boolean =
    !isNarrowViewport(width) || isShortViewport(height)

/**
 * Measures the current window and derives the lane from it.
 *
 * The rail is taken at its **collapsed** width even while it is expanded. The
 * expanded 260 dp is a transient, focus-driven state, and feeding it in would
 * recompute this on every focus move — and [LocalShellMetrics] is a *static*
 * local, so each change recomposes the whole subtree. Content is laid out
 * against the resting width; the rail overlaps it while open, exactly as it
 * does today.
 */
@Composable
fun rememberShellMetrics(): ShellMetrics {
    val config = LocalConfiguration.current
    val widthDp = config.screenWidthDp
    val heightDp = config.screenHeightDp
    return remember(widthDp, heightDp) {
        val width = widthDp.dp
        val height = heightDp.dp
        val rail = if (usesSidebarFor(width, height)) ShellDim.sidebarCollapsed else 0.dp
        ShellMetrics(windowWidth = width, windowHeight = height, lane = width - rail)
    }
}

/**
 * Measured once at the shell root and read everywhere, so no screen has to
 * re-derive sizes or guess at the rail's width.
 *
 * `static` because the value changes only on a genuine configuration change —
 * a rotation or a window resize — and a static local skips the per-read
 * bookkeeping a dynamic one pays for.
 *
 * The default is the Fire TV viewport with a collapsed rail, so anything that
 * reads this outside the shell (a preview, a test) gets the television numbers
 * rather than zero.
 */
val LocalShellMetrics =
    staticCompositionLocalOf {
        ShellMetrics(
            windowWidth = 960.dp,
            windowHeight = 540.dp,
            lane = 960.dp - ShellDim.sidebarCollapsed,
        )
    }
