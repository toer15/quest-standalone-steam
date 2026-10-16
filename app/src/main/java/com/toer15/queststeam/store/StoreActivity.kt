package com.toer15.queststeam.store

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.toer15.queststeam.ui.SteamColors
import com.toer15.queststeam.ui.SteamTheme

/**
 * Steam's own store page and checkout inside the app. The player signs in to the Steam store once
 * (kept in the app's cookies) and pays on Steam's site, so the purchase goes through Steam exactly
 * like in a browser. When this closes, the library syncs and the new game appears with Download.
 */
class StoreActivity : ComponentActivity() {
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0E141B.toInt()
        val start = intent.getStringExtra(EXTRA_URL) ?: "https://store.steampowered.com/"
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web?.canGoBack() == true) web?.goBack() else finish() }
        })
        setContent {
            SteamTheme {
                var title by remember { mutableStateOf("Steam Store") }
                var progress by remember { mutableIntStateOf(0) }
                Column(Modifier.fillMaxSize().background(SteamColors.Bg)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).clickable { finish() },
                            contentAlignment = Alignment.Center,
                        ) { Text("✕", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).clickable { if (web?.canGoBack() == true) web?.goBack() },
                            contentAlignment = Alignment.Center,
                        ) { Text("‹", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Purchases go through Steam's own checkout", color = SteamColors.Muted, fontSize = 12.sp)
                        }
                    }
                    if (progress in 1..99) LinearProgressIndicator(
                        progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = SteamColors.Accent, trackColor = Color.Transparent,
                    )
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                                    override fun onReceivedTitle(view: WebView, t: String?) { if (!t.isNullOrBlank()) title = t }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        val u = request.url
                                        // steam:// links need the desktop Steam client; ignore them. Other apps' links open outside.
                                        if (u.scheme == "steam") return true
                                        if (u.scheme != "http" && u.scheme != "https") {
                                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                                            return true
                                        }
                                        return false
                                    }
                                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { progress = 5 }
                                    override fun onPageFinished(view: WebView, url: String?) { progress = 100; CookieManager.getInstance().flush() }
                                }
                                loadUrl(start)
                                web = this
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        web?.destroy(); web = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        fun intent(ctx: Context, url: String) = Intent(ctx, StoreActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
