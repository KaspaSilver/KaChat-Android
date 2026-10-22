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
        // nor does a stored legacy max that somehow landed in quantum space (iOS 28ecee0)
        assertEquals(0, WalletManager.legacyMaxSpendingIndex(account.copy(maxSpendingAddressIndex = 1_000_002)))
        assertEquals(3, WalletManager.legacyMaxSpendingIndex(account.copy(spendingAddressIndex = 3, maxSpendingAddressIndex = 1_000_002)))
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

    // MARK: run 17 (iOS da2c8e2) - IOS-101 / AND-049 restore sweep + checked fresh slots,
    // IOS-105 / AND-051 the shield reads the full history

    @Test
    fun `a used fresh slot is skipped and the bound raised, up to 20 times`() = kotlinx.coroutines.runBlocking {
        val base = WalletManager.QUANTUM_INDEX_BASE
        var max = 3
        val inFlight = setOf(base + 4)
        val next = { WalletManager.nextFreshQuantumIndex(max, inFlight) }
        val skip = { index: Int -> max = maxOf(max, index - base); true }
        // slots 5 and 6 already have history (used elsewhere): 7 is handed out, past the send in flight
        val used = setOf("a${base + 5}", "a${base + 6}")
        assertEquals(base + 7, WalletManager.firstFreshQuantumSlot(next, { "a$it" }, { it in used }, skip))
        assertEquals(6, max)
        // an unanswerable check takes the slot as is - a REST outage never blocks a send
        assertEquals(base + 7, WalletManager.firstFreshQuantumSlot(next, { "a$it" }, { null }, skip))
        // so does a slot that can't be derived
        assertEquals(base + 7, WalletManager.firstFreshQuantumSlot(next, { null }, { true }, skip))
        // everything used: 20 skips, then the next slot unchecked
        var asked = 0
        val result = WalletManager.firstFreshQuantumSlot(next, { "a$it" }, { asked++; true }, skip)
        assertEquals(WalletManager.MAX_USED_FRESH_QUANTUM_SKIPS, asked)
        assertEquals(base + 7 + WalletManager.MAX_USED_FRESH_QUANTUM_SKIPS, result)
        assertEquals(6 + WalletManager.MAX_USED_FRESH_QUANTUM_SKIPS, max)
    }

    /** Two saved accounts' quantum bounds, which one is active, and the fresh-slot probe for
     *  [owner] wired the way [WalletManager.verifyFreshQuantumIndex] wires it: the next slot
     *  and every skip read and write [owner]'s bound only, and a skip writes nothing once
     *  [owner] has been removed (IOS-109 / AND-056). */
    private class ProbeStore {
        val saved = mutableSetOf(OWNER, OTHER)
        var active = OWNER
        val bound = mutableMapOf(OWNER to 3, OTHER to 9)

        suspend fun probe(owner: String, isUsed: suspend (String) -> Boolean?): Int {
            val base = WalletManager.QUANTUM_INDEX_BASE
            return WalletManager.firstFreshQuantumSlot(
                next = { WalletManager.nextFreshQuantumIndex(if (owner in saved) bound.getValue(owner) else -1, emptySet()) },
                // the owner's own chain: its name in the address, whatever account is active
                address = { index -> "$owner/${index - base}" },
                isUsed = isUsed,
                skip = { index ->
                    if (owner !in saved) false
                    else { bound[owner] = maxOf(bound.getValue(owner), index - base); true }
                },
            )
        }

        /** Each account's in-flight change picks (public index). */
        val inFlight = mutableMapOf(OWNER to mutableSetOf<Int>(), OTHER to mutableSetOf())

        /** A change pick / reservation on [owner], wired the way
         *  [WalletManager.pickFreshQuantumChange] / [WalletManager.allocateFreshQuantumIndices]
         *  wire it (iOS fb2323e): [owner]'s bound and in-flight picks, [owner]'s chain for the
         *  address, whichever account is active; nothing taken or written once [owner] is gone. */
        fun take(owner: String, count: Int, reserve: Boolean): List<Pair<Int, String>> {
            val base = WalletManager.QUANTUM_INDEX_BASE
            val revealedMax = if (owner in saved) bound.getValue(owner) else null
            val indices = WalletManager.freshQuantumAllocation(revealedMax, inFlight.getValue(owner), count) ?: return emptyList()
            if (reserve) bound[owner] = maxOf(bound.getValue(owner), indices.last() - base)
            else inFlight.getValue(owner) += indices
            return indices.map { it to "$owner/${it - base}" }
        }
    }

    @Test
    fun `an allocation takes its own account's slots in a row past its bound and in-flight picks`() {
        val base = WalletManager.QUANTUM_INDEX_BASE
        assertEquals(listOf(base + 4), WalletManager.freshQuantumAllocation(3, emptySet(), 1))
        assertEquals(listOf(base + 6, base + 7), WalletManager.freshQuantumAllocation(3, setOf(base + 5), 2))
        // removed account: nothing taken
        assertEquals(null, WalletManager.freshQuantumAllocation(null, emptySet(), 1))
        assertEquals(null, WalletManager.freshQuantumAllocation(3, emptySet(), 0))
    }

    @Test
    fun `a switch between probe and change pick never yields the other account's slot`() = kotlinx.coroutines.runBlocking {
        // iOS fb2323e (IOS-109 / AND-056 follow-up): A's slot 4 is used elsewhere and the user
        // switches to B while it is checked. The change pick still comes from A - the slot past
        // A's raised bound, on A's chain, held in flight on A - never B's unprobed slot 10
        val base = WalletManager.QUANTUM_INDEX_BASE
        val store = ProbeStore()
        val probed = store.probe(OWNER) { address ->
            store.active = OTHER
            address == "$OWNER/4"
        }
        val pick = store.take(OWNER, 1, reserve = false).single()
        assertEquals(OTHER, store.active)
        assertEquals(probed, pick.first)
        assertEquals(base + 5 to "$OWNER/5", pick)
        assertEquals(setOf(base + 5), store.inFlight[OWNER])
        assertTrue(store.inFlight.getValue(OTHER).isEmpty())
        assertEquals(9, store.bound[OTHER])
        // a second send on A while the first is in flight gets the next one, still A's
        assertEquals(base + 6 to "$OWNER/6", store.take(OWNER, 1, reserve = false).single())
    }

    @Test
    fun `a Request Payment reservation raises the starting account's bound only`() = kotlinx.coroutines.runBlocking {
        val base = WalletManager.QUANTUM_INDEX_BASE
        val store = ProbeStore()
        store.probe(OWNER) { store.active = OTHER; false }
        val reserved = store.take(OWNER, 1, reserve = true)
        assertEquals(listOf(base + 4 to "$OWNER/4"), reserved)
        assertEquals(4, store.bound[OWNER])
        assertEquals(9, store.bound[OTHER])
    }

    @Test
    fun `a pick or reservation whose account was removed after its probe writes nothing`() = kotlinx.coroutines.runBlocking {
        val store = ProbeStore()
        store.probe(OWNER) { store.saved -= OWNER; store.active = OTHER; false }
        assertTrue(store.take(OWNER, 1, reserve = false).isEmpty())
        assertTrue(store.take(OWNER, 1, reserve = true).isEmpty())
        assertEquals(3, store.bound[OWNER])
        assertTrue(store.inFlight.getValue(OWNER).isEmpty())
        assertEquals(9, store.bound[OTHER])
        assertTrue(store.inFlight.getValue(OTHER).isEmpty())
    }

    @Test
    fun `an account switch mid-probe writes only to the account it started for`() = kotlinx.coroutines.runBlocking {
        // AND-056 / IOS-109: A's slots 4 and 5 are used elsewhere; the user switches to B while
        // the first check is out. The probe still walks A's chain and raises A's bound - B's is
        // never touched, and every address checked is A's
        val base = WalletManager.QUANTUM_INDEX_BASE
        val store = ProbeStore()
        val asked = ArrayList<String>()
        val slot = store.probe(OWNER) { address ->
            asked += address
            store.active = OTHER
            address == "$OWNER/4" || address == "$OWNER/5"
        }
        assertEquals(OTHER, store.active)
        assertEquals(base + 6, slot)
        assertEquals(5, store.bound[OWNER])
        assertEquals(9, store.bound[OTHER])
        assertEquals(listOf("$OWNER/4", "$OWNER/5", "$OWNER/6"), asked)
    }

    @Test
    fun `a probe whose account was removed writes nothing`() = kotlinx.coroutines.runBlocking {
        // removed while the first check is out: the used slot is not written to anyone, and the
        // slot past it comes back unchecked
        val base = WalletManager.QUANTUM_INDEX_BASE
        val store = ProbeStore()
        var asked = 0
        val slot = store.probe(OWNER) { address ->
            asked++
            store.saved -= OWNER
            store.active = OTHER
            address == "$OWNER/4"
        }
        assertEquals(1, asked)
        assertEquals(base + 5, slot)
        assertEquals(3, store.bound[OWNER])
        assertEquals(9, store.bound[OTHER])
    }

    /** Two saved accounts' records, hidden sets and the spent marks, which one is active, and the
     *  writes a send makes once the network answers, wired the way WalletManager wires them (iOS
     *  ca09dd0, IOS-109 / AND-056): [reveal] as [WalletManager.revealSpendingAddress], [setPrimary]
     *  as [WalletManager.setSpendingAddressIndex], [markSpent] as
     *  [WalletManager.markQuantumAddressSpent]. */
    private class SendWriteStore {
        var accounts = listOf(
            WalletManager.Account(name = "A", address = OWNER, mnemonic = "a", spendingAddressIndex = 2, maxSpendingAddressIndex = 4, maxQuantumAddressIndex = 3),
            WalletManager.Account(name = "B", address = OTHER, mnemonic = "b", spendingAddressIndex = 5, maxSpendingAddressIndex = 7, maxQuantumAddressIndex = 9),
        )
        var active = OWNER
        val hidden = mutableMapOf<String, Set<Int>>(OTHER to setOf(1))
        val spent = mutableSetOf<String>()

        fun reveal(owner: String, index: Int): Boolean {
            val stored = accounts.find { it.address == owner }
            val (record, newHidden) = WalletManager.spendingReveal(stored, hidden[owner].orEmpty(), index) ?: return false
            accounts = accounts.map { if (it.address == owner) record else it }
            hidden[owner] = newHidden
            return true
        }

        fun setPrimary(owner: String, index: Int): Boolean {
            accounts = WalletManager.withSpendingAddressIndex(accounts, owner, index) ?: return false
            return true
        }

        fun markSpent(address: String, owner: String?) {
            if (!WalletManager.ownerStillSaved(accounts, owner)) return
            spent += address.lowercase()
        }

        fun record(owner: String) = accounts.single { it.address == owner }

        /** What primarySpendingIndexFlow shows: the active account's primary only. */
        fun primaryOnScreen() = accounts.find { it.address == active }?.spendingAddressIndex
    }

    @Test
    fun `a send that ends after an account switch writes only the account it started on`() {
        // iOS ca09dd0 (IOS-109 / AND-056, last part): a send from A's primary is out when the
        // user switches to B. Its change slot is listed on A, A's primary follows it and the spent
        // mark is A's - B's record, hidden set and on-screen primary are never touched
        val base = WalletManager.QUANTUM_INDEX_BASE
        val store = SendWriteStore()
        val otherBefore = store.record(OTHER)
        store.active = OTHER
        assertTrue(store.reveal(OWNER, base + 6))
        assertTrue(store.setPrimary(OWNER, base + 6))
        store.markSpent("$OWNER/q2", owner = OWNER)
        assertEquals(6, store.record(OWNER).maxQuantumAddressIndex)
        assertEquals(base + 6, store.record(OWNER).spendingAddressIndex)
        assertEquals(4, store.record(OWNER).maxSpendingAddressIndex)
        assertEquals(setOf(base + 4, base + 5), store.hidden[OWNER])
        assertEquals(setOf("$OWNER/q2"), store.spent)
        assertEquals(otherBefore, store.record(OTHER))
        assertEquals(setOf(1), store.hidden[OTHER])
        assertEquals(5, store.primaryOnScreen())
    }

    @Test
    fun `a send whose account was removed meanwhile writes nothing`() {
        val base = WalletManager.QUANTUM_INDEX_BASE
        val store = SendWriteStore()
        store.accounts = store.accounts.filter { it.address != OWNER }
        store.active = OTHER
        val before = store.accounts
        assertFalse(store.reveal(OWNER, base + 6))
        assertFalse(store.setPrimary(OWNER, base + 6))
        store.markSpent("$OWNER/q2", owner = OWNER)
        assertEquals(before, store.accounts)
        assertEquals(null, store.hidden[OWNER])
        assertEquals(setOf(1), store.hidden[OTHER])
        assertTrue(store.spent.isEmpty())
        // the active account's own writes (no owner) still go through
        store.markSpent("$OTHER/q0", owner = null)
        assertEquals(setOf("$OTHER/q0"), store.spent)
    }

    @Test
    fun `a reveal raises its own chain's bound only and lists the slot`() {
        val base = WalletManager.QUANTUM_INDEX_BASE
        val store = SendWriteStore()
        // legacy past the bound: 5 and 6 newly covered, kept hidden; the quantum bound untouched
        assertTrue(store.reveal(OWNER, 7))
        assertEquals(7, store.record(OWNER).maxSpendingAddressIndex)
        assertEquals(3, store.record(OWNER).maxQuantumAddressIndex)
        assertEquals(setOf(5, 6), store.hidden[OWNER])
        // a slot already covered is only un-hidden
        assertTrue(store.reveal(OWNER, 5))
        assertEquals(7, store.record(OWNER).maxSpendingAddressIndex)
        assertEquals(setOf(6), store.hidden[OWNER])
        // a quantum primary never widens the legacy bound
        assertTrue(store.setPrimary(OWNER, base + 2))
        assertEquals(7, store.record(OWNER).maxSpendingAddressIndex)
        assertEquals(3, store.record(OWNER).maxQuantumAddressIndex)
        assertEquals(base + 2, store.record(OWNER).spendingAddressIndex)
    }

    private fun sweep(
        activeUpTo: Int,
        funded: Set<Int> = emptySet(),
        activityServed: Boolean = true,
        balancesServed: Boolean = true,
        windows: MutableList<Int> = ArrayList(),
        stillSaved: () -> Boolean = { true },
    ) = kotlinx.coroutines.runBlocking {
        SpendingAddressDiscovery.sweepQuantumWindows(
            base = 0,
            derive = { indices -> windows += indices.first(); indices.associateWith { "q$it" } },
            activity = { addresses ->
                if (!activityServed) null
                else addresses.filter { it.drop(1).toInt() <= activeUpTo }.associateWith { 1L }
            },
            balances = { addresses ->
                if (!balancesServed) null
                else addresses.associateWith { if (it.drop(1).toInt() in funded) 5L else 0L }
            },
            stillSaved = stillSaved,
        )
    }

    /** [sweep] for an account that stays saved the whole time: it always finishes. */
    private fun sweepOwned(
        activeUpTo: Int,
        funded: Set<Int> = emptySet(),
        activityServed: Boolean = true,
        balancesServed: Boolean = true,
        windows: MutableList<Int> = ArrayList(),
    ) = sweep(activeUpTo, funded, activityServed, balancesServed, windows)!!

    @Test
    fun `a removed account stops the quantum sweep with nothing to write`() {
        // the account is checked before and after every window's derivation: removed during the
        // second window's derivation, the sweep stops right there and hands back nothing
        val windows = ArrayList<Int>()
        var checks = 0
        val stopped = sweep(activeUpTo = 450, funded = setOf(230), windows = windows, stillSaved = { ++checks <= 3 })
        assertEquals(null, stopped)
        assertEquals(listOf(0, 200), windows)
        // removed before it began: not one window derived
        val none = ArrayList<Int>()
        assertEquals(null, sweep(activeUpTo = 450, windows = none, stillSaved = { false }))
        assertEquals(emptyList<Int>(), none)
        // removed after the last window: still nothing to write
        var after = 0
        val lastWindows = ArrayList<Int>()
        // windows 0, 200, 400, 600 = 8 checks around their derivations, the 9th is the final one
        assertEquals(null, sweep(activeUpTo = 450, windows = lastWindows, stillSaved = { ++after <= 8 }))
        assertEquals(listOf(0, 200, 400, 600), lastWindows)
        // the account stays saved: the same sweep finishes
        assertEquals(450, sweep(activeUpTo = 450, stillSaved = { true })?.highestUsed)
    }

    /** Two saved accounts, the per-account hidden sets and quantum bounds the sweep writes, and
     *  which one is active - what [SpendingAddressDiscovery.runQuantumDiscovery] sees of
     *  WalletManager. */
    private class SweepStore {
        val saved = mutableSetOf(OWNER, OTHER)
        var active = OWNER
        val hidden = HashMap<String, MutableMap<Int, Boolean>>()
        val bound = mutableMapOf(OWNER to -1, OTHER to -1)
    }

    private fun quantumDiscovery(
        store: SweepStore,
        activeUpTo: Int,
        funded: Set<Int>,
        onWindow: (Int) -> Unit,
    ) = kotlinx.coroutines.runBlocking {
        SpendingAddressDiscovery.runQuantumDiscovery(
            owner = OWNER,
            base = 0,
            // the owner's own chain, taken at the start: it doesn't follow the active account
            derive = { indices -> onWindow(indices.first()); indices.associateWith { "q$it" } },
            activity = { addresses -> addresses.filter { it.drop(1).toInt() <= activeUpTo }.associateWith { 1L } },
            balances = { addresses -> addresses.associateWith { if (it.drop(1).toInt() in funded) 5L else 0L } },
            stillSaved = { OWNER in store.saved },
            previousMax = { store.bound[OWNER]?.takeIf { OWNER in store.saved } },
            setHidden = { owner, index, hidden -> store.hidden.getOrPut(owner) { HashMap() }[index] = hidden },
            raiseBound = { owner, quantumIndex -> if (quantumIndex > store.bound.getValue(owner)) store.bound[owner] = quantumIndex },
        )
    }

    @Test
    fun `an account switch mid-sweep still completes and writes to the sweep's own account`() {
        // IOS-107 (iOS e571f45): switched to another account during the second window, the sweep
        // carries on through every window and writes the hidden set and the bound to its OWN
        // account - the newly active one is never touched
        val store = SweepStore()
        val windows = ArrayList<Int>()
        val found = quantumDiscovery(store, activeUpTo = 450, funded = setOf(230)) { start ->
            windows += start
            if (start == 200) store.active = OTHER
        }
        assertEquals(OTHER, store.active)
        assertEquals(listOf(0, 200, 400, 600), windows)
        assertEquals(1, found)
        assertEquals(450, store.bound[OWNER])
        assertEquals(-1, store.bound[OTHER])
        assertEquals(setOf(OWNER), store.hidden.keys)
        val ownerHidden = store.hidden.getValue(OWNER)
        // the slots the new bound swept in start hidden, the funded one is shown
        assertEquals(false, ownerHidden[230])
        assertEquals((0..450).filter { it != 230 }.toSet(), ownerHidden.filterValues { it }.keys)
    }

    @Test
    fun `a removed account's quantum sweep drops its results`() {
        // removed during the second window: nothing is written anywhere and nothing counted
        val store = SweepStore()
        val found = quantumDiscovery(store, activeUpTo = 450, funded = setOf(230)) { start ->
            if (start == 200) {
                store.saved -= OWNER
                store.active = OTHER
            }
        }
        assertEquals(0, found)
        assertTrue(store.hidden.isEmpty())
        assertEquals(-1, store.bound[OWNER])
        assertEquals(-1, store.bound[OTHER])
        // removed before it starts: not one window derived
        val gone = SweepStore().apply { saved -= OWNER }
        val windows = ArrayList<Int>()
        assertEquals(0, quantumDiscovery(gone, activeUpTo = 450, funded = setOf(230)) { windows += it })
        assertEquals(emptyList<Int>(), windows)
        assertTrue(gone.hidden.isEmpty())
    }

    @Test
    fun `the sweep account derives its own chains from what it took at the start`() {
        // both chains from the account's own words (iOS e571f45 SpendingSweepAccount), whatever
        // account is active - the same addresses the reference vectors give
        val account = WalletManager.SpendingSweepAccount(OWNER, words, "", WalletManager.PassphraseForm.NFKD)
        val base = WalletManager.QUANTUM_INDEX_BASE
        val derived = account.derive(listOf(0, 1, base, base + 1))
        assertEquals("kaspa:qppt49jerlp2vjquvhfjcpzkna7g26jzu2r88wwsnt3zndq4qvnnywxtsehh8", derived[0])
        assertEquals("kaspa:qphqs4c490zy80q6rjlnetvyv3hclg8zz6h8l774rsprdprryz88xk3qp39k7", derived[1])
        assertEquals("kaspa:pp4csja82vqaa9hstf6ned49w8j2tl79jmwjk5v79999qxsrfkj9zvq7y9wme", derived[base])
        assertEquals("kaspa:pzc4ja4jywkqelzxeqn42z6mfqcf0aacgudadtszz52xpc9vr5wkxuyd47277", derived[base + 1])
        assertEquals(OWNER, account.owner)
        // another account's material gives another account's addresses
        val other = WalletManager.SpendingSweepAccount(OTHER, words, "other passphrase", WalletManager.PassphraseForm.NFKD)
        assertFalse(other.derive(listOf(0))[0] == derived[0])
    }

    @Test
    fun `the quantum sweep keeps stepping while a window shows activity`() {
        val windows = ArrayList<Int>()
        // activity up to slot 450: windows 0, 200, 400 show it, 600 is quiet and ends the sweep
        val result = sweepOwned(activeUpTo = 450, funded = setOf(230), windows = windows)
        assertEquals(listOf(0, 200, 400, 600), windows)
        assertEquals(listOf(230), result.funded)
        // the bound covers the highest USED slot, not only the funded one
        assertEquals(450, result.highestUsed)
    }

    @Test
    fun `the quantum sweep stops at 10,000 slots`() {
        val windows = ArrayList<Int>()
        val result = sweepOwned(activeUpTo = Int.MAX_VALUE, windows = windows)
        assertEquals(SpendingAddressDiscovery.QUANTUM_SCAN_LIMIT / SpendingAddressDiscovery.QUANTUM_SCAN_FLOOR, windows.size)
        assertEquals(SpendingAddressDiscovery.QUANTUM_SCAN_LIMIT - 1, result.highestUsed)
    }

    @Test
    fun `without the activity answer the quantum sweep reads one window's balances`() {
        val windows = ArrayList<Int>()
        val result = sweepOwned(activeUpTo = 450, funded = setOf(12, 230), activityServed = false, windows = windows)
        assertEquals(listOf(0), windows)
        assertEquals(listOf(12), result.funded)
        assertEquals(12, result.highestUsed)
        // nothing used at all: no bound
        assertEquals(-1, sweepOwned(activeUpTo = -1).highestUsed)
        // balances unreadable: nothing shown, the activity bound still set
        val unread = sweepOwned(activeUpTo = 250, funded = setOf(3), balancesServed = false)
        assertEquals(emptyList<Int>(), unread.funded)
        assertEquals(250, unread.highestUsed)
    }

    private fun page(spender: String?, size: Int) = List(size) { i ->
        TransactionResponse(
            transactionId = "%064x".format(i),
            inputsOrNull = listOf(TransactionInput("00".repeat(32), 0, if (i == 0) spender else "kaspa:qqother", 1L, "")),
            outputs = emptyList(),
            blockTime = null,
            payload = null,
        )
    }

    @Test
    fun `the shield reads the whole history, a page at a time`() = kotlinx.coroutines.runBlocking {
        val me = "kaspa:pqme"
        val size = WalletService.QUANTUM_SPENT_PAGE_SIZE
        val offsets = ArrayList<Int>()
        // the spend is on the third page: found
        val spent = WalletService.quantumSpentFromPages(me) { offset, limit ->
            offsets += offset
            page(if (offset == 2 * size) me.uppercase() else null, limit)
        }
        assertEquals(true, spent)
        assertEquals(listOf(0, size, 2 * size), offsets)
        // a short page ends the history: not spent
        assertEquals(false, WalletService.quantumSpentFromPages(me) { offset, limit -> page(null, if (offset == size) 3 else limit) })
        // a page that can't be read: unknown, never "not spent"
        assertEquals(null, WalletService.quantumSpentFromPages(me) { offset, limit -> if (offset == size) null else page(null, limit) })
        // deeper than 10,000 transactions: unknown
        var pages = 0
        assertEquals(null, WalletService.quantumSpentFromPages(me) { _, limit -> pages++; page(null, limit) })
        assertEquals(WalletService.QUANTUM_SPENT_MAX_PAGES, pages)
    }

    private companion object {
        const val OWNER = "kaspa:owner"
        const val OTHER = "kaspa:other"
    }
}
