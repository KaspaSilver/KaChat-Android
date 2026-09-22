package com.kachat.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource

/**
 * The Chess section's icon: a rook and a knight, side by side (iOS 6fd5248). The Material set has
 * no chess pieces, so the two glyphs are drawn once into a bitmap - black on transparent, so an
 * Icon's tint colours it like any other symbol - and scaled to whatever size each place asks for.
 */
private val chessIconBitmap by lazy {
    val side = 96
    val width = (side * 1.55f).toInt()
    val bitmap = Bitmap.createBitmap(width, side, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = side * 1.08f
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
    }
    // U+FE0E asks for the text glyphs, never an emoji rendering.
    val text = "♜︎♞︎"
    val metrics = paint.fontMetrics
    val baseline = side / 2f - (metrics.ascent + metrics.descent) / 2f
    Canvas(bitmap).drawText(text, width / 2f, baseline, paint)
    bitmap.asImageBitmap()
}

/** The painter a tab's icon is drawn with: the Kaspa mark, the chess pieces, or its vector. */
@Composable
fun Screen.tabIconPainter(): Painter = when {
    usesKaspaLogo -> painterResource(com.kachat.app.R.drawable.ic_kaspa_logo)
    this == Screen.Chess -> remember { BitmapPainter(chessIconBitmap) }
    else -> rememberVectorPainter(icon)
}
