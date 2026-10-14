package com.toer15.queststeam.steamclient

import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.data.AppItem
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.types.KeyValue
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamClientLogicTest {

    @Test fun progressUnknownUntilAChunkGivesSize() {
        val p = DepotProgress()
        assertEquals(0L to -1L, p.fraction(1, 0.1f))
        // 25% of depot 1 is 250 bytes -> depot is ~1000 bytes
        assertEquals(250L to 1000L, p.chunk(1, 0.25f, 250))
        // a file-only callback moves the fraction without bytes
        assertEquals(500L to 1000L, p.fraction(1, 0.5f))
    }

    @Test fun progressAddsDepotsAndNeverGoesBackwards() {
        val p = DepotProgress()
        p.chunk(1, 0.5f, 500)
        val (d, t) = p.chunk(2, 0.1f, 100)
        assertEquals(1000L + 1000L, t)
        assertEquals(500L + 100L, d)
        // late, out-of-order callback with a smaller fraction is ignored
        assertEquals(600L to 2000L, p.fraction(1, 0.2f))
        assertEquals(1000L + 100L to 2000L, p.depotDone(1, 1000))
    }

    @Test fun packageAppIdsSkipZero() {
        val pkg = KeyValue("1234").apply {
            children.add(KeyValue("appids").apply {
                children.add(KeyValue("0", "620"))
                children.add(KeyValue("1", "0"))
                children.add(KeyValue("2", "546560"))
            })
        }
        assertEquals(listOf(620, 546560), PicsParse.packageAppIds(pkg))
    }

    @Test fun playableAppsAreGamesAndApplications() {
        fun app(type: String?, name: String?) = KeyValue("appinfo").apply {
            children.add(KeyValue("common").apply {
                if (type != null) children.add(KeyValue("type", type))
                if (name != null) children.add(KeyValue("name", name))
            })
        }
        assertEquals(OwnedApp(620, "Portal 2", 0), PicsParse.playableApp(620, app("Game", "Portal 2")))
        assertEquals("VRChat", PicsParse.playableApp(438100, app("Application", "VRChat"))?.name)
        assertNull(PicsParse.playableApp(1, app("DLC", "Soundtrack")))
        assertNull(PicsParse.playableApp(2, app("Tool", "SDK")))
        assertNull(PicsParse.playableApp(3, app(null, "No type")))
        assertNull(PicsParse.playableApp(4, app("Game", "")))
    }

    @Test fun guardPromptAdapterBridgesToFutures() = runBlocking {
        val prompt = object : GuardPrompt {
            override suspend fun deviceCode(previousWasWrong: Boolean) = if (previousWasWrong) " 22222 " else "11111"
            override suspend fun emailCode(email: String?, previousWasWrong: Boolean) = "MAIL-$email"
            override suspend fun approveOnPhone() = false
        }
        val auth = prompt.asAuthenticator(this)
        assertEquals("11111", auth.getDeviceCode(false).await())
        assertEquals("22222", auth.getDeviceCode(true).await())
        assertEquals("MAIL-a@b.c", auth.getEmailCode("a@b.c", false).await())
        assertFalse(auth.acceptDeviceConfirmation().await())
    }

    @Test fun signedOutSessionReportsSignedOut() {
        val store = object : LoginStore {
            var v: SavedLogin? = null
            override fun load() = v
            override fun save(login: SavedLogin?) { v = login }
        }
        val s = SteamSession(store)
        assertFalse(s.isSignedIn)
        assertEquals(SteamSession.Status.SignedOut, s.status.value)
        store.v = SavedLogin("user", "token", 76561197960287930L)
        assertTrue(SteamSession(store).isSignedIn)
        assertEquals(SteamSession.Status.Offline, SteamSession(store).status.value)
    }

    @Test fun ensureOnlineWithoutLoginAsksToSignIn() = runBlocking {
        val store = object : LoginStore {
            override fun load(): SavedLogin? = null
            override fun save(login: SavedLogin?) {}
        }
        try {
            SteamSession(store).ensureOnline()
            throw AssertionError("expected SteamLoginException")
        } catch (e: SteamLoginException) {
            assertTrue(e.needsNewLogin)
        }
    }

    /** The real DepotDownloader + AppItem accept our arguments without a network connection. */
    @Test fun depotDownloaderConstructsOffline() {
        val dd = DepotDownloader(SteamClient(), emptyList(), maxDownloads = 8)
        val item = AppItem(appId = 620, installDirectory = "/tmp/x", os = "windows", osArch = "64", language = "english")
        assertEquals("windows", item.os)
        dd.close()
    }

    @Test fun friendlyErrors() {
        assertEquals("Wrong Steam account name or password.", SteamSession.friendlyAuthError(Exception("Failed: InvalidPassword")))
        assertTrue(SteamSession.friendlyAuthError(Exception("")).startsWith("Steam sign-in failed"))
    }
}
