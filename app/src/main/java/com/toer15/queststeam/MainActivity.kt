package com.toer15.queststeam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.toer15.queststeam.download.FolderImportSource
import com.toer15.queststeam.download.GameState
import com.toer15.queststeam.launcher.DeviceMode
import com.toer15.queststeam.steam.OwnedGame
import com.toer15.queststeam.steam.SessionStore
import com.toer15.queststeam.ui.CardActions
import com.toer15.queststeam.ui.CategoryPopup
import com.toer15.queststeam.ui.LetterFilter
import com.toer15.queststeam.ui.GameCard
import com.toer15.queststeam.ui.LibraryViewModel
import com.toer15.queststeam.ui.SteamColors
import com.toer15.queststeam.ui.SteamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SessionStore(this).steamId == null) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        setContent { SteamTheme { LauncherApp(onSignedOut = { startActivity(Intent(this, LoginActivity::class.java)); finish() }) } }
    }
}

private enum class Tab { Home, Games }

/** Built-in filters; genres from the Steam store are appended dynamically. */
object Categories {
    const val ALL = "All"; const val VR = "VR"; const val INSTALLED = "Installed"
    fun matches(cat: String, g: OwnedGame, installed: Boolean) = when (cat) {
        ALL -> true; VR -> g.isVr; INSTALLED -> installed; else -> cat in g.genres
    }
}

@Composable
private fun LauncherApp(onSignedOut: () -> Unit, vm: LibraryViewModel = viewModel()) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val games by vm.games.collectAsState()
    val states by vm.downloads.states.collectAsState()
    val profile by vm.profile.collectAsState()
    val status by vm.status.collectAsState()
    val syncing by vm.syncing.collectAsState()
    var pendingImport by remember { mutableStateOf<Int?>(null) }
    var confirmUninstall by remember { mutableStateOf<OwnedGame?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val id = pendingImport; pendingImport = null
        if (uri != null && id != null) vm.downloads.download(id, FolderImportSource(uri))
    }

    fun actionsFor(g: OwnedGame) = CardActions(
        onDownload = { vm.downloads.download(g.appId) },
        onImport = { pendingImport = g.appId; pickFolder.launch(null) },
        onCancel = { vm.downloads.cancel(g.appId) },
        onPlay = {
            val exe = vm.downloads.mainExe(g.appId)
            ctx.startActivity(Intent(ctx, RuntimeActivity::class.java).putExtra(RuntimeActivity.EXTRA_EXE, exe?.path).putExtra(RuntimeActivity.EXTRA_TITLE, g.name))
        },
        onUninstall = { confirmUninstall = g },
    )
    fun stateOf(g: OwnedGame) = states[g.appId] ?: vm.downloads.stateOf(g.appId)

    Column(Modifier.fillMaxSize().background(SteamColors.Bg)) {
        TopBar(tab, { tab = it }, profile?.avatarUrl, profile?.personaName, syncing,
            onSync = vm::sync,
            onSettings = { ctx.startActivity(Intent(ctx, RuntimeActivity::class.java)) },
            onSignOut = { vm.signOut(); onSignedOut() })
        if (status.isNotBlank()) Text(status, color = SteamColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        when (tab) {
            Tab.Home -> HomeScreen(games, ::stateOf, ::actionsFor, onBrowse = { tab = Tab.Games }, onSync = vm::sync)
            Tab.Games -> GamesScreen(games, ::stateOf, ::actionsFor)
        }
    }

    confirmUninstall?.let { g ->
        AlertDialog(onDismissRequest = { confirmUninstall = null },
            title = { Text("Uninstall ${g.name}?") }, text = { Text("Deletes the game's files from this device.") },
            confirmButton = { TextButton(onClick = { vm.downloads.uninstall(g.appId); confirmUninstall = null }) { Text("Uninstall") } },
            dismissButton = { TextButton(onClick = { confirmUninstall = null }) { Text("Cancel") } })
    }
}

@Composable
private fun TopBar(tab: Tab, onTab: (Tab) -> Unit, avatar: String?, name: String?, syncing: Boolean,
                   onSync: () -> Unit, onSettings: () -> Unit, onSignOut: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(SteamColors.Panel).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (t in Tab.entries) {
            val sel = t == tab
            Text(t.name.uppercase(), fontWeight = FontWeight.Bold, fontSize = 16.sp,
                color = if (sel) SteamColors.Accent else SteamColors.Text,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onTab(t) }
                    .background(if (sel) SteamColors.Card else Color.Transparent).padding(horizontal = 14.dp, vertical = 8.dp))
            Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSync, enabled = !syncing) { Icon(Icons.Filled.Refresh, "Sync Steam account") }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Runtime settings") }
        Box {
            Box(Modifier.size(40.dp).clip(CircleShape).background(SteamColors.Card).clickable { menu = true }, contentAlignment = Alignment.Center) {
                if (avatar.isNullOrBlank()) Icon(Icons.Filled.Person, "Profile")
                else AsyncImage(model = avatar, contentDescription = "Profile", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(name ?: "Steam user") }, onClick = {}, enabled = false)
                DropdownMenuItem(text = { Text("Sync Steam account") }, onClick = { menu = false; onSync() })
                DropdownMenuItem(text = { Text("Runtime & settings") }, onClick = { menu = false; onSettings() })
                DropdownMenuItem(text = { Text("Sign out") }, onClick = { menu = false; onSignOut() })
            }
        }
    }
}

@Composable
private fun HomeScreen(games: List<OwnedGame>, stateOf: (OwnedGame) -> GameState, actionsFor: (OwnedGame) -> CardActions,
                       onBrowse: () -> Unit, onSync: () -> Unit) {
    val installed = games.filter { stateOf(it) is GameState.Installed || stateOf(it) is GameState.Downloading }
    val recent = games.sortedByDescending { it.playtimeMinutes }.take(12)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Welcome back", style = MaterialTheme.typography.headlineSmall)
        Text(DeviceMode.label(androidx.compose.ui.platform.LocalContext.current), color = SteamColors.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        if (games.isEmpty()) {
            Button(onClick = onSync) { Text("Sync Steam account") }
            return@Column
        }
        Shelf("Installed & downloading", installed, stateOf, actionsFor, empty = "Nothing installed yet — open Games and tap a card.")
        Shelf("Most played", recent, stateOf, actionsFor)
        TextButton(onClick = onBrowse) { Text("Browse all ${games.size} games →") }
    }
}

@Composable
private fun Shelf(title: String, list: List<OwnedGame>, stateOf: (OwnedGame) -> GameState, actionsFor: (OwnedGame) -> CardActions, empty: String? = null) {
    Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
    if (list.isEmpty() && empty != null) { Text(empty, color = SteamColors.Muted, fontSize = 13.sp); Spacer(Modifier.height(8.dp)); return }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
        items(list, key = { it.appId }) { g -> GameCard(g, stateOf(g), actionsFor(g), Modifier.width(260.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GamesScreen(games: List<OwnedGame>, stateOf: (OwnedGame) -> GameState, actionsFor: (OwnedGame) -> CardActions) {
    var category by rememberSaveable { mutableStateOf(Categories.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var letters by rememberSaveable { mutableStateOf("") } // "" = all, "A" or "A-F"
    var popup by remember { mutableStateOf(false) }
    val genres = remember(games.map { it.genres }) { games.flatMap { it.genres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    val cats = listOf(Categories.ALL, Categories.VR, Categories.INSTALLED) + genres
    val shown = games.filter {
        Categories.matches(category, it, stateOf(it) is GameState.Installed) &&
            (query.isBlank() || it.name.contains(query, ignoreCase = true)) &&
            LetterFilter.matches(letters, it.name)
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Category slider: tapping the slider's browse button opens the category popup;
            // long-press on any chip opens it too.
            FilterChip(selected = query.isNotBlank() || letters.isNotEmpty(), onClick = { popup = true },
                label = { Text(if (letters.isNotEmpty()) "☰ $letters" else if (query.isNotBlank()) "☰ \"$query\"" else "☰ Browse") },
                modifier = Modifier.padding(start = 12.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cats) { c ->
                    val count = games.count { Categories.matches(c, it, stateOf(it) is GameState.Installed) }
                    Box(Modifier.combinedClickable(onClick = { category = c }, onLongClick = { popup = true })) {
                        FilterChip(selected = c == category, onClick = { category = c }, label = { Text("$c ($count)") })
                    }
                }
            }
        }
        if (shown.isEmpty()) Text("No games match.", color = SteamColors.Muted, modifier = Modifier.padding(16.dp))
        LazyVerticalGrid(GridCells.Adaptive(240.dp), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shown, key = { it.appId }) { g -> GameCard(g, stateOf(g), actionsFor(g)) }
        }
    }
    if (popup) CategoryPopup(
        games = games, query = query, onQuery = { query = it },
        onAllGames = { query = ""; letters = ""; category = Categories.ALL; popup = false },
        onLetters = { letters = it; popup = false },
        onDismiss = { popup = false },
    )
}
