package com.yancotv.android.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Type scale for the TV shell. Explicit rather than leaning on Material3
 * defaults, so sizes read as intentional at 10-ft viewing distance.
 *
 * Usage:
 *   Text(text = "Live TV", style = YancoType.SectionTitle, color = ...)
 *
 * ## MK.29.4 — the ramp is *compressed*, not scaled
 *
 * Measured on the canonical Fire TV Stick (AFTMM): 1920x1080 at densityDpi
 * 320, i.e. a **960 x 540 dp viewport** where 1 sp is 2 physical pixels. On
 * a 55" panel at 3 m that makes 1 sp subtend ≈ **1.45 arcmin**, so the whole
 * ramp can be checked against angular size rather than taste:
 *
 * | role   | before  | after   | after, arcmin |
 * |--------|---------|---------|---------------|
 * | Caption| 11 sp   | 12 sp   | 17.4          |
 * | Body   | 13 sp   | 14 sp   | 20.3          |
 * | Label  | 14 sp   | 15 sp   | 21.8          |
 * | TitleM | 18 sp   | 19 sp   | 27.6          |
 * | TitleL | 22 sp   | 23 sp   | 33.4          |
 * | DisplayS | 30 sp | 26 sp   | 37.7          |
 * | DisplayM | 40 sp | 30 sp   | 43.5          |
 * | DisplayCinematic | 44 sp | 34 sp | 49.3   |
 *
 * Two independent problems, pulling in opposite directions, which is why
 * neither a global multiplier nor a uniform cut could fix this:
 *
 *  1. **The floor was under the readability threshold.** ~16 arcmin is the
 *     usual comfortable-sustained-reading minimum; 11 sp sat right on it and
 *     the 22 hardcoded 10 sp sites were below it. Raised to 12 sp.
 *  2. **The ceiling was out of proportion to its container.** One line of
 *     `DisplayM` occupied 48 dp of a 540 dp-tall screen; in the browse
 *     preview pane the title wrapped to two lines and measured **95 dp of a
 *     303 dp pane — 31% of the pane spent on the title alone**, which is the
 *     "fonts too large / not optimised to its surrounding" report. Cut so
 *     the largest role is ≤ 6% of viewport height per line.
 *
 * Net effect: the largest:smallest ratio goes from 4.4x (44/10) to **2.8x**
 * (34/12), which is the band TV UIs actually live in — TiviMate's channel
 * rows / programme titles / detail headings span roughly 13→24 sp, and
 * `androidx.tv.material3.TypeScaleTokens` (numerically identical to phone
 * Material 3) is meant to be consumed by picking *larger roles* on TV, never
 * by applying gain to the whole scale.
 *
 * ### Why not a `fontScale` multiplier
 *
 * MB-298 tried exactly that and was reverted in `040516e`. `Density(density,
 * fontScale x k)` grows `sp` while freezing every `dp`, so text grows inside
 * containers that don't — and the multiplier needed to reach the readability
 * floor is a *decreasing* function of size (11 sp wanted x1.55, 44 sp wanted
 * x1.0), so no single constant is correct at both ends of the ramp. The
 * user-facing 90/100/110/125% preference still rides on that seam and
 * composes on top of these values; it is a preference, not the ramp.
 *
 * Line heights are held at ~1.30x for display roles and ~1.40x for reading
 * roles. Tighter than Material's default on the big end because a 2-line
 * hero title with generous leading is what pushed the preview pane over.
 */
object YancoType {
    // Overlines — small uppercase labels above sections ("LIVE", "EPISODES").
    // Letterspacing means these read larger than their nominal size; 12 sp is
    // the floor for anything the user is expected to actually read.
    val Overline =
        TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
        )

    // Captions — supporting metadata (channel numbers, timestamps).
    val Caption =
        TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.2.sp,
        )
    val CaptionStrong = Caption.copy(fontWeight = FontWeight.SemiBold)

    // Body — most row subtitles / muted descriptions.
    val Body =
        TextStyle(
            fontSize = 14.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Normal,
        )
    val BodyStrong = Body.copy(fontWeight = FontWeight.SemiBold)

    // BodyLong — multi-line reading text (plot synopses, help copy). The one
    // role tuned for sustained reading rather than glanceability, so it keeps
    // the loosest leading in the scale.
    val BodyLong =
        TextStyle(
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Normal,
        )

    // Label — buttons, chips, sidebar rows.
    val Label =
        TextStyle(
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
        )
    val LabelStrong = Label.copy(fontWeight = FontWeight.Bold)

    // Titles — row titles ("Live TV"), card titles.
    val TitleS =
        TextStyle(
            fontSize = 16.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.SemiBold,
        )
    val TitleM =
        TextStyle(
            fontSize = 19.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.SemiBold,
        )
    val TitleL =
        TextStyle(
            fontSize = 23.sp,
            lineHeight = 29.sp,
            fontWeight = FontWeight.Bold,
        )

    // Display — section banners, hero + preview titles. These are the roles
    // the compression bites hardest on; see the table above.
    val DisplayS =
        TextStyle(
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
        )
    val DisplayM =
        TextStyle(
            fontSize = 30.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
        )
    val DisplayCinematic =
        TextStyle(
            fontSize = 34.sp,
            lineHeight = 42.sp,
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic,
            letterSpacing = (-0.7).sp,
        )
}
