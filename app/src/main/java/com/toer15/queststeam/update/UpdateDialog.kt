package com.toer15.queststeam.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.toer15.queststeam.ui.LocalDeviceUi
import com.toer15.queststeam.ui.SteamColors
import com.toer15.queststeam.ui.formatBytes
import com.toer15.queststeam.ui.formatEta

/** Small, modern popup for every update state: checking, up to date, available, downloading, installing. */
@Composable
fun UpdateDialog(
    state: UpdateUi,
    onUpdateNow: (Release) -> Unit,
    onInstall: (UpdateUi.NeedsPermission) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    if (state == UpdateUi.Hidden) return
    val ui = LocalDeviceUi.current
    val scale = remember { Animatable(0.88f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
    val busy = state is UpdateUi.Downloading || state is UpdateUi.Installing
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(20.dp)
                .widthIn(min = 300.dp, max = if (ui.isQuest) 520.dp else 380.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = ((scale.value - 0.88f) / 0.12f).coerceIn(0f, 1f) }
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1E2E3E), Color(0xFF131C26))))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(28.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("SOFTWARE UPDATE", color = SteamColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(14.dp))
            AnimatedContent(targetState = state, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                contentKey = { it::class }, label = "update") { s ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    when (s) {
                        UpdateUi.Hidden -> Unit
                        UpdateUi.Checking -> {
                            CircularProgressIndicator(color = SteamColors.Accent, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(14.dp))
                            Title("Checking for updates…")
                        }
                        is UpdateUi.UpToDate -> {
                            Badge("✓", SteamColors.GreenBright)
                            Title("Latest version is already installed")
                            Sub("Version ${s.version}")
                            Spacer(Modifier.height(18.dp))
                            Primary("Done", onDismiss)
                        }
                        is UpdateUi.Available -> {
                            Badge("↑", SteamColors.Accent)
                            Title("New update available")
                            Sub("Version ${s.release.version} · ${formatBytes(s.release.apkSize)}  (you have ${Updater.currentVersion})")
                            Notes(s.release.notes)
                            Spacer(Modifier.height(18.dp))
                            Primary("Update now") { onUpdateNow(s.release) }
                            Secondary("Later", onDismiss)
                        }
                        is UpdateUi.Downloading -> {
                            Title("Downloading ${s.release.version}")
                            Spacer(Modifier.height(16.dp))
                            LinearProgressIndicator(
                                progress = { s.fraction },
                                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)),
                                color = SteamColors.Accent,
                                trackColor = Color.White.copy(alpha = 0.10f),
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${(s.fraction * 100).toInt()}%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(s.etaSeconds?.let { "${formatEta(it)} left" } ?: "Estimating time…", color = Color.White, fontSize = 15.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${formatBytes(s.bytes)} of ${if (s.total > 0) formatBytes(s.total) else "?"}", color = SteamColors.Muted, fontSize = 13.sp)
                                Text(if (s.bytesPerSecond > 0) "${formatBytes(s.bytesPerSecond)}/s" else "", color = SteamColors.Muted, fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(18.dp))
                            Secondary("Cancel", onCancel)
                        }
                        is UpdateUi.NeedsPermission -> {
                            Badge("!", Color(0xFFFFC46B))
                            Title("Allow updates from this app")
                            Sub("Android asks once: turn on \"Allow from this source\" for Quest Steam, come back, then tap Install.")
                            Spacer(Modifier.height(18.dp))
                            Primary("Install") { onInstall(s) }
                            Secondary("Cancel", onCancel)
                        }
                        is UpdateUi.Installing -> {
                            CircularProgressIndicator(color = SteamColors.Accent, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(14.dp))
                            Title("Installing ${s.release.version}…")
                            Sub("Confirm in the Android installer. The app restarts on the new version.")
                        }
                        is UpdateUi.Failed -> {
                            Badge("!", SteamColors.Danger)
                            Title("Update problem")
                            Sub(s.message)
                            Spacer(Modifier.height(18.dp))
                            Primary("Try again", onRetry)
                            Secondary("Close", onDismiss)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(symbol: String, color: Color) {
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Text(symbol, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Title(text: String) =
    Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)

@Composable
private fun Sub(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, color = SteamColors.Muted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
}

@Composable
private fun Notes(notes: String) {
    if (notes.isBlank()) return
    Spacer(Modifier.height(12.dp))
    Text(
        notes.replace("**", "").replace("`", ""),
        color = SteamColors.Text, fontSize = 13.sp, lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f)).verticalScroll(rememberScrollState()).padding(12.dp),
    )
}

@Composable
private fun Primary(label: String, onClick: () -> Unit) {
    val ui = LocalDeviceUi.current
    Button(
        onClick = onClick, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(ui.buttonHeight + 4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SteamColors.Accent, contentColor = Color(0xFF0E141B)),
    ) { Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
}

@Composable
private fun Secondary(label: String, onClick: () -> Unit) {
    val ui = LocalDeviceUi.current
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onClick, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(ui.buttonHeight)) {
        Text(label, color = SteamColors.Text)
    }
}
