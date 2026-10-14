package com.toer15.queststeam.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Quest 3 design: a large landscape panel aimed at with controllers or hands (big cards, text and hit targets). */
data class DeviceUi(
    val isQuest: Boolean,
    val gutter: Dp,
    val contentMaxWidth: Dp,
    val tabText: TextUnit,
    val tabPadH: Dp,
    val tabPadV: Dp,
    val avatar: Dp,
    val iconButton: Dp,
    val gridCardMin: Dp,
    val shelfCardWidth: Dp,
    val cardGap: Dp,
    val cardTitle: TextUnit,
    val buttonHeight: Dp,
    val heroHeight: Dp,
    val heroTitle: TextUnit,
    val welcomeText: TextUnit,
    val popupMaxWidth: Dp,
    val letterCell: Dp,
) {
    companion object {
        val Quest = DeviceUi(
            isQuest = true, gutter = 32.dp, contentMaxWidth = 1500.dp,
            tabText = 19.sp, tabPadH = 22.dp, tabPadV = 10.dp, avatar = 54.dp, iconButton = 52.dp,
            gridCardMin = 300.dp, shelfCardWidth = 380.dp, cardGap = 22.dp, cardTitle = 19.sp, buttonHeight = 52.dp,
            heroHeight = 320.dp, heroTitle = 38.sp, welcomeText = 34.sp,
            popupMaxWidth = 760.dp, letterCell = 56.dp,
        )
        val Current: DeviceUi get() = Quest
    }
}

val LocalDeviceUi = staticCompositionLocalOf { DeviceUi.Current }
