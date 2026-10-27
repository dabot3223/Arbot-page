package com.arbot.game.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Neon {
    val Space = Color(0xFF070B1A)
    val SpaceLight = Color(0xFF111833)
    val Panel = Color(0xCC0E1530)
    val Cyan = Color(0xFF22E4FF)
    val Green = Color(0xFF39FF88)
    val Magenta = Color(0xFFFF3DCB)
    val Purple = Color(0xFFA855F7)
    val Gold = Color(0xFFFFC93C)
    val Red = Color(0xFFFF4D5E)
    val Orange = Color(0xFFFF8A3D)
    val Text = Color(0xFFE8F0FF)
    val TextDim = Color(0xFF8D9BC2)
}

val Mono = FontFamily.Monospace

private val scheme = darkColorScheme(
    primary = Neon.Green,
    secondary = Neon.Cyan,
    tertiary = Neon.Magenta,
    background = Neon.Space,
    surface = Neon.SpaceLight,
    onPrimary = Neon.Space,
    onSecondary = Neon.Space,
    onBackground = Neon.Text,
    onSurface = Neon.Text,
    error = Neon.Red,
)

@Composable
fun ArbotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = MaterialTheme.typography.copy(
            bodyMedium = TextStyle(fontFamily = Mono, fontSize = 14.sp, color = Neon.Text),
            titleLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Neon.Text),
        ),
        content = content,
    )
}
