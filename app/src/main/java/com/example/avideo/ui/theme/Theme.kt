package com.example.avideo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Swiss Internationalist: system font (Roboto), tnum enabled via compose font feature
private val DarkColorScheme = darkColorScheme(
    background   = md_theme_dark_background,
    surface      = md_theme_dark_surface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    outline      = md_theme_dark_outline,
    primary      = md_theme_dark_primary,
    onPrimary    = md_theme_dark_onPrimary,
    onSurface    = md_theme_dark_onSurface,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    error        = md_theme_dark_error,
)

private val LightColorScheme = lightColorScheme(
    background      = md_theme_light_background,
    surface         = md_theme_light_surface,
    surfaceVariant  = md_theme_light_surfaceVariant,
    outline         = md_theme_light_outline,
    primary         = md_theme_light_primary,
    onPrimary       = md_theme_light_onPrimary,
    onSurface       = md_theme_light_onSurface,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
    error           = md_theme_light_error,
)

@Composable
fun AvideoTheme(
    darkTheme: Boolean = true, // dark-first per DESIGN.md §11
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}

// Typography: map strictly to Material 3 type scale roles
val Typography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W400,
        fontSize   = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W500,
        fontSize   = 32.sp,
        lineHeight = 40.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W500,
        fontSize   = 22.sp,
        lineHeight = 28.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W400,
        fontSize   = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W400,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W500,
        fontSize   = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W500,
        fontSize   = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)
