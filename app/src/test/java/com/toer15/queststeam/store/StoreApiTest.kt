package com.toer15.queststeam.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreApiTest {
    @Test fun parsesSearchPageAndSkipsBundles() {
        val json = """{"desc":"","items":[
            {"name":"Beat Saber","logo":"https:\/\/shared.akamai.steamstatic.com\/store_item_assets\/steam\/apps\/620980\/capsule_sm_120.jpg?t=1"},
            {"name":"Some Bundle","logo":"https:\/\/shared.akamai.steamstatic.com\/store_item_assets\/steam\/bundles\/123\/capsule.jpg"},
            {"name":"R.E.P.O.","logo":"https://cdn.akamai.steamstatic.com/steam/apps/3241660/capsule_sm_120.jpg"}]}"""
        val items = StoreApi.parseSearch(json)
        assertEquals(listOf(620980, 3241660), items.map { it.appId })
        assertEquals("Beat Saber", items[0].name)
    }

    @Test fun parsesStoreSearch() {
        val json = """{"total":2,"items":[{"type":"app","name":"Half-Life: Alyx","id":546560},{"type":"sub","name":"Pack","id":9}]}"""
        assertEquals(listOf(StoreItem(546560, "Half-Life: Alyx")), StoreApi.parseStoreSearch(json))
    }

    @Test fun parsesPricesDiscountsAndFree() {
        val json = """{
            "620980":{"success":true,"data":{"price_overview":{"currency":"USD","initial":2999,"final":2999,"discount_percent":0,"initial_formatted":"","final_formatted":"$29.99"}}},
            "546560":{"success":true,"data":{"price_overview":{"currency":"USD","initial":5999,"final":2999,"discount_percent":50,"initial_formatted":"$59.99","final_formatted":"$29.99"}}},
            "570":{"success":true,"data":[]},
            "1":{"success":false}}"""
        val p = StoreApi.parsePrices(json)
        assertEquals("$29.99", p[620980]!!.final)
        assertEquals(50, p[546560]!!.discountPercent)
        assertEquals("$59.99", p[546560]!!.initial)
        assertTrue(p[570]!!.free)
        assertTrue(1 !in p)
    }

    @Test fun parsesFeaturedLists() {
        val json = """{"top_sellers":{"id":"cat_topsellers","items":[{"id":730,"type":0,"name":"Counter-Strike 2"},{"id":5,"type":1,"name":"Pack"}]},
            "specials":{"items":[{"id":620980,"type":0,"name":"Beat Saber"}]}}"""
        assertEquals(listOf(730), StoreApi.parseFeatured(json, StoreApi.Section.TopSellers).map { it.appId })
        assertEquals(listOf(730, 620980), StoreApi.parseFeatured(json, StoreApi.Section.All).map { it.appId })
    }
}
