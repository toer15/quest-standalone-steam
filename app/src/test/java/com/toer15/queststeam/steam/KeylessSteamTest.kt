package com.toer15.queststeam.steam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class KeylessSteamTest {
    @Test fun parsesCurrentGamesListAttribute() {
        val html = """<template id="gameslist_config" data-profile-gameslist="{&quot;strProfileName&quot;:&quot;X&quot;,&quot;rgGames&quot;:[{&quot;appid&quot;:620,&quot;name&quot;:&quot;Portal 2&quot;,&quot;playtime_forever&quot;:125},{&quot;appid&quot;:546560,&quot;name&quot;:&quot;Half-Life: Alyx &amp; more&quot;}]}"></template>"""
        val g = KeylessSteam.parseGamesPage(html)!!
        assertEquals(2, g.size); assertEquals(620, g[0].appId); assertEquals(125, g[0].playtimeMinutes)
        assertEquals("Half-Life: Alyx & more", g[1].name)
    }

    @Test fun parsesLegacyRgGames() {
        val html = """<script>var rgGames = [{"appid":440,"name":"Team Fortress 2","hours_forever":"1,234.5"}];</script>"""
        val g = KeylessSteam.parseGamesPage(html)!!
        assertEquals(440, g[0].appId); assertEquals(74070, g[0].playtimeMinutes)
    }

    @Test fun unknownPageReturnsNull() = assertEquals(null, KeylessSteam.parseGamesPage("<html>nothing</html>"))

    /** Live: public profile XML works without a key. Run with -Dlive=1 */
    @Test fun liveProfileXml() {
        assumeTrue(System.getProperty("live") == "1")
        val p = KeylessSteam.profile("76561198006409530")
        assertNotNull(p); assertEquals("public", p!!.privacyState); assertTrue(p.summary.avatarUrl.startsWith("https://"))
        println("profile: ${p.summary.personaName} avatar ok")
    }

    /** Live: anonymous games page now requires login -> NeedsLoginException. */
    @Test fun liveGamesNeedsLogin() {
        assumeTrue(System.getProperty("live") == "1")
        try { KeylessSteam.ownedGames("76561198006409530", null); throw AssertionError("expected NeedsLogin") }
        catch (e: KeylessSteam.NeedsLoginException) { println("anonymous games page -> needs login (expected)") }
    }
}
