package com.toer15.queststeam.play

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Share the game log to another app (Messenger, Discord, email, Drive…): the whole log as a .txt
 * file, plus the lines that explain a failure as plain text for apps that only take text.
 */
object LogShare {
    fun share(ctx: Context) {
        val full = GameRunner.logForSharing()
        val dir = File(ctx.cacheDir, "logs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val file = File(dir, "quest-steam-log_$stamp.txt").apply { writeText(full) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        // Messaging apps cut long texts: the text part is the summary, the file has everything.
        val summary = full.lineSequence().first() + "\n" +
            GameRunner.importantLines(GameRunner.log.value, max = 60).joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Quest Steam log")
            putExtra(Intent.EXTRA_TEXT, summary.takeLast(15_000))
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Quest Steam log", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share the log").apply {
            if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(chooser)
    }
}
