package com.toer15.queststeam

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.toer15.queststeam.steam.KeylessSteam
import com.toer15.queststeam.steam.SessionStore
import com.toer15.queststeam.steam.SteamOpenId
import com.toer15.queststeam.ui.SteamColors
import com.toer15.queststeam.ui.SteamTheme
import com.toer15.queststeam.ui.steamBackdrop
import kotlinx.coroutines.launch

/**
 * Steam login via OpenID 2.0 in a WebView. The password stays on steamcommunity.com.
 * After OpenID returns, the same WebView opens the games page so the session cookies
 * are present for a private library.
 */
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0E141B.toInt()
        window.navigationBarColor = 0xFF070B10.toInt()
        CookieManager.getInstance().setAcceptCookie(true)
        setContent {
            SteamTheme {
                var showWeb by remember { mutableStateOf(false) }
                if (!showWeb) LoginLanding(onStart = { showWeb = true })
                else Column(Modifier.fillMaxSize().steamBackdrop()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { showWeb = false }) { Text("Back") }
                        Text("Sign in to Steam", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val wide = maxWidth >= 700.dp
                        Box(Modifier.fillMaxSize().padding(if (wide) 28.dp else 0.dp), contentAlignment = Alignment.Center) {
                            val webMod = if (wide) {
                                Modifier.widthIn(max = 540.dp).fillMaxHeight().clip(RoundedCornerShape(24.dp))
                            } else Modifier.fillMaxSize()
                            SteamSignInWeb(
                                modifier = webMod,
                                onSignedIn = {
                                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                    finish()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginLanding(onStart: () -> Unit) {
    Box(Modifier.fillMaxSize().steamBackdrop(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 440.dp).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("QUEST STEAM", color = SteamColors.Accent, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            Text("Your library, on device", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, textAlign = TextAlign.Center, lineHeight = 40.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                "Sign in with your Steam account. Your password stays on Steam's site, and a private profile still loads.",
                color = SteamColors.Muted, textAlign = TextAlign.Center, fontSize = 15.sp, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = SteamColors.Accent, contentColor = Color(0xFF0E141B)),
            ) { Text("Sign in with Steam", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SteamSignInWeb(modifier: Modifier, onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var finishing by remember { mutableStateOf(false) }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize().background(SteamColors.Bg),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(0xFF0E141B.toInt())
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        private var done = false

                        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                            val url = req.url.toString()
                            if (!url.startsWith(SteamOpenId.RETURN_TO)) return false
                            scope.launch {
                                val id = SteamOpenId.verify(req.url)
                                if (id != null) {
                                    SessionStore(ctx).steamId = id
                                    finishing = true
                                    view.loadUrl(KeylessSteam.GAMES_PAGE)
                                } else {
                                    Toast.makeText(ctx, "Steam login could not be verified", Toast.LENGTH_LONG).show()
                                    view.loadUrl(SteamOpenId.loginUrl())
                                }
                            }
                            return true
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (!finishing || done || !request.isForMainFrame) return
                            finishing = false
                            Toast.makeText(ctx, KeylessSteam.SIGN_IN_AGAIN, Toast.LENGTH_LONG).show()
                            view.loadUrl(SteamOpenId.loginUrl())
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (!finishing || done || url.contains("openid")) return
                            val path = android.net.Uri.parse(url).path ?: return
                            if (path.contains("login")) {
                                finishing = false
                                Toast.makeText(ctx, KeylessSteam.SIGN_IN_AGAIN, Toast.LENGTH_LONG).show()
                                view.loadUrl(SteamOpenId.loginUrl())
                                return
                            }
                            if (!path.contains("games")) return
                            val cm = CookieManager.getInstance()
                            cm.flush()
                            val cookie = cm.getCookie("https://steamcommunity.com")
                            if (KeylessSteam.hasSession(cookie)) {
                                done = true
                                onSignedIn()
                            } else {
                                finishing = false
                                Toast.makeText(ctx, KeylessSteam.SIGN_IN_AGAIN, Toast.LENGTH_LONG).show()
                                view.loadUrl(SteamOpenId.loginUrl())
                            }
                        }
                    }
                    loadUrl(SteamOpenId.loginUrl())
                }
            },
        )
        if (finishing) Box(Modifier.fillMaxSize().background(Color(0xF00E141B)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = SteamColors.Accent)
                Spacer(Modifier.height(16.dp))
                Text("Signing you in…", color = Color.White, fontWeight = FontWeight.Medium)
            }
        }
    }
}
