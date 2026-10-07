package com.kachat.app.ui.screens

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import com.kachat.app.util.MiddleTruncation

/**
 * Text that gives way in the MIDDLE when it does not fit in [maxLines] - iOS's
 * `.lineLimit(n).truncationMode(.middle)`, for addresses, transaction ids and the names in "to …"
 * lines, whose ends identify them. Measured against the width it is given, so it keeps as much of
 * each end as the space allows (see [MiddleTruncation]).
 */
@Composable
fun MiddleEllipsisText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
    textDecoration: TextDecoration? = null,
    maxLines: Int = 1,
    style: TextStyle = LocalTextStyle.current,
) {
    val measurer = rememberTextMeasurer()
    val merged = style.merge(
        TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            textAlign = textAlign ?: TextAlign.Unspecified,
            textDecoration = textDecoration,
        )
    )
    BoxWithConstraints(modifier) {
        val maxWidth = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val shown = remember(text, maxWidth, bounded, merged, maxLines) {
            if (!bounded) {
                text
            } else {
                MiddleTruncation.fit(text) { candidate ->
                    !measurer.measure(
                        AnnotatedString(candidate),
                        style = merged,
                        overflow = TextOverflow.Clip,
                        softWrap = maxLines > 1,
                        maxLines = maxLines,
                        constraints = Constraints(maxWidth = maxWidth),
                    ).hasVisualOverflow
                }
            }
        }
        Text(
            shown,
            style = merged,
            maxLines = maxLines,
            softWrap = maxLines > 1,
            overflow = TextOverflow.Clip,
            modifier = if (textAlign != null) Modifier.fillMaxWidth() else Modifier,
        )
    }
}
