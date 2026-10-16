package com.toer15.queststeam.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PreExecTest {
    private val rc = "[steam.exe]\nBOX64_ENV=WINEARGS=-noshaders -nooverlay\nBOX64_ENV1=WINEDLLOVERRIDES=gameoverlayrenderer=d\nBOX64_ENV2=WINPREEXEC=winpreexec\n\n" +
        "[CNC3.exe]\nBOX64_ENV=WINPREEXEC=winpreexec\n\n[Launcher.exe]\nbox64_env = winpreexec = winpreexec\n\n[witcher3.exe]\nBOX64_DYNAREC_STRONGMEM=1\n"

    @Test fun rcLosesOnlyThePreExecLines() {
        val out = RuntimeCore.sanitizeBox64Rc(rc)
        assertFalse(out, out.contains("WINPREEXEC", ignoreCase = true))
        assertTrue(out.contains("BOX64_ENV=WINEARGS=-noshaders -nooverlay"))
        assertTrue(out.contains("BOX64_ENV1=WINEDLLOVERRIDES=gameoverlayrenderer=d"))
        assertTrue(out.contains("[CNC3.exe]") && out.contains("[Launcher.exe]") && out.contains("[witcher3.exe]\nBOX64_DYNAREC_STRONGMEM=1"))
        assertEquals(rc.lines().size - 3, out.lines().size)
        assertEquals(out, RuntimeCore.sanitizeBox64Rc(out))
    }

    @Test fun disablePreExecFixesAnInstalledRuntimeOnce() {
        val root = Files.createTempDirectory("rootfs").toFile()
        try {
            val rcFile = root.resolve("etc/config.box64rc").apply { parentFile.mkdirs(); writeText(rc) }
            val helper = root.resolve("usr/local/bin/winpreexec").apply { parentFile.mkdirs(); writeText("#!fake") }
            val first = RuntimeCore.disablePreExec(root)
            assertEquals(2, first.size)
            assertFalse(rcFile.readText().contains("WINPREEXEC"))
            assertFalse(helper.exists())
            assertTrue(root.resolve("usr/local/bin/winpreexec.disabled").exists())
            assertTrue(RuntimeCore.disablePreExec(root).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun helperProcessesAreNamedAfterTheirProgram() {
        val r = "/data/user/0/com.qssteam/files/rootfs"
        assertEquals("winpreexec(Steam.exe)", GameLauncher.processName("$r/usr/local/bin/winpreexec C:\\Program Files (x86)\\Steam\\Steam.exe -silent"))
        assertEquals("Steam.exe(loading)", GameLauncher.processName("$r/opt/wine/lib/wine/x86_64-unix/wine C:\\Program Files (x86)\\Steam\\Steam.exe"))
        assertEquals("steam.exe", GameLauncher.processName("C:\\Program Files (x86)\\Steam\\steam.exe -silent"))
        assertEquals("wineserver", GameLauncher.processName("$r/opt/wine/bin/wineserver -p"))
    }

    @Test fun winesLauncherForAGameIsNotTheGame() {
        val r = "/data/user/0/com.qssteam/files/rootfs"
        val launcher = "$r/usr/local/bin/box64 $r/opt/wine/bin/wine explorer /desktop=game1,1280x720 C:\\games\\3241660\\REPO.exe"
        assertEquals("explorer.exe", GameLauncher.processName(launcher))
        assertFalse(GameLauncher.isProcessOf(GameLauncher.processName(launcher), "REPO.exe"))
        assertEquals("start.exe", GameLauncher.processName("$r/opt/wine/bin/wine start /unix $r/x/Game.exe"))
        // The game itself, before and after Wine names it by its Windows path.
        assertEquals("REPO.exe", GameLauncher.processName("$r/usr/local/bin/box64 $r/opt/wine/bin/wine C:\\games\\3241660\\REPO.exe"))
        assertEquals("REPO.exe", GameLauncher.processName("C:\\games\\3241660\\REPO.exe"))
    }

    @Test fun steamsOwnProgramsAreToldApartFromTheGame() {
        for (n in listOf("steam.exe", "Steam.exe", "steamwebhelper.exe", "steamservice.exe", "steamerrorreporter64.exe(loading)", "GameOverlayUI.exe")) {
            assertTrue(n, GameLauncher.isSteamProgram(n))
        }
        for (n in listOf("REPO.exe", "explorer.exe", "wineserver", "services.exe", "UnityCrashHandler64.exe")) {
            assertFalse(n, GameLauncher.isSteamProgram(n))
        }
    }

    @Test fun aGameIsFoundByItsExeName() {
        assertTrue(GameLauncher.isProcessOf("REPO.exe", "repo.exe"))
        assertTrue(GameLauncher.isProcessOf("REPO.exe(loading)", "REPO.exe"))
        assertFalse(GameLauncher.isProcessOf("winpreexec(REPO.exe)", "REPO.exe"))
        assertFalse(GameLauncher.isProcessOf("explorer.exe", "REPO.exe"))
    }
}
