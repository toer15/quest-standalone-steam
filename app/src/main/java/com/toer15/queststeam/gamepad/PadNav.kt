package com.toer15.queststeam.gamepad

import android.view.InputDevice
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Console-style controller navigation in the app: the D-pad / left stick moves between items,
 * A selects, B goes back, LB/RB switch tabs, Y opens filters, X syncs, Start checks for updates.
 * The activity turns controller buttons into these actions; the Compose UI reacts to them.
 */
object PadNav {
    enum class Action { Back, TabLeft, TabRight, Filters, Sync, Updates, FocusStart }

    private val _actions = MutableSharedFlow<Action>(extraBufferCapacity = 16)
    val actions: SharedFlow<Action> = _actions

    /** Set by the UI: whether anything on screen has controller focus. */
    @Volatile var hasFocus = false

    // B closes the most recent open thing first (e.g. a flipped game card). Main thread only.
    private val backHandlers = ArrayList<() -> Unit>()
    fun pushBack(h: () -> Unit) { backHandlers.remove(h); backHandlers.add(h) }
    fun removeBack(h: () -> Unit) { backHandlers.remove(h) }
    /** Runs the newest back handler; false if there is none. */
    fun consumeBack(): Boolean {
        val h = backHandlers.removeLastOrNull() ?: return false
        h()
        return true
    }

    fun isFromController(e: KeyEvent): Boolean {
        val s = e.source
        return (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /**
     * Handles a controller key for an activity. Returns null to let the activity dispatch it
     * normally, or the result to return from dispatchKeyEvent. [dispatch] sends a replacement event.
     */
    fun handle(e: KeyEvent, dispatch: (KeyEvent) -> Boolean): Boolean? {
        val code = e.keyCode
        val dpad = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        // First D-pad press with nothing focused: put focus on the top bar instead of doing nothing.
        if (dpad && !hasFocus) {
            if (e.action == KeyEvent.ACTION_DOWN) _actions.tryEmit(Action.FocusStart)
            return true
        }
        if (!isFromController(e)) return null
        val up = e.action == KeyEvent.ACTION_UP
        fun emit(a: Action): Boolean { if (up) _actions.tryEmit(a); return true }
        return when (code) {
            // A = select (the same as pressing the D-pad centre / Enter on the focused item).
            KeyEvent.KEYCODE_BUTTON_A -> dispatch(
                KeyEvent(e.downTime, e.eventTime, e.action, KeyEvent.KEYCODE_DPAD_CENTER, e.repeatCount, e.metaState,
                    e.deviceId, e.scanCode, e.flags, InputDevice.SOURCE_DPAD)
            )
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> emit(Action.Back)
            KeyEvent.KEYCODE_BUTTON_L1 -> emit(Action.TabLeft)
            KeyEvent.KEYCODE_BUTTON_R1 -> emit(Action.TabRight)
            KeyEvent.KEYCODE_BUTTON_Y -> emit(Action.Filters)
            KeyEvent.KEYCODE_BUTTON_X -> emit(Action.Sync)
            KeyEvent.KEYCODE_BUTTON_START -> emit(Action.Updates)
            else -> null
        }
    }
}
