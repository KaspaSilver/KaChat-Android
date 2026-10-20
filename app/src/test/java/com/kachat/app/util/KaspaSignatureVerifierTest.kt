package com.kachat.app.util

import com.google.gson.Gson
import com.kachat.app.services.TransactionOutput
import com.kachat.app.services.TransactionResponse
import com.kachat.app.util.KaspaSignatureVerifier.SignatureCheck
import kotlinx.coroutines.runBlocking
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

    private fun check(tx: TransactionResponse, spent: Map<String, TransactionOutput> = emptyMap()): SignatureCheck =
        runBlocking { KaspaSignatureVerifier.checkReceivedTransaction(tx) { hash, index -> spent["$hash:$index"] } }

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
            assertEquals(id, SignatureCheck.Invalid(0), check(tx.copy(inputs = listOf(tx.inputs[0].copy(signatureScript = flipped)))))
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
                tx.copy(inputs = listOf(input.copy(previousOutpointAmount = input.previousOutpointAmount!! - 1)))
            ))
            assertEquals(id, SignatureCheck.Unknown, check(tx.copy(payload = tx.payload!!.dropLast(2) + "00")))
        }
    }

    @Test
    fun `an unresolved input is looked up through its previous transaction`() {
        val tx = load(v1Plain)
        val input = tx.inputs[0]
        val unresolved = tx.copy(inputs = listOf(input.copy(previousOutpointAddress = null, previousOutpointAmount = null)))
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
    fun `what cannot be checked is unknown, a coinbase passes, a malformed signature fails`() {
        val tx = load(v0OneOut)
        val input = tx.inputs[0]
        // a coinbase has no signatures
        assertEquals(SignatureCheck.Valid, check(tx.copy(inputs = emptyList())))
        // a key-address input must carry a well-formed signature: a blank, short or wrongly pushed
        // one is a failure (AND-042), and the id rebuilds, so it is forged
        for (script in listOf("", "00", input.signatureScript.drop(2), "40" + input.signatureScript.drop(2))) {
            assertEquals(script, SignatureCheck.Invalid(0), check(tx.copy(inputs = listOf(input.copy(signatureScript = script)))))
        }
        // a sighash type other than SIGHASH_ALL can't be checked here: unknown, never a pass
        assertEquals(SignatureCheck.Unknown, check(tx.copy(inputs = listOf(input.copy(signatureScript = input.signatureScript.dropLast(2) + "81")))))
        // a wrong claimed id: a failing signature can't be called forged
        val flipped = input.signatureScript.substring(0, 10) + (if (input.signatureScript[10] == '0') '1' else '0') + input.signatureScript.substring(11)
        assertEquals(SignatureCheck.Unknown, check(tx.copy(transactionId = "00".repeat(32), inputs = listOf(input.copy(signatureScript = flipped)))))
        // an output without script data, a malformed covenant id, an address that doesn't decode
        assertEquals(SignatureCheck.Unknown, check(tx.copy(outputs = listOf(tx.outputs[0].copy(scriptPublicKey = "")))))
        assertEquals(SignatureCheck.Unknown, check(tx.copy(outputs = listOf(tx.outputs[0].copy(covenantAuthorizingInput = "0", covenantId = "abcd")))))
        assertEquals(SignatureCheck.Unknown, check(tx.copy(inputs = listOf(input.copy(previousOutpointAddress = "kaspa:nope")))))
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
