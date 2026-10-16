package com.toer15.queststeam.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.toer15.queststeam.BuildConfig
import com.toer15.queststeam.MainActivity

/**
 * After an in-app update Android closes the old version. This runs in the new version
 * (MY_PACKAGE_REPLACED) and opens the app again by itself. If the headset doesn't let an app
 * open from the background, a "Quest Steam updated · tap to open" notification is shown instead.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_JUST_UPDATED, true)
        // Only when the update was started from inside the app (not a manual sideload over adb).
        val prefs = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_RELAUNCH, false)) return
        prefs.edit().putBoolean(PREF_RELAUNCH, false).apply()
        val started = runCatching { ctx.startActivity(open) }.isSuccess
        // Background starts can be silently blocked; the notification is the reliable way back in.
        notify(ctx, open, started)
    }

    private fun notify(ctx: Context, open: Intent, started: Boolean) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Quest Steam updated to ${BuildConfig.VERSION_NAME}")
            .setContentText(if (started) "Opening the new version…" else "Tap to open the new version")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setFullScreenIntent(pi, true)
            .build()
        nm.notify(ID, n)
    }

    companion object {
        const val EXTRA_JUST_UPDATED = "just_updated"
        const val PREF_RELAUNCH = "relaunch_after_update"
        private const val CHANNEL = "app_updates"
        private const val ID = 21
    }
}
