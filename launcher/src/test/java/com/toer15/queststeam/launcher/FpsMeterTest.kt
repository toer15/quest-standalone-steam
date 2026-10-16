package com.toer15.queststeam.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsMeterTest {
    /**
     * Plays a game on the clock: frames every 1000/[fps] ms from [from] to [to] (exclusive), reading the
     * meter every [sampleEvery] ms as a timer of its own would. Returns what each reading said.
     */
    private fun play(m: FpsMeter, fps: Int, from: Long, to: Long, sampleEvery: Long = 137): List<Int> {
        val readings = mutableListOf<Int>()
        val period = 1000.0 / fps
        var nextFrame = from.toDouble()
        var nextSample = from + sampleEvery
        var t = from
        while (t < to) {
            if (t >= nextFrame) { m.frame(t); nextFrame += period }
            if (t >= nextSample) { readings += m.fps(t); nextSample += sampleEvery }
            t++
        }
        return readings
    }

    @Test fun steadyGameReadsTheSameEverySample() {
        val m = FpsMeter()
        val readings = play(m, 25, 0, 12_000).drop(15) // after the first two seconds
        assertTrue("readings $readings", readings.all { it in 24..26 })
    }

    @Test fun fasterGameReadsFaster() {
        val m = FpsMeter()
        val readings = play(m, 100, 0, 6_000).drop(20)
        assertTrue("readings $readings", readings.all { it in 98..102 })
    }

    @Test fun noFramesReadsZeroAfterTheWindow() {
        val m = FpsMeter()
        play(m, 30, 0, 3_000)
        assertEquals(0, m.fps(6_000))
    }

    @Test fun fewFramesAtTheStartDoNotReadHuge() {
        val m = FpsMeter()
        for (t in 0L..80L step 20L) m.frame(t) // 5 frames in 80 ms
        assertTrue(m.fps(100) <= 5)
    }

    @Test fun beforeAnyFrameIsZero() {
        assertEquals(0, FpsMeter().fps(1234))
    }

    @Test fun resetStartsOver() {
        val m = FpsMeter()
        play(m, 30, 0, 3_000)
        m.reset()
        assertEquals(0, m.fps(3_000))
        assertEquals(-1, m.takeSlowest())
        assertEquals(0L, m.takeLongestGap())
    }

    @Test fun aSlowdownShowsAsTheSlowestSecond() {
        val m = FpsMeter()
        play(m, 25, 0, 5_000)
        play(m, 2, 5_000, 13_000) // an 8 s stretch at 2 fps
        play(m, 25, 13_000, 18_000)
        val slow = m.takeSlowest()
        assertTrue("slowest $slow", slow in 1..3)
        assertEquals(-1, m.takeSlowest()) // taken: starts over
    }

    @Test fun aFreezeShowsInTheLongestGapAndTheSlowestSecond() {
        val m = FpsMeter()
        play(m, 25, 0, 4_000)
        play(m, 25, 7_000, 9_000) // nothing for about 3 s
        val gap = m.takeLongestGap()
        assertTrue("gap $gap", gap in 3_000..3_100)
        val slow = m.takeSlowest() // the block the freeze fell in: a few frames over about 4 s
        assertTrue("slowest $slow", slow in 0..8)
        assertEquals(0L, m.takeLongestGap()) // taken: starts over
    }

    @Test fun aSteadyGameHasASmallLongestGap() {
        val m = FpsMeter()
        play(m, 25, 0, 6_000)
        assertTrue(m.takeLongestGap() in 38..42)
    }

    @Test fun moreFramesThanTheBufferHoldsStillWorks() {
        val m = FpsMeter(windowMs = 2000, capacity = 64)
        val readings = play(m, 200, 0, 5_000).drop(20)
        // 64 frames fit: the reading is capped at what the buffer covers, never garbage.
        assertTrue("readings $readings", readings.all { it in 20..70 })
    }
}
