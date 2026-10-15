package com.toer15.queststeam

import android.content.Context
import android.content.Intent
import com.toer15.queststeam.download.DownloadManager
import com.toer15.queststeam.steam.PlayerSummary
import com.toer15.queststeam.store.StoreItem
import com.toer15.queststeam.store.StorePrice
import java.io.File

/** Release builds never enter demo mode. */
object DemoShots {
    const val armed: Boolean = false
    const val screen: String = ""
    const val flipAppId: Int = 0
    fun prepare(ctx: Context, intent: Intent) = false
    fun profile(): PlayerSummary? = null
    fun applyCardStates(downloads: DownloadManager) = Unit
    fun artFile(appId: Int): File? = null
    fun storeItems(): List<StoreItem> = emptyList()
    fun storePrices(): Map<Int, StorePrice> = emptyMap()
}
