package com.toer15.queststeam.display

import android.content.Context
import android.content.pm.PackageManager
import com.toer15.queststeam.launcher.RuntimeCore
import java.io.File

/**
 * GPU driver for games: Vortek (Winlator 11.2's default, LGPL-2.1). The game's Vulkan calls go to
 * libvulkan_vortek.so in the runtime, over a socket to the app, and run on the headset's own
 * Vulkan driver. Mesa Turnip (the alternative) talks to the GPU kernel driver directly, which the
 * Quest refuses ("BO init VK_ERROR_OUT_OF_DEVICE_MEMORY").
 */
object Vortek {
    const val VERSION = "2.1"
    const val SOCKET = "/tmp/.vortek/V0"
    private const val ASSET_DIR = "graphics"
    private const val DRIVER_ASSET = "vortek-2.1.tzst"
    const val PREF = "gpu_driver"
    const val VORTEK = "vortek"
    const val TURNIP = "turnip"

    fun selected(ctx: Context): String =
        ctx.getSharedPreferences("runtime", 0).getString(PREF, VORTEK) ?: VORTEK

    fun select(ctx: Context, driver: String) =
        ctx.getSharedPreferences("runtime", 0).edit().putString(PREF, driver).apply()

    fun isBundled(ctx: Context) = runCatching { ctx.assets.list(ASSET_DIR)?.contains(DRIVER_ASSET) == true }.getOrDefault(false)

    fun icd(rootfs: File) = File(rootfs, "usr/share/vulkan/icd.d/vortek_icd.aarch64.json")

    /** The headset's Vulkan version (major, minor), from Android's feature list. */
    fun deviceVulkan(ctx: Context): Pair<Int, Int>? {
        val v = ctx.packageManager.systemAvailableFeatures
            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version ?: return null
        return (v ushr 22) to ((v ushr 12) and 0x3ff)
    }

    /**
     * Puts the guest side of Vortek into the runtime (once per version) and the DXVK the headset can
     * run: DXVK 2.x needs Vulkan 1.3, else 1.10.3 (Winlator's own rule). Returns false if Vortek
     * isn't in this build or the runtime lacks a piece it needs.
     */
    fun prepare(ctx: Context, rootfs: File, log: (String) -> Unit): Boolean {
        if (!isBundled(ctx)) { log("Vortek GPU driver isn't in this build; using Turnip."); return false }
        val from = RuntimeCore.FROM_PREFIX.toByteArray()
        val to = RuntimeCore.replacementPrefix(ctx.packageName).toByteArray()
        val marker = File(rootfs, "usr/share/vulkan/.qss-vortek")
        val lib = File(rootfs, "usr/lib/libvulkan_vortek.so")
        if (runCatching { marker.readText().trim() }.getOrNull() != VERSION || !icd(rootfs).isFile || !lib.isFile) {
            ctx.assets.open("$ASSET_DIR/$DRIVER_ASSET").use { RuntimeCore.untarZstd(it, rootfs, from, to) }
            marker.writeText(VERSION)
            log("Installed the Vortek GPU driver $VERSION.")
        }
        // The ICD points at <rootfs>/lib/libvulkan_vortek.so.
        val viaLib = File(rootfs, "lib/libvulkan_vortek.so")
        if (!viaLib.exists()) {
            viaLib.parentFile?.mkdirs()
            runCatching { java.nio.file.Files.createSymbolicLink(viaLib.toPath(), lib.toPath()) }
                .onFailure { lib.copyTo(viaLib, overwrite = true) }
        }

        // The driver's own answer first (Android's feature flag says 1.1 on the Quest 3 even when the
        // driver does 1.3); DXVK 2.x needs 1.3, like Winlator's rule.
        val probed = VkProbe.info()
        val vk = probed?.let { it.major to it.minor } ?: deviceVulkan(ctx)
        val dxvk = if (vk == null || vk.first > 1 || vk.second >= 3) "2.4.1" else "1.10.3"
        log("Headset Vulkan ${vk?.let { "${it.first}.${it.second}" } ?: "unknown"}${probed?.gpu?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""} → DXVK $dxvk.")
        val windows = File(rootfs, "home/xuser/.wine/drive_c/windows")
        val dxvkMarker = File(windows, ".qss-dxvk")
        val current = runCatching { dxvkMarker.readText().trim() }.getOrNull() ?: "2.4.1"
        if (current != dxvk) {
            val asset = "dxvk-$dxvk.tzst"
            if (runCatching { ctx.assets.list(ASSET_DIR)?.contains(asset) == true }.getOrDefault(false)) {
                ctx.assets.open("$ASSET_DIR/$asset").use { RuntimeCore.untarZstd(it, windows, from, to) }
                dxvkMarker.writeText(dxvk)
                log("Installed DXVK $dxvk for this headset.")
            } else log("DXVK $dxvk isn't in this build; keeping DXVK $current.")
        }
        return true
    }
}
