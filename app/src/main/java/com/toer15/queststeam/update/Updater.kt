package com.toer15.queststeam.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.toer15.queststeam.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A published version of this app on GitHub Releases. */
data class Release(val version: String, val tag: String, val notes: String, val apkUrl: String, val apkSize: Long)

/**
 * In-app updates from this project's GitHub Releases: finds the newest release with this
 * device's APK (Quest 3 or phone), downloads it with progress, and hands it to Android's
 * package installer. Builds are signed with one fixed key, so the update installs over the
 * current app and keeps downloaded games.
 */
object Updater {
    const val REPO = "toer15/quest-standalone-steam"
    val assetName get() = if (BuildConfig.QUEST_BUILD) "quest-steam-quest3.apk" else "quest-steam-phone.apk"

    /** "0.5.1-quest" -> "0.5.1" */
    val currentVersion: String get() = BuildConfig.VERSION_NAME.substringBefore('-')

    /** Install results from [InstallResultReceiver]: null while idle. */
    val installResult = MutableStateFlow<String?>(null)

    /** Compares dotted versions ("v0.10.0" > "0.9.3"); non-numeric parts count as 0. */
    fun compareVersions(a: String, b: String): Int {
        val x = a.trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** Picks the highest-versioned, non-draft release that has [asset]. Pure, so it is unit-tested. */
    fun pickRelease(json: String, asset: String): Release? {
        val arr = JSONArray(json)
        var best: Release? = null
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val tag = r.optString("tag_name")
            if (tag.isBlank()) continue
            val assets = r.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name") != asset) continue
                val rel = Release(
                    version = tag.trimStart('v', 'V'),
                    tag = tag,
                    notes = r.optString("body").trim(),
                    apkUrl = a.optString("browser_download_url"),
                    apkSize = a.optLong("size", -1),
                )
                if (best == null || compareVersions(rel.version, best.version) > 0) best = rel
            }
        }
        return best
    }

    /** Newest release on GitHub for this device, or null if none has our APK. Network call. */
    fun fetchLatest(): Release? {
        val c = URL("https://api.github.com/repos/$REPO/releases?per_page=10").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "QuestSteam/${BuildConfig.VERSION_NAME}")
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        val code = c.responseCode
        if (code != 200) throw IOException(if (code == 403) "GitHub rate limit reached. Try again in a few minutes." else "GitHub returned HTTP $code")
        return pickRelease(c.inputStream.bufferedReader().readText(), assetName)
    }

    fun isNewer(r: Release) = compareVersions(r.version, currentVersion) > 0

    fun apkFile(ctx: Context) = File(ctx.externalCacheDir ?: ctx.cacheDir, "update.apk")

    /** Downloads the release APK. [progress] gets bytes done and total (-1 if unknown). */
    fun download(ctx: Context, r: Release, isCancelled: () -> Boolean, progress: (Long, Long) -> Unit): File {
        val out = apkFile(ctx)
        val part = File(out.path + ".part")
        part.delete()
        val c = URL(r.apkUrl).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "QuestSteam/${BuildConfig.VERSION_NAME}")
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        if (c.responseCode != 200) throw IOException("Download failed: HTTP ${c.responseCode}")
        val total = c.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
        var done = 0L
        c.inputStream.use { i ->
            part.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    if (isCancelled()) throw IOException("Cancelled")
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    progress(done, total)
                }
            }
        }
        if (total > 0 && done != total) throw IOException("Download incomplete ($done of $total bytes)")
        out.delete()
        if (!part.renameTo(out)) throw IOException("Couldn't save the update")
        return out
    }

    /** Android 8+ asks the user to allow installs from this app once. */
    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(ctx: Context) {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    /** Hands the APK to Android's package installer; the system shows its confirmation screen. */
    fun install(ctx: Context, apk: File) {
        installResult.value = null
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("update.apk", 0, apk.length()).use { o ->
                apk.inputStream().use { it.copyTo(o) }
                s.fsync(o)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResultReceiver::class.java), flags)
            s.commit(pi.intentSender)
        }
    }
}

/** Receives the package installer's status; shows the system confirmation when it asks for it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.installResult.value = "Installed"
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Updater.installResult.value = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled"
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "Can't install over this version (it was signed differently). Uninstall the app once, then install the new version."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage for the update"
                    else -> "Install failed${if (msg.isNotBlank()) ": $msg" else ""}"
                }
            }
        }
    }
}
