package com.toer15.queststeam.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.toer15.queststeam.download.GameState
import com.toer15.queststeam.steam.OwnedGame

data class CardActions(
    val onDownload: () -> Unit,
    val onImport: () -> Unit,
    val onCancel: () -> Unit,
    val onPlay: () -> Unit,
    val onUninstall: () -> Unit,
)

/** Card that flips in 3D on tap: front = art, back = actions. */
@Composable
fun GameCard(game: OwnedGame, state: GameState, actions: CardActions, modifier: Modifier = Modifier) {
    var flipped by rememberSaveable(game.appId) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (flipped) 180f else 0f, tween(450), label = "flip")
    Box(
        modifier
            .aspectRatio(460f / 215f * 0.82f)
            .graphicsLayer { rotationY = rotation; cameraDistance = 12f * density }
            .clip(RoundedCornerShape(10.dp))
            .clickable { flipped = !flipped }
    ) {
        if (rotation <= 90f) CardFront(game, state)
        else Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { CardBack(game, state, actions) }
    }
}

@Composable
private fun CardFront(game: OwnedGame, state: GameState) {
    Box(Modifier.fillMaxSize().background(SteamColors.Card)) {
        AsyncImage(model = game.headerUrl, contentDescription = game.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xE0101418))))
        if (game.isVr) Text("VR", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(SteamColors.Accent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Text(game.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (state) {
                is GameState.Installed -> Text("Installed", color = SteamColors.GreenBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                is GameState.Downloading -> Text("Downloading ${(state.fraction * 100).toInt()}%", color = SteamColors.Accent, fontSize = 12.sp)
                else -> Text("${game.playtimeMinutes / 60} h played", color = SteamColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CardBack(game: OwnedGame, state: GameState, a: CardActions) {
    Column(
        Modifier.fillMaxSize().background(SteamColors.Panel).padding(10.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(game.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (game.genres.isNotEmpty()) Text(game.genres.take(3).joinToString(" · "), fontSize = 11.sp, color = SteamColors.Muted, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        val green = ButtonDefaults.buttonColors(containerColor = SteamColors.Green, contentColor = Color.White)
        when (state) {
            is GameState.Downloading -> {
                LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                Text("${formatBytes(state.bytes)} / ${if (state.total > 0) formatBytes(state.total) else "?"} · ${state.etaSeconds?.let { "${formatEta(it)} left" } ?: "estimating…"}",
                    fontSize = 11.sp, color = SteamColors.Muted)
                OutlinedButton(onClick = a.onCancel) { Text("Cancel") }
            }
            is GameState.Installed -> {
                Button(onClick = a.onPlay, colors = green, modifier = Modifier.fillMaxWidth()) { Text("Play Game") }
                OutlinedButton(onClick = a.onUninstall, modifier = Modifier.fillMaxWidth()) { Text("Uninstall") }
            }
            else -> {
                if (state is GameState.Failed) Text(state.message, fontSize = 10.sp, color = Color(0xFFE57373), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Button(onClick = a.onDownload, modifier = Modifier.fillMaxWidth()) { Text("Download") }
                Text("Import from folder", color = SteamColors.Accent, fontSize = 12.sp, modifier = Modifier.clickable(onClick = a.onImport).padding(4.dp))
            }
        }
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

fun formatEta(s: Long): String = when {
    s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
    s >= 60 -> "${s / 60}m ${s % 60}s"
    else -> "${s}s"
}
