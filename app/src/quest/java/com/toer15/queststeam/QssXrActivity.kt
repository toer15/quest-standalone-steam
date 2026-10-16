package com.toer15.queststeam

import android.app.NativeActivity
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import com.toer15.queststeam.gamepad.GamepadBridge
import com.toer15.queststeam.vr.VrPanel

/**
 * The immersive VR view (libqssxr.so via NativeActivity). It shows the game's frames (SteamVR
 * bridge) and, whenever no game is drawing, the app itself as a panel in front of you: library,
 * downloads, Store and starting VR games, pointed at with the Touch controllers (trigger = tap,
 * thumbstick = scroll, thumbstick click = bring the panel back in front of you).
 * Input the native side doesn't use arrives here: a Bluetooth controller's buttons go to the game.
 */
class QssXrActivity : NativeActivity() {
    private var panel: VrPanel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    /** Called from native code (render thread) with the Surface behind the panel's quad layer. */
    @Keep
    fun onPanelSurface(surface: Surface, width: Int, height: Int) {
        runOnUiThread { panel?.release(); panel = runCatching { VrPanel(this, surface, width, height) }.getOrNull() }
    }

    /** Called from native code every frame the controller points at the panel. */
    @Keep
    fun onPanelPointer(u: Float, v: Float, hit: Boolean, down: Boolean, scroll: Float) {
        runOnUiThread { panel?.pointer(u, v, hit, down, scroll) }
    }

    override fun onDestroy() {
        panel?.release(); panel = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = GamepadBridge.onKey(event) || super.dispatchKeyEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = GamepadBridge.onMotion(event) || super.dispatchGenericMotionEvent(event)
}
