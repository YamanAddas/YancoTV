package com.yancotv.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/**
 * Hand-rolled nav icons. We avoid pulling in material-icons-extended
 * (~30k classes, bloats the APK) and Compose Material's built-in set
 * doesn't ship line-weight TV-style glyphs. Each icon is a tiny vector
 * so first-paint is still instant.
 *
 * All strokes at 1.6dp, rounded caps — matches the cinematic UI feel
 * better than the thicker default material line weight.
 */
object YancoIcons {
    val Home: ImageVector =
        buildLine("home") {
            moveTo(3f, 11.5f)
            lineTo(12f, 3.5f)
            lineTo(21f, 11.5f)
            moveTo(5.5f, 10f)
            lineTo(5.5f, 20f)
            lineTo(18.5f, 20f)
            lineTo(18.5f, 10f)
            moveTo(10f, 20f)
            lineTo(10f, 14.5f)
            lineTo(14f, 14.5f)
            lineTo(14f, 20f)
        }

    val Live: ImageVector =
        buildLine("live") {
            moveTo(3f, 6f)
            lineTo(21f, 6f)
            lineTo(21f, 17f)
            lineTo(3f, 17f)
            close()
            moveTo(8f, 20f)
            lineTo(16f, 20f)
            moveTo(12f, 17f)
            lineTo(12f, 20f)
        }

    val Guide: ImageVector =
        buildLine("guide") {
            moveTo(4f, 5f)
            lineTo(20f, 5f)
            lineTo(20f, 19f)
            lineTo(4f, 19f)
            close()
            moveTo(4f, 9.5f)
            lineTo(20f, 9.5f)
            moveTo(9.5f, 9.5f)
            lineTo(9.5f, 19f)
            moveTo(15f, 9.5f)
            lineTo(15f, 19f)
        }

    val Movies: ImageVector =
        buildLine("movies") {
            moveTo(4f, 4f)
            lineTo(20f, 4f)
            lineTo(20f, 20f)
            lineTo(4f, 20f)
            close()
            moveTo(4f, 4f)
            lineTo(8f, 8f)
            moveTo(10f, 4f)
            lineTo(14f, 8f)
            moveTo(16f, 4f)
            lineTo(20f, 8f)
            moveTo(4f, 8f)
            lineTo(20f, 8f)
            moveTo(10.5f, 11.5f)
            lineTo(15.5f, 14.5f)
            lineTo(10.5f, 17.5f)
            close()
        }

    val Series: ImageVector =
        buildLine("series") {
            moveTo(3.5f, 7.5f)
            lineTo(20.5f, 7.5f)
            lineTo(20.5f, 18f)
            lineTo(3.5f, 18f)
            close()
            moveTo(8f, 4.5f)
            lineTo(12f, 7.5f)
            moveTo(16f, 4.5f)
            lineTo(12f, 7.5f)
            moveTo(7f, 20.5f)
            lineTo(17f, 20.5f)
        }

    val Favorites: ImageVector =
        buildLine("favorites") {
            moveTo(12f, 20f)
            lineTo(4.5f, 12.5f)
            arcToRelative(4.5f, 4.5f, 0f, true, true, 7.5f, -4.5f)
            arcToRelative(4.5f, 4.5f, 0f, true, true, 7.5f, 4.5f)
            lineTo(12f, 20f)
            close()
        }

    val Search: ImageVector =
        buildLine("search") {
            moveTo(16f, 16f)
            lineTo(20.5f, 20.5f)
            moveTo(4f, 10.5f)
            arcToRelative(6.5f, 6.5f, 0f, true, true, 13f, 0f)
            arcToRelative(6.5f, 6.5f, 0f, true, true, -13f, 0f)
        }

    val Recordings: ImageVector =
        buildLine("recordings") {
            // Disc + record-dot — same idiom every DVR uses; reads as
            // "saved media" without colliding with the Live (red rect)
            // or Movies (filmstrip) icons.
            moveTo(3.5f, 12f)
            // Stand-in for an arc: trace a square + an inner dot. The
            // existing buildLine helper doesn't expose curves; the
            // square reads close enough at icon size.
            lineTo(3.5f, 4f)
            lineTo(20.5f, 4f)
            lineTo(20.5f, 20f)
            lineTo(3.5f, 20f)
            close()
            moveTo(9f, 9f)
            lineTo(15f, 9f)
            lineTo(15f, 15f)
            lineTo(9f, 15f)
            close()
        }

    val Settings: ImageVector =
        buildLine("settings") {
            moveTo(12f, 8.5f)
            arcToRelative(3.5f, 3.5f, 0f, true, true, 0f, 7f)
            arcToRelative(3.5f, 3.5f, 0f, true, true, 0f, -7f)
            moveTo(12f, 3.5f)
            lineTo(12f, 6f)
            moveTo(12f, 18f)
            lineTo(12f, 20.5f)
            moveTo(3.5f, 12f)
            lineTo(6f, 12f)
            moveTo(18f, 12f)
            lineTo(20.5f, 12f)
            moveTo(6f, 6f)
            lineTo(7.8f, 7.8f)
            moveTo(16.2f, 16.2f)
            lineTo(18f, 18f)
            moveTo(6f, 18f)
            lineTo(7.8f, 16.2f)
            moveTo(16.2f, 7.8f)
            lineTo(18f, 6f)
        }

    val Play: ImageVector =
        buildLine("play") {
            moveTo(7f, 4.5f)
            lineTo(19f, 12f)
            lineTo(7f, 19.5f)
            close()
        }

    val StarOutline: ImageVector =
        buildLine("star-outline") {
            moveTo(12f, 3.5f)
            lineTo(14.7f, 9.3f)
            lineTo(21f, 10f)
            lineTo(16.2f, 14.2f)
            lineTo(17.6f, 20.5f)
            lineTo(12f, 17.3f)
            lineTo(6.4f, 20.5f)
            lineTo(7.8f, 14.2f)
            lineTo(3f, 10f)
            lineTo(9.3f, 9.3f)
            close()
        }

    val StarFilled: ImageVector =
        buildFilled("star-filled") {
            moveTo(12f, 3.5f)
            lineTo(14.7f, 9.3f)
            lineTo(21f, 10f)
            lineTo(16.2f, 14.2f)
            lineTo(17.6f, 20.5f)
            lineTo(12f, 17.3f)
            lineTo(6.4f, 20.5f)
            lineTo(7.8f, 14.2f)
            lineTo(3f, 10f)
            lineTo(9.3f, 9.3f)
            close()
        }

    val Lock: ImageVector =
        buildLine("lock") {
            moveTo(6f, 11f)
            lineTo(18f, 11f)
            lineTo(18f, 20f)
            lineTo(6f, 20f)
            close()
            moveTo(8f, 11f)
            lineTo(8f, 8f)
            arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
            lineTo(16f, 11f)
        }

    val ChevronRight: ImageVector =
        buildLine("chevron-right") {
            moveTo(9f, 5f)
            lineTo(16f, 12f)
            lineTo(9f, 19f)
        }

    /** Chevron pointing down — used for expanded category-rail parents (MK.20.3). */
    val ChevronDown: ImageVector =
        buildLine("chevron-down") {
            moveTo(5f, 9f)
            lineTo(12f, 16f)
            lineTo(19f, 9f)
        }

    // === Settings-tab glyphs (Concept A) ========================================
    // Ported from yanco-ds/ds.jsx `I` map. Paths mirror the SVG d-attrs in that
    // file; if the design changes the source, update here.

    /** Grid of four squares — Groups tab. */
    val Grid: ImageVector =
        buildLine("grid") {
            moveTo(3f, 3f); lineTo(10f, 3f); lineTo(10f, 10f); lineTo(3f, 10f); close()
            moveTo(14f, 3f); lineTo(21f, 3f); lineTo(21f, 10f); lineTo(14f, 10f); close()
            moveTo(3f, 14f); lineTo(10f, 14f); lineTo(10f, 21f); lineTo(3f, 21f); close()
            moveTo(14f, 14f); lineTo(21f, 14f); lineTo(21f, 21f); lineTo(14f, 21f); close()
        }

    /** CC / subtitles badge — Subtitles tab. */
    val Subtitles: ImageVector =
        buildLine("subtitles") {
            // Rounded rect frame (2,5) → (22,19) with 1dp corner radius.
            moveTo(4f, 5f); lineTo(20f, 5f)
            arcToRelative(1f, 1f, 0f, false, true, 1f, 1f)
            lineTo(21f, 18f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, 1f)
            lineTo(4f, 19f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, -1f)
            lineTo(3f, 6f)
            arcToRelative(1f, 1f, 0f, false, true, 1f, -1f)
            close()
            // Upper-row narrow dashes (CC underscores).
            moveTo(7f, 11f); lineTo(6.5f, 11f)
            moveTo(14.5f, 11f); lineTo(14f, 11f)
            // Lower-row wide dashes (caption lines).
            moveTo(7f, 15f); lineTo(10f, 15f)
            moveTo(15f, 15f); lineTo(18f, 15f)
        }

    /** Ascending bars — Network tab. */
    val Signal: ImageVector =
        buildFilled("signal") {
            moveTo(3f, 13f); lineTo(5f, 13f); lineTo(5f, 18f); lineTo(3f, 18f); close()
            moveTo(8f, 9f); lineTo(10f, 9f); lineTo(10f, 18f); lineTo(8f, 18f); close()
            moveTo(13f, 5f); lineTo(15f, 5f); lineTo(15f, 18f); lineTo(13f, 18f); close()
            moveTo(18f, 1f); lineTo(20f, 1f); lineTo(20f, 18f); lineTo(18f, 18f); close()
        }

    /** Two interlocking chain links — Sources tab. */
    val Link: ImageVector =
        buildLine("link") {
            moveTo(10f, 14f)
            arcToRelative(5f, 5f, 0f, false, false, 7f, 0f)
            lineToRelative(3f, -3f)
            arcToRelative(5f, 5f, 0f, false, false, -7f, -7f)
            lineToRelative(-1f, 1f)
            moveTo(14f, 10f)
            arcToRelative(5f, 5f, 0f, false, false, -7f, 0f)
            lineToRelative(-3f, 3f)
            arcToRelative(5f, 5f, 0f, false, false, 7f, 7f)
            lineToRelative(1f, -1f)
        }

    /** Heraldic shield — Parental tab. */
    val Shield: ImageVector =
        buildLine("shield") {
            moveTo(12f, 3f)
            lineTo(20f, 6f)
            lineTo(20f, 12f)
            arcToRelative(8f, 8f, 0f, false, true, -8f, 9f)
            arcToRelative(8f, 8f, 0f, false, true, -8f, -9f)
            lineTo(4f, 6f)
            close()
        }

    /** Solid disc — Recordings tab. Filled so it reads as a "REC" indicator. */
    val Record: ImageVector =
        buildFilled("record") {
            // Circle radius 5 at (12,12), approximated with two half-arcs.
            moveTo(12f, 7f)
            arcToRelative(5f, 5f, 0f, true, true, 0f, 10f)
            arcToRelative(5f, 5f, 0f, true, true, 0f, -10f)
            close()
        }

    /** Bell silhouette — Notifications tab. */
    val Bell: ImageVector =
        buildLine("bell") {
            moveTo(6f, 16f)
            lineTo(6f, 11f)
            arcToRelative(6f, 6f, 0f, false, true, 12f, 0f)
            lineTo(18f, 16f)
            lineTo(20f, 18f)
            lineTo(4f, 18f)
            close()
            moveTo(10f, 21f)
            lineTo(14f, 21f)
        }

    /** Hard drive — Storage tab. */
    val Hdd: ImageVector =
        buildLine("hdd") {
            moveTo(3f, 6f)
            lineTo(21f, 6f)
            arcToRelative(1f, 1f, 0f, false, true, 1f, 1f)
            lineTo(22f, 17f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, 1f)
            lineTo(3f, 18f)
            arcToRelative(1f, 1f, 0f, false, true, -1f, -1f)
            lineTo(2f, 7f)
            arcToRelative(1f, 1f, 0f, false, true, 1f, -1f)
            close()
            moveTo(6f, 12f); lineTo(18f, 12f)
            moveTo(6f, 15f); lineTo(10f, 15f)
        }

    /** Angled key — Shortcuts tab. */
    val Key: ImageVector =
        buildLine("key") {
            // Bow: circle radius 4 at (8,15).
            moveTo(8f, 11f)
            arcToRelative(4f, 4f, 0f, true, true, 0f, 8f)
            arcToRelative(4f, 4f, 0f, true, true, 0f, -8f)
            // Shaft + two notches.
            moveTo(11f, 12f); lineTo(22f, 1f)
            moveTo(19f, 4f); lineTo(22f, 7f)
            moveTo(16f, 7f); lineTo(19f, 10f)
        }

    /** Encircled lowercase "i" — About tab. */
    val Info: ImageVector =
        buildLine("info") {
            moveTo(12f, 3f)
            arcToRelative(9f, 9f, 0f, true, true, 0f, 18f)
            arcToRelative(9f, 9f, 0f, true, true, 0f, -18f)
            // Dot of the "i" — drawn as a zero-length stroke so the rounded cap
            // paints a solid mark. Kept distinct from the stem for hinting.
            moveTo(12f, 8f); lineTo(12f, 8.01f)
            moveTo(12f, 12f); lineTo(12f, 16f)
        }

    /** Soft cloud silhouette — sidebar "SYNCED" footer chip. */
    val Cloud: ImageVector =
        buildLine("cloud") {
            moveTo(6f, 18f)
            lineTo(18f, 18f)
            arcToRelative(4f, 4f, 0f, false, false, 0f, -8f)
            arcToRelative(6f, 6f, 0f, false, false, -11f, -1f)
            arcTo(4f, 4f, 0f, false, false, 6f, 18f)
            close()
        }

    /** Split-fill disc — Appearance tab. Outline + left-half fill reads as a
     * "light / dark" pair. Uses both a stroked and a filled sub-path, so it
     * builds via [iconLayered] rather than the single-path helpers. */
    val Theme: ImageVector =
        iconLayered("theme") {
            stroke {
                moveTo(12f, 3f)
                arcToRelative(9f, 9f, 0f, true, true, 0f, 18f)
                arcToRelative(9f, 9f, 0f, true, true, 0f, -18f)
            }
            fill {
                moveTo(12f, 3f)
                arcToRelative(9f, 9f, 0f, false, false, 0f, 18f)
                close()
            }
        }

    private fun buildLine(
        name: String,
        block: PathBuilder.() -> Unit,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                addPath(
                    pathData =
                        androidx.compose.ui.graphics.vector
                            .PathData(block),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 1.6f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }.build()

    private fun buildFilled(
        name: String,
        block: PathBuilder.() -> Unit,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                addPath(
                    pathData =
                        androidx.compose.ui.graphics.vector
                            .PathData(block),
                    fill = SolidColor(Color.White),
                )
            }.build()

    // Multi-path helper: lets an icon mix line-style and solid-fill sub-paths
    // (used by [Theme]). Keeps the single-path [buildLine]/[buildFilled] pair
    // as the common case and reserves this one for genuinely layered glyphs.
    private class LayeredIconScope(
        private val builder: ImageVector.Builder,
    ) {
        fun stroke(block: PathBuilder.() -> Unit) {
            builder.addPath(
                pathData =
                    androidx.compose.ui.graphics.vector
                        .PathData(block),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }

        fun fill(block: PathBuilder.() -> Unit) {
            builder.addPath(
                pathData =
                    androidx.compose.ui.graphics.vector
                        .PathData(block),
                fill = SolidColor(Color.White),
            )
        }
    }

    private fun iconLayered(
        name: String,
        block: LayeredIconScope.() -> Unit,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                LayeredIconScope(this).block()
            }.build()
}
