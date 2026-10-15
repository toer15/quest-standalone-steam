package com.toer15.queststeam.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.toer15.queststeam.DemoShots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/**
 * Where Steam keeps a game's header picture. Newer games only have it under store_item_assets
 * (the old /steam/apps/ address is grey for them), and some only have a capsule or library image.
 */
object SteamImages {
    private const val NEW = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps"
    private const val OLD = "https://cdn.cloudflare.steamstatic.com/steam/apps"

    fun candidates(appId: Int) = listOf(
        "$NEW/$appId/header.jpg",
        "$OLD/$appId/header.jpg",
        "$NEW/$appId/capsule_616x353.jpg",
        "$NEW/$appId/capsule_231x87.jpg",
        "$NEW/$appId/library_600x900.jpg",
    )

    /** Last resort: the store's own header_image (has a hash in the path for new games). Cached. */
    suspend fun fromStore(ctx: Context, appId: Int): String? = withContext(Dispatchers.IO) {
        val prefs = ctx.getSharedPreferences("art", Context.MODE_PRIVATE)
        prefs.getString(appId.toString(), null)?.let { return@withContext it.ifBlank { null } }
        val url = runCatching {
            val o = JSONObject(URL("https://store.steampowered.com/api/appdetails?appids=$appId&filters=basic").readText())
            o.optJSONObject(appId.toString())?.optJSONObject("data")?.optString("header_image")?.takeIf { it.startsWith("http") }
        }.getOrNull()
        prefs.edit().putString(appId.toString(), url.orEmpty()).apply()
        url
    }
}

/** A game's picture: tries each Steam image address in turn, then shows the name instead of grey. */
@Composable
fun GameArt(appId: Int, name: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, showNameIfMissing: Boolean = false) {
    val ctx = LocalContext.current
    // Debug demo copies headers into the cache before the first frame. Decode that file here so
    // the cover is on screen without waiting for Coil or the Steam CDN.
    val bundled = remember(appId) {
        DemoShots.artFile(appId)?.let { file ->
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }
    }
    val urls = remember(appId) { SteamImages.candidates(appId) }
    var index by remember(appId) { mutableIntStateOf(0) }
    var storeUrl by remember(appId) { mutableStateOf<String?>(null) }
    var storeTried by remember(appId) { mutableStateOf(false) }
    var failed by remember(appId) { mutableStateOf(false) }
    val exhausted = index >= urls.size
    LaunchedEffect(appId, exhausted, bundled) {
        if (bundled == null && exhausted && !storeTried) { storeUrl = SteamImages.fromStore(ctx, appId); storeTried = true; if (storeUrl == null) failed = true }
    }
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF1A2836), Color(0xFF2A4256), Color(0xFF1A2836))))) {
        if (bundled != null) {
            Image(bundled, contentDescription = name, contentScale = contentScale, modifier = Modifier.fillMaxSize())
            return@Box
        }
        val model = if (!exhausted) urls[index] else storeUrl
        if (model != null && !failed) AsyncImage(
            model = model,
            contentDescription = name,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            onError = { if (!exhausted) index++ else failed = true },
        )
        if (failed && showNameIfMissing) Text(
            name, color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(12.dp),
        )
    }
}
