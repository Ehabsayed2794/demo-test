package com.estemshan.game.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme

/**
 * Estemshan brand theme (ui-ux-pro-max: Luxury black-gold + Dark Mode
 * OLED). Tokens mirror the web client exactly so both platforms read as
 * one product: near-black felt background, gold accent, warm ink text.
 * System font families only (serif ≈ Marcellus display tone, sans-serif
 * ≈ Saira body, monospace ≈ Spline Sans numbers) — no font downloads,
 * no new dependencies.
 */
object EstemshanColors {
  val Background = Color(0xFF0D0A07)
  val Surface = Color(0xFF17130E)
  val SurfaceHigh = Color(0xFF1D1912)
  val Gold = Color(0xFFE8A33D)
  val GoldDim = Color(0xFFA8742A)
  val Ink = Color(0xFFF0EADA)
  val InkDim = Color(0xFFA89F8E)
  val Error = Color(0xFFF25555)
}

private val EstemshanScheme = darkColorScheme(
  primary = EstemshanColors.Gold,
  onPrimary = Color.Black,
  secondary = EstemshanColors.GoldDim,
  onSecondary = EstemshanColors.Ink,
  background = EstemshanColors.Background,
  onBackground = EstemshanColors.Ink,
  surface = EstemshanColors.Surface,
  onSurface = EstemshanColors.Ink,
  surfaceVariant = EstemshanColors.SurfaceHigh,
  onSurfaceVariant = EstemshanColors.InkDim,
  error = EstemshanColors.Error,
  onError = Color.Black,
)

private val EstemshanTypography = Typography(
  displayLarge = TextStyle(
    fontFamily = FontFamily.Serif,
    fontSize = 40.sp,
    lineHeight = 44.sp,
    color = EstemshanColors.Ink,
  ),
  headlineMedium = TextStyle(
    fontFamily = FontFamily.Serif,
    fontSize = 26.sp,
    lineHeight = 30.sp,
    color = EstemshanColors.Ink,
  ),
  titleMedium = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 17.sp,
    lineHeight = 22.sp,
    color = EstemshanColors.Ink,
  ),
  bodyLarge = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    color = EstemshanColors.Ink,
  ),
  bodyMedium = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontSize = 14.sp,
    lineHeight = 19.sp,
    color = EstemshanColors.InkDim,
  ),
  labelLarge = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 17.sp,
    color = EstemshanColors.InkDim,
  ),
)

/** Single theme entry point — every screen composes inside this. */
@Composable
fun EstemshanTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = EstemshanScheme,
    typography = EstemshanTypography,
    content = content,
  )
}
