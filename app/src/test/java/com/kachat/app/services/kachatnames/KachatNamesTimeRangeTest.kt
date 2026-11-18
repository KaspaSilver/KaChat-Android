package com.kachat.app.services.kachatnames

import com.kachat.app.services.kachatnames.KachatNames.Codec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Name times come from the chain, a REST server or an indexer: checked before any math, so
 *  nothing wraps (iOS 83806b3d `periodEnd`, `saturatingAdd`, `maxTimeMs`). */
class KachatNamesTimeRangeTest {
    private val year = 31_536_000_000L

    @Test
    fun periodEndAddsWholePeriodsInRange() {
        assertEquals(2_000_000_000_000L + 2 * year, Codec.periodEnd(2_000_000_000_000L, 2, year))
    }

    @Test
    fun periodEndRefusesOverflowAndOutOfRangeTimes() {
        assertThrows(KachatNames.Failure::class.java) { Codec.periodEnd(2_000_000_000_000L, Long.MAX_VALUE, year) }
        assertThrows(KachatNames.Failure::class.java) { Codec.periodEnd(Long.MAX_VALUE - 1, 1, year) }
        assertThrows(KachatNames.Failure::class.java) { Codec.periodEnd(-1, 1, year) }
        assertThrows(KachatNames.Failure::class.java) { Codec.periodEnd(Codec.MAX_TIME_MS, 1, year) }
    }

    @Test
    fun saturatingAddStopsAtTheLimits() {
        assertEquals(5L, Codec.saturatingAdd(2, 3))
        assertEquals(Long.MAX_VALUE, Codec.saturatingAdd(Long.MAX_VALUE - 1, 10))
        assertEquals(Long.MIN_VALUE, Codec.saturatingAdd(Long.MIN_VALUE + 1, -10))
        // a name expiring at the end of time is still in grace, not wrapped round to lapsed
        assertEquals(Status.GRACE, Status.of(Long.MAX_VALUE - 5, 864_000_000L, Long.MAX_VALUE - 1))
    }

    @Test
    fun anIndexerRecordWithAnOutOfRangeTimeIsNotRead() {
        val owner = "aa".repeat(32)
        fun json(expiresAt: Long) = com.google.gson.JsonParser.parseString(
            """{"name":"alice","registered":true,"ownerKey":"$owner","expiresAt":$expiresAt,"outpoint":{"txId":"${"bb".repeat(32)}","index":0}}"""
        )
        val ok = IndexerApi.NameJson.parse(json(2_000_000_000_000L)).info { null }
        assertEquals("alice", ok?.name)
        assertNull(IndexerApi.NameJson.parse(json(Codec.MAX_TIME_MS + 1)).info { null })
    }
}
