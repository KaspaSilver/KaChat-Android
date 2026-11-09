package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Address Book merge's date rules (iOS d23e6f7a `AddressBookManager.merge`). */
class AddressBookMergeTest {

    private val now = 1_800_000_000_000L
    private val address = "kaspa:qqaddressbookmergetest"

    private fun entry(updatedAt: Long, name: String = "Alice") =
        AddressBookEntry(address = address, name = name, createdAt = 0L, updatedAt = updatedAt)

    @Test
    fun `an entry dated in the future is capped at now`() {
        val (kept, tombs) = AddressBookManager.merge(listOf(listOf(entry(now + 10_000_000))), emptyList(), now)
        assertEquals(now, kept.single().updatedAt)
        assertTrue(tombs.isEmpty())
    }

    @Test
    fun `a future-dated entry no longer beats a later edit`() {
        // Capped at now, the 2099 copy ties with an edit made now; the first side seen wins a tie.
        val (kept, _) = AddressBookManager.merge(
            listOf(listOf(entry(now, name = "Edited")), listOf(entry(4_070_908_800_000L, name = "Future"))),
            emptyList(),
            now,
        )
        assertEquals("Edited", kept.single().name)
    }

    @Test
    fun `a tombstone dated in the future is capped at now`() {
        // A deletion at or after an edit wins: capped at now, it ties with an edit made now.
        val (keptTie, tombsTie) = AddressBookManager.merge(
            listOf(listOf(entry(now))),
            listOf(listOf(AddressBookTombstone(address, now + 60_000))),
            now,
        )
        assertTrue(keptTie.isEmpty())
        assertEquals(now, tombsTie.single().deletedAt)
    }

    @Test
    fun `dates in the past are left as they are`() {
        val (kept, tombs) = AddressBookManager.merge(
            listOf(listOf(entry(now - 5_000))),
            listOf(listOf(AddressBookTombstone("kaspa:qqsomeoneelse", now - 9_000))),
            now,
        )
        assertEquals(now - 5_000, kept.single().updatedAt)
        assertEquals(now - 9_000, tombs.single().deletedAt)
    }

    @Test
    fun `an import over a deletion is dated in the next whole second after it`() {
        assertEquals(1_000L, AddressBookManager.afterTombstone(0L))
        assertEquals(1_000L, AddressBookManager.afterTombstone(999L))
        assertEquals(2_000L, AddressBookManager.afterTombstone(1_000L))
        assertEquals(1_700_000_001_000L, AddressBookManager.afterTombstone(1_700_000_000_456L))
    }

    @Test
    fun `an entry dated after its tombstone survives a merge with the copy holding the deletion`() {
        val tombstone = 1_700_000_000_456L
        // what the file round trip keeps: whole seconds
        val restored = entry(AddressBookManager.afterTombstone(tombstone))
        val (kept, tombs) = AddressBookManager.merge(
            listOf(listOf(restored)),
            listOf(listOf(AddressBookTombstone(address, tombstone / 1000 * 1000))),
            now,
        )
        assertEquals(1, kept.size)
        assertTrue(tombs.isEmpty())
    }
}
