package com.toer15.queststeam.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.toer15.queststeam.display.GameSurface
import com.toer15.queststeam.display.LeaveGameDialog
import com.toer15.queststeam.ui.SteamTheme
import com.winlator.widget.XServerView
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer

/**
 * Debug-only: the real game-screen chrome (Leave game, Keyboard) on the built-in X view.
 * Not in release builds.
 */
class GameChromeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF000000.toInt()
        val view = XServerView(this, XServer(ScreenInfo("1280x720")))
        val askLeave = intent.getBooleanExtra("leave", false)
        setContent {
            SteamTheme {
                GameSurface(view, onLeave = { })
                if (askLeave) LeaveGameDialog("Half-Life: Alyx", onStay = { }, onLeave = { })
            }
        }
    }
}
