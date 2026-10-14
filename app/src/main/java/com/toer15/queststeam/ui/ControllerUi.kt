package com.toer15.queststeam.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.toer15.queststeam.gamepad.Controller
import com.toer15.queststeam.gamepad.ControllerRelay
import com.toer15.queststeam.gamepad.Controllers
import com.toer15.queststeam.steam.OwnedGame

/** How the player chose to play. */
enum class PlayMode { VR, FLAT }

/** Whether a game can run in the headset through our SteamVR bridge. */
enum class VrSupport { YES, NOT_STEAMVR }

/** Re-reads "Display over other apps" whenever the screen comes back (after the Settings page). */
@Composable
fun rememberOverlayAllowed(): Boolean {
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(ControllerRelay.canOverlay(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) allowed = ControllerRelay.canOverlay(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return allowed
}

@Suppress("UNUSED_PARAMETER")
private fun icon(c: Controller?) = "🎮"

/** Top bar chip: the connected controller's exact model, or "No controller". */
@Composable
fun ControllerChip(modifier: Modifier = Modifier) {
    val ui = LocalDeviceUi.current
    val pads by Controllers.connected.collectAsState()
    val first = pads.firstOrNull()
    Row(
        modifier.clip(RoundedCornerShape(50))
            .background(if (first != null) SteamColors.GreenBright.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 12.dp, vertical = if (ui.isQuest) 8.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (first != null) SteamColors.GreenBright else SteamColors.Muted))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                first == null -> "No controller"
                pads.size > 1 -> "${icon(first)} ${first.model} +${pads.size - 1}"
                else -> "${icon(first)} ${first.model}"
            },
            color = if (first != null) Color.White else SteamColors.Muted,
            fontSize = if (ui.isQuest) 14.sp else 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp),
        )
    }
}

/** One line per controller: model + Connected, or how to connect one. */
@Composable
fun ControllerStatus(showHelp: Boolean = true) {
    val pads by Controllers.connected.collectAsState()
    Column {
        if (pads.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(SteamColors.Muted))
                Spacer(Modifier.width(10.dp))
                Text("Controller: Not connected", color = SteamColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            if (showHelp) Text(
                "Pair an Xbox, PlayStation (DualSense / DualShock) or other Bluetooth controller in Quest Settings → Devices → Bluetooth. It shows up here as soon as it connects.",
                color = SteamColors.Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, start = 20.dp),
            )
        } else pads.forEachIndexed { i, c ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(SteamColors.GreenBright))
                Spacer(Modifier.width(10.dp))
                Text("${icon(c)} ${c.model}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("Connected · player ${i + 1}", color = SteamColors.GreenBright, fontSize = 13.sp)
            }
        }
    }
}

/**
 * Steam-style popup before a game starts: choose VR (in the headset) or flat (on a screen), and
 * see whether a controller is connected. Works with the controller: A picks, B closes.
 */
@Composable
fun PlayDialog(
    game: OwnedGame,
    vrOffered: Boolean,
    vrSupport: VrSupport,
    onPlay: (PlayMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val ui = LocalDeviceUi.current
    val pads by Controllers.connected.collectAsState()
    val overlayAllowed = rememberOverlayAllowed()
    val first = remember { FocusRequester() }
    val scale = remember { Animatable(0.9f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
        runCatching { first.requestFocus() }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp && (e.key == Key.Back || e.key == Key.ButtonB || e.key == Key.Escape)) { onDismiss(); true } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = if (ui.isQuest) 640.dp else 420.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = ((scale.value - 0.9f) / 0.1f).coerceIn(0f, 1f) }
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1E2E3E), Color(0xFF131C26))))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(28.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(24.dp),
        ) {
            Text("PLAY", color = SteamColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text(game.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            if (vrOffered) {
                Text("How do you want to play?", color = SteamColors.Text, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                val vrOk = vrSupport != VrSupport.NOT_STEAMVR
                ModeTile(
                    "🥽  Play in VR", if (vrOk) "In the headset, like SteamVR. Use your Touch controllers." else "This game uses OpenXR/Oculus, not SteamVR, so the VR bridge can't run it yet.",
                    enabled = vrOk, modifier = Modifier.focusRequester(first),
                ) { onPlay(PlayMode.VR) }
                Spacer(Modifier.height(10.dp))
                ModeTile("🖥  Play flat", "On a big screen in your headset. Play with your controller.", enabled = true) { onPlay(PlayMode.FLAT) }
            } else {
                ModeTile("▶  Play", "On a big screen in your headset. Play with your controller.", enabled = true, modifier = Modifier.focusRequester(first)) { onPlay(PlayMode.FLAT) }
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.22f)).padding(14.dp)) {
                ControllerStatus()
            }
            if (pads.isNotEmpty() && !overlayAllowed) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Flat games: to keep the controller working while the game window is in front, allow \"Display over other apps\" for Quest Steam.",
                    color = Color(0xFFFFC46B), fontSize = 13.sp, lineHeight = 18.sp,
                )
                Text(
                    "Allow", color = SteamColors.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { ControllerRelay.requestOverlay(ctx) }.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "Cancel", color = SteamColors.Text, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))
                        .clickable(onClick = onDismiss).padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ModeTile(title: String, subtitle: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (enabled) SteamColors.Accent.copy(alpha = if (focused) 0.30f else 0.14f) else Color.White.copy(alpha = 0.04f))
            .border(if (focused) 3.dp else 1.dp, if (focused) SteamColors.Accent else Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(title, color = if (enabled) Color.White else SteamColors.Muted, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = SteamColors.Muted, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/** Visible focus outline for controller navigation (place before the clickable). */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(50), thickness: Dp = 3.dp): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }
        .border(if (focused) thickness else 0.dp, if (focused) SteamColors.Accent else Color.Transparent, shape)
}
