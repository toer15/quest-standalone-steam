package com.toer15.queststeam.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SteamUnzipTest {
    private fun zip(dir: File, vararg entries: Pair<String, String>) = File(dir, "p.zip").apply {
        ZipOutputStream(outputStream()).use { z ->
            entries.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
    }

    @Test fun differentlySpelledFilesAndFoldersAreReplacedNotDuplicated() {
        val tmp = Files.createTempDirectory("steam").toFile()
        try {
            val steam = File(tmp, "Steam").apply { mkdirs() }
            File(steam, "Steam.exe").writeText("installer")
            File(steam, "Bin").mkdirs()
            File(steam, "Bin/old.dll").writeText("old")
            SteamClientDownload.unzip(zip(tmp, "steam.exe" to "client", "bin/new.dll" to "new", "../evil.txt" to "x"), steam)
            assertEquals(listOf("Bin", "steam.exe"), steam.list()!!.sorted())
            assertEquals("client", File(steam, "steam.exe").readText())
            assertEquals(listOf("new.dll", "old.dll"), File(steam, "Bin").list()!!.sorted())
            assertFalse(File(tmp, "evil.txt").exists())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test fun bootstrappedOnlyOnceTheManifestIsWritten() {
        val steam = Files.createTempDirectory("steam").toFile()
        try {
            File(steam, "steamui.dll").writeText("x")
            assertFalse(SteamClientDownload.isBootstrapped(steam))
            File(steam, "package").mkdirs()
            File(steam, "package/steam_client_win64.manifest").writeText("x")
            assertTrue(SteamClientDownload.isBootstrapped(steam))
        } finally {
            steam.deleteRecursively()
        }
    }
}
