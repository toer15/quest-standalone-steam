package com.toer15.queststeam.launcher

/**
 * Frames per second over a sliding window, for the game screen's counter. A count over fixed
 * one-second blocks that a timer of its own reads once a second jumps by several fps for a game that
 * runs steadily; the last [windowMs] milliseconds counted at the moment of reading stay steady. Times
 * are milliseconds on any clock that only goes forward.
 */
class FpsMeter(private val windowMs: Long = 2000, capacity: Int = 1024) {
    private val times = LongArray(capacity)
    private var head = 0
    private var count = 0
    private var first = 0L
    private var secondStart = 0L
    private var secondFrames = 0
    private var slowest = Int.MAX_VALUE
    private var lastFrame = 0L
    private var longestGap = 0L

    /** Forget everything (the next program's frames start a new count). */
    @Synchronized fun reset() {
        head = 0; count = 0; first = 0L
        secondStart = 0L; secondFrames = 0; slowest = Int.MAX_VALUE
        lastFrame = 0L; longestGap = 0L
    }

    /** A frame was presented at [now]. */
    @Synchronized fun frame(now: Long) {
        if (count == 0) first = now else if (now - lastFrame > longestGap) longestGap = now - lastFrame
        lastFrame = now
        times[head] = now
        head = (head + 1) % times.size
        if (count < times.size) count++
        // The slowest second and the longest wait between two frames, for the log (a freeze shows in both).
        if (secondStart == 0L) secondStart = now
        if (now - secondStart >= 1000) {
            val rate = Math.round(secondFrames * 1000f / (now - secondStart))
            if (rate < slowest) slowest = rate
            secondStart = now
            secondFrames = 0
        }
        secondFrames++
    }

    /**
     * Frames per second at [now]: the frames of the last window over the window, over the time since the
     * first frame while that is shorter (but at least a second, so a few frames at the start don't read huge).
     */
    @Synchronized fun fps(now: Long): Int {
        if (count == 0) return 0
        var n = 0
        for (i in 0 until count) {
            val t = times[(head - 1 - i + times.size) % times.size]
            if (now - t > windowMs) break
            n++
        }
        val span = (now - first).coerceIn(1000L, windowMs)
        return Math.round(n * 1000f / span)
    }

    /** The lowest frames-per-second of any whole second since the last call (-1 = none yet), then starts over. */
    @Synchronized fun takeSlowest(): Int = (if (slowest == Int.MAX_VALUE) -1 else slowest).also { slowest = Int.MAX_VALUE }

    /** The longest wait between two frames, in ms, since the last call (0 = fewer than two frames), then starts over. */
    @Synchronized fun takeLongestGap(): Long = longestGap.also { longestGap = 0L }
}
