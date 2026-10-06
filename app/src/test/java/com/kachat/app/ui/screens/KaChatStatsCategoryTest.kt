package com.kachat.app.ui.screens

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** KaChat Stats' .kachat names categories (iOS b2d108b). */
class KaChatStatsCategoryTest {

    private fun json(text: String): JsonObject = Gson().fromJson(text, JsonObject::class.java)

    @Test
    fun `the five kachat keys are the indexer's stats keys`() {
        val keys = KaChatStatCategory.entries.map { it.key }
        assertEquals(
            listOf(
                "messages", "handshakes", "payments", "groupMessages", "groupUpdates", "publicChats",
                "kaposts", "kapostActions", "chessMoves", "chessGames",
                "kachatRegistrations", "kachatRenewals", "kachatSales", "kachatOffers", "kachatActivity",
                "selfStash",
            ),
            keys,
        )
    }

    @Test
    fun `kachat categories map from the stats response`() {
        val counts = mergeKaChatStatCategories(
            listOf(
                json(
                    """{"categories":{
                    "messages":{"total":100,"last24h":5,"last7d":20},
                    "kachatRegistrations":{"total":7,"last24h":1,"last7d":3},
                    "kachatRenewals":{"total":2},
                    "kachatSales":{"total":3,"last24h":0,"last7d":1},
                    "kachatOffers":{"total":4},
                    "kachatActivity":{"total":9,"last7d":2},
                    "someFutureKey":{"total":1000}
                }}"""
                )
            )
        )
        assertEquals(KaChatStatCounts(7, 1, 3), counts[KaChatStatCategory.KACHAT_REGISTRATIONS])
        assertEquals(KaChatStatCounts(2, null, null), counts[KaChatStatCategory.KACHAT_RENEWALS])
        assertEquals(KaChatStatCounts(3, 0, 1), counts[KaChatStatCategory.KACHAT_SALES])
        assertEquals(KaChatStatCounts(4, null, null), counts[KaChatStatCategory.KACHAT_OFFERS])
        assertEquals(KaChatStatCounts(9, null, 2), counts[KaChatStatCategory.KACHAT_ACTIVITY])
        assertEquals(6, counts.size)
    }

    @Test
    fun `kachat categories appear only once an indexer reports them`() {
        val counts = mergeKaChatStatCategories(listOf(json("""{"categories":{"messages":{"total":10},"selfStash":{"total":1}}}""")))
        val rows = kaChatStatRows(counts, KaChatStatRange.ALL)
        assertEquals(listOf(KaChatStatCategory.MESSAGES, KaChatStatCategory.SELF_STASH), rows.map { it.first })
        assertTrue(rows.none { it.first.key.startsWith("kachat") })
    }

    @Test
    fun `kachat categories count toward the total, in display order before saved records`() {
        val counts = mergeKaChatStatCategories(
            listOf(
                json(
                    """{"categories":{
                    "selfStash":{"total":1,"last24h":1},
                    "kachatSales":{"total":3},
                    "messages":{"total":100,"last24h":5},
                    "kachatRegistrations":{"total":7,"last24h":2}
                }}"""
                )
            )
        )
        val all = kaChatStatRows(counts, KaChatStatRange.ALL)
        assertEquals(
            listOf(
                KaChatStatCategory.MESSAGES,
                KaChatStatCategory.KACHAT_REGISTRATIONS,
                KaChatStatCategory.KACHAT_SALES,
                KaChatStatCategory.SELF_STASH,
            ),
            all.map { it.first },
        )
        assertEquals(111L, kaChatStatsTotal(all))
        // A category with no number for the range is left out of that range's total.
        assertEquals(8L, kaChatStatsTotal(kaChatStatRows(counts, KaChatStatRange.DAY)))
        assertNull(kaChatStatsTotal(kaChatStatRows(counts, KaChatStatRange.WEEK)))
    }

    @Test
    fun `the first indexer to report a kachat category wins`() {
        val counts = mergeKaChatStatCategories(
            listOf(
                json("""{"categories":{"kachatOffers":{"total":4}}}"""),
                json("""{"categories":{"kachatOffers":{"total":40},"kachatActivity":{"total":5}}}"""),
            )
        )
        assertEquals(4L, counts[KaChatStatCategory.KACHAT_OFFERS]?.total)
        assertEquals(5L, counts[KaChatStatCategory.KACHAT_ACTIVITY]?.total)
    }
}
