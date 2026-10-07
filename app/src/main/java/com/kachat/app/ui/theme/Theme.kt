package com.kachat.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// KaChat brand colors — inspired by Kaspa's blue/teal palette
// iOS's AccentColor asset exactly: sRGB (0.439, 0.780, 0.729) = #70C7BA, the same in both themes.
val KaspaBlue    = Color(0xFF70C7BA)
val KaspaTeal    = Color(0xFF70C7BA)
val KaspaDark    = Color(0xFF000000) // True black background for iOS look
val KaspaNavy    = Color(0xFF121212) // Slightly lighter black for surfaces
val KaspaCard    = Color(0xFF1E1E1E) // For "Saved Accounts" card style
val KaspaBorder  = Color(0xFF333333)
val KaspaText    = Color(0xFFFFFFFF)
val KaspaSubtext = Color(0xFF8D8D93)
val KaspaError   = Color(0xFFFC8181)

private val DarkColorScheme = darkColorScheme(
    primary          = KaspaTeal,
    onPrimary        = Color.Black,
    primaryContainer = KaspaTeal.copy(alpha = 0.2f),
    secondary        = KaspaTeal,
    onSecondary      = Color.Black,
    background       = Color.Black,
    onBackground     = Color.White,
    surface          = Color(0xFF1C1C1E),
    onSurface        = Color.White,
    surfaceVariant   = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF8D8D93),
    // Sheets, menus and dialogs read these; Material's defaults are its purple-tinted baseline.
    surfaceTint      = Color.Transparent,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF2C2C2E),
    surfaceBright    = Color(0xFF2C2C2E),
    surfaceDim       = Color.Black,
    inverseSurface   = Color.White,
    inverseOnSurface = Color.Black,
    inversePrimary   = KaspaTeal,
    tertiary         = KaspaTeal,
    onTertiary       = Color.Black,
    outline          = Color(0xFF38383A),
    outlineVariant   = Color(0xFF38383A),
    error            = Color(0xFFFF453A),
)

// Whatever Material component still reads the scheme gets iOS's colours: the accent in both themes
// (iOS has one AccentColor), the grouped backgrounds, label colours and systemRed.
private val LightColorScheme = lightColorScheme(
    primary          = KaspaTeal,
    onPrimary        = Color.Black,
    primaryContainer = KaspaTeal.copy(alpha = 0.2f),
    secondary        = KaspaTeal,
    onSecondary      = Color.Black,
    background       = Color(0xFFF2F2F7),
    onBackground     = Color.Black,
    surface          = Color.White,
    onSurface        = Color.Black,
    surfaceVariant   = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF8A8A8E),
    surfaceTint      = Color.Transparent,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF2F2F7),
    surfaceContainerHighest = Color(0xFFE5E5EA),
    surfaceBright    = Color.White,
    surfaceDim       = Color(0xFFF2F2F7),
    inverseSurface   = Color(0xFF1C1C1E),
    inverseOnSurface = Color.White,
    inversePrimary   = KaspaTeal,
    tertiary         = KaspaTeal,
    onTertiary       = Color.Black,
    outline          = Color(0xFFC6C6C8),
    outlineVariant   = Color(0xFFC6C6C8),
    error            = Color(0xFFFF3B30),
)

/**
 * App-specific semantic color roles, alongside (not instead of) Material3's [ColorScheme] — this
 * app's screens were all built against a fixed small palette of dark-mode literals (`Color.Black`,
 * `Color(0xFF1C1C1E)` cards, `Color.White`/`Color.Gray` text) rather than `MaterialTheme.colorScheme`,
 * so retrofitting light mode means giving every screen a themed equivalent of *that* palette
 * specifically, not a generic Material3 remap. `textOnAccent`/`accent` are deliberately identical
 * in both schemes: KaspaTeal itself doesn't change with the theme, and text/icons drawn on top of
 * it (e.g. the Swap button's label) need to stay dark for contrast either way.
 */
data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /** iOS `tertiaryLabel` - placeholders and the faintest captions. */
    val textTertiary: Color,
    val divider: Color,
    val accent: Color,
    val textOnAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** Your own chat bubble. Deliberately NOT [accent]: iOS darkens it in dark mode (a bright
     *  teal block behind white text is a glare on a black screen) and uses the light teal only in
     *  light mode - see iOS's `OutgoingBubble.color`, which these two values copy exactly. */
    val outgoingBubble: Color,
    /** Text and icons drawn on [outgoingBubble] - white in both themes, as on iOS. */
    val onOutgoingBubble: Color,
    /** The other person's chat bubble - iOS's `systemGray5` in each theme. */
    val incomingBubble: Color,
    /** Which appearance these colours are for - the sheet palette below keeps it. */
    val isDark: Boolean = false,
)

// iOS system colours, dark appearance: systemGroupedBackground / secondarySystemGroupedBackground /
// tertiary, label / secondaryLabel / tertiaryLabel (as they render on black), the opaque separator,
// and systemGreen / systemOrange / systemRed.
val DarkAppColors = AppColors(
    background     = Color.Black,
    surface        = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF2C2C2E),
    textPrimary    = Color.White,
    textSecondary  = Color(0xFF8D8D93),
    textTertiary   = Color(0xFF48484A),
    divider        = Color(0xFF38383A),
    accent         = KaspaTeal,
    textOnAccent   = Color.Black,
    success        = Color(0xFF30D158),
    warning        = Color(0xFFFF9F0A),
    danger         = Color(0xFFFF453A),
    outgoingBubble = Color(0xFF167368),
    onOutgoingBubble = Color.White,
    incomingBubble = Color(0xFF2C2C2E),
    isDark         = true,
)

/**
 * What a screen inside an iOS sheet is drawn with in dark mode: the sheet itself is the elevated
 * grouped background (#1C1C1E) and its cards the elevated secondary (#2C2C2E) - Settings is a
 * sheet on iOS, so its whole stack reads one step lighter than a tab. Light mode's sheets are
 * [LightPlainSheetAppColors] or, for a List or Form, the grouped #F2F2F7 behind white rows.
 */
val DarkSheetAppColors = DarkAppColors.copy(
    background     = Color(0xFF1C1C1E),
    surface        = Color(0xFF2C2C2E),
    surfaceVariant = Color(0xFF3A3A3C),
    divider        = Color(0xFF3D3D41),
)

/**
 * The top corners of every sheet: iOS's sheets round theirs at about 10pt, where Material's
 * default is 28dp.
 */
val IosSheetShape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)

/**
 * The tonal elevation every sheet passes to ModalBottomSheet. Material tints a sheet whose colour
 * is the scheme's surface by its elevation, and with this app's transparent surfaceTint that tint
 * is 5% black: light mode's white sheet ([LightPlainSheetAppColors]) would read #F2F2F2. iOS's is
 * white, so light mode takes none. Dark mode keeps Material's default, as it has rendered so far.
 */
val IosSheetTonalElevation: androidx.compose.ui.unit.Dp
    @Composable get() = if (LocalAppColors.current.isDark) 1.dp else 0.dp // 1 = BottomSheetDefaults.Elevation

/**
 * iOS's `.regularMaterial` as it reads on a white sheet in light mode: a faint neutral grey. iOS
 * blurs whatever lies beneath the card; older Android cannot blur the content under a view, so
 * this solid colour stands in for it. Its shape comes from the hairline and shadow that go with
 * it ([iosGlass]).
 */
val IosRegularMaterialLight = Color(0xFFF4F4F4)

/**
 * Draws [content] with the iOS sheet palette. Dark mode: the raised sheet colours
 * ([DarkSheetAppColors]) either way. Light mode: a sheet of plain content is white with
 * material-coloured cards ([LightPlainSheetAppColors]); a [grouped] one - iOS's List or Form in a
 * sheet - keeps the grouped background (#F2F2F7) behind white rows.
 */
@Composable
fun IosSheetColors(grouped: Boolean = false, content: @Composable () -> Unit) {
    val current = LocalAppColors.current
    val colors = when {
        current.isDark -> DarkSheetAppColors
        grouped -> current
        else -> LightPlainSheetAppColors
    }
    CompositionLocalProvider(LocalAppColors provides colors, content = content)
}

// The same iOS system colours, light appearance.
val LightAppColors = AppColors(
    background     = Color(0xFFF2F2F7),
    surface        = Color.White,
    surfaceVariant = Color(0xFFE5E5EA),
    textPrimary    = Color.Black,
    textSecondary  = Color(0xFF8A8A8E),
    textTertiary   = Color(0xFFC4C4C6),
    divider        = Color(0xFFC6C6C8),
    accent         = KaspaTeal,
    textOnAccent   = Color.Black,
    success        = Color(0xFF34C759),
    warning        = Color(0xFFFF9500),
    danger         = Color(0xFFFF3B30),
    outgoingBubble = Color(0xFF70C7BA),
    onOutgoingBubble = Color.White,
    incomingBubble = Color(0xFFE5E5EA)
)

/**
 * Light mode inside a sheet whose content is plain views (a VStack or ScrollView, not a List or
 * Form): iOS draws such a sheet on `systemBackground` - white - and its frosted cards and tiles
 * (`.regularMaterial`) a shade under it ([IosRegularMaterialLight]), each with a soft shadow.
 */
val LightPlainSheetAppColors = LightAppColors.copy(
    background = Color.White,
    surface = IosRegularMaterialLight,
)

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

/** Shorthand for `LocalAppColors.current` — mirrors the `MaterialTheme.colorScheme` accessor pattern. */
val MaterialTheme.appColors: AppColors
    @Composable get() = LocalAppColors.current

@Composable
fun KaChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // disabled — we use brand colors
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // Dynamic color available on Android 12+, but we opt out to keep brand identity
            if (darkTheme) DarkColorScheme else LightColorScheme
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge is enabled in MainActivity via enableEdgeToEdge(): the system bars are
            // transparent and content draws behind them, so the app's own background shows through.
            // Setting window.statusBarColor/navigationBarColor is deprecated (a no-op under
            // edge-to-edge on Android 15+/API 35) and is what Play's pre-launch report flags, so we
            // only drive the system-bar icon contrast here.
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalAppColors provides if (darkTheme) DarkAppColors else LightAppColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = KaChatTypography,
            content = content
        )
    }
}
