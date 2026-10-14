package com.toer15.queststeam.display

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toer15.queststeam.ui.SteamColors
import com.toer15.queststeam.ui.focusRing
import kotlinx.coroutines.delay

/** Live "is the game display app installed?" (re-checked every second until it is). */
@Composable
fun rememberDisplayInstalled(): Boolean {
    val ctx = LocalContext.current
    var installed by remember { mutableStateOf(GameDisplay.isAppInstalled(ctx)) }
    LaunchedEffect(installed) {
        while (!installed) { delay(1000); installed = GameDisplay.isAppInstalled(ctx) }
    }
    return installed
}

/** "Game display: ✓ Installed", or a one-tap Install button. */
@Composable
fun GameDisplayRow(onMessage: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val installed = rememberDisplayInstalled()
    if (installed) Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(SteamColors.GreenBright))
        Spacer(Modifier.width(10.dp))
        Text("Game display (Termux:X11): ✓ Installed", color = SteamColors.GreenBright, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    } else Column {
        Text(
            "Games need the game display app (Termux:X11) for their window. It's included: one tap installs it. You don't need the Termux app.",
            color = Color(0xFFFFC46B), fontSize = 13.sp, lineHeight = 18.sp,
        )
        Text(
            "Install game display app", color = Color(0xFF0E141B), fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).background(SteamColors.Accent).focusRing()
                .clickable { GameDisplay.installApp(ctx)?.let(onMessage) }
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}
