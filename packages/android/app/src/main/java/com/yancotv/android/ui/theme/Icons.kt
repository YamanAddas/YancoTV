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
}
