package com.toer15.queststeam.play
import org.junit.Assert.assertEquals
import org.junit.Test
class VdfTest {
    @Test fun parsesManifest() {
        val t = """
"win64"
{
	"version"		"1730000000"
	"bins_win64"
	{
		"file"		"bins_win64.zip.0123abcd"
		"size"		"12345"
		"sha2"		"aa"
		"zipvz"		"bins_win64.zip.vz.0123_999"
	}
	"public_all"
	{
		"file"		"public_all.zip.ffff"
		"size"		"77"
		"IsWin64"		"1"
	}
}"""
        val root = Vdf.parse(t)
        val top = root["win64"] as Map<*, *>
        assertEquals("1730000000", top["version"])
        assertEquals("bins_win64.zip.0123abcd", (top["bins_win64"] as Map<*, *>)["file"])
        assertEquals("77", (top["public_all"] as Map<*, *>)["size"])
    }
}
