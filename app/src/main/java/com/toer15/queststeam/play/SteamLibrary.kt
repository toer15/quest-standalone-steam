package com.toer15.queststeam.play

import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * Lets Steam's own library see the games this app downloaded, like on a PC: each one gets an
 * appmanifest in Steam's steamapps folder (installed, with the depot manifests the app downloaded)
 * and its folder linked in as steamapps/common/<appid>. Play in Steam then starts it in Steam's
 * session; if the game was updated since, Steam updates the same files.
 */
object SteamLibrary {
    /** Remembers which appmanifests this app wrote, so they go away when the game is uninstalled. */
    private const val OURS = ".qss-linked"

    /**
     * Adds every downloaded game in [gamesDir] that Steam doesn't know yet, and removes the ones
     * this app added whose game is gone. [name] gives a game's name. Returns the games added.
     */
    fun register(steamDir: File, gamesDir: File, name: (Int) -> String?): List<Int> {
        val steamapps = child(steamDir, "steamapps")
        val common = child(steamapps, "common").apply { mkdirs() }
        val oursFile = File(steamapps, OURS)
        val ours = oursFile.takeIf { it.isFile }?.readLines()?.mapNotNull { it.trim().toIntOrNull() }?.toMutableSet() ?: mutableSetOf()
        // Uninstalled in the app: Steam shouldn't show them as installed any more.
        for (id in ours.toList()) {
            if (File(File(gamesDir, id.toString()), ".qss-installed").exists()) continue
            File(steamapps, "appmanifest_$id.acf").delete()
            File(common, id.toString()).delete() // the link only, never the game
            ours -= id
        }
        val added = mutableListOf<Int>()
        gamesDir.listFiles()?.sortedBy { it.name }?.forEach { dir ->
            val appId = dir.name.toIntOrNull() ?: return@forEach
            if (!File(dir, ".qss-installed").exists()) return@forEach
            val acf = File(steamapps, "appmanifest_$appId.acf")
            if (acf.exists()) return@forEach // Steam's own, or added before: Steam keeps it up to date
            val depots = installedDepots(dir)
            if (depots.isEmpty()) return@forEach // without them Steam would download the whole game again
            val link = File(common, appId.toString())
            if (!link.exists()) runCatching { Files.createSymbolicLink(link.toPath(), dir.toPath()) }
            if (!link.exists()) return@forEach
            acf.writeText(appManifest(appId, name(appId) ?: "App $appId", appId.toString(), sizeOf(dir), depots, System.currentTimeMillis() / 1000))
            ours += appId
            added += appId
        }
        if (ours.isEmpty()) oursFile.delete() else oursFile.writeText(ours.sorted().joinToString("\n", postfix = "\n"))
        return added
    }

    /** depot id -> manifest id the app's downloader installed (JavaSteam's .DepotDownloader/depot.config). */
    fun installedDepots(gameDir: File): Map<Int, Long> = runCatching {
        val f = File(gameDir, ".DepotDownloader/depot.config")
        val ids = JSONObject(f.readText()).getJSONObject("installedManifestIDs")
        ids.keys().asSequence().associate { it.toInt() to ids.getLong(it) }
    }.getOrDefault(emptyMap())

    /** Steam's appmanifest_<appid>.acf for an installed game ("StateFlags" 4 = fully installed). */
    fun appManifest(appId: Int, name: String, installDir: String, size: Long, depots: Map<Int, Long>, now: Long): String {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val b = StringBuilder()
        b.append("\"AppState\"\n{\n")
        for ((k, v) in listOf(
            "appid" to appId.toString(), "universe" to "1", "name" to name, "StateFlags" to "4", "installdir" to installDir,
            "LastUpdated" to now.toString(), "SizeOnDisk" to size.toString(), "StagingSize" to "0", "buildid" to "0",
            "LastOwner" to "0", "UpdateResult" to "0", "BytesToDownload" to "0", "BytesDownloaded" to "0",
            "BytesToStage" to "0", "BytesStaged" to "0", "TargetBuildID" to "0", "AutoUpdateBehavior" to "0",
            "AllowOtherDownloadsWhileRunning" to "0", "ScheduledAutoUpdate" to "0",
        )) b.append("\t${q(k)}\t\t${q(v)}\n")
        b.append("\t\"InstalledDepots\"\n\t{\n")
        for ((depot, manifest) in depots.toSortedMap()) {
            b.append("\t\t${q(depot.toString())}\n\t\t{\n\t\t\t\"manifest\"\t\t${q(manifest.toString())}\n\t\t\t\"size\"\t\t\"0\"\n\t\t}\n")
        }
        b.append("\t}\n")
        b.append("\t\"UserConfig\"\n\t{\n\t\t\"language\"\t\t\"english\"\n\t}\n")
        b.append("\t\"MountedConfig\"\n\t{\n\t\t\"language\"\t\t\"english\"\n\t}\n")
        b.append("}\n")
        return b.toString()
    }

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** [name] in [parent] ignoring case (Steam's folders are spelled as Windows likes). */
    private fun child(parent: File, name: String): File =
        parent.listFiles()?.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: File(parent, name)
}
