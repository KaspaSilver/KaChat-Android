package com.kachat.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The iOS switch: a 51x31 pill with a white 27pt knob, the accent track when on and the system
 * gray track when off - what every iOS Toggle looks like under the app's accent tint
 * (MainTabView `.tint(.accentColor)`). Material's switch has an outlined track and a knob that
 * shrinks when off; one control for the whole app keeps every toggle the same.
 */
@Composable
fun IosSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val dark = LocalAppColors.current.isDark
    val track by animateColorAsState(
        if (checked) LocalAppColors.current.accent else if (dark) Color(0xFF39393D) else Color(0xFFE9E9EA),
        label = "iosSwitchTrack",
    )
    val knobOffset by animateDpAsState(if (checked) 22.dp else 2.dp, spring(dampingRatio = 0.75f, stiffness = 600f), label = "iosSwitchKnob")
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(width = 51.dp, height = 31.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(CircleShape)
            .background(track)
            .then(
                if (onCheckedChange != null) Modifier.toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    interactionSource = interaction,
                    indication = null,
                    onValueChange = onCheckedChange,
                ) else Modifier
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .size(27.dp)
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape)
        )
    }
}

/**
 * The iOS alert: 270pt wide, 14pt corners, a centred bold title over a centred message, and the
 * buttons as full-width cells under a hairline - Cancel on the left, the action on the right, a
 * hairline between them. Same parameters as Material3's AlertDialog, so every dialog in the app
 * takes the iOS shape without changing what it says or does. [containerColor] is accepted for
 * call-site compatibility; the alert uses the system alert colour as iOS does.
 */
@Composable
fun IosAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") containerColor: Color = Color.Unspecified,
    properties: DialogProperties = DialogProperties(),
) {
    val dark = LocalAppColors.current.isDark
    val background = if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F2)
    val hairline = if (dark) Color(0xFF545458).copy(alpha = 0.65f) else Color(0xFF3C3C43).copy(alpha = 0.29f)
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            modifier = modifier
                .width(270.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 19.dp, bottom = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CompositionLocalProvider(LocalContentColor provides LocalAppColors.current.textPrimary) {
                    if (title != null) {
                        ProvideTextStyle(LocalTextStyle.current.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)) {
                            title()
                        }
                    }
                    if (text != null) {
                        Box(Modifier.padding(top = if (title != null) 4.dp else 0.dp)) {
                            ProvideTextStyle(LocalTextStyle.current.copy(fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 17.sp)) {
                                text()
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(hairline))
            ProvideTextStyle(LocalTextStyle.current.copy(fontSize = 17.sp, textAlign = TextAlign.Center)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 44.dp)) {
                    if (dismissButton != null) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { dismissButton() }
                        Box(Modifier.width(0.5.dp).fillMaxHeight().background(hairline))
                    }
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { confirmButton() }
                }
            }
        }
    }
}

/**
 * The iOS activity indicator: eight rounded spokes, the brightest stepping round, tinted like
 * every ProgressView under the app's accent tint. Replaces Material's sweeping arc everywhere a
 * spinner shows. [strokeWidth] and [trackColor] are accepted for call-site compatibility.
 */
@Composable
fun IosActivityIndicator(
    modifier: Modifier = Modifier,
    color: Color = LocalAppColors.current.textSecondary,
    @Suppress("UNUSED_PARAMETER") strokeWidth: androidx.compose.ui.unit.Dp = 0.dp,
    @Suppress("UNUSED_PARAMETER") trackColor: Color = Color.Unspecified,
) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "iosSpinner")
    val step by transition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(durationMillis = 800, easing = androidx.compose.animation.core.LinearEasing)
        ),
        label = "iosSpinnerStep",
    )
    androidx.compose.foundation.Canvas(modifier.then(Modifier.size(24.dp))) {
        val spokes = 8
        val lead = step.toInt() % spokes
        val radius = size.minDimension / 2f
        val spokeWidth = radius * 0.24f
        for (i in 0 until spokes) {
            // The lead spoke is solid; the ones behind it fade out.
            val age = (lead - i + spokes) % spokes
            val alpha = 1f - age * (0.75f / spokes)
            rotate(degrees = i * 360f / spokes) {
                drawLine(
                    color = color.copy(alpha = color.alpha * alpha),
                    start = androidx.compose.ui.geometry.Offset(center.x, center.y - radius * 0.45f),
                    end = androidx.compose.ui.geometry.Offset(center.x, center.y - radius + spokeWidth / 2f),
                    strokeWidth = spokeWidth,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * The iOS text field: a rounded rectangle with a hairline edge and placeholder text - no
 * Material outline, no floating label. Takes OutlinedTextField's parameters so every field in the
 * app switches shape without changing behaviour; a [label] with no [placeholder] is shown as the
 * placeholder, as an iOS form shows a field's name. [colors] is accepted for call-site
 * compatibility; the field uses the system colours.
 */
@Composable
fun IosTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    @Suppress("UNUSED_PARAMETER") colors: androidx.compose.material3.TextFieldColors? = null,
) {
    val app = LocalAppColors.current
    val shape = RoundedCornerShape(10.dp)
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .clip(shape)
            .border(0.5.dp, if (isError) app.danger else app.divider, shape),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        placeholder = placeholder ?: label,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        prefix = prefix,
        suffix = suffix,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        shape = shape,
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = app.surface,
            unfocusedContainerColor = app.surface,
            disabledContainerColor = app.surface,
            errorContainerColor = app.surface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedTextColor = app.textPrimary,
            unfocusedTextColor = app.textPrimary,
            disabledTextColor = app.textSecondary,
            errorTextColor = app.textPrimary,
            cursorColor = app.accent,
            errorCursorColor = app.danger,
            focusedPlaceholderColor = app.textSecondary,
            unfocusedPlaceholderColor = app.textSecondary,
            focusedLeadingIconColor = app.textSecondary,
            unfocusedLeadingIconColor = app.textSecondary,
            focusedTrailingIconColor = app.textSecondary,
            unfocusedTrailingIconColor = app.textSecondary,
            focusedPrefixColor = app.textSecondary,
            unfocusedPrefixColor = app.textSecondary,
            focusedSuffixColor = app.textSecondary,
            unfocusedSuffixColor = app.textSecondary,
        ),
    )
}

/**
 * The floating glass button iOS puts in a list's corner (new chat, new public room, new KaPost):
 * a 56pt circle of material with a faint white rim and a soft shadow, the symbol in the accent.
 */
@Composable
fun IosGlassFab(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppColors.current
    Box(
        modifier = modifier
            .size(56.dp)
            .shadow(10.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(CircleShape)
            .background(app.surface.copy(alpha = 0.92f))
            .border(0.8.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = contentDescription, tint = app.accent, modifier = Modifier.size(24.dp))
    }
}
