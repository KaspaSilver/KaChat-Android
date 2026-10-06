package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class KaspaAddressTest {

    private fun randomAddress(): String {
        val pubKeyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return KaspaAddress.encode("kaspa", 0x00, pubKeyBytes)
    }

    @Test
    fun `addressFromScriptPublicKey inverts getScriptPublicKey for a schnorr address`() {
        val address = randomAddress()
        val scriptHex = KaspaAddress.getScriptPublicKey(address)
        assertEquals(address, KaspaAddress.addressFromScriptPublicKey(scriptHex))
    }

    @Test
    fun `addressFromScriptPublicKey rejects a non-schnorr or malformed script`() {
        assertNull(KaspaAddress.addressFromScriptPublicKey("aa14deadbeefdeadbeefdeadbeefdeadbeefdead87"))
        assertNull(KaspaAddress.addressFromScriptPublicKey("not hex"))
        assertNull(KaspaAddress.addressFromScriptPublicKey(""))
    }

    // MARK: - Network and payload validation (iOS ce20e87, IOS-003 / AND-011). Unit tests never
    // call KaspaNetwork.init, so the running network is mainnet.

    private fun bytes(n: Int) = ByteArray(n) { (it * 7 + 3).toByte() }

    @Test
    fun `an address of the running network is accepted`() {
        val schnorr = KaspaAddress.encode("kaspa", 0x00, bytes(32))
        val ecdsa = KaspaAddress.encode("kaspa", 0x01, bytes(33))
        val p2sh = KaspaAddress.encode("kaspa", 0x08, bytes(32))
        listOf(schnorr, ecdsa, p2sh).forEach {
            assertTrue(it, KaspaAddress.isValid(it))
            assertTrue(it, KaspaAddress.isValidOnActiveNetwork(it))
            assertNull(KaspaAddress.otherNetwork(it))
            assertNull(KaspaAddress.otherNetworkReason(it))
            KaspaAddress.requireActiveNetwork(it)
        }
    }

    @Test
    fun `the other network's address is refused with its reason`() {
        val testnet = KaspaAddress.encode("kaspatest", 0x00, bytes(32))
        assertTrue(KaspaAddress.isValid(testnet))
        assertFalse(KaspaAddress.isValidOnActiveNetwork(testnet))
        assertTrue(KaspaAddress.isValidOnNetwork(testnet, "kaspatest"))
        assertEquals(KaspaNetwork.Type.TESTNET, KaspaAddress.otherNetwork(testnet))
        assertEquals("This is a Testnet address. KaChat is on Mainnet.", KaspaAddress.otherNetworkReason(testnet))
        assertEquals(com.kachat.app.R.string.address_other_network_testnet, KaspaAddress.otherNetworkMessageRes(testnet))
        val e = assertThrows(IllegalArgumentException::class.java) { KaspaAddress.requireActiveNetwork(testnet) }
        assertEquals("This is a Testnet address. KaChat is on Mainnet.", e.message)
        // The same key on mainnet is fine.
        assertTrue(KaspaAddress.isValidOnActiveNetwork(KaspaNetwork.reencode(testnet, "kaspa")))
    }

    @Test
    fun `a scanned testnet code still reads as the other network`() {
        val testnet = KaspaAddress.encode("kaspatest", 0x00, bytes(32))
        val scanned = KaspaAddress.fromScanned("kaspa:$testnet")
        assertEquals(testnet, scanned)
        assertFalse(KaspaAddress.isValidOnActiveNetwork(scanned))
        assertEquals(com.kachat.app.R.string.address_other_network_testnet, KaspaAddress.otherNetworkMessageRes(scanned))
    }

    @Test
    fun `a payload that does not fit its version is refused`() {
        listOf(
            KaspaAddress.encode("kaspa", 0x00, bytes(33)),
            KaspaAddress.encode("kaspa", 0x00, bytes(31)),
            KaspaAddress.encode("kaspa", 0x00, bytes(20)),
            KaspaAddress.encode("kaspa", 0x01, bytes(32)),
            KaspaAddress.encode("kaspa", 0x08, bytes(33)),
            KaspaAddress.encode("kaspa", 0x08, bytes(20)),
            KaspaAddress.encode("kaspa", 0x02, bytes(32)),
            KaspaAddress.encode("kaspa", 0x07, bytes(32)),
        ).forEach {
            assertFalse(it, KaspaAddress.isValid(it))
            assertFalse(it, KaspaAddress.isValidOnActiveNetwork(it))
            assertNull(it, KaspaAddress.otherNetwork(it))
            assertThrows(IllegalArgumentException::class.java) { KaspaAddress.getScriptPublicKey(it) }
        }
        // ...on either network: a malformed testnet address is just invalid, not "the other one".
        val badTestnet = KaspaAddress.encode("kaspatest", 0x00, bytes(33))
        assertFalse(KaspaAddress.isValid(badTestnet))
        assertNull(KaspaAddress.otherNetworkReason(badTestnet))
    }

    @Test
    fun `an unknown prefix or a broken checksum is refused`() {
        assertFalse(KaspaAddress.isValid(KaspaAddress.encode("kaspadev", 0x00, bytes(32))))
        assertFalse(KaspaAddress.isValid(KaspaAddress.encode("bitcoin", 0x00, bytes(32))))
        val good = KaspaAddress.encode("kaspa", 0x00, bytes(32))
        val flipped = good.dropLast(1) + (if (good.last() == 'q') 'p' else 'q')
        assertFalse(KaspaAddress.isValid(flipped))
        assertFalse(KaspaAddress.isValid(good.substringAfter(':')))
        assertFalse(KaspaAddress.isValid(""))
    }

    @Test
    fun `profile links open the same key on the running network`() {
        val mainnet = KaspaAddress.encode("kaspa", 0x00, bytes(32))
        val testnet = KaspaNetwork.reencode(mainnet, "kaspatest")
        assertEquals(mainnet, KaspaAddress.onActiveNetwork(mainnet))
        assertEquals(mainnet, KaspaAddress.onActiveNetwork(testnet))
        assertEquals(mainnet, KaspaAddress.onActiveNetwork(mainnet.substringAfter(':')))
        assertEquals(mainnet, KaspaAddress.onActiveNetwork(testnet.substringAfter(':')))
        assertEquals(mainnet, KaspaAddress.onActiveNetwork(" ${mainnet.uppercase()} "))
        assertNull(KaspaAddress.onActiveNetwork("kaspa:notanaddress"))
        assertNull(KaspaAddress.onActiveNetwork(KaspaAddress.encode("kaspa", 0x00, bytes(33))))
    }
}
