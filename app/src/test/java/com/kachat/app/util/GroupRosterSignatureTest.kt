package com.kachat.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class GroupRosterSignatureTest {
    private val adminPrivateKey = run {
        var key: ByteArray
        do { key = ByteArray(32).also { SecureRandom().nextBytes(it) } } while (runCatching { Schnorr.publicKeyXOnly(key) }.isFailure)
        key
    }
    private val adminPub = Schnorr.publicKeyXOnly(adminPrivateKey)
    private val members = listOf("kaspa:qqb", "KASPA:QQA", "kaspa:qqé")

    private fun root(version: Long = 1_793_900_000_000L) = GroupCipher.buildSignedRootPayload(
        groupId = ByteArray(32) { 7 }, epoch = 3, groupRootEpoch = ByteArray(32) { 1 }, blindingKey = ByteArray(32) { 2 },
        adminSigningPub = adminPub, members = members, name = "Group Teat", rosterVersion = version, adminPrivateKey = adminPrivateKey,
    )

    private fun le(value: Long) = ByteArray(8) { (value shr (it * 8)).toByte() }

    @Test
    fun `the signed bytes follow the spec`() {
        val group = byteArrayOf(9, 8)
        val expected = byteArrayOf(1) + "gctl_roster".toByteArray() + group + le(3) + le(5) + le(2) +
            le(9) + "kaspa:qqa".toByteArray() + le(10) + "kaspa:qqé".toByteArray() +
            le(2) + "Hi".toByteArray()
        assertArrayEquals(expected, GroupCipher.buildRosterSigningPayload(group, 3, 5, listOf("KASPA:QQé", "kaspa:qqa"), "Hi"))
    }

    @Test
    fun `members sort by unsigned bytes, whatever order they arrive in`() {
        val group = byteArrayOf(1)
        val high = GroupCipher.buildRosterSigningPayload(group, 0, 0, listOf("é", "z"), "")
        assertArrayEquals(GroupCipher.buildRosterSigningPayload(group, 0, 0, listOf("z", "é"), ""), high)
        // "z" is 0x7A, and "é" starts 0xC3, which is above it only when read as unsigned.
        val zFirst = byteArrayOf(1) + "gctl_roster".toByteArray() + group + le(0) + le(0) + le(2) +
            le(1) + "z".toByteArray() + le(2) + "é".toByteArray() + le(0)
        assertArrayEquals(zFirst, high)
    }

    @Test
    fun `a built root verifies, on both signatures`() {
        assertTrue(GroupCipher.verifyRootPayload(root()))
        assertTrue(GroupCipher.verifyRoster(root()))
    }

    @Test
    fun `the roster order doesn't matter to the signature`() {
        assertTrue(GroupCipher.verifyRoster(root().copy(members = members.reversed())))
    }

    @Test
    fun `an edited roster, name or version fails while the root's own sig still passes`() {
        val editedMembers = root().copy(members = members + "kaspa:qqz")
        val editedName = root().copy(name = "Renamed")
        val editedVersion = root().copy(rosterVersion = 1_793_900_000_001L)
        for (edited in listOf(editedMembers, editedName, editedVersion)) {
            assertTrue(GroupCipher.verifyRootPayload(edited))
            assertFalse(GroupCipher.verifyRoster(edited))
        }
    }

    @Test
    fun `a root with only one of the two fields fails`() {
        assertFalse(GroupCipher.verifyRoster(root().copy(rosterSig = null)))
        assertFalse(GroupCipher.verifyRoster(root().copy(rosterVersion = null)))
    }

    @Test
    fun `the fields go over the wire under their spec names and come back`() {
        val json = GroupCipher.rootPayloadToJson(root(5))
        assertTrue(json.contains("\"roster_version\":5"))
        assertTrue(json.contains("\"roster_sig\":\""))
        assertEquals(5L, GroupCipher.rootPayloadFromJson(json)?.rosterVersion)
        assertTrue(GroupCipher.verifyRoster(GroupCipher.rootPayloadFromJson(json)!!))
    }

    @Test
    fun `an older client's root reads with no roster fields`() {
        val json = GroupCipher.rootPayloadToJson(root()).replace(Regex(",?\"roster_(version|sig)\":(\"[^\"]*\"|\\d+)"), "")
        val parsed = GroupCipher.rootPayloadFromJson(json)!!
        assertEquals(null, parsed.rosterVersion)
        assertEquals(null, parsed.rosterSig)
        assertTrue(GroupCipher.verifyRootPayload(parsed))
    }

    @Test
    fun `a key of the wrong length is a refusal`() {
        assertFalse(GroupCipher.verify(ByteArray(64), ByteArray(32), ByteArray(31)))
    }
}
