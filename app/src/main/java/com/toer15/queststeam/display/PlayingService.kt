package com.toer15.queststeam.display

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * While a game runs, Wine and the display server are child processes of this app. The game's
 * window (Termux:X11) is in front, so without a foreground service the headset could close this
 * app in the background and the game with it.
 */
class PlayingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Game running", NotificationManager.IMPORTANCE_LOW))
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "your game"
        startForeground(
            ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Playing $title")
                .setContentText("Quest Steam keeps the game running")
                .setOngoing(true)
                .build(),
        )
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "playing"
        private const val ID = 31
        private const val ACTION_STOP = "stop"
        private const val EXTRA_TITLE = "title"

        fun start(ctx: Context, title: String?) {
            runCatching { ctx.startForegroundService(Intent(ctx, PlayingService::class.java).putExtra(EXTRA_TITLE, title)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, PlayingService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
