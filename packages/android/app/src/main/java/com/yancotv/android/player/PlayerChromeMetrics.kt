package com.yancotv.android.player

/**
 * MK.34.10 — the player chrome's sizing arithmetic, with no Compose in it.
 *
 * Every number the design brief gives is a PHYSICAL PIXEL measured at
 * 1920x1080. That one fact caused the same bug three times in this milestone,
 * because dp is the unit you reach for in Compose and the difference is
 * invisible until something is measured on a density-2.0 device:
 *
 *  - MK.34.4: the whole dock was built at 2x. The hero measured 168px against a
 *    spec of 78-88, the row overflowed and crushed the three-dot control from
 *    52px to 48, and the overlay filled 57% of the screen against a 28% cap.
 *  - MK.34.4 again, in the type: the metadata line was set at 14sp = 28px,
 *    which is very nearly the size the TITLE should be. The hierarchy was
 *    inverted and it did not look wrong until both were measured.
 *  - MK.34.7: the options sheet was 640px against a 320-440 clamp; and fixing
 *    that, the RATIO was halved as well as the clamp bounds, so the sheet came
 *    out pinned to the clamp's floor at 320px.
 *
 * None of those were caught by reading the code. All three were caught by
 * measuring pixels on a TV. This file exists so the fourth one is caught by a
 * test instead: [PlayerChromeMetricsTest] asserts the brief's own numbers at the
 * reference width, so getting the units wrong fails in seconds rather than after
 * a build, an install and a `uiautomator` dump.
 *
 * **Everything is a fraction of screen width, never a fixed dp.** A device
 * reporting 960dp at density 2.0 and one reporting 1920dp at density 1.0 must
 * render the same PHYSICAL size, because physical size is the only thing a
 * viewer three metres away perceives. A plain dp constant gets that wrong on one
 * of the two, and halving a dp constant gets it wrong on the other.
 */
internal object PlayerChromeMetrics {
    /** The width the brief's proportions were quoted at, in physical pixels. */
    const val REFERENCE_WIDTH_PX = 1920f

    /**
     * Control size for [variant] at [screenWidthDp].
     *
     * Ratios are the brief's midpoints over 1920: hero 83px, transport 56px,
     * secondary 52px, menu icon 30px. The floors are an accessibility backstop
     * for small windows — they stop a phone in landscape shrinking a focus
     * target below what a D-pad user can pick out — and are deliberately NOT
     * part of the design.
     */
    fun hexSizeDp(variant: HexVariant, screenWidthDp: Float, touch: Boolean = false): Float {
        val ratio = when (variant) {
            HexVariant.HERO -> 0.0432f
            HexVariant.TRANSPORT -> 0.0292f
            HexVariant.SECONDARY -> 0.0271f
            HexVariant.MENU_ICON -> 0.0156f
        }
        val floor = when (variant) {
            HexVariant.HERO -> if (touch) TOUCH_TARGET_DP else 40f
            HexVariant.TRANSPORT -> if (touch) TOUCH_TARGET_DP else 27f
            HexVariant.SECONDARY -> if (touch) TOUCH_TARGET_DP else 25f
            // Not a target — the glyph inside a larger control — so it is not
            // held to the touch minimum.
            HexVariant.MENU_ICON -> 18f
        }
        return (screenWidthDp * ratio).coerceAtLeast(floor)
    }

    /**
     * MK.37.F — the floor for a control reached with a **finger**.
     *
     * The floors above were written for a D-pad, and the comment on them says
     * so: they stop a focus target shrinking below what a remote user can pick
     * out at three metres. A finger has a different minimum, and Android's is
     * **48 dp**.
     *
     * This was already wrong before portrait existed. Every one of the brief's
     * ratios is against a 1920 px television, so on a phone in landscape
     * (731 dp) *every* control already falls to its floor: transport at 27 dp,
     * secondary at 25 dp — a little over half the minimum touch target, on the
     * one form factor where they are touched rather than focused. Portrait
     * (411 dp) does not create the problem, it only makes every control sit
     * there.
     */
    const val TOUCH_TARGET_DP = 48f

    /** Brief: 24-32px horizontal padding at 1920. */
    fun dockPaddingDp(screenWidthDp: Float): Float = (screenWidthDp * 0.0146f).coerceAtLeast(10f)

    /** Brief: 12-18px between controls at 1920. */
    fun dockGapDp(screenWidthDp: Float): Float = (screenWidthDp * 0.0078f).coerceAtLeast(5f)

    fun dockVerticalPaddingDp(screenWidthDp: Float): Float = (screenWidthDp * 0.0057f).coerceAtLeast(4f)

    /**
     * Brief: `clamp(20px, 1.7vw, 30px)` for the programme title.
     *
     * 1.7vw of 1920 is 32.6px, so the clamp's 30px ceiling is what actually
     * applies at the reference width — the ratio is not the operative number
     * there, which is exactly why halving it was not obviously wrong.
     */
    fun titleFontSp(screenWidthDp: Float): Float = (screenWidthDp * 0.017f).coerceIn(10f, 15f)

    /**
     * Brief: `clamp(320px, 30vw, 440px)` for the options sheet.
     *
     * **30vw is DIMENSIONLESS** — 30% of the viewport, whatever units the
     * viewport is expressed in. Only the clamp BOUNDS were physical pixels
     * (320-440px at 1920 = 160-220dp at density 2.0). Converting the ratio as
     * well is what pinned the sheet to its floor.
     */
    fun sheetWidthDp(screenWidthDp: Float): Float = (screenWidthDp * 0.30f).coerceIn(160f, 220f)

    /** Brief: `max-height: 68vh`. Also dimensionless. */
    fun sheetMaxHeightDp(screenHeightDp: Float): Float = screenHeightDp * 0.68f

    /**
     * Physical pixels a [dp] value occupies at [density], for tests that want to
     * assert the brief's numbers in the units the brief was written in.
     */
    fun toPx(dp: Float, density: Float): Float = dp * density
}
