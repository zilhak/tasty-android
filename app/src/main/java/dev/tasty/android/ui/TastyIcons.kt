package dev.tasty.android.ui

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Icons from the Tasty design canon (tasty-page `vendor/icons`). Each is a 24 by 24 stroke drawing
 * with a 2 wide round stroke; the path data is copied from the canon SVG. Tint them with the
 * `Icon` composable.
 */
object TastyIcons {
    val plus = stroke("plus", "M12 5v14M5 12h14")
    val check = stroke("check", "m20 6-11 11-5-5")
    val chevronUp = stroke("chevronUp", "m18 15-6-6-6 6")
    val chevronDown = stroke("chevronDown", "m6 9 6 6 6-6")

    private fun stroke(
        name: String,
        path: String,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = null,
                stroke = SolidColor(androidx.compose.ui.graphics.Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ).build()
}
