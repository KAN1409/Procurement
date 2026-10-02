package com.edib.procurement.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Dark graphite with restrained red accents.
 *
 * No gradients, tight type scale, dense spacing: this is a document-control
 * tool that people scan quickly, not a consumer drive UI.
 */
object ProcurementColors {
    val Graphite900 = Color(0xFF14161A)
    val Graphite850 = Color(0xFF1A1D22)
    val Graphite800 = Color(0xFF20242A)
    val Graphite700 = Color(0xFF2A2F36)
    val Graphite600 = Color(0xFF363C45)
    val Graphite500 = Color(0xFF4A515C)

    val TextPrimary = Color(0xFFE6E8EB)
    val TextSecondary = Color(0xFFA0A7B0)
    val TextTertiary = Color(0xFF6E7681)

    val AccentRed = Color(0xFFC0392B)
    val AccentRedBright = Color(0xFFE74C3C)
    val AccentRedDim = Color(0xFF7E2318)

    val StatusOk = Color(0xFF4C9A6A)
    val StatusWarn = Color(0xFFC9A227)
    val StatusInfo = Color(0xFF4A7FB5)
}

private val Scheme = darkColorScheme(
    primary = ProcurementColors.AccentRedBright,
    onPrimary = Color.White,
    primaryContainer = ProcurementColors.AccentRedDim,
    onPrimaryContainer = ProcurementColors.TextPrimary,
    secondary = ProcurementColors.StatusInfo,
    onSecondary = Color.White,
    background = ProcurementColors.Graphite900,
    onBackground = ProcurementColors.TextPrimary,
    surface = ProcurementColors.Graphite850,
    onSurface = ProcurementColors.TextPrimary,
    surfaceVariant = ProcurementColors.Graphite700,
    onSurfaceVariant = ProcurementColors.TextSecondary,
    outline = ProcurementColors.Graphite600,
    outlineVariant = ProcurementColors.Graphite700,
    error = ProcurementColors.AccentRedBright,
    onError = Color.White,
    errorContainer = ProcurementColors.AccentRedDim,
    onErrorContainer = ProcurementColors.TextPrimary
)

/** Compact type scale: nothing oversized, everything readable at density. */
private val ProcurementTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 26.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 19.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 17.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 19.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp, lineHeight = 15.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp, lineHeight = 16.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 10.sp, lineHeight = 13.sp
    )
)

@Composable
fun ProcurementTheme(content: @Composable () -> Unit) {
    // Always dark: the palette is the product's identity here, and a light
    // variant would dilute the restrained document-control look.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = Scheme,
        typography = ProcurementTypography,
        content = content
    )
}
