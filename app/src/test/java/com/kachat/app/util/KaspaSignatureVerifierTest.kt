package com.kachat.app.util

import com.kachat.app.services.TransactionInput
import com.kachat.app.services.TransactionOutput
import com.kachat.app.services.TransactionResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KaspaSignatureVerifier] against real mainnet transactions, as api.kaspa.org's
 * `/transactions/{id}?resolve_previous_outpoints=light` served them (Oct 2026). Each one was also
 * checked with an independent reimplementation of iOS's `verifyTransactionSignatures`.
 */
class KaspaSignatureVerifierTest {

    private val zeroSubnet = "0000000000000000000000000000000000000000"

    /** e3faaa00…: one input, one output, no payload. */
    private fun oneInOneOut() = TransactionResponse(
        transactionId = "e3faaa000b3a9717247c72a61925bcf87307c63daba216d3e2491c83b5d2df60",
        inputs = listOf(
            TransactionInput(
                previousOutpointHash = "9816e49fae9a1ad37d76cc47cfe03816c93fab725d7689ad57306d06e6cb3aa2",
                previousOutpointIndex = 0,
                previousOutpointAddress = "kaspa:qqtk9u6alm8vz7tp5a425x9cacad7nezhpwmvz890yndpt9f07e0udw59l300",
                previousOutpointAmount = 39_051_523L,
                signatureScript = "411aeaddaf3fb694cdbc89e64473989ead6e91b5d4fb164cc91f962fd8c0da3db6c254205fdd3b0951f17bb6798500dabf192cb99ed2a69883346413d6e76b3f1201",
                sigOpCount = "1",
            )
        ),
        outputs = listOf(
            TransactionOutput(
                amount = 38_847_923L,
                scriptPublicKey = "20d85bd60b671b2ebbf9e19a741a7ed6a43f9400c8f2b146b092f5040bb869ceabac",
                scriptPublicKeyAddress = "kaspa:qrv9h4stvudjawleuxd8gxn766jrl9qqeretz34sjt6sgzacd882km8ngephg",
            )
        ),
        blockTime = 1792275794946L,
        payload = null,
        version = "0",
        subnetworkId = zeroSubnet,
    )

    /** 1feb8b58…: one input, a payment plus change. */
    private fun paymentWithChange() = TransactionResponse(
        transactionId = "1feb8b58162de1cac138a2061ce43c11f4d63d7b4195d4362016f3025d13e410",
        inputs = listOf(
            TransactionInput(
                previousOutpointHash = "88856e1cd602d7ce261655efbf82e6148764308ef1f37e786e8eea7d006121a7",
                previousOutpointIndex = 1,
                previousOutpointAddress = "kaspa:qzfmpw57trldaadplnufgrwnuzgeqmphcjtaue8rvj0f4c83yhc66mmq7369h",
                previousOutpointAmount = 136_743_575_690_636L,
                signatureScript = "411f0c39df7145a0dc1c05542b57e4c9c2aacf1242ed017be2fc4f0edc5107bd5b4179e94e1c40e8ab4c07a342647a73c9cd752128e8673ed0042e769c5d6a8e9c01",
                sigOpCount = "1",
            )
        ),
        outputs = listOf(
            TransactionOutput(
                amount = 1_567_961_000_000L,
                scriptPublicKey = "204d5b27bd8d48cb67715b3cb3abfa79a46f31dacb9837d061701b97af4b71153bac",
                scriptPublicKeyAddress = "kaspa:qpx4kfaa34yvkem3tv7t82l60xjx7vw6ewvr05rpwqde0t6twy2nk3lyljet6",
            ),
            TransactionOutput(
                amount = 135_175_614_484_836L,
                scriptPublicKey = "2093b0ba9e58fedef5a1fcf8940dd3e091906c37c497de64e3649e9ae0f125f1adac",
                scriptPublicKeyAddress = "kaspa:qzfmpw57trldaadplnufgrwnuzgeqmphcjtaue8rvj0f4c83yhc66mmq7369h",
            ),
        ),
        blockTime = null,
        payload = null,
        version = "0",
        subnetworkId = zeroSubnet,
    )

    /** 36671491…: a transaction carrying a payload (hashed into the sighash). */
    private fun withPayload() = TransactionResponse(
        transactionId = "366714913c41f726b12b980a7f22b63e9c4cebfc90aefecda443d75f0710652e",
        inputs = listOf(
            TransactionInput(
                previousOutpointHash = "3e7d46f370bfa419f2807656ceb9fb0a0975dd286d465cd5ed55c04d6ede9506",
                previousOutpointIndex = 0,
                previousOutpointAddress = "kaspa:qq8903f00vwqtaujqkfdpph36q0vpdq7qpa4ga2yhfv0e8exu59fk7s7a7g4q",
                previousOutpointAmount = 42_946_200_690L,
                signatureScript = "419aaca19581a9251cd2517d9bbacf4a202e74535c57422beec8f146af11ceadcaf7541dbbad07f1cac56f104bf44b8199da004aa97d7d3826d714d744f39834ed01",
                sigOpCount = "1",
            )
        ),
        outputs = listOf(
            TransactionOutput(
                amount = 42_946_009_410L,
                scriptPublicKey = "200e57c52f7b1c05f7920592d086f1d01ec0b41e007b547544ba58fc9f26e50a9bac",
                scriptPublicKeyAddress = "kaspa:qq8903f00vwqtaujqkfdpph36q0vpdq7qpa4ga2yhfv0e8exu59fk7s7a7g4q",
            )
        ),
        blockTime = null,
        payload = "6b6173706c61793a76313a7479636f6f6e2d666565643a626c61636b6f75743a6f6d656963683a6f6d6569636827735f6d696e655f6861645f615f706f7765725f626c61636b6f7574213a313739323237353837333a3064626539383862",
        version = "0",
        subnetworkId = zeroSubnet,
    )

    @Test
    fun `real mainnet signatures verify`() {
        assertNull(KaspaSignatureVerifier.invalidInputIndex(oneInOneOut()))
        assertNull(KaspaSignatureVerifier.invalidInputIndex(paymentWithChange()))
        assertNull(KaspaSignatureVerifier.invalidInputIndex(withPayload()))
    }

    @Test
    fun `a tampered signature fails`() {
        val tx = oneInOneOut()
        val sig = tx.inputs[0].signatureScript
        // flip one nibble inside the 64-byte signature
        val flipped = sig.substring(0, 10) + (if (sig[10] == '0') '1' else '0') + sig.substring(11)
        val tampered = tx.copy(inputs = listOf(tx.inputs[0].copy(signatureScript = flipped)))
        assertEquals(0, KaspaSignatureVerifier.invalidInputIndex(tampered))
    }

    @Test
    fun `a tampered output amount fails`() {
        val tx = paymentWithChange()
        val outputs = tx.outputs.toMutableList()
        outputs[0] = outputs[0].copy(amount = outputs[0].amount + 1)
        assertEquals(0, KaspaSignatureVerifier.invalidInputIndex(tx.copy(outputs = outputs)))
    }

    @Test
    fun `a redirected output fails`() {
        val tx = paymentWithChange()
        val outputs = tx.outputs.toMutableList()
        outputs[0] = outputs[0].copy(scriptPublicKey = tx.outputs[1].scriptPublicKey)
        assertEquals(0, KaspaSignatureVerifier.invalidInputIndex(tx.copy(outputs = outputs)))
    }

    @Test
    fun `a tampered spent amount or payload fails`() {
        val tx = withPayload()
        val input = tx.inputs[0]
        assertEquals(0, KaspaSignatureVerifier.invalidInputIndex(
            tx.copy(inputs = listOf(input.copy(previousOutpointAmount = input.previousOutpointAmount!! - 1)))
        ))
        assertEquals(0, KaspaSignatureVerifier.invalidInputIndex(tx.copy(payload = tx.payload!!.dropLast(2) + "00")))
    }

    @Test
    fun `what cannot be checked passes, as on iOS`() {
        val tx = oneInOneOut()
        val input = tx.inputs[0]
        // a non-standard signature script is not checked
        assertNull(KaspaSignatureVerifier.invalidInputIndex(tx.copy(inputs = listOf(input.copy(signatureScript = "00")))))
        // an input without its resolved address is left out; with none left there is nothing to check
        assertNull(KaspaSignatureVerifier.invalidInputIndex(tx.copy(inputs = listOf(input.copy(previousOutpointAddress = null)))))
        // an output without script data: the transaction cannot be rebuilt
        assertNull(KaspaSignatureVerifier.invalidInputIndex(tx.copy(outputs = listOf(tx.outputs[0].copy(scriptPublicKey = "")))))
        // an address that does not decode: the spent script cannot be rebuilt
        assertNull(KaspaSignatureVerifier.invalidInputIndex(tx.copy(inputs = listOf(input.copy(previousOutpointAddress = "kaspa:nope")))))
    }

    @Test
    fun `a signature valid for another transaction does not verify here`() {
        val a = oneInOneOut()
        val b = withPayload()
        val swapped = a.copy(inputs = listOf(a.inputs[0].copy(signatureScript = b.inputs[0].signatureScript)))
        assertFalse(KaspaSignatureVerifier.verifyTransactionSignatures(
            inputs = listOf(KaspaSignatureVerifier.VerificationInput(
                previousOutpointHash = swapped.inputs[0].previousOutpointHash,
                previousOutpointIndex = 0,
                signatureScript = swapped.inputs[0].signatureScript,
                previousOutpointAddress = swapped.inputs[0].previousOutpointAddress!!,
                previousOutpointAmount = swapped.inputs[0].previousOutpointAmount!!,
                sequence = 0,
                sigOpCount = 1,
            )),
            outputs = swapped.outputs.map { KaspaSignatureVerifier.VerificationOutput(it.amount, it.scriptPublicKey) },
            version = 0, lockTime = 0, subnetworkId = ByteArray(20), gas = 0, payload = ByteArray(0),
        ))
        assertTrue(KaspaSignatureVerifier.verifyTransactionSignatures(
            inputs = emptyList(), outputs = emptyList(),
            version = 0, lockTime = 0, subnetworkId = ByteArray(20), gas = 0, payload = ByteArray(0),
        ))
    }
}
