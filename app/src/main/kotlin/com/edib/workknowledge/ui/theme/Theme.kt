package com.edib.workknowledge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Visual direction: a serious document tool, not a chatbot toy.
 *
 * Graphite surfaces, one restrained red accent, tight type scale, minimal
 * corner radii. No gradients, no glow, no oversized rounded cards.
 */
private val Graphite = darkColorScheme(
    primary = Color(0xFFD0453F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4A1512),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFB9B4AE),
    onSecondary = Color(0xFF24211E),
    secondaryContainer = Color(0xFF3A3733),
    onSecondaryContainer = Color(0xFFE5E1DC),
    tertiary = Color(0xFF8F8B85),
    onTertiary = Color(0xFF161412),
    tertiaryContainer = Color(0xFF2A2826),
    onTertiaryContainer = Color(0xFFCFCBC5),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF0B0B0B),
    onBackground = Color(0xFFECEAE7),
    surface = Color(0xFF121211),
    onSurface = Color(0xFFECEAE7),
    surfaceVariant = Color(0xFF1C1B1A),
    onSurfaceVariant = Color(0xFF9E9A94),
    outline = Color(0xFF2E2C2A),
    outlineVariant = Color(0xFF1F1E1D),
    inverseSurface = Color(0xFFECEAE7),
    inverseOnSurface = Color(0xFF232120),
    inversePrimary = Color(0xFFB9312C)
)

private val Paper = lightColorScheme(
    primary = Color(0xFFA8291F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410100),
    secondary = Color(0xFF56504B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEAE2DC),
    onSecondaryContainer = Color(0xFF1B1815),
    tertiary = Color(0xFF716963),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAEBDD),
    onTertiaryContainer = Color(0xFF251D16),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F6F4),
    onBackground = Color(0xFF1B1A19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1A19),
    surfaceVariant = Color(0xFFEDE9E5),
    onSurfaceVariant = Color(0xFF4A4542),
    outline = Color(0xFFBDB8B3),
    outlineVariant = Color(0xFFDDD8D4),
    inverseSurface = Color(0xFF302E2C),
    inverseOnSurface = Color(0xFFF2EFEC),
    inversePrimary = Color(0xFFFFB4AB)
)

/** Dense, legible type scale suited to reading long documents. */
private val WorkTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.2.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    )
)

private val WorkShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp)
)

@Composable
fun WorkKnowledgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) Graphite else Paper,
        typography = WorkTypography,
        shapes = WorkShapes,
        content = content
    )
}
