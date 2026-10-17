package com.kachat.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp

/**
 * SF Symbols that Material has no single icon for, put together from Material's own paths so
 * they tint and size like any other icon.
 */
object IosSymbols {
    /** "lock.shield": a shield outline with a solid lock inside. */
    val LockShield: ImageVector by lazy {
        val builder = ImageVector.Builder(
            name = "LockShield",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        fun addPaths(group: VectorGroup) {
            for (node in group) {
                when (node) {
                    is VectorPath -> builder.addPath(
                        pathData = node.pathData,
                        pathFillType = node.pathFillType,
                        fill = SolidColor(Color.Black),
                    )
                    is VectorGroup -> addPaths(node)
                }
            }
        }
        addPaths(Icons.Outlined.Shield.root)
        builder.addGroup(scaleX = 0.4f, scaleY = 0.4f, pivotX = 12f, pivotY = 11.5f)
        addPaths(Icons.Filled.Lock.root)
        builder.clearGroup()
        builder.build()
    }
}
