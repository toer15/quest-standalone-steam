package com.toer15.queststeam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.toer15.queststeam.download.FolderImportSource
import com.toer15.queststeam.download.GameState
import com.toer15.queststeam.launcher.DeviceMode
import com.toer15.queststeam.steam.KeylessSteam
import com.toer15.queststeam.steam.OwnedGame
import com.toer15.queststeam.steam.SessionStore
import com.toer15.queststeam.ui.CardActions
import com.toer15.queststeam.ui.CategoryPopup
import com.toer15.queststeam.ui.GameCard
import com.toer15.queststeam.ui.LetterFilter
import com.toer15.queststeam.ui.LibraryViewModel
import com.toer15.queststeam.ui.SteamArt
import com.toer15.queststeam.ui.SteamColors
import com.toer15.queststeam.ui.SteamTheme
import com.toer15.queststeam.ui.steamBackdrop

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0E141B.toInt()
        window.navigationBarColor = 0xFF070B10.toInt()
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
    var showKey by remember { mutableStateOf(false) }

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

    Column(Modifier.fillMaxSize().steamBackdrop()) {
        TopBar(tab, { tab = it }, profile?.avatarUrl, profile?.personaName, syncing,
            onSync = vm::sync,
            onSettings = { ctx.startActivity(Intent(ctx, RuntimeActivity::class.java)) },
            onSignOut = { vm.signOut(); onSignedOut() },
            onApiKey = { showKey = true })
        if (status.isNotBlank()) Text(
            status,
            color = if (status == KeylessSteam.SIGN_IN_AGAIN) Color(0xFFFFC46B) else SteamColors.Muted,
            fontSize = 13.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(320)) { dir * it / 6 } + fadeIn(tween(280))) togetherWith
                    (slideOutHorizontally(tween(240)) { -dir * it / 8 } + fadeOut(tween(180)))
            },
            label = "tabs",
        ) { current ->
            when (current) {
                Tab.Home -> HomeScreen(games, ::stateOf, ::actionsFor, onBrowse = { tab = Tab.Games }, onSync = vm::sync)
                Tab.Games -> GamesScreen(games, ::stateOf, ::actionsFor)
            }
        }
    }

    if (showKey) ApiKeyDialog(vm.apiKey.orEmpty(), onSave = { vm.apiKey = it; showKey = false }, onDismiss = { showKey = false })

    confirmUninstall?.let { g ->
        AlertDialog(onDismissRequest = { confirmUninstall = null },
            title = { Text("Uninstall ${g.name}?") }, text = { Text("Deletes the game's files from this device.") },
            confirmButton = { TextButton(onClick = { vm.downloads.uninstall(g.appId); confirmUninstall = null }) { Text("Uninstall") } },
            dismissButton = { TextButton(onClick = { confirmUninstall = null }) { Text("Cancel") } })
    }
}

@Composable
private fun TopBar(tab: Tab, onTab: (Tab) -> Unit, avatar: String?, name: String?, syncing: Boolean,
                   onSync: () -> Unit, onSettings: () -> Unit, onSignOut: () -> Unit, onApiKey: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val spin by rememberInfiniteTransition(label = "sync").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "syncSpin",
    )
    Column(Modifier.fillMaxWidth().background(SteamColors.Glass)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (t in Tab.entries) {
                    val sel = t == tab
                    val bar by animateDpAsState(if (sel) 26.dp else 0.dp, tween(280), label = "tabBar")
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onTab(t) }.padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            t.name.uppercase(), fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.1.sp,
                            color = if (sel) Color.White else SteamColors.Muted,
                        )
                        Box(Modifier.padding(top = 5.dp).width(bar).height(3.dp).clip(RoundedCornerShape(50)).background(SteamColors.Accent))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSync, enabled = !syncing) {
                Icon(Icons.Filled.Refresh, "Sync Steam account", tint = SteamColors.Text, modifier = Modifier.graphicsLayer { if (syncing) rotationZ = spin })
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Runtime settings", tint = SteamColors.Text) }
            Box {
                Box(
                    Modifier.size(42.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(SteamColors.Accent, Color.White)))
                        .clickable { menu = true }
                        .padding(2.dp).clip(CircleShape).background(SteamColors.Card),
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatar.isNullOrBlank()) Icon(Icons.Filled.Person, "Profile", tint = SteamColors.Muted)
                    else AsyncImage(model = avatar, contentDescription = "Profile", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(name ?: "Steam user") }, onClick = {}, enabled = false)
                    DropdownMenuItem(text = { Text("Sync Steam account") }, onClick = { menu = false; onSync() })
                    DropdownMenuItem(text = { Text("Runtime & settings") }, onClick = { menu = false; onSettings() })
                    DropdownMenuItem(text = { Text("Steam API key (optional)…") }, onClick = { menu = false; onApiKey() })
                    DropdownMenuItem(text = { Text("Sign out") }, onClick = { menu = false; onSignOut() })
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
    }
}

@Composable
private fun HomeScreen(games: List<OwnedGame>, stateOf: (OwnedGame) -> GameState, actionsFor: (OwnedGame) -> CardActions,
                       onBrowse: () -> Unit, onSync: () -> Unit) {
    val installed = games.filter { stateOf(it) is GameState.Installed || stateOf(it) is GameState.Downloading }
    val recent = games.sortedByDescending { it.playtimeMinutes }.take(12)
    val hero = games.maxByOrNull { it.playtimeMinutes }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Welcome back", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.4).sp)
                Text(DeviceMode.label(androidx.compose.ui.platform.LocalContext.current), color = SteamColors.Muted, fontSize = 13.sp)
            }
        }
        if (games.isEmpty()) {
            item {
                Button(
                    onClick = onSync, shape = RoundedCornerShape(50),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SteamColors.Accent, contentColor = Color(0xFF0E141B)),
                ) { Text("Sync Steam account", fontWeight = FontWeight.Bold) }
            }
        } else if (hero != null) {
            item { HeroBanner(hero, stateOf(hero), actionsFor(hero)) }
            item { Shelf("Installed & downloading", installed, stateOf, actionsFor, empty = "Nothing installed yet — open Games and tap a card.") }
            item { Shelf("Most played", recent, stateOf, actionsFor) }
            item {
                TextButton(onClick = onBrowse, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("Browse all ${games.size} games →", fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun HeroBanner(game: OwnedGame, state: GameState, actions: CardActions) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        val wide = maxWidth >= 720.dp
        val h = if (wide) 280.dp else 210.dp
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(28.dp)).background(SteamColors.Card)) {
            SteamArt(game.headerUrl, game.name, Modifier.fillMaxSize(), ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xE6101418), Color(0x40101418), Color.Transparent))))
            Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                if (game.isVr) Text(
                    "VR", color = Color(0xFF0E141B), fontWeight = FontWeight.Bold, fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp).background(SteamColors.Accent, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Text("Most played", color = SteamColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                Text(game.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = if (wide) 32.sp else 24.sp, lineHeight = 34.sp)
                Spacer(Modifier.height(6.dp))
                Text("${game.playtimeMinutes / 60} h played", color = Color(0xFFD5DEE6), fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                when (state) {
                    is GameState.Installed -> Button(
                        onClick = actions.onPlay, shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(containerColor = SteamColors.Green, contentColor = Color.White),
                    ) { Text("Play Game") }
                    is GameState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.total > 0) CircularProgressIndicator(
                            progress = { state.fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.size(28.dp), color = SteamColors.Accent, trackColor = Color.White.copy(0.2f), strokeWidth = 3.dp,
                        ) else CircularProgressIndicator(modifier = Modifier.size(28.dp), color = SteamColors.Accent, strokeWidth = 3.dp)
                        Text("  Downloading ${(state.fraction * 100).toInt()}%", color = Color.White, fontSize = 14.sp)
                    }
                    else -> Text("Open the card to download", color = SteamColors.Muted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun Shelf(title: String, list: List<OwnedGame>, stateOf: (OwnedGame) -> GameState, actionsFor: (OwnedGame) -> CardActions, empty: String? = null) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Color.White, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    if (list.isEmpty() && empty != null) {
        Text(empty, color = SteamColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp, top = 4.dp)) {
        items(list, key = { it.appId }) { g -> GameCard(g, stateOf(g), actionsFor(g), Modifier.width(300.dp).padding(vertical = 6.dp)) }
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
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PillFilter(
                selected = query.isNotBlank() || letters.isNotEmpty(),
                onClick = { popup = true },
                label = if (letters.isNotEmpty()) "☰ $letters" else if (query.isNotBlank()) "☰ \"$query\"" else "☰ Browse",
                modifier = Modifier.padding(start = 16.dp),
            )
            LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cats) { c ->
                    val count = games.count { Categories.matches(c, it, stateOf(it) is GameState.Installed) }
                    Box(Modifier.combinedClickable(onClick = { category = c }, onLongClick = { popup = true })) {
                        PillFilter(selected = c == category, onClick = { category = c }, label = "$c ($count)")
                    }
                }
            }
        }
        if (shown.isEmpty()) Text("No games match.", color = SteamColors.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyVerticalGrid(
            GridCells.Adaptive(220.dp),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(shown, key = { it.appId }) { g -> GameCard(g, stateOf(g), actionsFor(g), Modifier.padding(2.dp)) }
        }
    }
    if (popup) CategoryPopup(
        games = games, query = query, onQuery = { query = it },
        onAllGames = { query = ""; letters = ""; category = Categories.ALL; popup = false },
        onLetters = { letters = it; popup = false },
        onDismiss = { popup = false },
    )
}

@Composable
private fun PillFilter(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.White.copy(alpha = 0.06f),
            labelColor = SteamColors.Text,
            selectedContainerColor = SteamColors.Accent.copy(alpha = 0.22f),
            selectedLabelColor = Color.White,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Color.White.copy(alpha = 0.14f),
            selectedBorderColor = SteamColors.Accent.copy(alpha = 0.85f),
        ),
    )
}

/** Optional: player's own Steam Web API key, stored only on this device. */
@Composable
private fun ApiKeyDialog(current: String, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf(current) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Steam Web API key (optional)") },
        text = {
            Column {
                Text("Not needed normally: your library loads from your Steam sign-in, including a private profile. Add your own key if that fails. It's stored only on this device.", fontSize = 13.sp, lineHeight = 18.sp)
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it.trim() }, singleLine = true,
                    label = { Text("API key") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://steamcommunity.com/dev/apikey"))) }) {
                    Text("Get a key: steamcommunity.com/dev/apikey")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.ifBlank { null }) }) { Text("Save") } },
        dismissButton = { Row { TextButton(onClick = { onSave(null) }) { Text("Remove") }; TextButton(onClick = onDismiss) { Text("Cancel") } } })
}
