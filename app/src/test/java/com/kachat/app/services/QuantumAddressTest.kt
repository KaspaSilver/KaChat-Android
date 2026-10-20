package com.kachat.app.services

import com.kachat.app.services.kachatnames.KachatNames
import com.kachat.app.services.kachatnames.KachatNamesService
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.KaspaTransactionSigner
import com.kachat.app.util.KaspaUtxoSelector
import com.kachat.app.util.KnsInscriptionScript
import com.kachat.app.util.Schnorr
import com.kachat.app.util.SendFeeModel
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDKeyDerivation
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quantum-safe spending addresses (KIP-22, iOS bba1b1e): the derivation, the P2SH script it
 * pays to, how a spend signs, and what it costs.
 *
 * The address vectors were computed by an independent pure-Python implementation (BIP39 PBKDF2,
 * BIP32 with its own secp256k1 arithmetic, blake2b-256, Kaspa's cashaddr checksum), checked first
 * against the published BIP39 "TREZOR" and BIP32 test-vector-1 values. It reproduces the legacy
 * spending addresses below - which this app has always derived - before its P2SH output is
 * trusted.
 */
class QuantumAddressTest {

    // Public all-zero-entropy 24-word BIP39 test mnemonic - not a real wallet.
    private val words = (List(23) { "abandon" } + "art")
    private val seed = WalletManager.seedFor(words, "")

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun blake2b256(data: ByteArray): ByteArray {
        val digest = Blake2bDigest(null, 32, null, null)
        digest.update(data, 0, data.size)
        return ByteArray(32).also { digest.doFinal(it, 0) }
    }

    @Test
    fun `the seed is the BIP39 one the Python reference computed`() {
        assertEquals(
            "408b285c123836004f4b8842c89324c1f01382450c0d439af345ba7fc49acf705489c6fc77dbd4e3dc1dd8cc6bc9f043db8ada1e243c4a0eafb290d399480840",
            hex(seed)
        )
    }

    @Test
    fun `legacy spending addresses match the Python reference (proves the reference)`() {
        val chain = WalletManager.spendingChainNode(seed)
        val expected = listOf(
            "kaspa:qppt49jerlp2vjquvhfjcpzkna7g26jzu2r88wwsnt3zndq4qvnnywxtsehh8",
            "kaspa:qphqs4c490zy80q6rjlnetvyv3hclg8zz6h8l774rsprdprryz88xk3qp39k7",
            "kaspa:qzqmrwjk6qyvd267q034qlhrhpdjdvtedl3uw298aha0hk2sl0kv62y06lahf",
        )
        expected.forEachIndexed { i, address ->
            assertEquals(address, WalletManager.addressFromKey(HDKeyDerivation.deriveChildKey(chain, ChildNumber(i, false))))
        }
    }

    @Test
    fun `quantum addresses at m-22-111111-0-0-n match the Python reference`() {
        val chain = WalletManager.quantumChainNode(seed)
        val expected = listOf(
            "kaspa:pp4csja82vqaa9hstf6ned49w8j2tl79jmwjk5v79999qxsrfkj9zvq7y9wme",
            "kaspa:pzc4ja4jywkqelzxeqn42z6mfqcf0aacgudadtszz52xpc9vr5wkxuyd47277",
            "kaspa:prm5kldes5ynvrmm5rl59ss78hdfmu74l8yxq8cqf0d33hfjcvvm7fr6m2aas",
        )
        val redeems = listOf(
            "2008ddc68dcaad0b901868b764afe822a54e09e930064b21cc0846b4d08620136eac",
            "203bc3d2f941e0e3f87bdff4ac632974ed3378ccebf0cd2821ed7496334bccbdf8ac",
            "20979a19859d7ce76ec130ea57c417fcefc88694434fa50ee5de9e5620df5e2d82ac",
        )
        expected.forEachIndexed { i, address ->
            assertEquals(address, WalletManager.quantumAddress(chain, i))
            val key = HDKeyDerivation.deriveChildKey(chain, ChildNumber(i, false))
            assertEquals(redeems[i], hex(WalletManager.quantumRedeemScript(key)))
            // the KNS commit-address helper agrees with the registry codec path
            assertEquals(address, KnsInscriptionScript.commitAddress(WalletManager.quantumRedeemScript(key), "kaspa"))
        }
        // the legacy and quantum chains never share an address
        val legacy = WalletManager.addressFromKey(HDKeyDerivation.deriveChildKey(WalletManager.spendingChainNode(seed), ChildNumber(0, false)))
        assertFalse(legacy == expected[0])
    }

    @Test
    fun `public index space keeps the two chains apart`() {
        assertFalse(WalletManager.isQuantumIndex(0))
        assertFalse(WalletManager.isQuantumIndex(999_999))
        assertTrue(WalletManager.isQuantumIndex(1_000_000))
        assertEquals("Address #3", WalletManager.spendingAddressTitle(3))
        assertEquals("Address #0", WalletManager.spendingAddressTitle(1_000_000))
        // a quantum primary never widens the legacy chain
        val account = WalletManager.Account(
            name = "t", address = "kaspa:x", mnemonic = "m",
            spendingAddressIndex = 1_000_004, maxSpendingAddressIndex = 7, maxQuantumAddressIndex = 4
        )
        assertEquals(7, WalletManager.legacyMaxSpendingIndex(account))
        assertEquals(4, WalletManager.maxQuantumIndex(account))
        assertEquals(9, WalletManager.legacyMaxSpendingIndex(account.copy(spendingAddressIndex = 9)))
        // a record from before the field reads as "no quantum slot yet"
        val old = com.google.gson.Gson().fromJson("""{"name":"a","address":"kaspa:x","mnemonic":"m"}""", WalletManager.Account::class.java)
        assertEquals(-1, WalletManager.maxQuantumIndex(old))
    }

    @Test
    fun `address to scriptPublicKey round-trips to the P2SH of the redeem script`() {
        val chain = WalletManager.quantumChainNode(seed)
        val key = HDKeyDerivation.deriveChildKey(chain, ChildNumber(0, false))
        val redeem = WalletManager.quantumRedeemScript(key)
        val address = WalletManager.quantumAddress(chain, 0)

        val (version, payload) = KaspaAddress.decode(address)
        assertEquals(8, version.toInt())
        assertArrayEquals(blake2b256(redeem), payload)

        // OP_BLAKE2B 0x20 <hash> OP_EQUAL
        val spk = KaspaAddress.getScriptPublicKey(address)
        assertEquals("aa20" + hex(blake2b256(redeem)) + "87", spk)
        assertEquals(hex(KachatNames.Codec.p2shScript(redeem)), spk)
        assertEquals(address, KachatNamesService.p2shAddress(KachatNames.Codec.p2shScript(redeem)))
    }

    /** A two-input spend of quantum UTXOs, built the way KaspaWalletEngine builds one. */
    private fun quantumSpend(): Triple<RawTransaction, List<UtxoEntry>, ByteArray> {
        val chain = WalletManager.quantumChainNode(seed)
        val key = HDKeyDerivation.deriveChildKey(chain, ChildNumber(1, false))
        val address = WalletManager.quantumAddress(chain, 1)
        val spk = KaspaAddress.getScriptPublicKey(address)
        val utxos = listOf(150_000_000L, 70_000_000L).mapIndexed { i, amount ->
            UtxoEntry(
                address = address,
                outpoint = Outpoint(transactionId = "%064x".format(i + 0xabc), index = i),
                utxoEntry = UtxoData(amount = amount, scriptPublicKey = ScriptPublicKey(spk), blockDaaScore = 1L, isCoinbase = false)
            )
        }
        val change = WalletManager.quantumAddress(chain, 2)
        val tx = RawTransaction(
            inputs = utxos.map { RawInput(previousOutpoint = it.outpoint, signatureScript = "") },
            outputs = listOf(
                RawOutputWithVersion(200_000_000L, ScriptPublicKeyWithVersion(KaspaAddress.getScriptPublicKey("kaspa:qppt49jerlp2vjquvhfjcpzkna7g26jzu2r88wwsnt3zndq4qvnnywxtsehh8"), 0)),
                RawOutputWithVersion(19_990_000L, ScriptPublicKeyWithVersion(KaspaAddress.getScriptPublicKey(change), 0)),
            ),
            gas = 0,
            payload = null
        )
        return Triple(tx, utxos, key.privKeyBytes)
    }

    @Test
    fun `a quantum spend signs over the P2SH script and pushes the redeem script after the signature`() {
        val (tx, utxos, privateKey) = quantumSpend()
        val redeem = WalletManager.quantumRedeemScript(HDKeyDerivation.deriveChildKey(WalletManager.quantumChainNode(seed), ChildNumber(1, false)))
        val xOnly = Schnorr.publicKeyXOnly(privateKey)
        assertArrayEquals(xOnly, redeem.copyOfRange(1, 33))

        val signed = KaspaTransactionSigner.signTransaction(tx, utxos, privateKey, redeemScript = redeem)
        signed.inputs.forEachIndexed { i, input ->
            val script = input.signatureScript.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            // push 65 (sig + SIGHASH_ALL), then the canonical push of the 34-byte redeem script
            assertEquals(KaspaMass.KIP22_SIGNATURE_SCRIPT_LEN, script.size)
            assertEquals(0x41, script[0].toInt() and 0xff)
            assertEquals(0x01, script[65].toInt() and 0xff)
            assertEquals(0x22, script[66].toInt() and 0xff)
            assertArrayEquals(redeem, script.copyOfRange(67, 101))

            // the sighash hashes the UTXO's P2SH scriptPublicKey (OP_BLAKE2B <hash> OP_EQUAL)
            val p2sh = utxos[i].utxoEntry.scriptPublicKey.scriptPublicKey
            assertTrue(p2sh.startsWith("aa20") && p2sh.endsWith("87"))
            val sighash = KaspaTransactionSigner.calculateSighash(tx, i, utxos[i].utxoEntry.amount, p2sh)
            val signature = script.copyOfRange(1, 65)
            assertTrue(Schnorr.verify(sighash, signature, xOnly))
            // ...and not the redeem script
            val wrong = KaspaTransactionSigner.calculateSighash(tx, i, utxos[i].utxoEntry.amount, hex(redeem))
            assertFalse(Schnorr.verify(wrong, signature, xOnly))
        }

        // a plain P2PK spend is unchanged: 66 bytes, nothing after SIGHASH_ALL
        val plain = KaspaTransactionSigner.signTransaction(tx, utxos, privateKey)
        plain.inputs.forEach { assertEquals(132, it.signatureScript.length) }
    }

    @Test
    fun `a quantum source pays exactly the extra signature-script bytes per input`() {
        val extraPerInput = KaspaMass.KIP22_SIGNATURE_SCRIPT_LEN - KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN
        assertEquals(35, extraPerInput)
        // the engine's size for a redeem script is the constant every estimator uses
        assertEquals(
            KaspaMass.KIP22_SIGNATURE_SCRIPT_LEN,
            KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN + KnsInscriptionScript.canonicalPushSize(34)
        )
        assertEquals(KaspaMass.KIP22_SIGNATURE_SCRIPT_LEN, WalletManager.signatureScriptSize(1_000_000))
        assertEquals(KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN, WalletManager.signatureScriptSize(5))
        assertEquals(KaspaMass.SCHNORR_SIGNATURE_SCRIPT_LEN, WalletManager.signatureScriptSize(null))

        val rate = 100L
        for (inputs in 1..5) {
            val legacyMass = KaspaMass.calculateMass(inputs, listOf(34, 35), 0)
            val quantumMass = KaspaMass.calculateMass(inputs, listOf(34, 35), 0, sigScriptLens = KaspaMass.sigScriptLensFor(inputs, KaspaMass.KIP22_SIGNATURE_SCRIPT_LEN))
            assertEquals(extraPerInput.toLong() * inputs, quantumMass - legacyMass)
            assertEquals(
                extraPerInput.toLong() * inputs * rate,
                SendFeeModel.baseFee(inputs, rate, signatureScriptSize = KaspaMass.KIP22_SIGNATURE_SCRIPT_LEN) - SendFeeModel.baseFee(inputs, rate)
            )
        }

        // the selector the real send runs: same coins picked, fee larger by exactly that mass
        val (_, utxos, _) = quantumSpend()
        val legacy = KaspaUtxoSelector.selectUtxosAndCalculateFee(utxos, 200_000_000L, rate, null, 34, 35)
        val quantum = KaspaUtxoSelector.selectUtxosAndCalculateFee(
            utxos, 200_000_000L, rate, null, 34, 35, signatureScriptSize = KaspaMass.KIP22_SIGNATURE_SCRIPT_LEN
        )
        assertEquals(2, legacy.selectedUtxos.size)
        assertEquals(legacy.selectedUtxos.size, quantum.selectedUtxos.size)
        assertEquals(extraPerInput.toLong() * quantum.selectedUtxos.size * rate, quantum.estimatedFee - legacy.estimatedFee)
        val manualLegacy = KaspaUtxoSelector.selectManualUtxosAndCalculateFee(utxos, 200_000_000L, rate, 34, 35)
        val manualQuantum = KaspaUtxoSelector.selectManualUtxosAndCalculateFee(utxos, 200_000_000L, rate, 34, 35, signatureScriptSize = KaspaMass.KIP22_SIGNATURE_SCRIPT_LEN)
        assertEquals(extraPerInput.toLong() * 2 * rate, manualQuantum.estimatedFee - manualLegacy.estimatedFee)
    }

    @Test
    fun `a fresh change slot is past the revealed bound and every slot still in flight`() {
        val base = WalletManager.QUANTUM_INDEX_BASE
        // nothing revealed, nothing in flight: quantum #0
        assertEquals(base, WalletManager.nextFreshQuantumIndex(-1, emptySet()))
        assertEquals(base + 4, WalletManager.nextFreshQuantumIndex(3, emptySet()))
        // a send in flight holds its pick: the next one goes past it
        assertEquals(base + 5, WalletManager.nextFreshQuantumIndex(3, setOf(base + 4)))
        assertEquals(base + 7, WalletManager.nextFreshQuantumIndex(3, setOf(base + 4, base + 6)))
        // a pick already covered by the bound (revealed meanwhile) changes nothing
        assertEquals(base + 9, WalletManager.nextFreshQuantumIndex(8, setOf(base + 4)))
    }
}
