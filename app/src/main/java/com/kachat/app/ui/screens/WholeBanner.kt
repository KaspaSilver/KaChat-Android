package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage

/**
 * A profile banner shown whole: full width at the picture's own proportions (held to 1.5:1 to
 * 8:1, the picture fitted inside), instead of a fixed height that cropped it (iOS c66bfc7,
 * KNSBannerImageView `fitsWidth`). Until the picture has loaded - or when it can't - the canvas is
 * [placeholderHeight] tall and shows [placeholder].
 */
@Composable
fun WholeBanner(
    model: Any?,
    placeholderHeight: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    placeholder: @Composable () -> Unit,
) {
    var aspect by remember(model) { mutableStateOf<Float?>(null) }
    val sized = aspect?.let { Modifier.aspectRatio(it) } ?: Modifier.height(placeholderHeight)
    Box(modifier.fillMaxWidth().then(sized).clip(shape)) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            loading = { Box(Modifier.fillMaxSize()) { placeholder() } },
            error = { Box(Modifier.fillMaxSize()) { placeholder() } },
            onSuccess = { state: AsyncImagePainter.State.Success ->
                val size = state.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) {
                    aspect = (size.width / size.height).coerceIn(MIN_BANNER_ASPECT, MAX_BANNER_ASPECT)
                }
            },
        )
    }
}

private const val MIN_BANNER_ASPECT = 1.5f
private const val MAX_BANNER_ASPECT = 8f
