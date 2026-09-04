package com.yancotv.android.ui.shell

/**
 * MB-416 — turning a touch position into a slot, in the layout's own direction.
 *
 * ### The bug
 *
 * `SectionFlowBar` renders through a `Row`, whose children mirror under RTL, and
 * positions its indicator with `Modifier.offset` off `Alignment.CenterStart`,
 * both of which are layout-direction aware. So on an Arabic phone the bar draws
 * Home at the RIGHT end and More at the LEFT — correctly.
 *
 * Its input was not. Both the tap and the drag divided a raw `position.x` — a
 * distance from the physical left edge — by the slot width and used the result
 * as a logical index. Two of the three agreed and the third did not, so every
 * tab activated its mirror image: pressing **المزيد** at the left end went Home,
 * and pressing **الرئيسية** at the right end opened the More sheet.
 *
 * Device-observed on the Pixel XL in Arabic, 2026-09-04, by tapping all six
 * slots left to right and recording where each landed.
 *
 * This is `SettingsPrimitives`' MK.31.2 lesson arriving in a second component:
 * *"the fill is drawn from `Alignment.CenterStart`, which IS mirrored under RTL,
 * so key and touch input have to be logical too, or the three disagree."*
 * The comment was right and lived in the wrong file.
 *
 * ### Why these are functions rather than two lines inside the composable
 *
 * The arithmetic is where the bug was, and it is the part no device test can
 * pin cheaply — driving a phone through six taps in two layout directions takes
 * minutes and a person to read the result. As plain functions the property is
 * assertable: every visual position maps to the slot the viewer sees there,
 * under both directions.
 */

/**
 * The slot the viewer sees at [x], measured from the layout's physical left edge.
 *
 * @param x pointer position within the bar's content box, in pixels.
 * @param slotPx one slot's width in pixels. Must be positive; a zero here would
 *   otherwise divide and hand back a nonsense index on the first frame, before
 *   measurement has happened.
 * @param slots total slots, including the trailing More one.
 * @param rtl the bar's layout direction, not the device's — a single screen can
 *   differ from the system when the in-app language does.
 */
internal fun flowBarSlotAt(x: Float, slotPx: Float, slots: Int, rtl: Boolean): Int {
    if (slotPx <= 0f || slots <= 0) return 0
    val visual = (x / slotPx).toInt().coerceIn(0, slots - 1)
    return if (rtl) slots - 1 - visual else visual
}

/**
 * The continuous indicator position for a drag at [x].
 *
 * The half-slot subtraction centres the indicator under the finger rather than
 * letting its leading edge track it. Under RTL the indicator's leading edge is
 * its RIGHT one — `Modifier.offset` grows away from the start, and start is the
 * right — so the whole expression mirrors, not just the slot index.
 *
 * Returned unclamped at the ends on purpose: the caller clamps to the same
 * range it uses for the animation, and folding the clamp in here would hide
 * which of the two is wrong when they ever disagree.
 */
internal fun flowBarDragTarget(x: Float, slotPx: Float, slots: Int, rtl: Boolean): Float {
    if (slotPx <= 0f || slots <= 0) return 0f
    val visual = x / slotPx
    return (if (rtl) slots - visual else visual) - 0.5f
}
