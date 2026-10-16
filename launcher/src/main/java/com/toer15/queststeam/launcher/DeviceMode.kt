package com.toer15.queststeam.launcher

import android.content.Context
import android.os.Build

/** Phone vs Quest detection. On phones (and Quest without the VR bridge) games run in a flat 2D window. */
object DeviceMode {
    val isQuest: Boolean
        get() = Build.MANUFACTURER.equals("Oculus", true) || Build.MANUFACTURER.equals("Meta", true) ||
            Build.MODEL.contains("Quest", true)

    fun hasVrFeature(ctx: Context) = ctx.packageManager.hasSystemFeature("android.hardware.vr.headtracking")

    fun label(ctx: Context) = if (isQuest || hasVrFeature(ctx)) "Quest mode · VR or flat games" else "Phone mode (flat)"
}
