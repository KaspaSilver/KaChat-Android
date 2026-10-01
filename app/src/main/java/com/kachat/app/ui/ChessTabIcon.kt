package com.kachat.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.size
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

/**
 * The ".kachat" wordmark as a tintable bitmap - the Kaspa Hub tile, the dock item and Customize
 * Dock draw it the way they draw the chess pieces (iOS KachatTabIcon). Black on transparent; the
 * word is wider than it is tall.
 */
private val kachatWordmarkBitmap by lazy {
    val side = 96
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = side * 0.62f
        // iOS draws it heavy (UIFont .heavy, rounded design); black is the heaviest system weight.
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    val text = ".kachat"
    val width = maxOf(paint.measureText(text).toInt() + 8, side)
    val bitmap = Bitmap.createBitmap(width, side, Bitmap.Config.ARGB_8888)
    val metrics = paint.fontMetrics
    val baseline = side / 2f - (metrics.ascent + metrics.descent) / 2f
    Canvas(bitmap).drawText(text, width / 2f, baseline, paint)
    bitmap.asImageBitmap()
}

/**
 * The box a tab's icon is drawn in, [size] tall. The chess pieces and the .kachat wordmark are
 * wider than they are tall, and iOS draws them at the full height with their natural width
 * (ChessTabIcon / KachatTabIcon `image(side:)`); a [size] square shrank them to fit its width,
 * which left the wordmark a sliver.
 */
fun Screen.tabIconModifier(size: androidx.compose.ui.unit.Dp): androidx.compose.ui.Modifier = when (this) {
    Screen.Chess -> androidx.compose.ui.Modifier.size(width = size * (chessIconBitmap.width.toFloat() / chessIconBitmap.height), height = size)
    Screen.KachatNames -> androidx.compose.ui.Modifier.size(width = size * (kachatWordmarkBitmap.width.toFloat() / kachatWordmarkBitmap.height), height = size)
    else -> androidx.compose.ui.Modifier.size(size)
}

/** The painter a tab's icon is drawn with: the Kaspa mark, the chess pieces, the .kachat
 *  wordmark, or its vector. */
@Composable
fun Screen.tabIconPainter(): Painter = when {
    usesKaspaLogo -> painterResource(com.kachat.app.R.drawable.ic_kaspa_logo)
    this == Screen.Chess -> remember { BitmapPainter(chessIconBitmap) }
    this == Screen.KachatNames -> remember { BitmapPainter(kachatWordmarkBitmap) }
    else -> rememberVectorPainter(icon)
}
