package com.kachat.app.models

import com.kachat.app.services.kachatnames.NameInfo
import com.kachat.app.services.kachatnames.Outpoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings > Customization > Show .kachat Endings (iOS 94ed3a35 `KachatNameLabel`). */
class KachatNameLabelTest {

    @After
    fun restoreDefault() {
        KachatNameLabel.showsEnding = true
    }

    @Test
    fun `endings show by default`() {
        assertEquals("alice.kachat", KachatNameLabel.display("alice"))
    }

    @Test
    fun `off shows the name bare`() {
        KachatNameLabel.showsEnding = false
        assertEquals("alice", KachatNameLabel.display("alice"))
    }

    @Test
    fun `a registry name follows the switch`() {
        val info = NameInfo(
            name = "alice",
            key = ByteArray(32),
            owner = ByteArray(32),
            price = 0L,
            expiresAt = 0L,
            outpoint = Outpoint(ByteArray(32), 0),
        )
        assertEquals("alice.kachat", info.display)
        KachatNameLabel.showsEnding = false
        assertEquals("alice", info.display)
    }
}
