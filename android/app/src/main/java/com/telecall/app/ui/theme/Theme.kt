package com.telecall.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF1B4D89)
private val NavyDark = Color(0xFF12365F)
private val Sky = Color(0xFFD6E4F5)

// Status colours. Deliberately not red/green only — several of these need to
// be distinguishable by agents with colour-vision deficiency, so they differ
// in lightness as well as hue and are always paired with a text label.
val StatusLead = Color(0xFF1B7F4B)
val StatusCallLater = Color(0xFFB26A00)
val StatusNotInterested = Color(0xFF9A3B3B)
val StatusWrongNumber = Color(0xFF6B4F8A)
val StatusSwitchedOff = Color(0xFF5A6472)
val StatusNoAnswer = Color(0xFF2E6DA4)

private val LightColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = Sky,
    onPrimaryContainer = NavyDark,
    secondary = Color(0xFF4A6572),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF1F7),
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC3F0),
    onPrimary = Color(0xFF00325C),
    primaryContainer = Color(0xFF12365F),
    onPrimaryContainer = Sky,
    background = Color(0xFF11151A),
    surface = Color(0xFF171C22),
    surfaceVariant = Color(0xFF232A32)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun TelecallTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
