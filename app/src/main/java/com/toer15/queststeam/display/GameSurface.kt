package com.toer15.queststeam.display

import android.annotation.SuppressLint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.toer15.queststeam.gamepad.GamepadBridge
import com.toer15.queststeam.ui.SteamColors
import com.toer15.queststeam.ui.focusRing
import com.winlator.widget.XServerView
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode

/**
 * The game, drawn by the built-in display, filling the app's window. Pointing and clicking
 * (Touch controller trigger, hand pinch, or a mouse) moves the game's mouse and clicks;
 * the thumbstick / mouse wheel scrolls. [onLeave] asks to leave the game.
 */
@Composable
fun GameSurface(view: XServerView, onLeave: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { _ ->
                (view.parent as? ViewGroup)?.removeView(view)
                attachInput(view)
                view.onResume()
                view
            },
        )
        Text(
            "✕  Leave game", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.45f)).focusRing().clickable(onClick = onLeave)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        var typing by remember { mutableStateOf(false) }
        if (typing) TypingBar(Modifier.align(Alignment.TopCenter), onClose = { typing = false })
        else Text(
            "⌨  Keyboard", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.45f)).focusRing().clickable { typing = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * Typing into the game with the Quest's keyboard: what's typed in the box goes to the game as key
 * presses right away (so it lands in whatever field is selected in the game, e.g. Steam's sign-in).
 * The box hides the text (it may be a password).
 */
@Composable
private fun TypingBar(modifier: Modifier, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier.padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Select a field in the game, then type here", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { new ->
                    val (delete, add) = SoftKeys.diff(text, new)
                    SoftKeys.backspace(delete)
                    SoftKeys.type(add)
                    text = new
                },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.White),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { SoftKeys.tap(XKeycode.KEY_ENTER); text = "" }),
                modifier = Modifier.width(220.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.1f))
                    .padding(horizontal = 10.dp, vertical = 8.dp).focusRequester(focus),
            )
            KeyButton("⌫") { if (text.isEmpty()) SoftKeys.tap(XKeycode.KEY_BKSP) else { SoftKeys.backspace(1); text = text.dropLast(1) } }
            KeyButton("Tab") { SoftKeys.tap(XKeycode.KEY_TAB); text = "" }
            KeyButton("Enter") { SoftKeys.tap(XKeycode.KEY_ENTER); text = "" }
            KeyButton("Done", onClose)
        }
    }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
    Text(
        label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f)).focusRing()
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** "Leave the game?" over the game: Keep playing / Leave game. */
@Composable
fun LeaveGameDialog(
    title: String, onStay: () -> Unit, onLeave: () -> Unit,
    message: String = "The game closes and you go back to your library.",
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(SteamColors.Panel).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Leave $title?", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(message, color = SteamColors.Muted, fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Keep playing", color = Color(0xFF0E141B), fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(SteamColors.Accent).focusRing()
                        .clickable(onClick = onStay).padding(horizontal = 22.dp, vertical = 12.dp),
                )
                Text(
                    "Leave game", color = SteamColors.Danger, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).focusRing()
                        .clickable(onClick = onLeave).padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** Keys while the game shows: controller → game (XInput), everything else → the game's keyboard. */
fun gameKeyEvent(event: KeyEvent): Boolean {
    if (GamepadBridge.onKey(event)) return true
    val server = BuiltInDisplay.server ?: return false
    if (event.keyCode == KeyEvent.KEYCODE_BACK) return false
    return runCatching { server.keyboard.onKeyEvent(event) }.getOrDefault(false)
}

@SuppressLint("ClickableViewAccessibility")
private fun attachInput(view: XServerView) {
    var buttonDown: Pointer.Button? = null
    fun move(x: Float, y: Float) {
        val server = BuiltInDisplay.server ?: return
        BuiltInDisplay.toScreen(x, y)?.let { (sx, sy) -> server.injectPointerMove(sx, sy) }
    }
    view.setOnTouchListener { _, e ->
        val server = BuiltInDisplay.server ?: return@setOnTouchListener false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                move(e.x, e.y)
                val b = if (e.isFromSource(android.view.InputDevice.SOURCE_MOUSE) && e.buttonState and MotionEvent.BUTTON_SECONDARY != 0)
                    Pointer.Button.BUTTON_RIGHT else Pointer.Button.BUTTON_LEFT
                server.injectPointerButtonPress(b); buttonDown = b
            }
            MotionEvent.ACTION_MOVE -> move(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                move(e.x, e.y)
                buttonDown?.let { server.injectPointerButtonRelease(it) }; buttonDown = null
            }
            // Second finger: right click.
            MotionEvent.ACTION_POINTER_DOWN -> server.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
            MotionEvent.ACTION_POINTER_UP -> server.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
        }
        true
    }
    view.setOnGenericMotionListener { _, e ->
        val server = BuiltInDisplay.server ?: return@setOnGenericMotionListener false
        if (GamepadBridge.onMotion(e)) return@setOnGenericMotionListener true
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> { move(e.x, e.y); true }
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) {
                    val b = if (v > 0) Pointer.Button.BUTTON_SCROLL_UP else Pointer.Button.BUTTON_SCROLL_DOWN
                    server.injectPointerButtonPress(b); server.injectPointerButtonRelease(b)
                }
                true
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                move(e.x, e.y)
                if (e.actionButton == MotionEvent.BUTTON_SECONDARY) server.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT)
                true
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (e.actionButton == MotionEvent.BUTTON_SECONDARY) server.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT)
                true
            }
            else -> false
        }
    }
}
