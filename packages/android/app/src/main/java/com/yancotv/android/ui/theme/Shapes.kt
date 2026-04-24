package com.yancotv.android.ui.theme

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Hex-inspired shape library. The app's component language is built on
 * cut-corner + elongated-hex geometry rather than plain rounded rectangles —
 * the shapes here are shared by tiles, chips, and buttons so the visual
 * family stays coherent.
 *
 * Each shape is a small [Shape] subclass so it can pull real px from the
 * container size and from [Density] when the bevel depth needs to be
 * expressed in dp rather than as a fraction of height.
 *
 * Conventions:
 *   - `bevel` means a single straight cut instead of a rounded corner.
 *   - "Hex capsule" cuts both the left and right edges inwards so the shape
 *     has six sides but is wider than tall — used for horizontal TV tiles
 *     where a literal hexagon would crop poorly.
 *   - "Cut-corner card" is a rectangle with top-left + bottom-right corners
 *     bevelled, and the other two softly rounded. Keeps posters readable
 *     while adding the angular identity.
 */
object YancoShapes {
    /** Horizontal hex capsule — Live TV channel tiles, favourite list rows. */
    val HexCapsule: Shape = HexCapsuleShape(cutFraction = 0.28f, pointRoundDp = 0.dp)

    /** Softer hex capsule — same silhouette but rounded points. */
    val HexCapsuleSoft: Shape = HexCapsuleShape(cutFraction = 0.30f, pointRoundDp = 3.dp)

    /**
     * Cut-corner poster — top-right + bottom-left bevelled, the other two
     * corners squared. Concept A signature shape (`hex-2cut` in the design
     * CSS): clean diagonal cuts on opposite corners, no rounding so the
     * facets read as crisp machined edges instead of softened pillows.
     */
    val CutCornerCard: Shape = CutCornerCardShape(cut = 22.dp, round = 0.dp)

    /** Smaller cut-corner for compact cards (home rail tiles, sidebar rows). */
    val CutCornerCardSmall: Shape = CutCornerCardShape(cut = 16.dp, round = 0.dp)

    /** Larger cut-corner for hero panels and the live-TV preview pane. */
    val CutCornerCardLarge: Shape = CutCornerCardShape(cut = 32.dp, round = 0.dp)

    /** Chip silhouette — angular cut on the leading edge, rounded trailing edge. */
    val ChipBevel: Shape = ChipBevelShape()

    /** Button silhouette — slim horizontal hex with symmetrical bevels. */
    val ButtonBevel: Shape = ButtonBevelShape()

    /**
     * Long horizontal hex pill — flat top + bottom edges, two short angled
     * sides on the left + right. Used by the vertical category rail so a
     * line of category text reads naturally inside the hex outline. Proportions
     * tuned for a ~210dp × ~46dp pill: side caps end up ~22px wide (clamped),
     * leaving the bulk of the width as a flat top/bottom border for the label.
     */
    val HexPill: Shape = HexCapsuleShape(cutFraction = 0.5f, pointRoundDp = 0.dp)
}

/**
 * Elongated horizontal hex. [cutFraction] controls how much of the height
 * the left/right side bevels take (0.28 = ~15°). [pointRoundDp] softens
 * the two side tips so the shape doesn't read as razor-sharp at any size.
 */
internal class HexCapsuleShape(
    private val cutFraction: Float,
    private val pointRoundDp: Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val h = size.height
        val w = size.width
        val cut = (h * cutFraction).coerceIn(10f, 36f)
        val r = with(density) { pointRoundDp.toPx() }.coerceAtMost(cut * 0.7f)
        val mid = h / 2f

        val path =
            Path().apply {
                if (r <= 0.5f) {
                    moveTo(cut, 0f)
                    lineTo(w - cut, 0f)
                    lineTo(w, mid)
                    lineTo(w - cut, h)
                    lineTo(cut, h)
                    lineTo(0f, mid)
                    close()
                } else {
                    moveTo(cut + r, 0f)
                    lineTo(w - cut - r, 0f)
                    quadraticBezierTo(w - cut, 0f, w - cut + r * 0.5f, r * 0.5f)
                    lineTo(w - r * 0.5f, mid - r)
                    quadraticBezierTo(w, mid, w - r * 0.5f, mid + r)
                    lineTo(w - cut + r * 0.5f, h - r * 0.5f)
                    quadraticBezierTo(w - cut, h, w - cut - r, h)
                    lineTo(cut + r, h)
                    quadraticBezierTo(cut, h, cut - r * 0.5f, h - r * 0.5f)
                    lineTo(r * 0.5f, mid + r)
                    quadraticBezierTo(0f, mid, r * 0.5f, mid - r)
                    lineTo(cut - r * 0.5f, r * 0.5f)
                    quadraticBezierTo(cut, 0f, cut + r, 0f)
                    close()
                }
            }
        return Outline.Generic(path)
    }
}

/**
 * Concept A `hex-2cut` shape — top-right + bottom-left corners bevelled,
 * top-left + bottom-right left square. When `round` > 0 the bevelled
 * corners are softened with a quarter-arc; at `round = 0` the cuts are
 * crisp diagonals (the default in Concept A). Polygon spec from the
 * design CSS:
 *
 *   `0 0, calc(100% - cut) 0, 100% cut, 100% 100%, cut 100%, 0 calc(100% - cut)`
 */
internal class CutCornerCardShape(
    private val cut: Dp,
    private val round: Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val cap = minOf(size.width, size.height) * 0.4f
        val c = with(density) { cut.toPx() }.coerceAtMost(cap)
        val r = with(density) { round.toPx() }.coerceAtMost(c * 0.5f)

        val path =
            Path().apply {
                // Start at the top-left square corner.
                moveTo(0f, 0f)
                // Top edge to the start of the top-right bevel.
                lineTo(size.width - c, 0f)
                // Bevelled top-right — straight diagonal, optionally softened.
                if (r <= 0.5f) {
                    lineTo(size.width, c)
                } else {
                    lineTo(size.width - r, 0f)
                    quadraticBezierTo(size.width, 0f, size.width, r)
                    lineTo(size.width, c)
                }
                // Right edge to the bottom-right square corner.
                lineTo(size.width, size.height)
                // Bottom edge to the start of the bottom-left bevel.
                lineTo(c, size.height)
                // Bevelled bottom-left — straight diagonal, optionally softened.
                if (r <= 0.5f) {
                    lineTo(0f, size.height - c)
                } else {
                    lineTo(r, size.height)
                    quadraticBezierTo(0f, size.height, 0f, size.height - r)
                    lineTo(0f, size.height - c)
                }
                // Left edge back to the top-left square corner.
                lineTo(0f, 0f)
                close()
            }
        return Outline.Generic(path)
    }
}

/**
 * Chip silhouette — single angular cut on the leading edge, a half-pill
 * rounded trailing edge. Reads as "part of the hex family" without being
 * a full hexagon (which would crowd short labels).
 */
internal class ChipBevelShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val h = size.height
        val cut = (h * 0.42f).coerceIn(8f, 16f)
        val r = h / 2f
        val path =
            Path().apply {
                moveTo(cut, 0f)
                lineTo(size.width - r, 0f)
                arcTo(
                    rect =
                        Rect(
                            left = size.width - h,
                            top = 0f,
                            right = size.width,
                            bottom = h,
                        ),
                    startAngleDegrees = -90f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = false,
                )
                lineTo(cut, h)
                lineTo(0f, h / 2f)
                close()
            }
        return Outline.Generic(path)
    }
}

/**
 * Button silhouette — symmetrical horizontal hex with both-end bevels,
 * slightly steeper than the tile so buttons feel more pointed.
 */
internal class ButtonBevelShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val h = size.height
        val cut = (h * 0.42f).coerceIn(10f, 22f)
        val mid = h / 2f
        val path =
            Path().apply {
                moveTo(cut, 0f)
                lineTo(size.width - cut, 0f)
                lineTo(size.width, mid)
                lineTo(size.width - cut, h)
                lineTo(cut, h)
                lineTo(0f, mid)
                close()
            }
        return Outline.Generic(path)
    }
}
