package com.toer15.queststeam.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WineRegistryTest {
    private fun reg(text: String) = File.createTempFile("user", ".reg").apply { writeText(text); deleteOnExit() }

    @Test fun addsMissingKey() {
        val f = reg("WINE REGISTRY Version 2\n\n[Control Panel\\\\Desktop] 1\n\"A\"=\"b\"\n")
        WineRegistry.setString(f, "Software\\Wine\\Drivers", "Audio", "alsa")
        val t = f.readText()
        assertTrue(t, t.contains("[Software\\\\Wine\\\\Drivers] "))
        assertTrue(t, t.contains("\"Audio\"=\"alsa\""))
        assertTrue(t, t.contains("\"A\"=\"b\""))
    }

    @Test fun replacesValueInExistingKey() {
        val f = reg("WINE REGISTRY Version 2\n\n[Software\\\\Wine\\\\Drivers] 1\n#time=1\n\"Audio\"=\"pulse\"\n\"Graphics\"=\"x11\"\n\n[Other] 2\n\"X\"=\"y\"\n")
        WineRegistry.setString(f, "Software\\Wine\\Drivers", "Audio", "")
        val t = f.readText()
        assertTrue(t, t.contains("\"Audio\"=\"\"\n\"Graphics\"=\"x11\""))
        assertEquals(1, Regex("\"Audio\"").findAll(t).count())
        assertTrue(t, t.contains("[Other] 2\n\"X\"=\"y\""))
    }

    @Test fun addsValueToExistingKey() {
        val f = reg("[Software\\\\Wine\\\\Drivers] 1\n#time=1\n\"Graphics\"=\"x11\"\n\n[Other] 2\n")
        WineRegistry.setString(f, "Software\\Wine\\Drivers", "Audio", "alsa")
        assertTrue(f.readText(), f.readText().contains("\"Graphics\"=\"x11\"\n\"Audio\"=\"alsa\"\n\n[Other] 2"))
    }
}
