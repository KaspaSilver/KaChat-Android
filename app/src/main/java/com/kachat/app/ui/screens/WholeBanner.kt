package com.kachat.app.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.imageLoader
import coil.memory.MemoryCache

/**
 * A profile banner shown whole: full width at the picture's own proportions (held to 1.5:1 to
 * 8:1, the picture fitted inside), instead of a fixed height that cropped it (iOS c66bfc7,
 * KNSBannerImageView `fitsWidth`). Until the picture has loaded - or when it can't - the canvas is
 * [placeholderHeight] tall and shows [placeholder]; one already decoded in memory shows at once.
 */
@Composable
fun WholeBanner(
    model: Any?,
    placeholderHeight: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    placeholder: @Composable () -> Unit,
) {
    // Already decoded in memory: shown, at its proportions, on this very frame (iOS d17d208). A
    // LazyColumn drops a row scrolled out of view, so without this a banner coming back flashed
    // its placeholder at the placeholder height until Coil answered.
    val context = LocalContext.current
    val memoryImage = remember(model) {
        (model as? String)?.let { context.imageLoader.memoryCache?.get(MemoryCache.Key(it))?.bitmap }
    }
    var aspect by remember(model) { mutableStateOf(memoryImage?.let { bannerAspect(it.width.toFloat(), it.height.toFloat()) }) }
    val sized = aspect?.let { Modifier.aspectRatio(it) } ?: Modifier.height(placeholderHeight)
    // A new request once User Info > Rescan Profile downloaded this banner again (iOS bc95d17).
    val refetched = rememberProfileImageModel(model as? String)
    Box(modifier.fillMaxWidth().then(sized).clip(shape)) {
        SubcomposeAsyncImage(
            model = if (model is String) refetched else model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            // While a rescanned banner reloads, the cached picture stays up rather than the
            // placeholder (its placeholder is the picture itself).
            loading = { state ->
                if (state.painter != null) SubcomposeAsyncImageContent(Modifier.fillMaxSize())
                else if (memoryImage != null) {
                    Image(memoryImage.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else Box(Modifier.fillMaxSize()) { placeholder() }
            },
            // A failed (re)load still shows the picture already in memory, as iOS's
            // `loadedImage ?? memoryImage`; only with nothing in memory is it the placeholder.
            error = {
                if (memoryImage != null) {
                    Image(memoryImage.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else Box(Modifier.fillMaxSize()) { placeholder() }
            },
            onSuccess = { state: AsyncImagePainter.State.Success ->
                val size = state.painter.intrinsicSize
                bannerAspect(size.width, size.height)?.let { aspect = it }
            },
        )
    }
}

/** A picture's proportions held to 1.5:1 to 8:1; null when it has no size. */
private fun bannerAspect(width: Float, height: Float): Float? =
    if (width > 0f && height > 0f) (width / height).coerceIn(MIN_BANNER_ASPECT, MAX_BANNER_ASPECT) else null

private const val MIN_BANNER_ASPECT = 1.5f
private const val MAX_BANNER_ASPECT = 8f
