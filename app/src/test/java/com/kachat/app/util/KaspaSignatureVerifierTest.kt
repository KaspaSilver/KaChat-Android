package com.kachat.app.util

import com.google.gson.Gson
import com.kachat.app.services.Outpoint
import com.kachat.app.services.RawInput
import com.kachat.app.services.RawOutputWithVersion
import com.kachat.app.services.RawTransaction
import com.kachat.app.services.ScriptPublicKey
import com.kachat.app.services.ScriptPublicKeyWithVersion
import com.kachat.app.services.TransactionOutput
import com.kachat.app.services.TransactionResponse
import com.kachat.app.services.UtxoData
import com.kachat.app.services.UtxoEntry
import com.kachat.app.services.WalletManager
import com.kachat.app.util.KaspaSignatureVerifier.SignatureCheck
import kotlinx.coroutines.runBlocking
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDKeyDerivation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KaspaSignatureVerifier] against real mainnet transactions, as api.kaspa.org's
 * `/transactions/{id}?inputs=true&outputs=true&resolve_previous_outpoints=light` served them
 * (Oct 2026; test/resources/kaspa-transactions), parsed with Gson like the app parses them.
 * Every one was also checked with an independent script following rusty-kaspa's
 * `calc_schnorr_signature_hash` (iOS 5806362's vectors plus our own).
 */
class KaspaSignatureVerifierTest {

    private fun load(txId: String): TransactionResponse {
        val stream = javaClass.classLoader!!.getResourceAsStream("kaspa-transactions/$txId.json")
            ?: error("missing fixture $txId")
        return stream.reader().use { Gson().fromJson(it, TransactionResponse::class.java) }
    }

    /** The receiving wallet: an address in none of the fixtures (only compared, never decoded). */
    private val me = "kaspa:qqnotinanyfixture"

    private fun received(tx: TransactionResponse, spent: Map<String, TransactionOutput> = emptyMap(), myAddress: String = me) =
        runBlocking { KaspaSignatureVerifier.checkReceivedTransaction(tx, myAddress) { hash, index -> spent["$hash:$index"] } }

    private fun check(tx: TransactionResponse, spent: Map<String, TransactionOutput> = emptyMap()): SignatureCheck =
        received(tx, spent).check

    // version 0
    private val v0Change = "5edbcad29c52e5670a5989f32dd749d8f1d6b5fbd74bce30c8af4ca58e1c24c0"
    private val v0OneOut = "e3faaa000b3a9717247c72a61925bcf87307c63daba216d3e2491c83b5d2df60"
    private val v0Payment = "1feb8b58162de1cac138a2061ce43c11f4d63d7b4195d4362016f3025d13e410"
    private val v0Payload = "366714913c41f726b12b980a7f22b63e9c4cebfc90aefecda443d75f0710652e"
    // version 1: no covenant outputs; on a non-native subnetwork with a payload; covenant outputs
    private val v1Plain = "3ac700b8ee7c941bb6cc2fb8562e0f7087a0fbbd851a0eca897f0e5af0e7e737"
    private val v1Subnetwork = "97b1b6a45da35592dda9b32afd2e6f548cca854e19472aa819e4823266eeff18"
    private val v1Covenants = listOf(
        "cd2f93b3d9f6692b6dda6de8fa2f54a46052c2d6480c5536bea178494902566d",
        "82453d2419bb80efebb16ca3bf1ac0efece375835ccd392c7eb63b4bc3eba640",
        "400589f6ca2247430d907511101c628bad7f5a1411ba3b1703efeb460c92fba5",
    )

    @Test
    fun `real version 0 signatures verify`() {
        for (id in listOf(v0Change, v0OneOut, v0Payment, v0Payload)) {
            assertEquals(id, SignatureCheck.Valid, check(load(id)))
        }
    }

    @Test
    fun `real version 1 signatures verify, covenant outputs and other subnetworks included`() {
        for (id in listOf(v1Plain, v1Subnetwork) + v1Covenants) {
            assertEquals(id, SignatureCheck.Valid, check(load(id)))
        }
    }

    @Test
    fun `the fixtures are what they claim to be`() {
        assertEquals("0", load(v0Change).version)
        assertEquals("1", load(v1Plain).version)
        for (id in v1Covenants) {
            val tx = load(id)
            assertTrue(id, tx.outputs.any { it.covenantAuthorizingInput != null && it.covenantId?.length == 64 })
        }
    }

    @Test
    fun `a tampered signature fails`() {
        for (id in listOf(v0OneOut, v1Plain)) {
            val tx = load(id)
            val sig = tx.inputs[0].signatureScript
            val flipped = sig.substring(0, 10) + (if (sig[10] == '0') '1' else '0') + sig.substring(11)
            assertEquals(id, SignatureCheck.Invalid(0), check(tx.copy(inputsOrNull = listOf(tx.inputs[0].copy(signatureScript = flipped)))))
        }
    }

    @Test
    fun `the REST fields rebuild the real transaction ids`() {
        for (id in listOf(v0Change, v0OneOut, v0Payment, v0Payload, v1Plain, v1Subnetwork) + v1Covenants) {
            val tx = load(id)
            val rebuilt = KaspaSignatureVerifier.restTransactionId(
                version = tx.version!!.toInt(),
                inputs = tx.inputs.map { Triple(it.previousOutpointHash, it.previousOutpointIndex, 0L) },
                outputs = tx.outputs.map { output ->
                    val covenant = output.covenantId?.let { idHex ->
                        KaspaTransactionSigner.OutputCovenant(
                            output.covenantAuthorizingInput!!.toInt(),
                            ByteArray(32) { i -> idHex.substring(2 * i, 2 * i + 2).toInt(16).toByte() },
                        )
                    }
                    Triple(output.amount, ByteArray(output.scriptPublicKey.length / 2) { i ->
                        output.scriptPublicKey.substring(2 * i, 2 * i + 2).toInt(16).toByte()
                    }, covenant)
                },
                lockTime = 0, subnetworkId = ByteArray(tx.subnetworkId!!.length / 2) { i ->
                    tx.subnetworkId!!.substring(2 * i, 2 * i + 2).toInt(16).toByte()
                },
                gas = 0,
                payload = ByteArray((tx.payload ?: "").length / 2) { i -> tx.payload!!.substring(2 * i, 2 * i + 2).toInt(16).toByte() },
            )
            assertEquals(id, id, rebuilt)
        }
    }

    // A failure is Invalid only when the REST fields rebuild the claimed id (XP-033): a changed
    // output, covenant or payload changes the id too, so the API's answer is incomplete - Unknown.
    @Test
    fun `a tampered or redirected output is unknown, as the id no longer rebuilds`() {
        for (id in listOf(v0Payment, v1Plain)) {
            val tx = load(id)
            val more = tx.outputs.toMutableList().also { it[0] = it[0].copy(amount = it[0].amount + 1) }
            assertEquals(id, SignatureCheck.Unknown, check(tx.copy(outputs = more)))
            val redirected = tx.outputs.toMutableList().also { it[0] = it[0].copy(scriptPublicKey = tx.outputs[1].scriptPublicKey) }
            assertEquals(id, SignatureCheck.Unknown, check(tx.copy(outputs = redirected)))
        }
    }

    @Test
    fun `a tampered covenant binding is unknown, as the id no longer rebuilds`() {
        val tx = load(v1Covenants[0])
        val bound = tx.outputs.indexOfFirst { it.covenantId != null }
        val otherId = tx.outputs.toMutableList().also {
            it[bound] = it[bound].copy(covenantId = "00".repeat(32))
        }
        assertEquals(SignatureCheck.Unknown, check(tx.copy(outputs = otherId)))
        val unbound = tx.outputs.toMutableList().also {
            it[bound] = it[bound].copy(covenantAuthorizingInput = null, covenantId = null)
        }
        assertEquals(SignatureCheck.Unknown, check(tx.copy(outputs = unbound)))
    }

    @Test
    fun `a tampered spent amount fails, a tampered payload is unknown`() {
        for (id in listOf(v0Payload, v1Subnetwork)) {
            val tx = load(id)
            val input = tx.inputs[0]
            // the spent amount is not part of the id: the API described the transaction, the signature fails
            assertEquals(id, SignatureCheck.Invalid(0), check(
                tx.copy(inputsOrNull = listOf(input.copy(previousOutpointAmount = input.previousOutpointAmount!! - 1)))
            ))
            assertEquals(id, SignatureCheck.Unknown, check(tx.copy(payload = tx.payload!!.dropLast(2) + "00")))
        }
    }

    @Test
    fun `an unresolved input is looked up through its previous transaction`() {
        val tx = load(v1Plain)
        val input = tx.inputs[0]
        val unresolved = tx.copy(inputsOrNull = listOf(input.copy(previousOutpointAddress = null, previousOutpointAmount = null)))
        // not readable yet: held, never dropped
        assertEquals(SignatureCheck.Unknown, check(unresolved))
        // the previous transaction's output at that index fills it in
        val spent = TransactionOutput(
            amount = input.previousOutpointAmount!!,
            scriptPublicKey = KaspaAddress.getScriptPublicKey(input.previousOutpointAddress!!),
            scriptPublicKeyAddress = input.previousOutpointAddress,
            index = input.previousOutpointIndex,
        )
        assertEquals(SignatureCheck.Valid, check(unresolved, mapOf("${input.previousOutpointHash}:${input.previousOutpointIndex}" to spent)))
        // and a wrong one is caught
        val wrong = spent.copy(amount = spent.amount + 1)
        assertEquals(SignatureCheck.Invalid(0), check(unresolved, mapOf("${input.previousOutpointHash}:${input.previousOutpointIndex}" to wrong)))
    }

    @Test
    fun `the spent output is found by its index, else by position`() {
        val outputs = listOf(
            TransactionOutput(amount = 1, scriptPublicKey = "aa", scriptPublicKeyAddress = "a", index = 1),
            TransactionOutput(amount = 2, scriptPublicKey = "bb", scriptPublicKeyAddress = "b", index = 0),
        )
        val previous = load(v0OneOut).copy(outputs = outputs)
        assertEquals(2L, KaspaSignatureVerifier.outputAt(previous, 0)?.amount)
        val unindexed = previous.copy(outputs = outputs.map { it.copy(index = null) })
        assertEquals(1L, KaspaSignatureVerifier.outputAt(unindexed, 0)?.amount)
        assertEquals(null, KaspaSignatureVerifier.outputAt(unindexed, 5))
    }

    @Test
    fun `what cannot be checked is unknown, a coinbase too, a malformed signature fails`() {
        val tx = load(v0OneOut)
        val input = tx.inputs[0]
        // a coinbase has no signatures: nothing was checked, nothing is vouched for (IOS-094)
        assertEquals(SignatureCheck.Unknown, check(tx.copy(inputsOrNull = emptyList())))
        // a key-address input must carry a well-formed signature: a blank, short or wrongly pushed
        // one is a failure (AND-042), and the id rebuilds, so it is forged
        for (script in listOf("", "00", input.signatureScript.drop(2), "40" + input.signatureScript.drop(2))) {
            assertEquals(script, SignatureCheck.Invalid(0), check(tx.copy(inputsOrNull = listOf(input.copy(signatureScript = script)))))
        }
        // a sighash type other than SIGHASH_ALL can't be checked here: unknown, never a pass
        assertEquals(SignatureCheck.Unknown, check(tx.copy(inputsOrNull = listOf(input.copy(signatureScript = input.signatureScript.dropLast(2) + "81")))))
        // a wrong claimed id: a failing signature can't be called forged
        val flipped = input.signatureScript.substring(0, 10) + (if (input.signatureScript[10] == '0') '1' else '0') + input.signatureScript.substring(11)
        assertEquals(SignatureCheck.Unknown, check(tx.copy(transactionId = "00".repeat(32), inputsOrNull = listOf(input.copy(signatureScript = flipped)))))
        // an output without script data, a malformed covenant id, an address that doesn't decode
        assertEquals(SignatureCheck.Unknown, check(tx.copy(outputs = listOf(tx.outputs[0].copy(scriptPublicKey = "")))))
        assertEquals(SignatureCheck.Unknown, check(tx.copy(outputs = listOf(tx.outputs[0].copy(covenantAuthorizingInput = "0", covenantId = "abcd")))))
        assertEquals(SignatureCheck.Unknown, check(tx.copy(inputsOrNull = listOf(input.copy(previousOutpointAddress = "kaspa:nope")))))
    }

    // KIP-22 quantum-address (P2SH) spends (iOS 54265bf), signed by the wallet's own signer with
    // the QuantumAddressTest vectors (public all-zero-entropy test mnemonic - not a real wallet).
    private val quantumChain by lazy { WalletManager.quantumChainNode(WalletManager.seedFor(List(23) { "abandon" } + "art", "")) }

    /** A two-input spend from quantum address #1, signed with key #[signer]'s key and redeem. */
    private fun quantumSpend(signer: Int = 1): Pair<RawTransaction, List<UtxoEntry>> {
        val address = WalletManager.quantumAddress(quantumChain, 1)
        val spk = KaspaAddress.getScriptPublicKey(address)
        val utxos = listOf(150_000_000L, 70_000_000L).mapIndexed { i, amount ->
            UtxoEntry(
                address = address,
                outpoint = Outpoint(transactionId = "%064x".format(i + 0xabc), index = i),
                utxoEntry = UtxoData(amount = amount, scriptPublicKey = ScriptPublicKey(spk), blockDaaScore = 1L, isCoinbase = false)
            )
        }
        val tx = RawTransaction(
            inputs = utxos.map { RawInput(previousOutpoint = it.outpoint, signatureScript = "") },
            outputs = listOf(
                RawOutputWithVersion(200_000_000L, ScriptPublicKeyWithVersion(KaspaAddress.getScriptPublicKey("kaspa:qppt49jerlp2vjquvhfjcpzkna7g26jzu2r88wwsnt3zndq4qvnnywxtsehh8"), 0)),
                RawOutputWithVersion(19_990_000L, ScriptPublicKeyWithVersion(KaspaAddress.getScriptPublicKey(WalletManager.quantumAddress(quantumChain, 2)), 0)),
            ),
        )
        val key = HDKeyDerivation.deriveChildKey(quantumChain, ChildNumber(signer, false))
        return KaspaTransactionSigner.signTransaction(tx, utxos, key.privKeyBytes, redeemScript = WalletManager.quantumRedeemScript(key)) to utxos
    }

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    /** The verifier's answer for [tx] as the REST API would describe it, its id rebuilt from the
     *  same fields (so a failure is Invalid); [scripts] replaces the signature scripts. */
    private fun verifyQuantum(tx: RawTransaction, utxos: List<UtxoEntry>, scripts: List<String> = tx.inputs.map { it.signatureScript }): SignatureCheck {
        val id = KaspaSignatureVerifier.restTransactionId(
            version = 0,
            inputs = tx.inputs.map { Triple(it.previousOutpoint.transactionId, it.previousOutpoint.index, it.sequence) },
            outputs = tx.outputs.map { Triple(it.amount, bytes(it.scriptPublicKey.scriptPublicKey), null) },
            lockTime = 0, subnetworkId = ByteArray(20), gas = 0, payload = ByteArray(0),
        )!!
        return KaspaSignatureVerifier.verifyTransactionSignatures(
            claimedTxId = id,
            inputs = tx.inputs.mapIndexed { i, input ->
                KaspaSignatureVerifier.VerificationInput(
                    previousOutpointHash = input.previousOutpoint.transactionId,
                    previousOutpointIndex = input.previousOutpoint.index,
                    signatureScript = scripts[i],
                    previousOutpointAddress = utxos[i].address,
                    previousOutpointAmount = utxos[i].utxoEntry.amount,
                    sequence = input.sequence,
                    sigOpCount = input.sigOpCount,
                )
            },
            outputs = tx.outputs.map { KaspaSignatureVerifier.VerificationOutput(it.amount, it.scriptPublicKey.scriptPublicKey) },
            version = 0, lockTime = 0, subnetworkId = ByteArray(20), gas = 0, payload = ByteArray(0),
        )
    }

    @Test
    fun `a real KIP-22 spend verifies`() {
        val (tx, utxos) = quantumSpend()
        tx.inputs.forEach { assertEquals(202, it.signatureScript.length) }
        assertTrue(utxos[0].utxoEntry.scriptPublicKey.scriptPublicKey.startsWith("aa20"))
        assertEquals(SignatureCheck.Valid, verifyQuantum(tx, utxos))
    }

    @Test
    fun `a KIP-22 spend with a tampered signature fails`() {
        val (tx, utxos) = quantumSpend()
        val scripts = tx.inputs.map { it.signatureScript }.toMutableList()
        val sig = scripts[1]
        scripts[1] = sig.substring(0, 10) + (if (sig[10] == '0') '1' else '0') + sig.substring(11)
        assertEquals(SignatureCheck.Invalid(1), verifyQuantum(tx, utxos, scripts))
    }

    @Test
    fun `a KIP-22 spend whose redeem hashes to another address fails`() {
        // key #0 signs the sighash of address #1's outputs correctly, but its redeem is address #0's:
        // the signature checks out against its own key, the key just isn't this address's
        val (tx, utxos) = quantumSpend(signer = 0)
        val key = HDKeyDerivation.deriveChildKey(quantumChain, ChildNumber(0, false))
        val sighash = KaspaTransactionSigner.calculateSighash(tx, 0, utxos[0].utxoEntry.amount, utxos[0].utxoEntry.scriptPublicKey.scriptPublicKey)
        assertTrue(Schnorr.verify(sighash, bytes(tx.inputs[0].signatureScript).copyOfRange(1, 65), Schnorr.publicKeyXOnly(key.privKeyBytes)))
        assertEquals(SignatureCheck.Invalid(0), verifyQuantum(tx, utxos))
    }

    @Test
    fun `a KIP-22 sighash type other than SIGHASH_ALL is unknown`() {
        val (tx, utxos) = quantumSpend()
        val scripts = tx.inputs.map { it.signatureScript }.toMutableList()
        scripts[0] = scripts[0].substring(0, 130) + "81" + scripts[0].substring(132)
        assertEquals(SignatureCheck.Unknown, verifyQuantum(tx, utxos, scripts))
    }

    @Test
    fun `a covenant-shaped P2SH spend is skipped`() {
        val (tx, utxos) = quantumSpend()
        val script = tx.inputs[0].signatureScript
        // not the KIP-22 shape: a redeem that isn't <push-32> <key> OP_CHECKSIG, a longer script,
        // a different push - each a covenant spend, not signature-checked (and the input with it)
        val covenants = listOf(
            script.dropLast(2) + "87",
            script + "51",
            script.substring(0, 132) + "21" + script.substring(134),
            "00",
            "",
        )
        // ...and with not one input checked the payment is not vouched for: unknown (AND-054)
        for (covenant in covenants) {
            assertEquals(covenant, SignatureCheck.Unknown, verifyQuantum(tx, utxos, listOf(covenant, covenant)))
        }
        // one checked KIP-22 input beside a skipped one is enough
        assertEquals(SignatureCheck.Valid, verifyQuantum(tx, utxos, listOf(covenants[0], tx.inputs[1].signatureScript)))
        // a skipped input doesn't excuse the KIP-22 one beside it
        val second = tx.inputs[1].signatureScript
        val flipped = second.substring(0, 10) + (if (second[10] == '0') '1' else '0') + second.substring(11)
        assertEquals(SignatureCheck.Invalid(1), verifyQuantum(tx, utxos, listOf(covenants[0], flipped)))
        // a signature script that isn't hex can't be on chain: invalid outright (AND-046)
        assertEquals(SignatureCheck.Invalid(0), verifyQuantum(tx, utxos, listOf("zz", tx.inputs[1].signatureScript)))
    }

    // MARK: run 17 (iOS 5a78d06): AND-046 malformed = invalid outright, AND-054 nothing checked =
    // unknown, IOS-094 the sender from the checked inputs

    @Test
    fun `a malformed P2PK signature script is invalid even when the claimed id doesn't rebuild`() {
        val tx = load(v0OneOut)
        val input = tx.inputs[0]
        for (script in listOf("", "00", input.signatureScript.drop(2), "40" + input.signatureScript.drop(2), "zz")) {
            assertEquals(script, SignatureCheck.Invalid(0), check(
                tx.copy(transactionId = "00".repeat(32), inputsOrNull = listOf(input.copy(signatureScript = script)))
            ))
        }
    }

    @Test
    fun `a KIP-22 redeem for another address is invalid even when the claimed id doesn't rebuild`() {
        val (tx, utxos) = quantumSpend(signer = 0)
        val result = KaspaSignatureVerifier.verifyTransactionSignatures(
            claimedTxId = "00".repeat(32),
            inputs = tx.inputs.mapIndexed { i, input ->
                KaspaSignatureVerifier.VerificationInput(
                    input.previousOutpoint.transactionId, input.previousOutpoint.index, input.signatureScript,
                    utxos[i].address, utxos[i].utxoEntry.amount, input.sequence, input.sigOpCount,
                )
            },
            outputs = tx.outputs.map { KaspaSignatureVerifier.VerificationOutput(it.amount, it.scriptPublicKey.scriptPublicKey) },
            version = 0, lockTime = 0, subnetworkId = ByteArray(20), gas = 0, payload = ByteArray(0),
        )
        assertEquals(SignatureCheck.Invalid(0), result)
    }

    @Test
    fun `every input is examined before an unknown, so a later malformed one is invalid`() {
        val (tx, utxos) = quantumSpend()
        val scripts = tx.inputs.map { it.signatureScript }.toMutableList()
        // input 0: a sighash type the check doesn't hash (undecided); input 1: not even hex
        scripts[0] = scripts[0].substring(0, 130) + "81" + scripts[0].substring(132)
        assertEquals(SignatureCheck.Unknown, verifyQuantum(tx, utxos, scripts))
        scripts[1] = "zz"
        assertEquals(SignatureCheck.Invalid(1), verifyQuantum(tx, utxos, scripts))
        // an unresolved input first doesn't hide a malformed one after it either
        val first = KaspaSignatureVerifier.verifyTransactionSignatures(
            claimedTxId = "00".repeat(32),
            inputs = tx.inputs.mapIndexed { i, input ->
                KaspaSignatureVerifier.VerificationInput(
                    input.previousOutpoint.transactionId, input.previousOutpoint.index,
                    if (i == 1) "zz" else input.signatureScript,
                    if (i == 0) null else utxos[i].address, utxos[i].utxoEntry.amount, input.sequence, input.sigOpCount,
                )
            },
            outputs = tx.outputs.map { KaspaSignatureVerifier.VerificationOutput(it.amount, it.scriptPublicKey.scriptPublicKey) },
            version = 0, lockTime = 0, subnetworkId = ByteArray(20), gas = 0, payload = ByteArray(0),
        )
        assertEquals(SignatureCheck.Invalid(1), first)
    }

    @Test
    fun `a payment whose every input is ECDSA had nothing checked and is unknown`() {
        val tx = load(v0OneOut)
        val ecdsa = KaspaAddress.encode("kaspa", 1, ByteArray(33) { if (it == 0) 2 else 7 })
        val input = tx.inputs[0].copy(previousOutpointAddress = ecdsa, signatureScript = "00")
        assertEquals(SignatureCheck.Unknown, check(tx.copy(inputsOrNull = listOf(input))))
    }

    @Test
    fun `a valid payment's sender is the first checked input that isn't ours`() {
        val tx = load(v0Payment)
        val payer = tx.inputs[0].previousOutpointAddress!!
        val valid = received(tx)
        assertEquals(SignatureCheck.Valid, valid.check)
        assertEquals(payer, valid.sender)
        // an input filled in from its previous transaction counts as checked too
        val input = tx.inputs[0]
        val unresolved = tx.copy(inputsOrNull = listOf(input.copy(previousOutpointAddress = null, previousOutpointAmount = null)) + tx.inputs.drop(1))
        val spent = TransactionOutput(
            amount = input.previousOutpointAmount!!,
            scriptPublicKey = KaspaAddress.getScriptPublicKey(payer),
            scriptPublicKeyAddress = payer,
            index = input.previousOutpointIndex,
        )
        assertEquals(payer, received(unresolved, mapOf("${input.previousOutpointHash}:${input.previousOutpointIndex}" to spent)).sender)
        // our own inputs are never the sender; with only ours there is none
        assertEquals(SignatureCheck.Valid, received(tx, myAddress = payer.uppercase()).check)
        assertEquals(null, received(tx, myAddress = payer.uppercase()).sender)
        // nothing proven, no sender
        assertEquals(null, received(tx.copy(transactionId = "00".repeat(32), inputsOrNull = listOf(input.copy(signatureScript = "")))).sender)
    }

    // MARK: run 18 (iOS 2343e84): IOS-094 the sender only from inputs whose signature VERIFIED

    /** A forger's key (test-only bytes) and its P2PK address. */
    private val forgerKey = ByteArray(32) { (it + 41).toByte() }
    private val forger by lazy { KaspaAddress.encode("kaspa", 0, Schnorr.publicKeyXOnly(forgerKey)) }
    /** An ECDSA address: its inputs are skipped unchecked. */
    private val ecdsaVictim = KaspaAddress.encode("kaspa", 1, ByteArray(33) { if (it == 0) 2 else 7 })

    /** A two-input payment as the REST API describes it: input 0 spent from [first] and left with
     *  an unchecked script, input 1 spent from the forger's address and really signed by them. */
    private fun forgedPayment(first: String): TransactionResponse {
        val utxos = listOf(first to 300_000_000L, forger to 50_000_000L).mapIndexed { i, (address, amount) ->
            UtxoEntry(
                address = address,
                outpoint = Outpoint(transactionId = "%064x".format(i + 0xdef), index = i),
                utxoEntry = UtxoData(amount = amount, scriptPublicKey = ScriptPublicKey(KaspaAddress.getScriptPublicKey(address)), blockDaaScore = 1L, isCoinbase = false)
            )
        }
        val raw = RawTransaction(
            inputs = utxos.map { RawInput(previousOutpoint = it.outpoint, signatureScript = "") },
            outputs = listOf(RawOutputWithVersion(349_990_000L, ScriptPublicKeyWithVersion(KaspaAddress.getScriptPublicKey(forger), 0))),
        )
        val signed = KaspaTransactionSigner.signTransaction(raw, utxos, forgerKey)
        return TransactionResponse(
            transactionId = "00".repeat(32),
            inputsOrNull = signed.inputs.mapIndexed { i, input ->
                com.kachat.app.services.TransactionInput(
                    previousOutpointHash = input.previousOutpoint.transactionId,
                    previousOutpointIndex = input.previousOutpoint.index,
                    previousOutpointAddress = utxos[i].address,
                    previousOutpointAmount = utxos[i].utxoEntry.amount,
                    // input 0: whatever the forger likes - its kind isn't checked here
                    signatureScript = if (i == 0) "00" else input.signatureScript,
                    sequence = input.sequence.toString(),
                    sigOpCount = input.sigOpCount.toString(),
                )
            },
            outputs = signed.outputs.mapIndexed { i, output ->
                TransactionOutput(output.amount, output.scriptPublicKey.scriptPublicKey, forger, index = i)
            },
            blockTime = 1L,
            payload = "",
            version = "0",
            subnetworkId = "00".repeat(20),
        )
    }

    @Test
    fun `an unchecked ECDSA input listed first is never the sender, the verified one is`() {
        val tx = forgedPayment(first = ecdsaVictim)
        val verdict = received(tx)
        // the forger's own input verifies, the ECDSA one is skipped: valid...
        assertEquals(SignatureCheck.Valid, verdict.check)
        // ...and the sender is the input whose signature verified, never the first non-mine one
        assertEquals(forger, verdict.sender)
        // the verified inputs are reported by index
        val verified = ArrayList<Int>()
        KaspaSignatureVerifier.verifyTransactionSignatures(
            claimedTxId = tx.transactionId,
            inputs = tx.inputs.map {
                KaspaSignatureVerifier.VerificationInput(
                    it.previousOutpointHash, it.previousOutpointIndex, it.signatureScript,
                    it.previousOutpointAddress, it.previousOutpointAmount, 0L, 1,
                )
            },
            outputs = tx.outputs.map { KaspaSignatureVerifier.VerificationOutput(it.amount, it.scriptPublicKey) },
            version = 0, lockTime = 0, subnetworkId = ByteArray(20), gas = 0, payload = ByteArray(0),
            onVerifiedInput = { verified += it },
        )
        assertEquals(listOf(1), verified)
    }

    @Test
    fun `only unchecked inputs not ours beside a verified own input name no sender and go the unknown way`() {
        val tx = forgedPayment(first = ecdsaVictim)
        // the verified input is ours: the only non-mine input is the unchecked ECDSA one
        val verdict = received(tx, myAddress = forger.uppercase())
        assertEquals(SignatureCheck.Valid, verdict.check)
        assertEquals(null, verdict.sender)
        // the payment sync then takes the unknown way (node + indexer), never the REST label
        assertEquals(SignatureCheck.Unknown, com.kachat.app.repository.ChatRepository.paymentSignatureCheck(verdict))
        // a valid verdict that does name a sender stays valid; the other verdicts pass through
        assertEquals(SignatureCheck.Valid, com.kachat.app.repository.ChatRepository.paymentSignatureCheck(received(tx)))
        assertEquals(
            SignatureCheck.Invalid(1),
            com.kachat.app.repository.ChatRepository.paymentSignatureCheck(KaspaSignatureVerifier.ReceivedCheck(SignatureCheck.Invalid(1), null))
        )
        assertEquals(
            SignatureCheck.Unknown,
            com.kachat.app.repository.ChatRepository.paymentSignatureCheck(KaspaSignatureVerifier.ReceivedCheck(SignatureCheck.Unknown, null))
        )
    }

    @Test
    fun `the wallet's own version 0 signing hash is unchanged by covenant data`() {
        // A version-0 transaction ignores covenants entirely (rusty-kaspa: v1 only).
        val tx = com.kachat.app.services.RawTransaction(
            version = 0,
            inputs = listOf(com.kachat.app.services.RawInput(com.kachat.app.services.Outpoint("11".repeat(32), 0), "", 0, 1)),
            outputs = listOf(com.kachat.app.services.RawOutputWithVersion(5, com.kachat.app.services.ScriptPublicKeyWithVersion("20" + "22".repeat(32) + "ac"))),
        )
        val plain = KaspaTransactionSigner.calculateSighash(tx, 0, 10, "20" + "33".repeat(32) + "ac")
        val withCovenant = KaspaTransactionSigner.calculateSighash(
            tx, 0, 10, "20" + "33".repeat(32) + "ac",
            listOf(KaspaTransactionSigner.OutputCovenant(0, ByteArray(32) { 9 }))
        )
        org.junit.Assert.assertArrayEquals(plain, withCovenant)
        // ...while version 1 hashes it differently
        val v1 = KaspaTransactionSigner.calculateSighash(tx.copy(version = 1), 0, 10, "20" + "33".repeat(32) + "ac")
        org.junit.Assert.assertFalse(plain.contentEquals(v1))
    }
}
