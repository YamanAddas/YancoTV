package com.yancotv.android.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography scale for the TV shell. Explicit rather than leaning on
 * Material3 defaults so sizes read as intentional at 10-ft viewing
 * distance on Fire TV. Labels < body < title < display.
 *
 * Usage:
 *   Text(text = "Live TV", style = YancoType.SectionTitle, color = ...)
 */
object YancoType {
    // Overlines — small uppercase labels above sections ("LIVE", "EPISODES").
    val Overline = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
    )

    // Captions — supporting metadata (channel numbers, timestamps).
    val Caption = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    )
    val CaptionStrong = Caption.copy(fontWeight = FontWeight.SemiBold)

    // Body — most row subtitles / muted descriptions.
    val Body = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    )
    val BodyStrong = Body.copy(fontWeight = FontWeight.SemiBold)
    val BodyLong = TextStyle(
        fontSize = 14.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    )

    // Label — buttons, chips, sidebar rows.
    val Label = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
    )
    val LabelStrong = Label.copy(fontWeight = FontWeight.Bold)

    // Titles — row titles ("Live TV"), card titles.
    val TitleS = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val TitleM = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val TitleL = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
    )

    // Display — section banners, detail hero title.
    val DisplayS = TextStyle(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    )
    val DisplayM = TextStyle(
        fontSize = 40.sp,
        lineHeight = 48.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.8).sp,
    )
    val DisplayCinematic = TextStyle(
        fontSize = 44.sp,
        lineHeight = 52.sp,
        fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic,
        letterSpacing = (-0.9).sp,
    )
}
