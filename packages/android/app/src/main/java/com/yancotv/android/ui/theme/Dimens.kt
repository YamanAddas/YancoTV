package com.yancotv.android.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 8-point spacing scale. Every padding / gap in the shell should pull from
 * this object so the rhythm stays consistent. `xxs` is the only value
 * below 8 (pixel-snug dividers); everything else respects the grid.
 */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val section = 40.dp
    val page = 48.dp
}

/**
 * Corner radius scale. Bands rather than a geometric series — picks map to
 * real components (chips / cards / panels / hero) so a code reader can
 * infer intent from the token, not the pixel value.
 */
object Radius {
    val chip = 6.dp
    val control = 10.dp
    val card = 14.dp
    val panel = 20.dp
    val hero = 28.dp
    val pill = 999.dp
}

/**
 * Structural dimensions for the TV shell. Sidebar / groups / info rails and
 * list row heights all live here so a redesign can tune rhythm from one
 * spot without hunting magic numbers.
 */
object ShellDim {
    val sidebarCollapsed = 92.dp
    val sidebarExpanded = 260.dp

    /**
     * MB-300 — width of the Settings *inner* tab rail (the second sidebar,
     * left of the settings content pane). Was a bare `380.dp` literal at
     * the call site.
     *
     * 380 was transplanted 1:1 out of the design handoff
     * (`docs/design/design_handoff_yancotv/designs/settings.html:109`), whose
     * canvas declares `width=1920` at `:5`. The Fire TV viewport is
     * 1920x1080 at densityDpi 320 = **960x540 dp** — half the design canvas
     * in units. So 380px of a 1920px canvas (19.8%) became 380dp of a 960dp
     * viewport (39.6%): a 2x unit error.
     *
     * The damage compounds: with the app rail collapsed the settings content
     * pane got 960 - 92 - 96(page padding) - 24(gap) - 380 = **368dp**, and
     * the breadcrumb inside it only 76.84dp of glyph budget — which is why
     * "SOURCES" wrapped to four clipped lines. With the app rail *expanded*
     * the pane collapsed to 200dp and the active breadcrumb chip's budget
     * went negative.
     *
     * Proportionally faithful would be 190dp. 240 is chosen instead so the
     * rail still holds the larger TV type roles at a 125% font scale
     * ("Appearance" needs 104.8dp of the resulting 148dp label slot) without
     * needing to be revisited when the type ramp lands.
     */
    val settingsRailWidth = 240.dp
    val categoriesPanelWidth = 240.dp
    val groupsWidth = 256.dp
    val infoWidth = 320.dp
    val rowHeight = 78.dp
    val rowHeightWithEpg = 92.dp
    val rowThumb = 56.dp
    val posterTile = 220.dp
    val posterTileAspect = 16f / 9f
    val heroHeight = 520.dp
    val detailPosterWidth = 240.dp
}
