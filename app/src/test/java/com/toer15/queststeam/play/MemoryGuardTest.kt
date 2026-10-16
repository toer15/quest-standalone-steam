package com.toer15.queststeam.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGuardTest {
    @Test fun plentyOfMemoryIsFine() {
        assertEquals(MemoryGuard.Level.OK, MemoryGuard.level(availMb = 2400, thresholdMb = 400))
    }

    @Test fun tightMemoryStartsGivingBackPictures() {
        // 1000 MB free with Android's line at 400: 600 MB of room is not yet low, 500 is.
        assertEquals(MemoryGuard.Level.OK, MemoryGuard.level(1000, 400))
        assertEquals(MemoryGuard.Level.LOW, MemoryGuard.level(900, 400))
    }

    @Test fun almostOutClosesThingsThatAreNotNeeded() {
        assertEquals(MemoryGuard.Level.CRITICAL, MemoryGuard.level(availMb = 600, thresholdMb = 400))
        assertEquals(MemoryGuard.Level.LOW, MemoryGuard.level(availMb = 650, thresholdMb = 400))
    }

    @Test fun androidSayingItIsLowAlwaysCounts() {
        assertEquals(MemoryGuard.Level.CRITICAL, MemoryGuard.level(availMb = 5000, thresholdMb = 400, systemLow = true))
    }

    @Test fun theSystemsOwnTrimRequestsCount() {
        assertEquals(MemoryGuard.Level.OK, MemoryGuard.level(3000, 400, trim = 5))
        assertEquals(MemoryGuard.Level.LOW, MemoryGuard.level(3000, 400, trim = MemoryGuard.TRIM_RUNNING_LOW))
        assertEquals(MemoryGuard.Level.CRITICAL, MemoryGuard.level(3000, 400, trim = MemoryGuard.TRIM_RUNNING_CRITICAL))
        // Hidden-UI and background trims are about the app being out of sight, not about running out.
        assertEquals(MemoryGuard.Level.OK, MemoryGuard.level(3000, 400, trim = MemoryGuard.TRIM_UI_HIDDEN))
        assertEquals(MemoryGuard.Level.OK, MemoryGuard.level(3000, 400, trim = MemoryGuard.TRIM_BACKGROUND))
    }

    @Test fun theRunFromYourLogStaysOutOfTheDestructiveLevel() {
        // The v0.19.2 log: 1108 MB free as the game started, 953 while it loaded, 655 at its first frame.
        // With Android's line around 400 MB none of that closes Steam's browser; the last two ask for pictures back.
        for (free in listOf(1108L, 953L, 655L)) {
            assertTrue("free $free", MemoryGuard.level(free, 400) != MemoryGuard.Level.CRITICAL)
        }
        assertEquals(MemoryGuard.Level.LOW, MemoryGuard.level(655, 400))
    }

    @Test fun lookingAgainSoonerAsMemoryRunsOut() {
        val ok = MemoryGuard.nextCheckMs(MemoryGuard.Level.OK, 3000, 400)
        val nearly = MemoryGuard.nextCheckMs(MemoryGuard.Level.OK, 1400, 400)
        val low = MemoryGuard.nextCheckMs(MemoryGuard.Level.LOW, 800, 400)
        val critical = MemoryGuard.nextCheckMs(MemoryGuard.Level.CRITICAL, 500, 400)
        assertTrue("$ok $nearly $low $critical", ok > nearly && nearly > low && low > critical)
    }

    @Test fun aGameGetsTheProcessorAndSteamGivesWay() {
        val n = ProcessPriority.pick(gameUp = true, busy = true, steamSignedIn = true)
        assertEquals(ProcessPriority.LOWEST, n.steam)
        assertEquals(ProcessPriority.POLITE, n.others)
    }

    @Test fun steamSigningInRunsFullSpeedButPolitely() {
        for (busy in listOf(true, false)) {
            val n = ProcessPriority.pick(gameUp = false, busy = busy, steamSignedIn = false)
            assertEquals(ProcessPriority.POLITE, n.steam)
            assertEquals(ProcessPriority.POLITE, n.others)
        }
    }

    @Test fun inTheAppWithSteamReadyEverythingWaits() {
        val n = ProcessPriority.pick(gameUp = false, busy = false, steamSignedIn = true)
        assertEquals(ProcessPriority.LOWEST, n.steam)
        assertEquals(ProcessPriority.LOWEST, n.others)
    }

    @Test fun politeStaysOnTheBigCoresLowestDoesNot() {
        // Android moves a thread to its background group (small cores only) from priority 10 up.
        assertTrue(ProcessPriority.POLITE in 1..9)
        assertTrue(ProcessPriority.LOWEST >= 10)
    }
}
