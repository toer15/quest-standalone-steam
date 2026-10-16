package com.toer15.queststeam.display

/** The headset's real Vulkan version and GPU, straight from the driver (native, in libwinlator). */
object VkProbe {
    data class Info(val major: Int, val minor: Int, val gpu: String)

    @Volatile private var cached: Info? = null

    fun info(): Info? = cached ?: runCatching {
        System.loadLibrary("winlator")
        val parts = probe()?.split("|", limit = 3) ?: return null
        val v = parts[0].toLong().toInt()
        Info(v ushr 22, (v ushr 12) and 0x3ff, parts.getOrElse(2) { "" })
    }.getOrNull()?.also { cached = it }

    private external fun probe(): String?
}
