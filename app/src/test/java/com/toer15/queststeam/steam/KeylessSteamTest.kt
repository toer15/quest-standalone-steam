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

    @Test fun signedInPageReturnsGamesEvenIfMarkupMentionsPrivacy() {
        val html = """<div class="profile_private_info"></div><template data-profile-gameslist="{&quot;rgGames&quot;:[{&quot;appid&quot;:10,&quot;name&quot;:&quot;Counter-Strike&quot;,&quot;playtime_forever&quot;:5}]}"></template>"""
        val g = KeylessSteam.interpretGamesResponse("steamLoginSecure=session", 200, html)
        assertEquals(10, g[0].appId)
    }

    @Test fun missingSessionAsksToSignInAgain() {
        try {
            KeylessSteam.interpretGamesResponse(null, 200, "<html>profile_private_info</html>")
            throw AssertionError("expected NeedsLogin")
        } catch (e: KeylessSteam.NeedsLoginException) {
            assertEquals(KeylessSteam.SIGN_IN_AGAIN, e.message)
            assertTrue(!e.message!!.contains("Public"))
            assertTrue(!e.message!!.contains("privacy", ignoreCase = true))
            assertTrue(!e.message!!.contains("Game details"))
        }
    }

    @Test fun rejectedSessionAsksToSignInAgain() {
        try {
            KeylessSteam.interpretGamesResponse("steamLoginSecure=session", 302, "")
            throw AssertionError("expected NeedsLogin")
        } catch (e: KeylessSteam.NeedsLoginException) {
            assertEquals("Sign out and sign in again", e.message)
        }
    }

    @Test fun privateWallWithoutGamesAsksToSignInAgain() {
        try {
            KeylessSteam.interpretGamesResponse("steamLoginSecure=session", 200, """<div class="profile_private_info">This profile is private</div>""")
            throw AssertionError("expected NeedsLogin")
        } catch (e: KeylessSteam.NeedsLoginException) {
            assertEquals(KeylessSteam.SIGN_IN_AGAIN, e.message)
        }
    }

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
        catch (e: KeylessSteam.NeedsLoginException) { assertEquals(KeylessSteam.SIGN_IN_AGAIN, e.message) }
    }
}
