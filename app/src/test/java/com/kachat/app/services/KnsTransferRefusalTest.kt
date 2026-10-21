package com.kachat.app.services

import com.kachat.app.R
import com.kachat.app.util.KaspaAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** iOS 2a8ca5a: KNS cannot move a domain out of a quantum-safe (KIP-22) address, and a domain
 *  sent to any script (P2SH) address would be stuck there for good. */
class KnsTransferRefusalTest {

    private val regular = KaspaAddress.encode("kaspa", 0x00, ByteArray(32) { 1 })
    private val script = KaspaAddress.encode("kaspa", 0x08, ByteArray(32) { 2 })

    @Test
    fun `a transfer from a quantum-safe source is refused`() {
        assertEquals(
            R.string.kns_transfer_quantum_source,
            WalletService.knsTransferRefusalRes(WalletManager.QUANTUM_INDEX_BASE + 3, regular)
        )
    }

    @Test
    fun `a transfer to a script address is refused, from any source`() {
        assertEquals(R.string.kns_transfer_to_script_address, WalletService.knsTransferRefusalRes(null, script))
        assertEquals(R.string.kns_transfer_to_script_address, WalletService.knsTransferRefusalRes(4, script))
    }

    @Test
    fun `a regular transfer goes ahead`() {
        assertNull(WalletService.knsTransferRefusalRes(null, regular))
        assertNull(WalletService.knsTransferRefusalRes(7, regular))
    }
}
