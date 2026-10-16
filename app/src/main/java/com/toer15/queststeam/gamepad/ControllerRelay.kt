package com.toer15.queststeam.gamepad

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/**
 * Sends the controller to a flat game while the game's window (Termux:X11) is in front.
 *
 * Android only gives controller input to the focused window, so this service shows a tiny
 * focusable "🎮 → game" badge on top. It takes the controller's input and passes it to the game
 * (through [GamepadBridge]); touches and pointer clicks still go to the game window underneath.
 * Needs "Display over other apps" (Settings, or `adb shell appops set com.qssteam SYSTEM_ALERT_WINDOW allow`).
 */
class ControllerRelay : Service() {
    private var badge: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, notification())
        if (badge == null && canOverlay(this)) showBadge()
        return START_NOT_STICKY
    }

    @SuppressLint("SetTextI18n")
    private fun showBadge() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = object : TextView(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = GamepadBridge.onKey(event) || super.dispatchKeyEvent(event)
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = GamepadBridge.onMotion(event) || super.dispatchGenericMotionEvent(event)
        }
        view.text = "🎮 → game"
        view.textSize = 13f
        view.setTextColor(Color.WHITE)
        view.setPadding(22, 10, 22, 10)
        view.background = GradientDrawable().apply { cornerRadius = 40f; setColor(0xB01A9FFF.toInt()) }
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        // Tap the badge to give the keyboard back to the game window.
        view.setOnClickListener { stopSelf() }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (so controller input comes here), but touches outside go to the game.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 24; y = 24 }
        runCatching { wm.addView(view, lp); view.requestFocus(); badge = view }
    }

    override fun onDestroy() {
        badge?.let { v -> runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } }
        badge = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Controller to game", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Controller connected to your game")
            .setContentText(Controllers.connected.value.firstOrNull()?.model ?: "Waiting for a controller")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "controller"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "stop"

        /** Settings.canDrawOverlays can lag behind a fresh grant (or an adb appops grant); app-ops is current. */
        fun canOverlay(ctx: Context): Boolean {
            if (Settings.canDrawOverlays(ctx)) return true
            return runCatching {
                val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, android.os.Process.myUid(), ctx.packageName) ==
                    android.app.AppOpsManager.MODE_ALLOWED
            }.getOrDefault(false)
        }

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, ControllerRelay::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, ControllerRelay::class.java).setAction(ACTION_STOP)) }
        }

        /** Opens "Display over other apps" for this app (may not exist on every headset build). */
        fun requestOverlay(ctx: Context): Boolean = runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }
}
