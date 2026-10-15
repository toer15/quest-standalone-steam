package com.toer15.queststeam.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SteamLibraryTest {
    private fun game(games: File, id: Int, depots: String?): File = File(games, id.toString()).apply {
        mkdirs()
        File(this, "Game.exe").writeBytes(ByteArray(1000))
        File(this, ".qss-installed").writeText("")
        if (depots != null) File(this, ".DepotDownloader").apply { mkdirs() }.resolve("depot.config").writeText(depots)
    }

    @Test fun downloadedGamesBecomeInstalledInSteamAndLeaveWhenUninstalled() {
        val tmp = Files.createTempDirectory("lib").toFile()
        try {
            val steam = File(tmp, "Steam").apply { mkdirs() }
            File(steam, "SteamApps").mkdirs() // spelled differently: used as is
            val games = File(tmp, "games")
            val repo = game(games, 3241660, """{"installedManifestIDs":{"3241661":8467164963406297134}}""")
            game(games, 10, null) // no depot info: Steam would download it again, so it's left out

            assertEquals(listOf(3241660), SteamLibrary.register(steam, games) { if (it == 3241660) "R.E.P.O." else null })
            val acf = File(steam, "SteamApps/appmanifest_3241660.acf")
            assertTrue(acf.isFile)
            val vdf = Vdf.parse(acf.readText())
            @Suppress("UNCHECKED_CAST") val app = vdf["AppState"] as Map<String, Any>
            assertEquals("R.E.P.O.", app["name"]); assertEquals("4", app["StateFlags"]); assertEquals("3241660", app["installdir"])
            assertTrue((app["SizeOnDisk"] as String).toLong() >= 1000)
            @Suppress("UNCHECKED_CAST") val depots = app["InstalledDepots"] as Map<String, Map<String, Any>>
            assertEquals("8467164963406297134", depots["3241661"]!!["manifest"])
            val link = File(steam, "SteamApps/common/3241660")
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertTrue(File(link, "Game.exe").isFile)
            assertFalse(File(steam, "SteamApps/appmanifest_10.acf").exists())

            // Again: nothing new; Steam's own copy of the manifest isn't touched.
            acf.appendText("// steam edit\n")
            assertTrue(SteamLibrary.register(steam, games) { null }.isEmpty())
            assertTrue(acf.readText().endsWith("// steam edit\n"))

            // Uninstalled in the app: gone from Steam, the game folder itself untouched by the link removal.
            File(repo, ".qss-installed").delete()
            SteamLibrary.register(steam, games) { null }
            assertFalse(acf.exists())
            assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue(File(repo, "Game.exe").isFile)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test fun namesAreQuoted() {
        val text = SteamLibrary.appManifest(1, "A \"quoted\" \\ name", "1", 0, mapOf(2 to 3L), 0)
        @Suppress("UNCHECKED_CAST") val app = Vdf.parse(text)["AppState"] as Map<String, Any>
        assertEquals("1", app["appid"])
        assertTrue(text, text.contains("\"A \\\"quoted\\\" \\\\ name\""))
    }
}
