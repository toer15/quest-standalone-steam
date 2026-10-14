package com.toer15.queststeam

import android.app.NativeActivity
import android.view.KeyEvent
import android.view.MotionEvent
import com.toer15.queststeam.gamepad.GamepadBridge

/**
 * The immersive VR view (libqssxr.so via NativeActivity). The native side leaves input events
 * unhandled, so they arrive here: a Bluetooth controller's buttons and sticks go to the game.
 */
class QssXrActivity : NativeActivity() {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = GamepadBridge.onKey(event) || super.dispatchKeyEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = GamepadBridge.onMotion(event) || super.dispatchGenericMotionEvent(event)
}
