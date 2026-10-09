package com.estemshan.game.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import com.estemshan.game.R

/**
 * Estemshan brand theme (ui-ux-pro-max: Luxury black-gold + Dark Mode
 * OLED). Tokens mirror the web client exactly so both platforms read as
 * one product: near-black felt background, gold accent, warm ink text.
 *
 * Typefaces are the design-system faces, bundled in res/font (all OFL,
 * fetched from Google Fonts): Marcellus display, Saira body
 * (400/500/600/700), Spline Sans Mono numerals (400/500/600/700), and
 * Noto Naskh Arabic (500/600) as the Arabic face — the Ranked
 * matchmaking spec names it explicitly for rank titles, chained as a
 * glyph fallback after the Latin faces exactly like the CSS stacks.
 */
val DisplayFamily = FontFamily(
  Font(R.font.marcellus_regular, FontWeight.Normal),
  Font(R.font.noto_naskh_arabic_medium, FontWeight.Medium),
  Font(R.font.noto_naskh_arabic_semibold, FontWeight.SemiBold),
)

val BodyFamily = FontFamily(
  Font(R.font.saira_regular, FontWeight.Normal),
  Font(R.font.saira_medium, FontWeight.Medium),
  Font(R.font.saira_semibold, FontWeight.SemiBold),
  Font(R.font.saira_bold, FontWeight.Bold),
  Font(R.font.noto_naskh_arabic_medium, FontWeight.Medium),
  Font(R.font.noto_naskh_arabic_semibold, FontWeight.SemiBold),
)

val MonoFamily = FontFamily(
  Font(R.font.spline_sans_mono_regular, FontWeight.Normal),
  Font(R.font.spline_sans_mono_medium, FontWeight.Medium),
  Font(R.font.spline_sans_mono_semibold, FontWeight.SemiBold),
  Font(R.font.spline_sans_mono_bold, FontWeight.Bold),
)
object EstemshanColors {
  val Background = Color(0xFF0D0A07)
  val Surface = Color(0xFF17130E)
  val SurfaceHigh = Color(0xFF1D1912)
  val Gold = Color(0xFFE8A33D)
  val GoldDim = Color(0xFFA8742A)
  // S57 pixel-parity: exact table-system.css tokens. --accent-hi #F4B860
  // (win score, CTA gradient start), --pill #1D1A16 (chips), --panel-line
  // rgba(255,240,210,.07) (hairline borders). Additive only.
  val GoldHi = Color(0xFFF4B860)
  val Pill = Color(0xFF1D1A16)
  val PanelLine = Color(0x12FFF0D2)
  // S57: primary-CTA ink #2a1d0c on the gold gradient.
  val OnGold = Color(0xFF2A1D0C)
  val Ink = Color(0xFFF0EADA)
  val InkDim = Color(0xFFA89F8E)
  // S57 annotation 02 (design-ui/standings-ranked-result): inkFaint is the
  // corrected #8A8272 (was #6F685B). Used for faint labels in the Ranked
  // block only; InkDim above is untouched.
  val InkFaint = Color(0xFF8A8272)
  // S57: --legal #69C87E from table-system.css (RP-gain value). Local to
  // the Ranked rewards strip; no other screen reads it.
  val Legal = Color(0xFF69C87E)
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
    fontFamily = DisplayFamily,
    fontSize = 40.sp,
    lineHeight = 44.sp,
    color = EstemshanColors.Ink,
  ),
  headlineMedium = TextStyle(
    fontFamily = DisplayFamily,
    fontSize = 26.sp,
    lineHeight = 30.sp,
    color = EstemshanColors.Ink,
  ),
  titleMedium = TextStyle(
    fontFamily = BodyFamily,
    fontSize = 17.sp,
    lineHeight = 22.sp,
    color = EstemshanColors.Ink,
  ),
  bodyLarge = TextStyle(
    fontFamily = BodyFamily,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    color = EstemshanColors.Ink,
  ),
  bodyMedium = TextStyle(
    fontFamily = BodyFamily,
    fontSize = 14.sp,
    lineHeight = 19.sp,
    color = EstemshanColors.InkDim,
  ),
  labelLarge = TextStyle(
    fontFamily = MonoFamily,
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
