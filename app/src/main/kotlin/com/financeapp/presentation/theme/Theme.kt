package com.financeapp.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Spacing scale (4-pt grid). */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Horizontal screen gutter. */
    val gutter = 20.dp
    /** Minimum touch target (Material / WCAG). */
    val touch = 48.dp
}

private val Base = Typography()

/** Tabular figures keep columns of amounts aligned and stop digits jittering while typing. */
private const val TABULAR = "tnum"

val FinanceTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR, letterSpacing = (-0.5).sp),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR, letterSpacing = (-0.5).sp),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.Medium),
    bodyLarge = Base.bodyLarge,
    bodyMedium = Base.bodyMedium,
    bodySmall = Base.bodySmall,
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Medium),
    labelMedium = Base.labelMedium,
    labelSmall = Base.labelSmall
)

/** Text style for money in lists and cards. */
val AmountStyle = TextStyle(fontFeatureSettings = TABULAR, fontWeight = FontWeight.SemiBold)

val FinanceShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun FinanceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalFinanceColors provides if (darkTheme) DarkExtended else LightExtended) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = FinanceTypography,
            shapes = FinanceShapes,
            content = content
        )
    }
}

object FinanceTheme {
    val extended: FinanceExtendedColors
        @Composable @ReadOnlyComposable get() = LocalFinanceColors.current
}

/** ARGB long stored on Category → Compose colour. */
fun categoryColor(argb: Long): Color = Color(argb)
