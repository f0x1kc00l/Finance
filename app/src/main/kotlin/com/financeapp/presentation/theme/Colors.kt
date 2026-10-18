package com.financeapp.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Palette: "ink & violet". A calm neutral base with one brand accent; colour is used for
 * meaning (accent = action, green = under budget / less spending, red = over budget), and
 * every coloured status also has a text or symbol so it never relies on colour alone.
 * All text/background pairs below meet WCAG AA (≥ 4.5:1) for body text.
 */

private val Violet600 = Color(0xFF5B4BD6)
private val Violet300 = Color(0xFFB4AAFF)
private val Ink950 = Color(0xFF0E0E14)
private val Ink900 = Color(0xFF16161F)
private val Ink850 = Color(0xFF1E1E29)
private val Ink800 = Color(0xFF282836)
private val Ink100 = Color(0xFFEDEDF3)
private val Ink050 = Color(0xFFF6F6FA)

val LightColors: ColorScheme = lightColorScheme(
    primary = Violet600,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E2FF),
    onPrimaryContainer = Color(0xFF1C1259),
    secondary = Color(0xFF5E5B71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E1F2),
    onSecondaryContainer = Color(0xFF1B192B),
    background = Ink050,
    onBackground = Color(0xFF1A1A22),
    surface = Ink050,
    onSurface = Color(0xFF1A1A22),
    surfaceVariant = Ink100,
    onSurfaceVariant = Color(0xFF55556A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFBFE),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0F0F6),
    surfaceContainerHighest = Ink100,
    outline = Color(0xFF8A8AA0),
    outlineVariant = Color(0xFFDADAE4),
    error = Color(0xFFC62F3A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD8),
    onErrorContainer = Color(0xFF410006)
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = Violet300,
    onPrimary = Color(0xFF241873),
    primaryContainer = Color(0xFF3C2FA8),
    onPrimaryContainer = Color(0xFFE6E2FF),
    secondary = Color(0xFFC8C4DA),
    onSecondary = Color(0xFF302E40),
    secondaryContainer = Color(0xFF3A3850),
    onSecondaryContainer = Color(0xFFE4E1F2),
    background = Ink950,
    onBackground = Color(0xFFEDEDF5),
    surface = Ink950,
    onSurface = Color(0xFFEDEDF5),
    surfaceVariant = Ink850,
    onSurfaceVariant = Color(0xFFA9A9BE),
    surfaceContainerLowest = Color(0xFF0A0A0F),
    surfaceContainerLow = Color(0xFF131319),
    surfaceContainer = Ink900,
    surfaceContainerHigh = Ink850,
    surfaceContainerHighest = Ink800,
    outline = Color(0xFF77778C),
    outlineVariant = Color(0xFF2E2E3D),
    error = Color(0xFFFF8A85),
    onError = Color(0xFF5C0710),
    errorContainer = Color(0xFF7A1520),
    onErrorContainer = Color(0xFFFFDAD8)
)

/** Semantic colours Material's scheme doesn't cover. */
@Immutable
data class FinanceExtendedColors(
    val positive: Color,
    val onPositiveContainer: Color,
    val positiveContainer: Color,
    val warning: Color,
    val negative: Color
)

val LightExtended = FinanceExtendedColors(
    positive = Color(0xFF17845A),
    positiveContainer = Color(0xFFD5F2E4),
    onPositiveContainer = Color(0xFF00391F),
    warning = Color(0xFF9A6700),
    negative = Color(0xFFC62F3A)
)

val DarkExtended = FinanceExtendedColors(
    positive = Color(0xFF6FD6A6),
    positiveContainer = Color(0xFF0E3B28),
    onPositiveContainer = Color(0xFFC6F2DC),
    warning = Color(0xFFF2C14E),
    negative = Color(0xFFFF8A85)
)

val LocalFinanceColors = staticCompositionLocalOf { LightExtended }
