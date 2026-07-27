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

    // MK.29.4 — no call sites. Kept (rather than deleted) because the
    // list/info-rail layout they describe is still on the roadmap, but they
    // were NOT recalibrated against the 960x540 dp viewport in this pass and
    // must not be adopted as-is: `infoWidth` at 320 dp is a third of the TV
    // screen's width, and `rowHeight` at 78 dp yields under 6 visible rows
    // where TiviMate shows 8-9. Re-derive before first use.
    val groupsWidth = 256.dp
    val infoWidth = 320.dp
    val rowHeight = 78.dp
    val rowHeightWithEpg = 92.dp

    val rowThumb = 56.dp
    val posterTile = 220.dp
    val posterTileAspect = 16f / 9f

    /**
     * MB-303 — width ÷ height of a VOD poster frame.
     *
     * Movie / series artwork is portrait: the Xtream `stream_icon` /
     * `cover_big` fields and TMDB's `poster_path` are both 2:3 (TMDB
     * serves 500x750 / 780x1170). The browse preview pane rendered that
     * into a **444x303 dp landscape box with `ContentScale.Crop`**
     * (measured on the Fire TV AFTMM, 960x540 dp viewport): Crop scales
     * to cover, so a 1000x1500 poster came out 444x666 and the box
     * showed the middle 303 dp — **45% of the poster's height, title
     * block and credit block both cut off.**
     *
     * Consumers size the frame from the pane HEIGHT and derive width
     * from this ratio, so the whole poster is always on screen. Paired
     * with `ContentScale.Fit` (not Crop) inside the frame, because
     * providers are inconsistent — a minority ship 16:9 grabs or square
     * art under the same field, and Fit letterboxes those into the frame
     * instead of cropping them a second time.
     */
    val posterAspect = 2f / 3f

    /**
     * Fraction of the preview row given to the poster slot on VOD. The
     * frame is height-driven (see [posterAspect]) and centres inside the
     * slot; the slot only exists to bound the width so a tall/narrow
     * phone-portrait pane can't hand the poster the whole row and starve
     * the meta column. 0.30 of the measured 740 dp TV row = 222 dp,
     * against the 202 dp a 303 dp-tall frame actually needs — 20 dp of
     * slack, and the remaining 0.70 (518 dp) goes to title + plot +
     * actions.
     */
    val posterSlotWeight = 0.30f

    /**
     * MK.29.4 — backdrop height on the content detail page.
     *
     * Was 520.dp, i.e. **96% of the Fire TV's 540 dp viewport**: opening a
     * movie showed a full screen of backdrop art and nothing else, with the
     * title, poster, plot and Play button all below the fold. The value
     * reads plausibly on a phone (≈65% of an 800 dp-tall portrait screen),
     * which is how it survived — the TV case is the one that's wrong, and
     * TV is the canonical target.
     *
     * 330 dp is 61% of the TV viewport, so the backdrop still dominates the
     * top of the page while the title block lands in view. Paired with
     * [detailHeroContentOffset], which positions that block inside it.
     */
    val heroHeight = 330.dp

    /**
     * Distance the detail page's title/poster/actions column is pushed down
     * so it sits in the dark band of the backdrop's gradient rather than
     * over the bright middle of the artwork. Scaled with [heroHeight]
     * (was a bare 220.dp literal at the call site, tuned against the old
     * 520 dp backdrop).
     */
    val detailHeroContentOffset = 140.dp

    /**
     * Detail-page poster width. At [posterAspect] this is 300 dp tall, so
     * starting from [detailHeroContentOffset] it ends at ~472 dp — inside
     * the 540 dp fold, which the previous 240 dp (360 dp tall, ending at
     * 532 dp) only just was.
     */
    val detailPosterWidth = 200.dp
}
