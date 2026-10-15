package com.toer15.queststeam.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.toer15.queststeam.gamepad.PadNav
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
fun GameCard(game: OwnedGame, state: GameState, actions: CardActions, modifier: Modifier = Modifier, startFlipped: Boolean = false) {
    // A restored grid would otherwise put a deep-linked card back on its front. The demo path
    // keeps its own state so the back (Download / Play / Uninstall) is showing on the first frame.
    val savedFlip = rememberSaveable(game.appId) { mutableStateOf(false) }
    val demoFlip = remember(game.appId) { mutableStateOf(startFlipped) }
    var flipped by if (startFlipped) demoFlip else savedFlip
    val rotationAnim = remember(game.appId, startFlipped) { Animatable(if (startFlipped) 180f else 0f) }
    LaunchedEffect(flipped) {
        val target = if (flipped) 180f else 0f
        if (kotlin.math.abs(rotationAnim.value - target) > 0.5f) rotationAnim.animateTo(target, tween(480))
    }
    val rotation = rotationAnim.value
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else if (hovered || focused) 1.025f else 1f, tween(140), label = "press")
    // Controller: A on a focused card flips it and moves focus to its main button (Download / Play /
    // Cancel); D-pad reaches the others; B turns the card back and puts focus on it again.
    var padFlip by remember { mutableStateOf(false) }
    var refocusCard by remember { mutableStateOf(false) }
    val cardFocus = remember { FocusRequester() }
    DisposableEffect(flipped) {
        val unflip: () -> Unit = { flipped = false; if (padFlip) refocusCard = true }
        // A deep-linked card stays on its back; a stray Back must not turn it over before a capture.
        if (flipped && !startFlipped) PadNav.pushBack(unflip)
        onDispose { PadNav.removeBack(unflip) }
    }
    LaunchedEffect(refocusCard) {
        if (refocusCard) { delay(60); runCatching { cardFocus.requestFocus() }; refocusCard = false }
    }
    Box(
        modifier
            .aspectRatio(1.42f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationY = rotation
                cameraDistance = 18f * density
                shadowElevation = 6.dp.toPx()
                shape = CardShape
                clip = false
                spotShadowColor = Color.Black.copy(alpha = 0.45f)
            }
            .clip(CardShape)
            // Controller focus: a bright outline on the selected card.
            .border(if (focused) 3.dp else 0.dp, if (focused) SteamColors.Accent else Color.Transparent, CardShape)
            .hoverable(interaction)
            .focusRequester(cardFocus)
            .clickable(interactionSource = interaction, indication = null) { padFlip = !flipped && focused; flipped = !flipped }
    ) {
        if (rotation <= 90f) CardFront(game, state)
        else Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { CardBack(game, state, actions, autoFocus = padFlip) }
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

/** Static placeholder while art loads (an animated shimmer per card kept the whole grid redrawing). */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF1A2836), Color(0xFF2A4256), Color(0xFF1A2836)))))
}

@Composable
private fun CardFront(game: OwnedGame, state: GameState) {
    val ui = LocalDeviceUi.current
    Box(Modifier.fillMaxSize().background(SteamColors.Card)) {
        GameArt(game.appId, game.name, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.42f to Color.Transparent, 1f to Color(0xF0101418))))
        if (game.isVr) Text(
            "VR", color = Color(0xFF0E141B), fontWeight = FontWeight.Bold, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).background(SteamColors.Accent, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp)) {
            Text(game.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = ui.cardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val small = if (ui.isQuest) 14.sp else 11.sp
            when (state) {
                is GameState.Installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Installed", color = Color(0xFF10200A), fontSize = small, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(SteamColors.GreenBright, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    if (game.sizeBytes > 0) Text("  ${formatBytes(game.sizeBytes)}", color = Color(0xFFD5DEE6), fontSize = small)
                }
                // Blue bar sliding over grey, with "37% of 4.5 GB · 3m left".
                is GameState.Downloading -> {
                    DownloadBar(state, Modifier.fillMaxWidth().height(7.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(progressLine(state, game.sizeBytes), color = SteamColors.Accent, fontSize = small, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                else -> Text(
                    listOfNotNull(game.sizeBytes.takeIf { it > 0 }?.let(::formatBytes), "${game.playtimeMinutes / 60} h played").joinToString(" · "),
                    color = Color(0xFFD5DEE6), fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun CardBack(game: OwnedGame, state: GameState, a: CardActions, autoFocus: Boolean = false) {
    val ui = LocalDeviceUi.current
    val btn = Modifier.fillMaxWidth().height(ui.buttonHeight)
    // The main button for this state gets controller focus (again after Download → Downloading → Installed).
    val primary = remember { FocusRequester() }
    LaunchedEffect(autoFocus, state::class) {
        if (autoFocus) { delay(50); runCatching { primary.requestFocus() } }
    }
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
                DownloadBar(state, Modifier.fillMaxWidth().height(10.dp))
                Spacer(Modifier.height(6.dp))
                val total = if (state.total > 0) state.total else game.sizeBytes
                Text(
                    if (total > 0) "${(state.fraction * 100).toInt()}% · ${formatBytes(state.bytes)} of ${formatBytes(total)}" else state.note ?: "Starting…",
                    fontSize = if (ui.isQuest) 15.sp else 12.sp, color = SteamColors.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.etaSeconds?.let { "${formatEta(it)} left" } ?: state.note ?: "Estimating time left…",
                    fontSize = if (ui.isQuest) 13.sp else 11.sp, color = SteamColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = a.onCancel, shape = RoundedCornerShape(50), modifier = Modifier.focusRequester(primary).focusRing().height(ui.buttonHeight)) { Text("Cancel") }
            }
            is GameState.Installed -> {
                Button(onClick = a.onPlay, colors = green, shape = RoundedCornerShape(50), modifier = btn.focusRequester(primary).focusRing()) { Text("Play Game", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = a.onUninstall, shape = RoundedCornerShape(50), modifier = btn.focusRing()) { Text("Uninstall") }
            }
            else -> {
                if (state is GameState.Failed) Text(state.message, fontSize = 11.sp, color = SteamColors.Danger, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Button(onClick = a.onDownload, shape = RoundedCornerShape(50), modifier = btn.focusRequester(primary).focusRing()) {
                    Text((if (state is GameState.Failed) "Retry download" else "Download") + (if (game.sizeBytes > 0) " · ${formatBytes(game.sizeBytes)}" else ""), fontWeight = FontWeight.Bold)
                }
                Text("Import from folder", color = SteamColors.Accent, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).focusRing().clickable(onClick = a.onImport).padding(6.dp))
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

/** Download progress: blue sliding over grey; animated stripes while the size isn't known yet. */
@Composable
fun DownloadBar(state: GameState.Downloading, modifier: Modifier = Modifier) {
    val track = Color(0xFF4A5663)
    if (state.total > 0) {
        val p by animateFloatAsState(state.fraction.coerceIn(0f, 1f), tween(400), label = "dl")
        Box(modifier.clip(RoundedCornerShape(50)).background(track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(Color(0xFF1A9FFF), SteamColors.Accent))))
        }
    } else {
        LinearProgressIndicator(modifier = modifier.clip(RoundedCornerShape(50)), color = SteamColors.Accent, trackColor = track)
    }
}

/** "37% of 4.5 GB · 3m left" (or the current step while the size is unknown). */
fun progressLine(s: GameState.Downloading, knownSize: Long): String {
    val total = if (s.total > 0) s.total else knownSize
    if (total <= 0 || s.total <= 0) return listOfNotNull(s.note ?: "Starting…", knownSize.takeIf { it > 0 }?.let { formatBytes(it) }).joinToString(" · ")
    return "${(s.fraction * 100).toInt()}% of ${formatBytes(total)}" + (s.etaSeconds?.let { " · ${formatEta(it)} left" } ?: "")
}
