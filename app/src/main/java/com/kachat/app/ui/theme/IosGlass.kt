package com.kachat.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * SwiftUI's `.shadow(color:radius:x:y:)` under a rounded card: [color] blurred by [radius] and
 * dropped by [y], drawn behind the card. Drawn with the platform's shadow layer, which hardware
 * rendering supports on shapes from Android 9; on 8 the card simply has none.
 */
fun Modifier.iosShadow(cornerRadius: Dp, color: Color, radius: Dp, y: Dp): Modifier = drawBehind {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.TRANSPARENT
        setShadowLayer(radius.toPx(), 0f, y.toPx(), color.toArgb())
    }
    val r = cornerRadius.toPx()
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint) }
}

/**
 * The app's frosted card as iOS draws it with `glassBackground` (the action sheet rows and tiles,
 * Cold Storage's and the chats' cards): `.regularMaterial` in a [cornerRadius] rounded rectangle,
 * a 0.8 white hairline at 18%, and a soft shadow (black at 12%, radius 10, 5 down - iOS
 * `kachatGlass` uses 10%, 8 and 4). The material is [AppColors.surface]: a blur of what lies
 * beneath cannot be drawn on older Android, so each surface carries a solid stand-in for it
 * (see [LightPlainSheetAppColors]).
 */
@Composable
fun Modifier.iosGlass(
    cornerRadius: Dp,
    shadowAlpha: Float = 0.12f,
    shadowRadius: Dp = 10.dp,
    shadowY: Dp = 5.dp,
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .iosShadow(cornerRadius, Color.Black.copy(alpha = shadowAlpha), shadowRadius, shadowY)
        .clip(shape)
        .background(LocalAppColors.current.surface)
        .border(0.8.dp, Color.White.copy(alpha = 0.18f), shape)
}
