package com.toer15.queststeam.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.toer15.queststeam.steam.OwnedGame

/** Letter filter: "" = all, "A" = single letter, "A-F" = range, "#" = non-letters. */
object LetterFilter {
    val GROUPS = listOf("A-F", "G-L", "M-R", "S-Z", "#")
    fun first(name: String) = name.trimStart().firstOrNull()?.uppercaseChar() ?: '#'
    fun matches(f: String, name: String): Boolean {
        if (f.isEmpty()) return true
        val c = first(name)
        return when {
            f == "#" -> c !in 'A'..'Z'
            f.length == 3 && f[1] == '-' -> c in f[0]..f[2]
            else -> c == f[0]
        }
    }
}

/**
 * Category popup: opens small, then grows into a slightly larger panel (scale + size animation).
 * Contains: search field, "All games", and A–Z jump list (with A–F / G–L / M–R / S–Z groups).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryPopup(
    games: List<OwnedGame>, query: String, onQuery: (String) -> Unit,
    onAllGames: () -> Unit, onLetters: (String) -> Unit, onDismiss: () -> Unit,
) {
    val scale = remember { Animatable(0.6f) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        scale.animateTo(0.92f, tween(160, easing = FastOutSlowInEasing))
        expanded = true
        scale.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
    }
    val counts = remember(games) { games.groupingBy { LetterFilter.first(it.name).let { c -> if (c in 'A'..'Z') c.toString() else "#" } }.eachCount() }
    val pill = RoundedCornerShape(50)
    val ui = LocalDeviceUi.current

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = ((scale.value - 0.6f) / 0.4f).coerceIn(0f, 1f) }
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xF216212C))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                .animateContentSize(tween(260))
                .widthIn(min = 300.dp, max = if (expanded) ui.popupMaxWidth else 340.dp)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Browse", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                label = { Text("Search games") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SteamColors.Accent,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                    focusedTextColor = SteamColors.Text,
                    unfocusedTextColor = SteamColors.Text,
                    cursorColor = SteamColors.Accent,
                    focusedLabelColor = SteamColors.Accent,
                    unfocusedLabelColor = SteamColors.Muted,
                ),
            )
            if (query.isNotBlank()) Text("${games.count { it.name.contains(query, true) }} matches", fontSize = 12.sp, color = SteamColors.Muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(14.dp))
            Text(
                "All games (${games.size})", fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.fillMaxWidth().clip(pill).clickable(onClick = onAllGames)
                    .background(SteamColors.Accent.copy(alpha = 0.18f)).padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (expanded) {
                Spacer(Modifier.height(16.dp))
                Text("Jump by letter", fontSize = 12.sp, color = SteamColors.Muted, letterSpacing = 0.4.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    LetterFilter.GROUPS.forEach { g ->
                        Text(
                            g, fontWeight = FontWeight.SemiBold, color = SteamColors.Text,
                            modifier = Modifier.clip(pill).clickable { onLetters(g) }
                                .background(SteamColors.Card).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ('A'..'Z').forEach { c ->
                        val n = counts[c.toString()] ?: 0
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.size(width = ui.letterCell, height = ui.letterCell + 6.dp)
                                .clip(RoundedCornerShape(14.dp)).clickable(enabled = n > 0) { onLetters(c.toString()) }
                                .background(if (n > 0) SteamColors.Card else Color.White.copy(alpha = 0.04f)).padding(top = 6.dp),
                        ) {
                            Text(c.toString(), fontWeight = FontWeight.Bold, color = if (n > 0) SteamColors.Text else SteamColors.Muted)
                            Text("$n", fontSize = 10.sp, color = SteamColors.Muted)
                        }
                    }
                }
                Spacer(Modifier.width(1.dp))
            }
        }
    }
}
