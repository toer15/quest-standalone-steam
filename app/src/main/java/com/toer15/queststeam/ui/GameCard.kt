package com.toer15.queststeam.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.toer15.queststeam.download.GameState
import com.toer15.queststeam.steam.OwnedGame

data class CardActions(
    val onDownload: () -> Unit,
    val onImport: () -> Unit,
    val onCancel: () -> Unit,
    val onPlay: () -> Unit,
    val onUninstall: () -> Unit,
)

private val CardShape = RoundedCornerShape(22.dp)

/** Card that flips in 3D on tap: front = art, back = actions. */
@Composable
fun GameCard(game: OwnedGame, state: GameState, actions: CardActions, modifier: Modifier = Modifier) {
    var flipped by rememberSaveable(game.appId) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (flipped) 180f else 0f, tween(480), label = "flip")
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else if (hovered) 1.025f else 1f, tween(140), label = "press")
    Box(
        modifier
            .aspectRatio(1.42f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationY = rotation
                cameraDistance = 18f * density
                shadowElevation = 18.dp.toPx()
                shape = CardShape
                clip = false
                spotShadowColor = Color.Black.copy(alpha = 0.45f)
            }
            .clip(CardShape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { flipped = !flipped }
    ) {
        if (rotation <= 90f) CardFront(game, state)
        else Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { CardBack(game, state, actions) }
    }
}

@Composable
fun SteamArt(url: String, contentDescription: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        loading = { ShimmerBox(Modifier.fillMaxSize()) },
        error = { Box(Modifier.fillMaxSize().background(SteamColors.Card)) },
    )
}

@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    Box(
        modifier.background(
            Brush.linearGradient(
                listOf(Color(0xFF1A2836), Color(0xFF3D5C73), Color(0xFF1A2836)),
                start = Offset(x - 500f, 0f),
                end = Offset(x, 280f),
            ),
        ),
    )
}

@Composable
private fun CardFront(game: OwnedGame, state: GameState) {
    val ui = LocalDeviceUi.current
    Box(Modifier.fillMaxSize().background(SteamColors.Card)) {
        SteamArt(game.headerUrl, game.name, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.42f to Color.Transparent, 1f to Color(0xF0101418))))
        if (game.isVr) Text(
            "VR", color = Color(0xFF0E141B), fontWeight = FontWeight.Bold, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).background(SteamColors.Accent, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
        if (state is GameState.Downloading) Box(Modifier.align(Alignment.TopStart).padding(10.dp)) {
            if (state.total > 0) CircularProgressIndicator(
                progress = { state.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.size(40.dp),
                color = SteamColors.Accent,
                trackColor = Color.White.copy(alpha = 0.2f),
                strokeWidth = 3.5.dp,
            ) else CircularProgressIndicator(modifier = Modifier.size(40.dp), color = SteamColors.Accent, strokeWidth = 3.5.dp)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
            Text(game.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = ui.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            when (state) {
                is GameState.Installed -> Text(
                    "Installed", color = Color(0xFF10200A), fontSize = if (ui.isQuest) 14.sp else 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(SteamColors.GreenBright, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
                is GameState.Downloading -> Text("Downloading ${(state.fraction * 100).toInt()}%", color = SteamColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                else -> Text("${game.playtimeMinutes / 60} h played", color = Color(0xFFD5DEE6), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun CardBack(game: OwnedGame, state: GameState, a: CardActions) {
    val ui = LocalDeviceUi.current
    val btn = Modifier.fillMaxWidth().height(ui.buttonHeight)
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF24364A), SteamColors.Panel))).padding(14.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(game.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = ui.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (game.genres.isNotEmpty()) Text(game.genres.take(3).joinToString(" · "), fontSize = 12.sp, color = SteamColors.Muted, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        val green = ButtonDefaults.buttonColors(containerColor = SteamColors.Green, contentColor = Color.White)
        when (state) {
            is GameState.Downloading -> {
                LinearProgressIndicator(
                    progress = { state.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                    color = SteamColors.Accent,
                    trackColor = Color.White.copy(alpha = 0.12f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (state.total > 0) "${formatBytes(state.bytes)} / ${formatBytes(state.total)} · ${state.etaSeconds?.let { "${formatEta(it)} left" } ?: "estimating time…"}"
                    else state.note ?: "Starting…",
                    fontSize = if (ui.isQuest) 14.sp else 11.sp, color = SteamColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (state.total > 0 && state.note != null) Text(state.note, fontSize = if (ui.isQuest) 12.sp else 10.sp, color = SteamColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = a.onCancel, shape = RoundedCornerShape(50), modifier = Modifier.height(ui.buttonHeight)) { Text("Cancel") }
            }
            is GameState.Installed -> {
                Button(onClick = a.onPlay, colors = green, shape = RoundedCornerShape(50), modifier = btn) { Text("Play Game", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = a.onUninstall, shape = RoundedCornerShape(50), modifier = btn) { Text("Uninstall") }
            }
            else -> {
                if (state is GameState.Failed) Text(state.message, fontSize = 11.sp, color = SteamColors.Danger, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Button(onClick = a.onDownload, shape = RoundedCornerShape(50), modifier = btn) { Text(if (state is GameState.Failed) "Retry download" else "Download", fontWeight = FontWeight.Bold) }
                Text("Import from folder", color = SteamColors.Accent, fontSize = 13.sp, modifier = Modifier.clickable(onClick = a.onImport).padding(6.dp))
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
