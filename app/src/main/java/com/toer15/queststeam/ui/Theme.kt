package com.toer15.queststeam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object SteamColors {
    val Bg = Color(0xFF0E141B)
    val Panel = Color(0xFF1B2838)
    val Card = Color(0xFF243447)
    val Accent = Color(0xFF66C0F4)
    val Green = Color(0xFF5C7E10)
    val GreenBright = Color(0xFFBEEE11)
    val Text = Color(0xFFE8F0F6)
    val Muted = Color(0xFF8F98A0)
    val Danger = Color(0xFFFF8A80)
    val Glass = Color(0x99101820)
}

private val SteamTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.4.sp),
)

fun Modifier.steamBackdrop(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF141C26), Color(0xFF0C1218), Color(0xFF070B10))))
    drawRect(
        Brush.radialGradient(
            listOf(Color(0x553A7A96), Color.Transparent),
            center = Offset(size.width * 0.9f, 0f),
            radius = size.width * 0.85f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            listOf(Color(0x33264A6E), Color.Transparent),
            center = Offset(0f, size.height),
            radius = size.maxDimension * 0.65f,
        ),
    )
}

@Composable
fun SteamTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = SteamColors.Accent, onPrimary = Color(0xFF0E141B),
        secondary = SteamColors.GreenBright,
        background = SteamColors.Bg, surface = SteamColors.Panel, surfaceVariant = SteamColors.Card,
        onBackground = SteamColors.Text, onSurface = SteamColors.Text, onSurfaceVariant = SteamColors.Muted,
    ),
    typography = SteamTypography,
    content = content,
)
