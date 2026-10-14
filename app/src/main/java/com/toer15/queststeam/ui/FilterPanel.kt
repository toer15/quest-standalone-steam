package com.toer15.queststeam.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toer15.queststeam.steam.OwnedGame

enum class GameSort(val label: String) { Name("Name A–Z"), MostPlayed("Most played"), Installed("Installed first") }

/** What the Home tab shows. Empty/false everywhere = everything. */
data class HomeFilters(
    val vrOnly: Boolean = false,
    val installedOnly: Boolean = false,
    val notInstalledOnly: Boolean = false,
    val playedOnly: Boolean = false,
    val genres: Set<String> = emptySet(),
    val sort: GameSort = GameSort.Name,
) {
    val activeCount get() = listOf(vrOnly, installedOnly, notInstalledOnly, playedOnly).count { it } + genres.size

    fun matches(g: OwnedGame, installed: Boolean): Boolean =
        (!vrOnly || g.isVr) &&
            (!installedOnly || installed) &&
            (!notInstalledOnly || !installed) &&
            (!playedOnly || g.playtimeMinutes > 0) &&
            (genres.isEmpty() || g.genres.any { it in genres })

    fun apply(games: List<OwnedGame>, isInstalled: (OwnedGame) -> Boolean): List<OwnedGame> {
        val list = games.filter { matches(it, isInstalled(it)) }
        return when (sort) {
            GameSort.Name -> list.sortedBy { it.name.lowercase() }
            GameSort.MostPlayed -> list.sortedByDescending { it.playtimeMinutes }
            GameSort.Installed -> list.sortedWith(compareByDescending<OwnedGame> { isInstalled(it) }.thenBy { it.name.lowercase() })
        }
    }
}

/** Side panel sliding in from the right with check boxes for the Home tab. */
@Composable
fun FilterPanel(
    visible: Boolean,
    filters: HomeFilters,
    genres: List<String>,
    matching: Int,
    onChange: (HomeFilters) -> Unit,
    onClose: () -> Unit,
) {
    val ui = LocalDeviceUi.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
            // Dim the rest; a tap outside closes the panel.
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.TopEnd),
            enter = slideInHorizontally(tween(260)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(200)) { it } + fadeOut(tween(160)),
        ) {
            Column(
                Modifier.fillMaxHeight().width(if (ui.isQuest) 420.dp else 320.dp).padding(12.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xF2182432))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(26.dp))
                    .padding(22.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Filters", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                }
                Text("$matching games match", color = SteamColors.Muted, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Section("Show")
                    Check("VR games only", filters.vrOnly) { onChange(filters.copy(vrOnly = it)) }
                    Check("Installed only", filters.installedOnly) { onChange(filters.copy(installedOnly = it, notInstalledOnly = if (it) false else filters.notInstalledOnly)) }
                    Check("Not installed only", filters.notInstalledOnly) { onChange(filters.copy(notInstalledOnly = it, installedOnly = if (it) false else filters.installedOnly)) }
                    Check("Played games only", filters.playedOnly) { onChange(filters.copy(playedOnly = it)) }

                    Section("Sort by")
                    GameSort.entries.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(filters.copy(sort = s)) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = filters.sort == s, onClick = { onChange(filters.copy(sort = s)) },
                                colors = RadioButtonDefaults.colors(selectedColor = SteamColors.Accent, unselectedColor = SteamColors.Muted),
                            )
                            Text(s.label, color = SteamColors.Text, fontSize = 16.sp)
                        }
                    }

                    if (genres.isNotEmpty()) {
                        Section("Genres")
                        genres.forEach { g ->
                            Check(g, g in filters.genres) { on -> onChange(filters.copy(genres = if (on) filters.genres + g else filters.genres - g)) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Reset", color = SteamColors.Text, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))
                            .clickable { onChange(HomeFilters()) }.padding(horizontal = 22.dp, vertical = 12.dp),
                    )
                    Text(
                        "Done", color = Color(0xFF0E141B), fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(SteamColors.Accent)
                            .clickable(onClick = onClose).padding(horizontal = 26.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(), color = SteamColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked, onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = SteamColors.Accent, uncheckedColor = SteamColors.Muted, checkmarkColor = Color(0xFF0E141B)),
        )
        Text(label, color = SteamColors.Text, fontSize = 16.sp)
    }
}
