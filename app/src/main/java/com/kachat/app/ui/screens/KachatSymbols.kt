package com.kachat.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp

/**
 * The .kachat screens' SF Symbols that have no Material counterpart, drawn as original vectors that
 * read the same: a Material base glyph with SF's badge (a plus, a checkmark, an exclamation mark)
 * at its top right, a slash across it, or a ring around it. Apple's symbols themselves may not
 * ship outside Apple platforms. Black on a 24 x 24 grid, so an Icon tints them like any other icon.
 */
object KachatSymbols {
    /** SF `at.badge.plus`: claim / register a name. */
    val AtBadgePlus: ImageVector by lazy { badged("AtBadgePlus", Icons.Default.AlternateEmail) { plusBadge() } }

    /** SF `calendar.badge.plus`: extend a name. */
    val CalendarBadgePlus: ImageVector by lazy { badged("CalendarBadgePlus", Icons.Outlined.CalendarToday) { plusBadge() } }

    /** SF `person.crop.circle.badge.checkmark`: Set as Primary. */
    val PersonCircleBadgeCheckmark: ImageVector by lazy {
        badged("PersonCircleBadgeCheckmark", Icons.Outlined.AccountCircle) {
            moveTo(17f, 4f); lineTo(19f, 6f); lineTo(23f, 1.5f)
        }
    }

    /** SF `person.crop.circle.badge.exclamationmark`: no profile found. */
    val PersonCircleBadgeExclamation: ImageVector by lazy {
        badged("PersonCircleBadgeExclamation", Icons.Outlined.AccountCircle) {
            moveTo(20f, 1f); lineTo(20f, 5f)
            moveTo(20f, 7.8f); lineTo(20f, 7.9f)
        }
    }

    /** SF `tag.slash`: delist a name. */
    val TagSlash: ImageVector by lazy {
        ImageVector.Builder("TagSlash", 24.dp, 24.dp, 24f, 24f).apply {
            addPathsOf(Icons.Outlined.Sell)
            addPath(path { moveTo(3f, 3f); lineTo(21f, 21f) }.nodes(), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round)
        }.build()
    }

    /** SF `at.circle`: no .kachat names yet. */
    val AtCircle: ImageVector by lazy {
        ImageVector.Builder("AtCircle", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                path {
                    circle(12f, 12f, 11f)
                    circle(12f, 12f, 9.5f)
                }.nodes(),
                pathFillType = PathFillType.EvenOdd,
                fill = SolidColor(Color.Black),
            )
            addGroup(scaleX = 0.62f, scaleY = 0.62f, pivotX = 12f, pivotY = 12f)
            addPathsOf(Icons.Default.AlternateEmail)
            clearGroup()
        }.build()
    }

    /** [base] shrunk into the bottom-left three quarters, with [badge] stroked in the top-right corner. */
    private fun badged(name: String, base: ImageVector, badge: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addGroup(scaleX = 0.75f, scaleY = 0.75f, translationY = 6f)
            addPathsOf(base)
            clearGroup()
            addPath(
                PathBuilder().apply(badge).nodes,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    private fun PathBuilder.plusBadge() {
        moveTo(20f, 1f); lineTo(20f, 7f)
        moveTo(17f, 4f); lineTo(23f, 4f)
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    private fun path(block: PathBuilder.() -> Unit): PathBuilder = PathBuilder().apply(block)

    private fun PathBuilder.nodes() = this.nodes

    /** Re-adds every path of [icon] (Material icons are flat lists of filled paths). */
    private fun ImageVector.Builder.addPathsOf(icon: ImageVector) {
        fun walk(group: VectorGroup) {
            for (node in group) {
                when (node) {
                    is VectorPath -> addPath(
                        node.pathData,
                        pathFillType = node.pathFillType,
                        fill = node.fill,
                        fillAlpha = node.fillAlpha,
                        stroke = node.stroke,
                        strokeAlpha = node.strokeAlpha,
                        strokeLineWidth = node.strokeLineWidth,
                        strokeLineCap = node.strokeLineCap,
                        strokeLineJoin = node.strokeLineJoin,
                        strokeLineMiter = node.strokeLineMiter,
                    )
                    is VectorGroup -> walk(node)
                }
            }
        }
        walk(icon.root)
    }
}
