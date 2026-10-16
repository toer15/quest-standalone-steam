package com.toer15.queststeam.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DirectLaunchTest {
    @Test fun playStartsOnlyTheGame() {
        val cold = DirectLaunch.plan("R.E.P.O.", openingSteam = false, runtimeInstalled = true, gameFileExists = true)
        assertEquals(DirectLaunch.SteamRole.NONE, cold.steam)
        assertEquals("Launching R.E.P.O.…", cold.step)
        assertFalse(cold.waitForSteamSignIn)
        assertFalse(cold.step.contains("Steam"))
    }

    @Test fun runtimeAndMissingGameStayOnTheGameNotSteam() {
        val preparing = DirectLaunch.plan("R.E.P.O.", false, runtimeInstalled = false, gameFileExists = false)
        assertEquals(DirectLaunch.PREPARING, preparing.step)
        assertEquals(DirectLaunch.SteamRole.NONE, preparing.steam)
        assertFalse(preparing.waitForSteamSignIn)

        val missing = DirectLaunch.plan("R.E.P.O.", false, runtimeInstalled = true, gameFileExists = false)
        assertEquals("Launching R.E.P.O.…", missing.step)
        assertFalse(missing.step.contains("Steam"))
    }

    @Test fun onlyOpeningSteamItselfUsesTheSteamScreen() {
        val steam = DirectLaunch.plan("Steam", openingSteam = true, runtimeInstalled = true, gameFileExists = true)
        assertEquals(DirectLaunch.SteamRole.SHOW_STEAM, steam.steam)
        assertEquals("Starting Steam…", steam.step)
        assertFalse(steam.waitForSteamSignIn)
        assertEquals("Launching R.E.P.O.… 12s", DirectLaunch.launching("R.E.P.O.", 12))
        assertEquals(DirectLaunch.RUNNING, "Running")
    }
}
