package com.toer15.queststeam.store

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toer15.queststeam.DemoShots
import com.toer15.queststeam.ui.GameArt
import com.toer15.queststeam.ui.LocalDeviceUi
import com.toer15.queststeam.ui.SteamColors
import com.toer15.queststeam.ui.focusRing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Store browsing state: section or search, pages loaded so far, prices. */
class StoreViewModel(app: Application) : AndroidViewModel(app) {
    private val _items = MutableStateFlow<List<StoreItem>>(emptyList())
    val items: StateFlow<List<StoreItem>> = _items
    private val _prices = MutableStateFlow<Map<Int, StorePrice>>(emptyMap())
    val prices: StateFlow<Map<Int, StorePrice>> = _prices
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _section = MutableStateFlow(StoreApi.Section.TopSellers)
    val section: StateFlow<StoreApi.Section> = _section
    private val _term = MutableStateFlow("")
    val term: StateFlow<String> = _term

    private var job: Job? = null
    private var searchJob: Job? = null
    private var endReached = false

    init {
        if (DemoShots.armed) {
            _items.value = DemoShots.storeItems()
            _prices.value = DemoShots.storePrices()
            endReached = true
        } else reload()
    }

    fun select(s: StoreApi.Section) { _section.value = s; _term.value = ""; reload() }

    /** Typing in the search bar: searches every game on Steam after a short pause. */
    fun search(t: String) {
        _term.value = t
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(450); reload() }
    }

    fun reload() {
        if (DemoShots.armed) return
        job?.cancel()
        endReached = false
        _items.value = emptyList()
        loadMore()
    }

    fun loadMore() {
        if (DemoShots.armed) return
        if (_loading.value && job?.isActive == true) return
        if (endReached) return
        val start = _items.value.size
        job = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val page = StoreApi.page(_section.value, _term.value, start)
                if (page.isEmpty()) {
                    endReached = true
                    if (start == 0) _error.value = if (_term.value.isNotBlank()) "No games found for \"${_term.value}\"." else "Couldn't reach the Steam store. Check your internet connection."
                }
                val known = _items.value.map { it.appId }.toHashSet()
                val fresh = page.filter { it.appId !in known }
                if (fresh.isEmpty() && page.isNotEmpty()) endReached = true
                _items.value = _items.value + fresh
                val missing = fresh.map { it.appId }.filter { it !in _prices.value }
                if (missing.isNotEmpty()) _prices.value = _prices.value + StoreApi.prices(missing)
            } finally {
                _loading.value = false
            }
        }
    }
}

/**
 * Store tab: search bar at the top middle, sections (top sellers, new, specials, VR, free, all),
 * then every matching Steam game, loading more as you scroll. Tap a game for details and Buy.
 */
@Composable
fun StoreScreen(owned: Set<Int>, onOpenGame: (StoreItem) -> Unit, vm: StoreViewModel = viewModel()) {
    val ui = LocalDeviceUi.current
    val items by vm.items.collectAsState()
    val prices by vm.prices.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val section by vm.section.collectAsState()
    val term by vm.term.collectAsState()
    val focus = LocalFocusManager.current
    val grid = rememberLazyGridState()

    // Infinite scroll: load the next page near the end.
    val nearEnd by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(grid) {
        snapshotFlow { nearEnd to grid.layoutInfo.totalItemsCount }.collect { (last, total) ->
            if (total > 0 && last >= total - 12) vm.loadMore()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Search bar, top middle.
        Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
            OutlinedTextField(
                value = term,
                onValueChange = vm::search,
                singleLine = true,
                placeholder = { Text("Search the Steam store") },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = SteamColors.Muted) },
                trailingIcon = {
                    if (term.isNotEmpty()) Text("✕", color = SteamColors.Muted, fontSize = 18.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { vm.search("") }.padding(10.dp))
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                shape = RoundedCornerShape(50),
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = ui.gutter),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SteamColors.Accent, unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                    focusedTextColor = SteamColors.Text, unfocusedTextColor = SteamColors.Text, cursorColor = SteamColors.Accent,
                    focusedContainerColor = Color.White.copy(alpha = 0.05f), unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                ),
            )
        }
        if (term.isBlank()) LazyRow(
            contentPadding = PaddingValues(horizontal = ui.gutter, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(StoreApi.Section.entries.toList()) { s ->
                val sel = s == section
                Text(
                    s.label,
                    color = if (sel) Color(0xFF0E141B) else SteamColors.Text, fontWeight = FontWeight.SemiBold,
                    fontSize = if (ui.isQuest) 16.sp else 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (sel) SteamColors.Accent else Color.White.copy(alpha = 0.07f))
                        .focusRing()
                        .clickable { vm.select(s) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        } else Text(
            "Results for \"$term\"", color = SteamColors.Muted, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = ui.gutter, vertical = 8.dp),
        )
        error?.let { Text(it, color = SteamColors.Muted, modifier = Modifier.padding(horizontal = ui.gutter, vertical = 8.dp)) }
        LazyVerticalGrid(
            GridCells.Adaptive(ui.gridCardMin),
            state = grid,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(ui.gutter),
            horizontalArrangement = Arrangement.spacedBy(ui.cardGap),
            verticalArrangement = Arrangement.spacedBy(ui.cardGap),
        ) {
            items(items, key = { it.appId }) { item ->
                StoreCard(item, prices[item.appId], item.appId in owned) { onOpenGame(item) }
            }
            if (loading) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SteamColors.Accent, modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}

@Composable
private fun StoreCard(item: StoreItem, price: StorePrice?, owned: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.clip(shape).background(SteamColors.Card)
            .border(if (focused) 3.dp else 0.dp, if (focused) SteamColors.Accent else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        GameArt(item.appId, item.name, Modifier.fillMaxWidth().aspectRatio(460f / 215f), showNameIfMissing = true)
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(item.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (owned) Text(
                    "IN LIBRARY", color = Color(0xFF10200A), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(SteamColors.GreenBright, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
                ) else PriceTag(price)
            }
        }
    }
}

@Composable
fun PriceTag(price: StorePrice?, big: Boolean = false) {
    val fs = if (big) 18.sp else 14.sp
    when {
        price == null -> Text("…", color = SteamColors.Muted, fontSize = fs)
        price.discountPercent > 0 -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "-${price.discountPercent}%", color = Color(0xFFBEEE11), fontSize = fs, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color(0xFF4C6B22), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
            price.initial?.let { Text(it, color = SteamColors.Muted, fontSize = fs * 0.85f, textDecoration = TextDecoration.LineThrough) }
            Spacer(Modifier.width(6.dp))
            Text(price.final, color = Color(0xFFBEEE11), fontSize = fs, fontWeight = FontWeight.SemiBold)
        }
        else -> Text(if (price.free) "Free to play" else price.final, color = SteamColors.Text, fontSize = fs, fontWeight = FontWeight.SemiBold)
    }
}

/** Game details from the store with Buy (Steam's checkout) or "In your library". */
@Composable
fun StoreGameDialog(item: StoreItem, owned: Boolean, onBuy: () -> Unit, onOpenInLibrary: () -> Unit, onDismiss: () -> Unit) {
    val ui = LocalDeviceUi.current
    val details by produceState<StoreDetails?>(null, item.appId) { value = StoreApi.details(item.appId) }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = if (ui.isQuest) 720.dp else 440.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1E2E3E), Color(0xFF131C26))))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(28.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            GameArt(item.appId, item.name, Modifier.fillMaxWidth().aspectRatio(460f / 215f), showNameIfMissing = true)
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState())) {
                Text(details?.name?.ifBlank { null } ?: item.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                val d = details
                if (d == null) CircularProgressIndicator(color = SteamColors.Accent, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                else if (d.description.isNotBlank()) Text(d.description, color = SteamColors.Text, fontSize = 15.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (owned) Text("In your library", color = SteamColors.GreenBright, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    else if (d?.comingSoon == true) Text("Coming soon", color = SteamColors.Muted, fontSize = 16.sp)
                    else PriceTag(d?.price, big = true)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Close", color = SteamColors.Text, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).focusRing()
                            .clickable(onClick = onDismiss).padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    val label = when {
                        owned -> "Go to game"
                        d?.price?.free == true -> "Add to library"
                        d?.comingSoon == true -> "View on Steam"
                        else -> "Buy" + (d?.price?.final?.let { " · $it" } ?: "")
                    }
                    Text(
                        label, color = Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (owned) SteamColors.Accent.copy(alpha = 0.4f) else SteamColors.Green)
                            .focusRing().clickable { if (owned) onOpenInLibrary() else onBuy() }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
                if (!owned) Text(
                    "You pay on Steam's own checkout page (sign in to the Steam store once). The game then shows up in your library with Download.",
                    color = SteamColors.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
