package com.toer15.queststeam

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.toer15.queststeam.steam.SessionStore
import com.toer15.queststeam.steam.SteamOpenId
import kotlinx.coroutines.launch

/**
 * Steam login via OpenID 2.0 in a WebView. The user types their password on
 * steamcommunity.com only; we never see it. We get back a verified SteamID64.
 */
class LoginActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                if (!url.startsWith(SteamOpenId.RETURN_TO)) return false
                lifecycleScope.launch {
                    val id = SteamOpenId.verify(req.url)
                    if (id != null) {
                        SessionStore(this@LoginActivity).steamId = id
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        finish()
                    } else {
                        Toast.makeText(this@LoginActivity, "Steam login could not be verified", Toast.LENGTH_LONG).show()
                        web.loadUrl(SteamOpenId.loginUrl())
                    }
                }
                return true
            }
        }
        web.loadUrl(SteamOpenId.loginUrl())
    }
}
