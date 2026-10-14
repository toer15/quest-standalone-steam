package com.toer15.queststeam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object SteamColors {
    val Bg = Color(0xFF171A21)
    val Panel = Color(0xFF1B2838)
    val Card = Color(0xFF2A475E)
    val Accent = Color(0xFF66C0F4)
    val Green = Color(0xFF5C7E10)
    val GreenBright = Color(0xFFA4D007)
    val Text = Color(0xFFC7D5E0)
    val Muted = Color(0xFF8F98A0)
}

@Composable
fun SteamTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = SteamColors.Accent, onPrimary = Color.Black,
        secondary = SteamColors.GreenBright,
        background = SteamColors.Bg, surface = SteamColors.Panel, surfaceVariant = SteamColors.Card,
        onBackground = SteamColors.Text, onSurface = SteamColors.Text, onSurfaceVariant = SteamColors.Muted,
    ),
    content = content,
)
