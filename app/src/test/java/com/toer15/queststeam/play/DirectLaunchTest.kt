package com.toer15.queststeam.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DirectLaunchTest {
    @Test fun playGoesToTheGameWithoutWaitingForSteam() {
        val cold = DirectLaunch.plan(
            title = "R.E.P.O.",
            openingSteam = false,
            runtimeInstalled = true,
            gameFileExists = true,
            steamInstalled = true,
            startSteamWithGames = true,
            steamAlreadyRunning = false,
        )
        assertEquals(DirectLaunch.SteamRole.SILENT_BESIDE_GAME, cold.steam)
        assertEquals("Launching R.E.P.O.…", cold.step)
        assertFalse(cold.waitForSteamSignIn)
        assertFalse(cold.step.startsWith("Starting Steam"))

        val running = DirectLaunch.plan(
            title = "R.E.P.O.",
            openingSteam = false,
            runtimeInstalled = true,
            gameFileExists = true,
            steamInstalled = true,
            startSteamWithGames = true,
            steamAlreadyRunning = true,
        )
        assertEquals(DirectLaunch.SteamRole.NONE, running.steam)
        assertEquals("Launching R.E.P.O.…", running.step)
        assertFalse(running.waitForSteamSignIn)
    }

    @Test fun runtimeAndMissingGameStayOnTheGameNotSteam() {
        val preparing = DirectLaunch.plan("R.E.P.O.", false, runtimeInstalled = false, gameFileExists = false, true, true, false)
        assertEquals(DirectLaunch.PREPARING, preparing.step)
        assertEquals(DirectLaunch.SteamRole.NONE, preparing.steam)
        assertFalse(preparing.waitForSteamSignIn)

        val missing = DirectLaunch.plan("R.E.P.O.", false, runtimeInstalled = true, gameFileExists = false, true, true, false)
        assertEquals("Launching R.E.P.O.…", missing.step)
        assertFalse(missing.step.contains("Steam"))

        val noClient = DirectLaunch.plan("R.E.P.O.", false, true, true, steamInstalled = false, startSteamWithGames = true, steamAlreadyRunning = false)
        assertEquals(DirectLaunch.SteamRole.NONE, noClient.steam)
        assertEquals("Launching R.E.P.O.…", noClient.step)
    }

    @Test fun onlyOpeningSteamItselfUsesTheSteamScreen() {
        val steam = DirectLaunch.plan("Steam", openingSteam = true, true, true, true, true, false)
        assertEquals(DirectLaunch.SteamRole.SHOW_STEAM, steam.steam)
        assertEquals("Starting Steam…", steam.step)
        assertFalse(steam.waitForSteamSignIn)
        assertEquals("Launching R.E.P.O.… 12s", DirectLaunch.launching("R.E.P.O.", 12))
        assertEquals(DirectLaunch.RUNNING, "Running")
    }
}
