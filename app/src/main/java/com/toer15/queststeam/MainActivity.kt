package com.toer15.queststeam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.toer15.queststeam.gamepad.PadNav
import com.toer15.queststeam.play.GameRunner
import com.toer15.queststeam.store.StoreActivity
import com.toer15.queststeam.store.StoreApi
import com.toer15.queststeam.store.StoreGameDialog
import com.toer15.queststeam.store.StoreItem
import com.toer15.queststeam.store.StoreScreen
import com.toer15.queststeam.ui.ControllerChip
import com.toer15.queststeam.ui.PlayDialog
import com.toer15.queststeam.ui.PlayMode
import com.toer15.queststeam.ui.VrSupport
import com.toer15.queststeam.ui.focusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
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
import com.toer15.queststeam.steam.SteamAccount
import com.toer15.queststeam.ui.LocalDeviceUi
import com.toer15.queststeam.ui.FilterPanel
import com.toer15.queststeam.ui.HomeFilters
import com.toer15.queststeam.update.UpdateDialog
import com.toer15.queststeam.update.UpdateUi
import com.toer15.queststeam.update.UpdateViewModel
import androidx.compose.foundation.layout.widthIn
import com.toer15.queststeam.ui.CardActions
import com.toer15.queststeam.ui.CategoryPopup
import com.toer15.queststeam.ui.GameCard
import com.toer15.queststeam.ui.LetterFilter
import com.toer15.queststeam.ui.LibraryViewModel
import com.toer15.queststeam.ui.GameArt
import com.toer15.queststeam.ui.SteamColors
import com.toer15.queststeam.ui.SteamTheme
import com.toer15.queststeam.ui.steamBackdrop

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0E141B.toInt()
        window.navigationBarColor = 0xFF070B10.toInt()
        if (SessionStore(this).steamId == null && !SteamAccount.get(this).session.isSignedIn) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        if (intent.getBooleanExtra(com.toer15.queststeam.update.UpdatedReceiver.EXTRA_JUST_UPDATED, false)) {
            android.widget.Toast.makeText(this, "Updated to ${BuildConfig.VERSION_NAME}", android.widget.Toast.LENGTH_LONG).show()
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(21)
        }
        val toLogin = { startActivity(Intent(this, LoginActivity::class.java)); finish() }
        setContent { SteamTheme { LauncherApp(onSignedOut = toLogin, onSteamSignIn = toLogin) } }
    }

    // A Bluetooth controller drives the app like a console (see PadNav).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        PadNav.handle(event) { super.dispatchKeyEvent(it) } ?: super.dispatchKeyEvent(event)
}

/** Top bar tabs, left to right: Home, Store (middle), Games. */
private enum class Tab { Home, Store, Games }

/** Built-in filters; genres from the Steam store are appended dynamically. */
object Categories {
    const val ALL = "All"; const val VR = "VR"; const val INSTALLED = "Installed"
    fun matches(cat: String, g: OwnedGame, installed: Boolean) = when (cat) {
        ALL -> true; VR -> g.isVr; INSTALLED -> installed; else -> cat in g.genres
    }
}

@Composable
internal fun LauncherApp(
    onSignedOut: () -> Unit,
    onSteamSignIn: () -> Unit,
    /** Shown as the panel inside the VR view: VR games start right there, without leaving VR. */
    inVr: Boolean = false,
    vm: LibraryViewModel = viewModel(),
    updates: UpdateViewModel = viewModel(),
) {
    val ui = LocalDeviceUi.current
    val updateState by updates.ui.collectAsState()
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
    var lastCrash by remember { mutableStateOf(CrashGuard.lastCrash()) }
    var homeFilters by remember { mutableStateOf(HomeFilters()) }
    var filtersOpen by remember { mutableStateOf(false) }
    var playFor by remember { mutableStateOf<OwnedGame?>(null) }
    var storeItem by remember { mutableStateOf<StoreItem?>(null) }
    // Back from Steam's checkout: sync so a game just bought shows up in the library.
    val storeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.sync() }
    val homeTabFocus = remember { FocusRequester() }

    // Controller buttons (LB/RB tabs, B back, Y filters, X sync, Start updates).
    LaunchedEffect(Unit) {
        PadNav.actions.collect { a ->
            when (a) {
                PadNav.Action.Back -> when {
                    storeItem != null -> storeItem = null
                    playFor != null -> playFor = null
                    filtersOpen -> filtersOpen = false
                    PadNav.consumeBack() -> Unit
                    tab != Tab.Home -> tab = Tab.Home
                }
                PadNav.Action.TabLeft -> tab = Tab.entries[(tab.ordinal - 1 + Tab.entries.size) % Tab.entries.size]
                PadNav.Action.TabRight -> tab = Tab.entries[(tab.ordinal + 1) % Tab.entries.size]
                PadNav.Action.Filters -> if (tab == Tab.Home) filtersOpen = !filtersOpen
                PadNav.Action.Sync -> vm.sync()
                PadNav.Action.Updates -> updates.check()
                PadNav.Action.FocusStart -> runCatching { homeTabFocus.requestFocus() }
            }
        }
    }
    // A new release can come out while the app stays open: check again whenever it comes back.
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) updates.checkQuietly() }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val id = pendingImport; pendingImport = null
        if (uri != null && id != null) vm.downloads.download(id, FolderImportSource(uri))
    }

    // One CardActions per game, so cards don't recompose just because new lambdas were created.
    val actionsCache = remember { HashMap<Int, CardActions>() }
    fun newActions(g: OwnedGame) = CardActions(
        onDownload = { vm.downloads.download(g.appId) },
        onImport = { pendingImport = g.appId; pickFolder.launch(null) },
        onCancel = { vm.downloads.cancel(g.appId) },
        // Steam-style popup first: VR or flat, and the controller status.
        onPlay = { playFor = g },
        onUninstall = { confirmUninstall = g },
    )
    fun actionsFor(g: OwnedGame) = actionsCache.getOrPut(g.appId) { newActions(g) }
    fun stateOf(g: OwnedGame) = states[g.appId] ?: vm.downloads.stateOf(g.appId)

    fun play(g: OwnedGame, mode: PlayMode) {
        val vr = mode == PlayMode.VR
        val exe = vm.downloads.mainExe(g.appId, vr)
        if (inVr && vr && exe != null) {
            // Already in the headset view: start the game here; the panel shows the loading screen.
            GameRunner.play(ctx, exe, vm.downloads.launchArgs(g.appId, true), RuntimeActivity.MODE_VR, g.name)
            return
        }
        ctx.startActivity(
            Intent(ctx, RuntimeActivity::class.java)
                .putExtra(RuntimeActivity.EXTRA_EXE, exe?.path)
                .putExtra(RuntimeActivity.EXTRA_ARGS, vm.downloads.launchArgs(g.appId, vr))
                .putExtra(RuntimeActivity.EXTRA_TITLE, g.name)
                .putExtra(RuntimeActivity.EXTRA_MODE, if (vr) RuntimeActivity.MODE_VR else RuntimeActivity.MODE_FLAT)
                .putExtra(RuntimeActivity.EXTRA_APPID, g.appId)
        )
    }

    Box(
        Modifier.fillMaxSize().steamBackdrop().onFocusChanged { PadNav.hasFocus = it.hasFocus },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = ui.contentMaxWidth).fillMaxSize()) {
            TopBar(tab, { tab = it }, homeTabFocus, profile?.avatarUrl, profile?.personaName, syncing,
                steamClient = vm.steamClientSignedIn,
                onSync = vm::sync,
                onSettings = { ctx.startActivity(Intent(ctx, RuntimeActivity::class.java)) },
                onEnterVr = if (!inVr && VrBridge.canPlayInVr(ctx)) ({ VrBridge.enterVr(ctx) }) else null,
                onSignOut = { vm.signOut(); onSignedOut() },
                onSteamSignIn = onSteamSignIn,
                onApiKey = { showKey = true })
            if (status.isNotBlank()) Text(
                status,
                color = if (status == KeylessSteam.SIGN_IN_AGAIN || status.contains("Sign in to Steam")) Color(0xFFFFC46B) else SteamColors.Muted,
                fontSize = if (ui.isQuest) 15.sp else 13.sp, lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = ui.gutter, vertical = 6.dp),
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
                    Tab.Home -> HomeScreen(
                        games, ::stateOf, ::actionsFor, onBrowse = { tab = Tab.Games }, onSync = vm::sync,
                        onCheckUpdates = updates::check, filters = homeFilters, onOpenFilters = { filtersOpen = true },
                    )
                    Tab.Store -> StoreScreen(owned = remember(games) { games.mapTo(HashSet()) { it.appId } }, onOpenGame = { storeItem = it })
                    Tab.Games -> GamesScreen(games, ::stateOf, ::actionsFor)
                }
            }
        }
        val allGenres = remember(games) { games.flatMap { it.genres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
        FilterPanel(
            visible = filtersOpen && tab == Tab.Home,
            filters = homeFilters,
            genres = allGenres,
            matching = homeFilters.apply(games) { stateOf(it) is GameState.Installed }.size,
            onChange = { homeFilters = it },
            onClose = { filtersOpen = false },
        )
        storeItem?.let { item ->
            StoreGameDialog(
                item = item,
                owned = games.any { it.appId == item.appId },
                onBuy = { storeLauncher.launch(StoreActivity.intent(ctx, StoreApi.storeUrl(item.appId))) },
                onOpenInLibrary = { storeItem = null; tab = Tab.Games },
                onDismiss = { storeItem = null },
            )
        }
        playFor?.let { g ->
            // VR is offered for VR games when the headset bridge is in this build; SteamVR (OpenVR) games can use it.
            val canVr = VrBridge.canPlayInVr(ctx)
            val steamVr by produceState<Boolean?>(null, g.appId) {
                value = withContext(Dispatchers.IO) { runCatching { VrBridge.isVrGame(vm.downloads.dirFor(g.appId)) }.getOrDefault(false) }
            }
            val vrOffered = canVr && (g.isVr || steamVr == true || vm.downloads.hasVrLaunch(g.appId))
            PlayDialog(
                game = g,
                vrOffered = vrOffered,
                vrSupport = if (steamVr == false && g.isVr) VrSupport.NOT_STEAMVR else VrSupport.YES,
                onPlay = { mode -> playFor = null; play(g, mode) },
                onDismiss = { playFor = null },
            )
        }
    }

    // Platform dialogs need an Activity window; the VR panel is a virtual display, so it skips these
    // (updates and crash reports show in the 2D app) and confirms Uninstall inside the panel.
    if (!inVr) UpdateDialog(
        state = updateState,
        onUpdateNow = updates::updateNow,
        onInstall = { updates.install(it.release, it.apk) },
        onCancel = updates::cancel,
        onDismiss = updates::dismiss,
        onRetry = { (updateState as? UpdateUi.Failed)?.release?.let(updates::updateNow) ?: updates.check() },
    )

    if (!inVr) lastCrash?.let { report ->
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { CrashGuard.clearLastCrash(); lastCrash = null },
            title = { Text("The app closed unexpectedly last time") },
            text = {
                Column {
                    Text("Copy this and send it to the developer so it can be fixed:", fontSize = 13.sp)
                    Text(
                        report.take(4000), fontSize = 11.sp, lineHeight = 14.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(top = 8.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(report)) }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { CrashGuard.clearLastCrash(); lastCrash = null }) { Text("Close") } },
        )
    }

    if (showKey && !inVr) ApiKeyDialog(vm.apiKey.orEmpty(), onSave = { vm.apiKey = it; showKey = false }, onDismiss = { showKey = false })

    if (inVr) confirmUninstall?.let { g ->
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { confirmUninstall = null },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.clip(RoundedCornerShape(24.dp)).background(SteamColors.Panel).clickable {}.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Uninstall ${g.name}?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Deletes the game's files from this device.", color = SteamColors.Muted, fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { confirmUninstall = null }) { Text("Cancel") }
                    TextButton(onClick = { vm.downloads.uninstall(g.appId); confirmUninstall = null }) { Text("Uninstall", color = SteamColors.Danger) }
                }
            }
        }
    } else confirmUninstall?.let { g ->
        AlertDialog(onDismissRequest = { confirmUninstall = null },
            title = { Text("Uninstall ${g.name}?") }, text = { Text("Deletes the game's files from this device.") },
            confirmButton = { TextButton(onClick = { vm.downloads.uninstall(g.appId); confirmUninstall = null }) { Text("Uninstall") } },
            dismissButton = { TextButton(onClick = { confirmUninstall = null }) { Text("Cancel") } })
    }
}

/** Spins only while syncing; an always-running infinite animation would redraw the app every frame. */
@Composable
private fun SyncIcon(syncing: Boolean) {
    if (!syncing) { Icon(Icons.Filled.Refresh, "Sync Steam account", tint = SteamColors.Text); return }
    val spin by rememberInfiniteTransition(label = "sync").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "syncSpin",
    )
    Icon(Icons.Filled.Refresh, "Syncing", tint = SteamColors.Text, modifier = Modifier.graphicsLayer { rotationZ = spin })
}

@Composable
private fun TopBar(tab: Tab, onTab: (Tab) -> Unit, homeFocus: FocusRequester, avatar: String?, name: String?, syncing: Boolean, steamClient: Boolean,
                   onSync: () -> Unit, onSettings: () -> Unit, onEnterVr: (() -> Unit)?, onSignOut: () -> Unit, onSteamSignIn: () -> Unit, onApiKey: () -> Unit) {
    val ui = LocalDeviceUi.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(SteamColors.Glass)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = ui.gutter - 4.dp, vertical = if (ui.isQuest) 12.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (t in Tab.entries) {
                    val sel = t == tab
                    val bar by animateDpAsState(if (sel) 26.dp else 0.dp, tween(280), label = "tabBar")
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = (if (t == Tab.Home) Modifier.focusRequester(homeFocus) else Modifier)
                            .clip(RoundedCornerShape(12.dp)).focusRing(RoundedCornerShape(12.dp)).clickable { onTab(t) }
                            .padding(horizontal = ui.tabPadH, vertical = ui.tabPadV),
                    ) {
                        Text(
                            t.name.uppercase(), fontWeight = FontWeight.Bold, fontSize = ui.tabText, letterSpacing = 1.1.sp,
                            color = if (sel) Color.White else SteamColors.Muted,
                        )
                        Box(Modifier.padding(top = 5.dp).width(bar).height(3.dp).clip(RoundedCornerShape(50)).background(SteamColors.Accent))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Connected controller (exact model) or "No controller".
            ControllerChip(Modifier.padding(end = 8.dp))
            // The app in the headset view (library, downloads and VR games, with the Touch controllers).
            if (onEnterVr != null) Text(
                "🥽 VR", color = SteamColors.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.07f))
                    .focusRing().clickable(onClick = onEnterVr).padding(horizontal = 14.dp, vertical = 9.dp),
            )
            IconButton(onClick = onSync, enabled = !syncing, modifier = Modifier.focusRing().size(ui.iconButton)) {
                SyncIcon(syncing)
            }
            if (ui.isQuest) IconButton(onClick = onSettings, modifier = Modifier.focusRing().size(ui.iconButton)) { Icon(Icons.Filled.Settings, "Runtime settings", tint = SteamColors.Text) }
            Box {
                Box(
                    Modifier.size(ui.avatar).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(SteamColors.Accent, Color.White)))
                        .focusRing(CircleShape)
                        .clickable { menu = true }
                        .padding(2.dp).clip(CircleShape).background(SteamColors.Card),
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatar.isNullOrBlank()) Icon(Icons.Filled.Person, "Profile", tint = SteamColors.Muted)
                    else AsyncImage(model = avatar, contentDescription = "Profile", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(name ?: "Steam user") }, onClick = {}, enabled = false)
                    if (!steamClient) DropdownMenuItem(text = { Text("Sign in to Steam (sync + downloads)") }, onClick = { menu = false; onSteamSignIn() })
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
                       onBrowse: () -> Unit, onSync: () -> Unit, onCheckUpdates: () -> Unit,
                       filters: HomeFilters, onOpenFilters: () -> Unit) {
    // Everything on Home follows the filter panel.
    val shown = filters.apply(games) { stateOf(it) is GameState.Installed }
    val installed = shown.filter { stateOf(it) is GameState.Installed || stateOf(it) is GameState.Downloading }
    val recent = shown.sortedByDescending { it.playtimeMinutes }.take(12)
    val hero = shown.maxByOrNull { it.playtimeMinutes }
    val ui = LocalDeviceUi.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = ui.gutter, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Welcome back", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = ui.welcomeText, letterSpacing = (-0.4).sp)
                    Text(DeviceMode.label(androidx.compose.ui.platform.LocalContext.current), color = SteamColors.Muted, fontSize = 13.sp)
                }
                // Filters: side panel with check boxes (VR only, installed, genres, sort).
                Text(
                    if (filters.activeCount > 0) "☰  Filters (${filters.activeCount})" else "☰  Filters",
                    color = if (filters.activeCount > 0) Color(0xFF0E141B) else SteamColors.Text,
                    fontSize = if (ui.isQuest) 16.sp else 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (filters.activeCount > 0) SteamColors.Accent else Color.White.copy(alpha = 0.07f))
                        .focusRing()
                        .clickable(onClick = onOpenFilters)
                        .padding(horizontal = if (ui.isQuest) 18.dp else 12.dp, vertical = if (ui.isQuest) 12.dp else 8.dp),
                )
                Spacer(Modifier.width(10.dp))
                // Check for updates: opens the update popup.
                Text(
                    "⟳  Check for updates",
                    color = SteamColors.Text, fontSize = if (ui.isQuest) 16.sp else 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.07f))
                        .focusRing()
                        .clickable(onClick = onCheckUpdates)
                        .padding(horizontal = if (ui.isQuest) 18.dp else 12.dp, vertical = if (ui.isQuest) 12.dp else 8.dp),
                )
            }
        }
        if (games.isEmpty()) {
            item {
                Button(
                    onClick = onSync, shape = RoundedCornerShape(50),
                    modifier = Modifier.padding(horizontal = ui.gutter, vertical = 8.dp).height(ui.buttonHeight),
                    colors = ButtonDefaults.buttonColors(containerColor = SteamColors.Accent, contentColor = Color(0xFF0E141B)),
                ) { Text("Sync Steam games", fontWeight = FontWeight.Bold) }
            }
        } else if (hero == null) {
            item { Text("No games match the filters.", color = SteamColors.Muted, modifier = Modifier.padding(horizontal = ui.gutter, vertical = 12.dp)) }
        } else {
            item { HeroBanner(hero, stateOf(hero), actionsFor(hero)) }
            item { Shelf("Installed & downloading", installed, stateOf, actionsFor, empty = "Nothing installed yet — tap a game card and Download.") }
            item { Shelf("Most played", recent, stateOf, actionsFor) }
            item { Shelf("Your games · ${shown.size}", shown, stateOf, actionsFor, empty = "No games match the filters.") }
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
    val ui = LocalDeviceUi.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = ui.gutter, vertical = 8.dp)) {
        val wide = maxWidth >= 720.dp
        val h = ui.heroHeight
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(28.dp)).background(SteamColors.Card)) {
            GameArt(game.appId, game.name, Modifier.fillMaxSize(), ContentScale.Crop, showNameIfMissing = true)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xE6101418), Color(0x40101418), Color.Transparent))))
            Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                if (game.isVr) Text(
                    "VR", color = Color(0xFF0E141B), fontWeight = FontWeight.Bold, fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp).background(SteamColors.Accent, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Text("Most played", color = SteamColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
                Text(game.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = if (wide) ui.heroTitle else ui.heroTitle * 0.85f, lineHeight = ui.heroTitle * 1.1f, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Text("${game.playtimeMinutes / 60} h played", color = Color(0xFFD5DEE6), fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                when (state) {
                    is GameState.Installed -> Button(
                        onClick = actions.onPlay, shape = RoundedCornerShape(50), modifier = Modifier.focusRing().height(ui.buttonHeight),
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
    val ui = LocalDeviceUi.current
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = if (ui.isQuest) 22.sp else 17.sp, color = Color.White, modifier = Modifier.padding(horizontal = ui.gutter, vertical = 8.dp))
    if (list.isEmpty() && empty != null) {
        Text(empty, color = SteamColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = ui.gutter, vertical = 4.dp))
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(ui.cardGap), contentPadding = PaddingValues(start = ui.gutter, end = ui.gutter, bottom = 12.dp, top = 4.dp)) {
        items(list, key = { it.appId }) { g -> GameCard(g, stateOf(g), actionsFor(g), Modifier.width(ui.shelfCardWidth).padding(vertical = 6.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GamesScreen(games: List<OwnedGame>, stateOf: (OwnedGame) -> GameState, actionsFor: (OwnedGame) -> CardActions) {
    val ui = LocalDeviceUi.current
    var category by rememberSaveable { mutableStateOf(Categories.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var letters by rememberSaveable { mutableStateOf("") } // "" = all, "A" or "A-F"
    var popup by remember { mutableStateOf(false) }
    // Only the installed set matters for filtering, not every download-progress tick.
    val installed = games.filter { stateOf(it) is GameState.Installed }.mapTo(HashSet()) { it.appId }
    val genres = remember(games) { games.flatMap { it.genres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    val cats = remember(genres) { listOf(Categories.ALL, Categories.VR, Categories.INSTALLED) + genres }
    val counts = remember(games, installed, cats) {
        cats.associateWith { c -> games.count { Categories.matches(c, it, it.appId in installed) } }
    }
    val shown = remember(games, installed, category, query, letters) {
        games.filter {
            Categories.matches(category, it, it.appId in installed) &&
                (query.isBlank() || it.name.contains(query, ignoreCase = true)) &&
                LetterFilter.matches(letters, it.name)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PillFilter(
                selected = query.isNotBlank() || letters.isNotEmpty(),
                onClick = { popup = true },
                label = if (letters.isNotEmpty()) "☰ $letters" else if (query.isNotBlank()) "☰ \"$query\"" else "☰ Browse",
                modifier = Modifier.padding(start = ui.gutter),
            )
            LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cats) { c ->
                    val count = counts[c] ?: 0
                    Box(Modifier.combinedClickable(onClick = { category = c }, onLongClick = { popup = true })) {
                        PillFilter(selected = c == category, onClick = { category = c }, label = "$c ($count)")
                    }
                }
            }
        }
        if (shown.isEmpty()) Text("No games match.", color = SteamColors.Muted, modifier = Modifier.padding(horizontal = ui.gutter, vertical = 8.dp))
        LazyVerticalGrid(
            GridCells.Adaptive(ui.gridCardMin),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(ui.gutter),
            horizontalArrangement = Arrangement.spacedBy(ui.cardGap),
            verticalArrangement = Arrangement.spacedBy(ui.cardGap),
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
    val ui = LocalDeviceUi.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = if (ui.isQuest) 16.sp else 13.sp, modifier = Modifier.padding(vertical = if (ui.isQuest) 8.dp else 0.dp)) },
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
