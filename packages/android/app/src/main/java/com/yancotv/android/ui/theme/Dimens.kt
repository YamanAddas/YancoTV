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
