package com.toer15.queststeam.play

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toer15.queststeam.ui.GameArt
import com.toer15.queststeam.ui.SteamColors
import com.toer15.queststeam.ui.focusRing

/**
 * Loading screen while a game starts: the game's picture, what's happening ("Starting the game
 * display…", "Loading R.E.P.O.… 12s"), then "Opening game now", then the game's window takes over.
 * While playing it offers Back to game / Stop game; on failure it shows why with the log's last lines.
 */
@Composable
fun LaunchOverlay(stage: LaunchStage, appId: Int, log: String, onShowGame: () -> Unit, onStop: () -> Unit, onDismiss: () -> Unit) {
    if (stage == LaunchStage.Idle) return
    var hidden by remember(stage::class) { mutableStateOf(false) }
    if (hidden) return
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 720.dp).fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1E2E3E), Color(0xFF101820))))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(28.dp)),
        ) {
            if (appId > 0) GameArt(appId, titleOf(stage), Modifier.fillMaxWidth().aspectRatio(460f / 170f), showNameIfMissing = true)
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(titleOf(stage), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                AnimatedContent(stage::class, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) }, label = "stage") { _ ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        when (stage) {
                            is LaunchStage.Working -> {
                                if (stage.progress != null) {
                                    LinearProgressIndicator(
                                        progress = { stage.progress.coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)),
                                        color = SteamColors.Accent, trackColor = Color(0xFF4A5663),
                                    )
                                } else CircularProgressIndicator(color = SteamColors.Accent, strokeWidth = 4.dp, modifier = Modifier.size(54.dp))
                                Spacer(Modifier.height(14.dp))
                                Text("Loading…", color = SteamColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                                Text(stage.step, color = SteamColors.Text, fontSize = 17.sp, textAlign = TextAlign.Center)
                                stage.detail?.let {
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        it, color = Color(0xFFAEB9C3), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp, maxLines = 6,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(10.dp),
                                    )
                                }
                                Spacer(Modifier.height(14.dp))
                                Text("The first start of a game takes longest: Windows code is translated for the headset.", color = SteamColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(14.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (stage.canShowWindow) OverlayButton("Show game window", primary = true, onClick = onShowGame)
                                    OverlayButton("Cancel", primary = false, onClick = onStop)
                                }
                            }
                            is LaunchStage.Opening -> {
                                Text("✓", color = SteamColors.GreenBright, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                                Text("Opening game now…", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            }
                            is LaunchStage.Running -> {
                                Text(if (stage.vr) "Playing in VR" else "Playing", color = SteamColors.GreenBright, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (stage.vr) "The game is in the headset view." else "The game is in its own window. If you closed it, tap Back to game.",
                                    color = SteamColors.Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(16.dp))
                                val focus = remember { FocusRequester() }
                                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (!stage.vr) OverlayButton("Back to game", primary = true, modifier = Modifier.focusRequester(focus), onClick = onShowGame)
                                    OverlayButton("Stop game", primary = false, onClick = onStop)
                                    OverlayButton("Hide", primary = false) { hidden = true }
                                }
                            }
                            is LaunchStage.Failed -> {
                                Text("The game didn't start", color = SteamColors.Danger, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(stage.message, color = SteamColors.Text, fontSize = 14.sp, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    GameRunner.importantLines(log).joinToString("\n"),
                                    color = Color(0xFFC8D2DA), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp,
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp))
                                        .background(Color.Black.copy(alpha = 0.4f)).verticalScroll(rememberScrollState()).padding(10.dp),
                                )
                                Spacer(Modifier.height(12.dp))
                                OverlayButton("Close", primary = true, onClick = onDismiss)
                            }
                            LaunchStage.Idle -> {}
                        }
                    }
                }
            }
        }
    }
}

private fun titleOf(s: LaunchStage) = when (s) {
    is LaunchStage.Working -> s.title
    is LaunchStage.Opening -> s.title
    is LaunchStage.Running -> s.title
    is LaunchStage.Failed -> s.title
    LaunchStage.Idle -> ""
}

@Composable
private fun OverlayButton(label: String, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, color = if (primary) Color(0xFF0E141B) else SteamColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp,
        modifier = modifier.clip(RoundedCornerShape(50)).background(if (primary) SteamColors.Accent else Color.White.copy(alpha = 0.08f))
            .focusRing().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
    )
}
