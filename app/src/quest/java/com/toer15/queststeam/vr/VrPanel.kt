package com.toer15.queststeam.vr

import android.app.Activity
import android.app.Presentation
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.toer15.queststeam.LauncherApp
import com.toer15.queststeam.LoginActivity
import com.toer15.queststeam.display.GameDisplay
import com.toer15.queststeam.play.GameRunner
import com.toer15.queststeam.play.LaunchOverlay
import com.toer15.queststeam.ui.SteamColors
import com.toer15.queststeam.ui.SteamTheme

/**
 * The app's own screen inside the VR view: a private virtual display renders the library (the same
 * Compose UI as the 2D panel) into the Surface of an OpenXR quad layer. Controller pointer events
 * from the native side become touch events; a dot shows where you're pointing.
 */
class VrPanel(private val activity: Activity, surface: Surface, private val width: Int, private val height: Int) {
    private val owners = PanelOwners(activity)
    private val display: VirtualDisplay = activity.getSystemService(DisplayManager::class.java)
        .createVirtualDisplay("qss-vr-panel", width, height, DPI, surface, 0)
    private val cursor: View
    private val presentation: Presentation
    private var downTime = 0L
    private var isDown = false

    init {
        presentation = Presentation(activity, display.display)
        val root = FrameLayout(presentation.context)
        root.setViewTreeLifecycleOwner(owners)
        root.setViewTreeViewModelStoreOwner(owners)
        root.setViewTreeSavedStateRegistryOwner(owners)
        root.setBackgroundColor(0xFF0E141B.toInt())
        val compose = ComposeView(presentation.context).apply {
            setContent {
                CompositionLocalProvider(LocalContext provides activity, LocalActivityResultRegistryOwner provides owners) {
                    SteamTheme { PanelContent(activity) }
                }
            }
        }
        root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        cursor = View(presentation.context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE666C0F4.toInt()); setStroke(3, Color.WHITE) }
            visibility = View.GONE
        }
        root.addView(cursor, FrameLayout.LayoutParams(CURSOR, CURSOR))
        presentation.setContentView(root)
        presentation.show()
    }

    /** Controller pointer: (u, v) on the panel, trigger held = finger down, thumbstick = scroll. */
    fun pointer(u: Float, v: Float, hit: Boolean, down: Boolean, scroll: Float) {
        val x = (u * width).coerceIn(0f, width - 1f)
        val y = (v * height).coerceIn(0f, height - 1f)
        cursor.visibility = if (hit) View.VISIBLE else View.GONE
        cursor.translationX = x - CURSOR / 2f
        cursor.translationY = y - CURSOR / 2f
        cursor.alpha = if (down) 1f else 0.8f
        val target = presentation.window?.decorView ?: return
        val now = SystemClock.uptimeMillis()
        when {
            down && !isDown -> { downTime = now; touch(target, MotionEvent.ACTION_DOWN, x, y, now); isDown = true }
            down && isDown -> touch(target, MotionEvent.ACTION_MOVE, x, y, now)
            !down && isDown -> { touch(target, MotionEvent.ACTION_UP, x, y, now); isDown = false }
        }
        if (hit && !down && scroll != 0f) {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; setAxisValue(MotionEvent.AXIS_VSCROLL, scroll * 0.6f) })
            val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            target.dispatchGenericMotionEvent(e)
            e.recycle()
        }
    }

    private fun touch(target: View, action: Int, x: Float, y: Float, now: Long) {
        val e = MotionEvent.obtain(downTime, now, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(e)
        e.recycle()
    }

    fun release() {
        runCatching { presentation.dismiss() }
        owners.destroy()
        display.release()
    }

    companion object {
        private const val DPI = 192 // 1920 x 1200 px = 1600 x 1000 dp
        private const val CURSOR = 26
    }
}

@androidx.compose.runtime.Composable
private fun PanelContent(activity: Activity) {
    val stage by GameRunner.stage.collectAsState()
    val log by GameRunner.log.collectAsState()
    Box(Modifier.fillMaxSize()) {
        LauncherApp(
            onSignedOut = { activity.finish() },
            onSteamSignIn = { activity.startActivity(Intent(activity, LoginActivity::class.java)) },
            inVr = true,
        )
        LaunchOverlay(
            stage = stage, appId = 0, log = log,
            onShowGame = { GameRunner.showGameNow(activity) },
            onStop = { GameRunner.stop(activity) },
            onDismiss = { GameRunner.dismissFailure() },
        )
        Text(
            "Exit VR", color = SteamColors.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp).clip(RoundedCornerShape(50))
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                .clickable { activity.finish() }.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/** Lifecycle, ViewModels, saved state and activity launches for Compose on the virtual display. */
private class PanelOwners(private val activity: Activity) :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, HasDefaultViewModelProviderFactory, ActivityResultRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
        ViewModelProvider.AndroidViewModelFactory.getInstance(activity.application)
    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras().apply { set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, activity.application) }

    /** Pickers and the Store open as normal panels; their results aren't needed in VR. */
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            runCatching { activity.startActivity(contract.createIntent(activity, input)) }
        }
    }

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
