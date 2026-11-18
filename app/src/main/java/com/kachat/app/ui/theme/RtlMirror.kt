package com.kachat.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.LayoutDirection

/**
 * Flips a left/right glyph in a right-to-left layout (Arabic, Persian, Hebrew), as iOS's
 * `chevron.backward` / `chevron.right` SF Symbols flip on their own. For glyphs with no
 * auto-mirrored Material version (`ArrowBackIosNew`, `ChevronRight`) and chevrons drawn on a
 * Canvas. Drawing only: layout and touch are untouched.
 */
fun Modifier.mirrorInRtl(): Modifier = this.drawWithContent {
    if (layoutDirection == LayoutDirection.Rtl) {
        scale(scaleX = -1f, scaleY = 1f) { this@drawWithContent.drawContent() }
    } else {
        drawContent()
    }
}

/** True for the directional icons the app uses that Material doesn't mirror itself, so a shared
 *  button (a back chevron on a bar) can flip them with [mirrorInRtl]. */
fun ImageVector.needsRtlMirror(): Boolean =
    this == Icons.Filled.ArrowBackIosNew || this == Icons.Filled.ChevronRight || this == Icons.Filled.ChevronLeft

/** [mirrorInRtl] when [icon] is one of the directional icons in [needsRtlMirror]. */
fun Modifier.mirrorInRtlIfDirectional(icon: ImageVector): Modifier =
    if (icon.needsRtlMirror()) mirrorInRtl() else this
