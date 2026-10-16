package com.toer15.queststeam.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WineEnvironmentTest {
    @Test fun unsetRemovesAVariable() {
        val env = RuntimeCore.wineEnvironment("/r", null, "test", mapOf("BOX64_DYNAREC_SAFEFLAGS" to RuntimeCore.UNSET, "X" to "1"))
        assertFalse(env.containsKey("BOX64_DYNAREC_SAFEFLAGS"))
        assertEquals("1", env["X"])
        assertEquals("2", RuntimeCore.wineEnvironment("/r", null, "test")["BOX64_DYNAREC_SAFEFLAGS"])
    }

    @Test fun presetKeysAreThePresetsSettings() {
        val base = RuntimeCore.wineEnvironment("/r", null, "test")
        assertTrue(RuntimeCore.BOX64_PRESET_KEYS.all { it in base })
        // Every BOX64_DYNAREC_* the runtime sets is in the list (Fast unsets the whole preset).
        assertEquals(base.keys.filter { it.startsWith("BOX64_DYNAREC_") }.toSet(), RuntimeCore.BOX64_PRESET_KEYS.toSet())
        val fast = RuntimeCore.wineEnvironment("/r", null, "test", RuntimeCore.BOX64_PRESET_KEYS.associateWith { RuntimeCore.UNSET } + mapOf("BOX64_DYNACACHE" to "1"))
        assertTrue(RuntimeCore.BOX64_PRESET_KEYS.none { it in fast })
        assertEquals("1", fast["BOX64_DYNACACHE"])
        assertEquals("1", fast["BOX64_DYNAREC"])
    }

    @Test fun dxvkStateCacheIsKept() {
        assertEquals("/r/home/xuser/.cache/dxvk", RuntimeCore.wineEnvironment("/r", null, "test")["DXVK_STATE_CACHE_PATH"])
    }
}
