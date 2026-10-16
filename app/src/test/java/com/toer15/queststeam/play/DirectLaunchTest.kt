package com.toer15.queststeam.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun aGameShowsAsSoonAsItsProcessStarts() {
        assertTrue(DirectLaunch.showImmediately(openingSteam = false))
        assertFalse(DirectLaunch.showImmediately(openingSteam = true))
    }

    @Test fun playNeverStartsSteamExe() {
        assertTrue(DirectLaunch.startsOnlyTheGame(openingSteam = false))
        assertFalse(DirectLaunch.startsOnlyTheGame(openingSteam = true))
        val game = DirectLaunch.plan("R.E.P.O.", openingSteam = false, runtimeInstalled = true, gameFileExists = true)
        assertEquals(DirectLaunch.SteamRole.NONE, game.steam)
        assertFalse(game.step.contains("Steam"))
    }

    @Test fun playUsesSteamsOwnApplaunchForThatGame() {
        val game = DirectLaunch.plan(
            "R.E.P.O.", openingSteam = false, runtimeInstalled = true, gameFileExists = true,
            appId = 3241660, steamInstalled = true,
        )
        assertEquals(DirectLaunch.SteamRole.APPLAUNCH, game.steam)
        assertEquals("Launching R.E.P.O.…", game.step)
        assertFalse(game.step.contains("Starting Steam"))
        assertFalse(game.waitForSteamSignIn)
        assertEquals(
            listOf("-silent", "-applaunch", "3241660", "-screen-width", "960", "-screen-height", "540"),
            DirectLaunch.applaunchArgs(3241660, listOf("-screen-width", "960", "-screen-height", "540")),
        )
        assertFalse(DirectLaunch.usesApplaunch(openingSteam = true, appId = 3241660, steamInstalled = true))
        assertFalse(DirectLaunch.usesApplaunch(openingSteam = false, appId = 0, steamInstalled = true))
    }

    @Test fun aRunningSteamIsKeptForTheGame() {
        val beside = DirectLaunch.plan(
            "R.E.P.O.", openingSteam = false, runtimeInstalled = true, gameFileExists = true,
            appId = 3241660, steamInstalled = true, steamRunning = true,
        )
        assertEquals(DirectLaunch.SteamRole.BESIDE_STEAM, beside.steam)
        assertEquals("Launching R.E.P.O.…", beside.step)
        assertFalse(beside.waitForSteamSignIn)
        // Opening Steam's own window still opens Steam.
        val steam = DirectLaunch.plan("Steam", openingSteam = true, runtimeInstalled = true, gameFileExists = true, steamRunning = true)
        assertEquals(DirectLaunch.SteamRole.SHOW_STEAM, steam.steam)
    }

    @Test fun noSteamRunningMeansSteamStartsFirstAndTheAppStartsTheGame() {
        val cold = DirectLaunch.plan(
            "R.E.P.O.", openingSteam = false, runtimeInstalled = true, gameFileExists = true,
            appId = 3241660, steamInstalled = true, steamStartsGame = false,
        )
        assertEquals(DirectLaunch.SteamRole.STEAM_FIRST, cold.steam)
        assertEquals("Launching R.E.P.O.…", cold.step)
        assertFalse(cold.step.contains("Starting Steam"))
        // A game that isn't in Steam's library (no app id) has nothing for Steam to do: it starts alone.
        val loose = DirectLaunch.plan("Game", false, true, true, appId = 0, steamInstalled = true, steamStartsGame = false)
        assertEquals(DirectLaunch.SteamRole.NONE, loose.steam)
        // Steam not installed: alone.
        val noSteam = DirectLaunch.plan("Game", false, true, true, appId = 3241660, steamInstalled = false, steamStartsGame = false)
        assertEquals(DirectLaunch.SteamRole.NONE, noSteam.steam)
        // Steam running already: next to it, whichever way Steam starts games.
        for (starts in listOf(true, false)) {
            val beside = DirectLaunch.plan("Game", false, true, true, appId = 3241660, steamInstalled = true, steamRunning = true, steamStartsGame = starts)
            assertEquals(DirectLaunch.SteamRole.BESIDE_STEAM, beside.steam)
        }
    }

    @Test fun theGameScreenShowsHowFarSteamIs() {
        assertEquals(
            "Launching R.E.P.O.… 34s · Steam is signing in (34s of about 80s)",
            DirectLaunch.waitingNote("R.E.P.O.", 34, DirectLaunch.Wait.STEAM_SIGNING_IN, lastSignInS = 80, steamSeconds = 34),
        )
        assertEquals("Launching R.E.P.O.… 5s · Steam is signing in (5s)", DirectLaunch.waitingNote("R.E.P.O.", 5, DirectLaunch.Wait.STEAM_SIGNING_IN, steamSeconds = 5))
        assertEquals("Launching R.E.P.O.… 5s", DirectLaunch.waitingNote("R.E.P.O.", 5, DirectLaunch.Wait.NONE, lastSignInS = 80, steamSeconds = 5))
    }

    @Test fun theGameScreenSaysWhatItWaitsFor() {
        assertEquals("Launching R.E.P.O.… 12s", DirectLaunch.waitingNote("R.E.P.O.", 12, DirectLaunch.Wait.NONE))
        assertEquals(
            "Launching R.E.P.O.… 12s · Steam is signing in (93s last time)",
            DirectLaunch.waitingNote("R.E.P.O.", 12, DirectLaunch.Wait.STEAM_SIGNING_IN, lastSignInS = 93),
        )
        assertEquals("Launching R.E.P.O.… 12s · Steam is signing in", DirectLaunch.waitingNote("R.E.P.O.", 12, DirectLaunch.Wait.STEAM_SIGNING_IN))
        assertEquals("Launching R.E.P.O.… 40s · Steam is starting it", DirectLaunch.waitingNote("R.E.P.O.", 40, DirectLaunch.Wait.STEAM_STARTING_GAME))
        assertFalse(DirectLaunch.waitingNote("R.E.P.O.", 5, DirectLaunch.Wait.STEAM_SIGNING_IN).contains("Starting Steam"))
    }
}
