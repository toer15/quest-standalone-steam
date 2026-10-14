package com.toer15.queststeam.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun comparesVersionsNumerically() {
        assertTrue(Updater.compareVersions("v0.10.0", "0.9.3") > 0)
        assertTrue(Updater.compareVersions("0.5.1", "0.5.1-quest") == 0)
        assertTrue(Updater.compareVersions("0.5", "0.5.1") < 0)
        assertTrue(Updater.compareVersions("1.0.0", "0.99.99") > 0)
    }

    @Test fun picksNewestReleaseWithThisDevicesApk() {
        val json = """[
          {"tag_name":"v0.5.0","draft":false,"body":"old","assets":[{"name":"quest-steam-quest3.apk","size":100,"browser_download_url":"https://x/0.5.0/q.apk"}]},
          {"tag_name":"v0.6.0","draft":false,"body":"**New** stuff","assets":[
             {"name":"quest-steam-quest3.apk","size":200,"browser_download_url":"https://x/0.6.0/q.apk"},
             {"name":"quest-steam-phone.apk","size":190,"browser_download_url":"https://x/0.6.0/p.apk"}]},
          {"tag_name":"v0.7.0","draft":true,"assets":[{"name":"quest-steam-quest3.apk","size":1,"browser_download_url":"https://x/draft"}]},
          {"tag_name":"v0.6.5","draft":false,"assets":[{"name":"quest-steam-phone.apk","size":5,"browser_download_url":"https://x/0.6.5/p.apk"}]}
        ]"""
        val q = Updater.pickRelease(json, "quest-steam-quest3.apk")!!
        assertEquals("0.6.0", q.version)
        assertEquals("https://x/0.6.0/q.apk", q.apkUrl)
        assertEquals(200L, q.apkSize)
        assertEquals("**New** stuff", q.notes)
        val p = Updater.pickRelease(json, "quest-steam-phone.apk")!!
        assertEquals("0.6.5", p.version)
        assertNull(Updater.pickRelease(json, "nothing.apk"))
    }
}
