package com.hgtv.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val ColorScheme = darkColorScheme(
    background = Bg,
    surface = Surface,
    surfaceVariant = KeySurface,
    outline = Outline,
    primary = Primary,
    onPrimary = OnPrimary,
    onBackground = OnBg,
    onSurface = OnBg,
    secondary = Success,
    tertiary = SeasonGold,
    error = Warning,
)

// DESIGN.md §4 排版
object HgType {
    val pageTitle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = OnBg)
    val sectionTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = OnBg)
    val body = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, color = OnBg)
    val caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = OnBgVariant)
    val key = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = OnKey)
    val badge = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = OnBgVariant)
}

@Composable
fun HgtvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
