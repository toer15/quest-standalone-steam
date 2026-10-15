package com.toer15.queststeam.steam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.toer15.queststeam.play.GameRunner
import com.toer15.queststeam.steamclient.FriendMessage
import com.toer15.queststeam.steamclient.SteamFriend
import com.toer15.queststeam.steamclient.SteamSession
import com.toer15.queststeam.ui.SteamColors
import com.toer15.queststeam.ui.focusRing
import `in`.dragonbra.javasteam.enums.EPersonaState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private val InGame = Color(0xFF90BA3C)
private val Online = Color(0xFF57CBDE)

/** Chat messages of this app session, per friend, and who wrote since you last looked. */
object FriendChats {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _all = MutableStateFlow<Map<Long, List<FriendMessage>>>(emptyMap())
    val all: StateFlow<Map<Long, List<FriendMessage>>> = _all
    private val _unread = MutableStateFlow<Set<Long>>(emptySet())
    val unread: StateFlow<Set<Long>> = _unread
    @Volatile private var started = false

    fun start(session: SteamSession) {
        if (started) return
        started = true
        scope.launch {
            session.messages.collect { m ->
                _all.update { it + (m.friendId to ((it[m.friendId] ?: emptyList()) + m).takeLast(300)) }
                if (m.incoming) _unread.update { it + m.friendId }
            }
        }
    }

    fun read(friendId: Long) = _unread.update { it - friendId }
}

/** Names of the games friends play (Steam only sends the app id), from the store, cached. */
object GameNames {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val asked = ConcurrentHashMap.newKeySet<Int>()
    private val _names = MutableStateFlow<Map<Int, String>>(emptyMap())
    val names: StateFlow<Map<Int, String>> = _names

    fun want(appIds: Collection<Int>, known: (Int) -> String?) {
        for (id in appIds) {
            if (id == 0 || !asked.add(id)) continue
            val local = known(id)
            if (local != null) { _names.update { it + (id to local) }; continue }
            scope.launch {
                runCatching {
                    val root = JSONObject(URL("https://store.steampowered.com/api/appdetails?appids=$id&filters=basic").readText())
                    root.getJSONObject(id.toString()).getJSONObject("data").getString("name")
                }.getOrNull()?.let { n -> _names.update { it + (id to n) } }
            }
        }
    }
}

/**
 * Steam's friends and chat, native and fast like the Store tab: from the app's own Steam connection,
 * not Steam for Windows' window (which is slow under the Windows translation). Steam for Windows
 * still runs in the background for online games; the card at the top says whether it's running.
 */
@Composable
fun FriendsScreen(
    myName: String?, myAvatar: String?, knownGame: (Int) -> String?,
    onSignIn: () -> Unit, onOpenSteamWindow: () -> Unit,
) {
    val ctx = LocalContext.current
    val session = remember { SteamAccount.get(ctx).session }
    val status by session.status.collectAsState()
    val friends by session.friends.collectAsState()
    val chats by FriendChats.all.collectAsState()
    val unread by FriendChats.unread.collectAsState()
    val gameNames by GameNames.names.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    val signedIn = status != SteamSession.Status.SignedOut

    LaunchedEffect(signedIn) {
        if (!signedIn) return@LaunchedEffect
        FriendChats.start(session)
        error = runCatching { session.openFriends() }.exceptionOrNull()?.let { it.message ?: it.toString() }
    }
    LaunchedEffect(friends) { GameNames.want(friends.filter { it.inGame && it.gameName == null }.map { it.gameAppId }, knownGame) }
    selected?.let { id -> LaunchedEffect(id, chats[id]?.size) { FriendChats.read(id) } }

    if (!signedIn) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Friends & chat", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Sign in to Steam in the app to see your friends, what they play, and chat with them.",
                color = SteamColors.Muted, fontSize = 15.sp,
            )
            Spacer(Modifier.height(16.dp))
            Pill("Sign in to Steam", primary = true, onClick = onSignIn)
        }
        return
    }

    val sorted = remember(friends, gameNames) {
        friends.filter { it.name.isNotBlank() }.sortedWith(
            compareByDescending<SteamFriend> { it.inGame }.thenByDescending { it.online }
                .thenByDescending { if (it.online) 0L else it.lastLogoff }.thenBy { it.name.lowercase() },
        )
    }
    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(0.42f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(myAvatar, 52.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(myName ?: session.personaName.value ?: "You", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (status) {
                            SteamSession.Status.Online -> "Online"
                            SteamSession.Status.Connecting, SteamSession.Status.SigningIn -> "Connecting to Steam…"
                            else -> "Offline"
                        },
                        color = if (status == SteamSession.Status.Online) Online else SteamColors.Muted, fontSize = 14.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            SteamForWindowsCard(onOpenSteamWindow)
            error?.let { Text(it, color = SteamColors.Danger, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(8.dp))
            if (sorted.isEmpty()) {
                Text(
                    if (status == SteamSession.Status.Online) "Loading your friends…" else "Connecting to Steam…",
                    color = SteamColors.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                val groups = listOf(
                    "In-game" to sorted.filter { it.inGame },
                    "Online" to sorted.filter { it.online && !it.inGame },
                    "Offline" to sorted.filter { !it.online },
                )
                for ((label, list) in groups) {
                    if (list.isEmpty()) continue
                    item(key = "h-$label") {
                        Text(
                            "${label.uppercase()} (${list.size})", color = SteamColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { it.steamId }) { f ->
                        FriendRow(f, statusText(f, gameNames), selected == f.steamId, f.steamId in unread) { selected = f.steamId }
                    }
                }
            }
        }
        Box(Modifier.weight(0.58f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(SteamColors.Panel.copy(alpha = 0.85f))) {
            val friend = sorted.firstOrNull { it.steamId == selected } ?: friends.firstOrNull { it.steamId == selected }
            if (friend == null) {
                Text(
                    "Choose a friend to chat.", color = SteamColors.Muted, fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                ChatPanel(session, friend, statusText(friend, gameNames), chats[friend.steamId].orEmpty()) { error = it }
            }
        }
    }
}

private fun statusText(f: SteamFriend, names: Map<Int, String>): String = when {
    f.inGame -> "Playing " + (f.gameName ?: names[f.gameAppId] ?: "a game")
    f.state == EPersonaState.Away || f.state == EPersonaState.Snooze -> "Away"
    f.state == EPersonaState.Busy -> "Busy"
    f.online -> "Online"
    f.lastLogoff > 0 -> "Last online " + ago(f.lastLogoff)
    else -> "Offline"
}

private fun ago(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / (24 * 60)} days ago"
    }
}

@Composable
private fun FriendRow(f: SteamFriend, status: String, selected: Boolean, unread: Boolean, onClick: () -> Unit) {
    val color = when { f.inGame -> InGame; f.online -> Online; else -> SteamColors.Muted }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .focusRing(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(f.avatarUrl, 42.dp, alpha = if (f.online) 1f else 0.55f)
            Box(Modifier.align(Alignment.BottomEnd).size(11.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, color = if (f.online) Color.White else SteamColors.Text.copy(alpha = 0.7f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, color = color, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (unread) Box(Modifier.size(10.dp).clip(CircleShape).background(SteamColors.Accent))
    }
}

@Composable
private fun ChatPanel(session: SteamSession, f: SteamFriend, status: String, messages: List<FriendMessage>, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember(f.steamId) { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(0) }
    fun send() {
        val t = text.trim()
        if (t.isEmpty()) return
        text = ""
        scope.launch { runCatching { session.sendMessage(f.steamId, t) }.onFailure { onError("Couldn't send: ${it.message}") } }
    }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(f.avatarUrl, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(f.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(status, color = if (f.inGame) InGame else if (f.online) Online else SteamColors.Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, reverseLayout = true) {
            if (messages.isEmpty()) item {
                Text("No messages yet. Messages sent here also show up in Steam on your other devices.", color = SteamColors.Muted, fontSize = 14.sp)
            }
            items(messages.reversed(), key = { it.time.toString() + it.text.hashCode() + it.incoming }) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (m.incoming) Arrangement.Start else Arrangement.End) {
                    Text(
                        m.text, color = if (m.incoming) Color.White else Color(0xFF0E141B), fontSize = 15.sp,
                        modifier = Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (m.incoming) Color.White.copy(alpha = 0.10f) else SteamColors.Accent)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text("Message ${f.name}") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SteamColors.Accent, unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Pill("Send", primary = true) { send() }
        }
    }
}

/** Steam for Windows (what online games talk to): running in the background or not, and its window. */
@Composable
private fun SteamForWindowsCard(onOpenSteamWindow: () -> Unit) {
    val ctx = LocalContext.current
    val running by produceState(GameRunner.steamRunning()) {
        while (true) { value = GameRunner.steamRunning(); delay(3000) }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SteamColors.Card.copy(alpha = 0.8f)).padding(12.dp)) {
        Text(
            if (running) "● Steam for online games is running in the background" else "○ Steam for online games starts when you play",
            color = if (running) InGame else SteamColors.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (running) "Show its window" else "Open its window (slow)", primary = false, onClick = onOpenSteamWindow)
            if (running) Pill("Close it", primary = false) { GameRunner.stop(ctx) }
        }
    }
}

@Composable
private fun Avatar(url: String?, size: androidx.compose.ui.unit.Dp, alpha: Float = 1f) {
    Box(Modifier.size(size).clip(CircleShape).background(SteamColors.Card)) {
        if (!url.isNullOrBlank()) AsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Crop, alpha = alpha,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun Pill(label: String, primary: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (primary) Color(0xFF0E141B) else SteamColors.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (primary) SteamColors.Accent else Color.White.copy(alpha = 0.08f))
            .focusRing().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    )
}
