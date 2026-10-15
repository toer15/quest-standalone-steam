package com.toer15.queststeam

import android.content.Context
import android.content.Intent
import com.toer15.queststeam.download.DownloadManager
import com.toer15.queststeam.steam.PlayerSummary

/** Release builds never enter demo mode. */
object DemoShots {
    const val armed: Boolean = false
    fun prepare(ctx: Context, intent: Intent) = false
    fun profile(): PlayerSummary? = null
    fun applyCardStates(downloads: DownloadManager) = Unit
}
