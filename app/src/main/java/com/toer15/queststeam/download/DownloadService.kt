package com.toer15.queststeam.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import com.toer15.queststeam.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps downloads running when the app's panel is closed or in the background: Horizon OS
 * pauses or stops background apps, which froze downloads mid-way ("still says downloading").
 * A foreground service with a notification, plus Wi-Fi and CPU wake locks, keeps it going.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, build("Preparing download…", null))
        if (wake == null) wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qss:download").apply { setReferenceCounted(false); acquire() }
        if (wifi == null) wifi = @Suppress("DEPRECATION") (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "qss:download").apply { setReferenceCounted(false); acquire() }
        if (watch == null) watch = scope.launch {
            val dm = DownloadManager.get(this@DownloadService)
            dm.states.collectLatest { states ->
                val active = states.filterValues { it is GameState.Downloading }
                if (active.isEmpty()) { stopSelf(); return@collectLatest }
                val (id, s) = active.entries.first()
                s as GameState.Downloading
                val name = dm.nameOf(id)
                val pct = if (s.total > 0) (s.fraction * 100).toInt() else null
                val text = when {
                    pct != null -> "$pct% · ${formatSize(s.bytes)} of ${formatSize(s.total)}" + (s.etaSeconds?.let { " · ${eta(it)} left" } ?: "")
                    else -> s.note ?: "Starting…"
                }
                val more = if (active.size > 1) " (+${active.size - 1} more)" else ""
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build("Downloading $name$more", text, pct))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }

    private fun build(title: String, text: String?, pct: Int? = null): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Game downloads", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setProgress(100, pct ?: 0, pct == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 11

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, DownloadService::class.java)) }
        }

        fun formatSize(b: Long): String = when {
            b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
            else -> "${b / 1024} KB"
        }

        private fun eta(s: Long) = when {
            s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
            s >= 60 -> "${s / 60}m ${s % 60}s"
            else -> "${s}s"
        }
    }
}
