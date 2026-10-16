package com.toer15.queststeam.play

/**
 * How full the headset's memory is. Steam's browser alone takes 2 to 4 GB while Steam signs in, and the
 * game comes on top; when Android runs out it stutters, the picture freezes and then it closes the app
 * (with Steam and the game). The app gives memory back before that. Pure logic, so it is tested.
 */
object MemoryGuard {
    enum class Level {
        /** Enough room. */
        OK,
        /** Getting tight: pictures the app keeps are dropped, and Steam's browser stays paused. */
        LOW,
        /** Almost out: what isn't needed right now is closed (Steam's browser, then a game waiting in the background). */
        CRITICAL,
    }

    /** Free memory above Android's own "low memory" line below which the app starts giving memory back, in MB. */
    const val LOW_HEADROOM_MB = 600L

    /** Below this, the app closes Steam's browser and a game waiting in the background. */
    const val CRITICAL_HEADROOM_MB = 220L

    // Android's ComponentCallbacks2 trim levels, which the system sends the app when memory runs short.
    const val TRIM_RUNNING_LOW = 10
    const val TRIM_RUNNING_CRITICAL = 15
    const val TRIM_UI_HIDDEN = 20
    const val TRIM_BACKGROUND = 40

    /**
     * [availMb]: memory Android could hand out now; [thresholdMb]: where Android itself calls memory low;
     * [systemLow]: Android says it is low; [trim]: the trim level the system last asked the app for (0 = none).
     */
    fun level(availMb: Long, thresholdMb: Long, systemLow: Boolean = false, trim: Int = 0): Level {
        val headroom = availMb - thresholdMb
        return when {
            systemLow || trim == TRIM_RUNNING_CRITICAL || headroom < CRITICAL_HEADROOM_MB -> Level.CRITICAL
            trim == TRIM_RUNNING_LOW || headroom < LOW_HEADROOM_MB -> Level.LOW
            else -> Level.OK
        }
    }

    /** How long to wait before looking again: sooner the closer memory is to running out. */
    fun nextCheckMs(level: Level, availMb: Long, thresholdMb: Long): Long = when {
        level == Level.CRITICAL -> 1_000L
        level == Level.LOW -> 1_500L
        availMb - thresholdMb < LOW_HEADROOM_MB * 2 -> 2_500L
        else -> 4_000L
    }
}

/**
 * CPU priority (Linux nice value) of the Windows programs. Wine and Box64 start dozens of threads that use
 * every core for minutes (Steam signing in, a game loading); at the normal priority they compete with the
 * headset's own system threads and the app's display threads, and the headset stalls. [POLITE] still uses
 * every free core at full speed, but gives way to anything else that wants the CPU; it is just below
 * Android's "background" priority (10), which would also move the threads onto the small cores only.
 */
object ProcessPriority {
    const val NORMAL = 0
    const val POLITE = 9
    const val LOWEST = 19

    /** Nice values for Steam's programs and for everything else Wine runs. */
    data class Nice(val steam: Int, val others: Int)

    /**
     * [gameUp]: a game is starting or running next to Steam; [busy]: a loading screen or the game screen is up;
     * [steamSignedIn]: Steam has signed in.
     */
    fun pick(gameUp: Boolean, busy: Boolean, steamSignedIn: Boolean): Nice = when {
        // The game gets the processor; Steam only ticks over behind it.
        gameUp -> Nice(steam = LOWEST, others = POLITE)
        // Steam signing in, or a loading screen: everything runs, yielding only to the system.
        busy || !steamSignedIn -> Nice(steam = POLITE, others = POLITE)
        // Signed in and you're in the app: the app stays smooth, everything Wine runs waits.
        else -> Nice(steam = LOWEST, others = LOWEST)
    }
}
