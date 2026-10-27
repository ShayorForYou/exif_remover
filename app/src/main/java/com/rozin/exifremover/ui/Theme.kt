package com.rozin.exifremover.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rozin.exifremover.core.ThemeMode

// Neutral zinc greys with near-black actions. The only hue is a muted clay, reserved for location.

private val LightColors = lightColorScheme(
    primary = Color(0xFF18181B),
    onPrimary = Color(0xFFFAFAFA),
    primaryContainer = Color(0xFFF1F1F3),
    onPrimaryContainer = Color(0xFF18181B),
    secondary = Color(0xFF52525B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1F1F3),
    onSecondaryContainer = Color(0xFF27272A),
    tertiary = Color(0xFFA4553F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF7EFEC),
    onTertiaryContainer = Color(0xFF7A3B2A),
    error = Color(0xFFB42318),
    onError = Color.White,
    errorContainer = Color(0xFFFBEFEE),
    onErrorContainer = Color(0xFF8A1C12),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF18181B),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFF1F1F3),
    onSurfaceVariant = Color(0xFF71717A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF4F4F5),
    surfaceContainerHigh = Color(0xFFEDEDEF),
    surfaceContainerHighest = Color(0xFFE4E4E7),
    outline = Color(0xFFA1A1AA),
    outlineVariant = Color(0xFFE7E7EA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFEDEDEF),
    onPrimary = Color(0xFF18181B),
    primaryContainer = Color(0xFF232326),
    onPrimaryContainer = Color(0xFFEDEDEF),
    secondary = Color(0xFFA1A1AA),
    onSecondary = Color(0xFF18181B),
    secondaryContainer = Color(0xFF232326),
    onSecondaryContainer = Color(0xFFE4E4E7),
    tertiary = Color(0xFFD9A594),
    onTertiary = Color(0xFF3B1D14),
    tertiaryContainer = Color(0xFF2B211E),
    onTertiaryContainer = Color(0xFFE8C2B6),
    error = Color(0xFFF2A39B),
    onError = Color(0xFF3B0A06),
    errorContainer = Color(0xFF2C1917),
    onErrorContainer = Color(0xFFF2B8B1),
    background = Color(0xFF0C0C0E),
    onBackground = Color(0xFFEDEDEF),
    surface = Color(0xFF0C0C0E),
    onSurface = Color(0xFFEDEDEF),
    surfaceVariant = Color(0xFF1C1C1F),
    onSurfaceVariant = Color(0xFF8E8E96),
    surfaceContainerLowest = Color(0xFF09090B),
    surfaceContainerLow = Color(0xFF141416),
    surfaceContainer = Color(0xFF19191C),
    surfaceContainerHigh = Color(0xFF202023),
    surfaceContainerHighest = Color(0xFF29292D),
    outline = Color(0xFF5A5A62),
    outlineVariant = Color(0xFF26262A),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.Medium),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/** Whether [mode] resolves to dark right now; SYSTEM follows the device setting live. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun AppTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (mode.isDark()) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
