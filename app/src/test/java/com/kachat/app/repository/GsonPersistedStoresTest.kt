package com.kachat.app.repository

import com.kachat.app.util.KaspaNetwork
import com.kachat.app.util.PersistedJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The two Gson stores audit AND-001 found unkept by R8: each round-trips, its known-field list
 * matches the class (so a new field cannot be mistaken for a renamed one), and a record a
 * minified build wrote with renamed fields is refused rather than misread.
 */
class GsonPersistedStoresTest {

    private fun instanceFieldNames(type: Class<*>): Set<String> =
        type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }.toSet()

    // ConnectionProfile

    @Test
    fun `connection profile round-trips`() {
        val custom = ConnectionProfile(
            indexerUrl = "https://indexer.example",
            kapostIndexerUrl = "",
            broadcastIndexerUrl = null,
            pushIndexerUrl = "https://push.example",
            translationServiceUrl = "https://tr.example",
            kaspaRestUrl = "https://rest.example",
            trustedNodeAddress = "node.example:16110",
            savedNodeAddresses = "[]",
        )
        assertEquals(custom, ConnectionProfile.decode(ConnectionProfile.encode(custom)))
        val testnet = ConnectionProfile.defaults(KaspaNetwork.Type.TESTNET)
        assertEquals(testnet, ConnectionProfile.decode(ConnectionProfile.encode(testnet)))
    }

    @Test
    fun `connection profile field list matches the class`() {
        assertEquals(instanceFieldNames(ConnectionProfile::class.java), ConnectionProfile.FIELDS)
    }

    @Test
    fun `connection profile written with renamed fields is refused`() {
        val obfuscated = """{"a":"https://indexer.example","b":"","e":"https://tr.example"}"""
        assertTrue(ConnectionProfile.isForeignRecord(obfuscated))
        assertNull(ConnectionProfile.decode(obfuscated))
    }

    @Test
    fun `connection profile with every field null reads as the empty profile`() {
        assertFalse(ConnectionProfile.isForeignRecord("{}"))
        assertEquals(ConnectionProfile(), ConnectionProfile.decode("{}"))
    }

    // ChatRequestStore

    @Test
    fun `message requests state round-trips`() {
        val state = ChatRequestState(
            accepted = setOf("kaspa:accepted1", "kaspa:accepted2"),
            privateChats = setOf("kaspa:private"),
            blocked = setOf("kaspa:blocked"),
            inboxTagged = setOf("kaspa:tagged"),
            inboxCursor = 1_234_567L,
            startedAt = 1_700_000_000_000L,
        )
        assertEquals(state, ChatRequestStore.decode(ChatRequestStore.encode(state)))
    }

    @Test
    fun `message requests field list matches the stored class`() {
        val stored = Class.forName("com.kachat.app.repository.ChatRequestStore\$Stored")
        assertEquals(instanceFieldNames(stored), ChatRequestStore.STORED_FIELDS)
    }

    @Test
    fun `message requests written with renamed fields is refused, not misfiled`() {
        // The blocked list under an obfuscated key must never come back as the accepted one.
        val obfuscated = """{"a":["kaspa:x"],"b":[],"c":["kaspa:blocked"],"d":[],"e":5,"f":1700000000000}"""
        assertTrue(ChatRequestStore.isForeignRecord(obfuscated))
        assertNull(ChatRequestStore.decode(obfuscated))
    }

    @Test
    fun `message requests saved before a field existed still reads`() {
        val older = """{"accepted":["kaspa:a"],"blocked":["kaspa:b"],"startedAt":42}"""
        assertFalse(ChatRequestStore.isForeignRecord(older))
        val state = ChatRequestStore.decode(older)!!
        assertEquals(setOf("kaspa:a"), state.accepted)
        assertEquals(setOf("kaspa:b"), state.blocked)
        assertEquals(emptySet<String>(), state.privateChats)
        assertEquals(0L, state.inboxCursor)
        assertEquals(42L, state.startedAt)
    }

    // PersistedJson

    @Test
    fun `only an object with an unknown key counts as foreign`() {
        val known = setOf("x", "y")
        assertFalse(PersistedJson.hasForeignFields("""{"x":1}""", known))
        assertTrue(PersistedJson.hasForeignFields("""{"x":1,"a":2}""", known))
        assertFalse(PersistedJson.hasForeignFields("[1,2]", known))
        assertFalse(PersistedJson.hasForeignFields("not json {", known))
    }
}
