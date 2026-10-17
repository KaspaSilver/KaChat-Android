package com.kachat.app.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRepositoryTest {

    @Test
    fun `fresh incoming handshake from an unknown sender is pending`() {
        assertEquals(
            "pending",
            ChatRepository.deriveIncomingHandshakeStatus(existingStatus = null, existingHandshakeComplete = false)
        )
    }

    @Test
    fun `incoming handshake is a reply if we already sent one to this contact`() {
        assertEquals(
            "active",
            ChatRepository.deriveIncomingHandshakeStatus(existingStatus = "pending", existingHandshakeComplete = true)
        )
    }

    @Test
    fun `already-active conversation stays active on a repeat handshake`() {
        assertEquals(
            "active",
            ChatRepository.deriveIncomingHandshakeStatus(existingStatus = "active", existingHandshakeComplete = false)
        )
    }

    @Test
    fun `a rejected contact re-requesting is treated as a fresh pending request`() {
        assertEquals(
            "pending",
            ChatRepository.deriveIncomingHandshakeStatus(existingStatus = "rejected", existingHandshakeComplete = false)
        )
    }

    @Test
    fun `their handshake marked as a response activates the conversation even if we never sent one`() {
        // e.g. we manually added them (or messaged them without a formal handshake) and they
        // themselves send a handshake back marking it as a reply — that should clear our
        // pending/request-to-connect state even though existingHandshakeComplete is false.
        assertEquals(
            "active",
            ChatRepository.deriveIncomingHandshakeStatus(existingStatus = "pending", existingHandshakeComplete = false, incomingIsResponse = true)
        )
    }

    @Test
    fun `a fresh incoming handshake not marked as a response stays pending`() {
        assertEquals(
            "pending",
            ChatRepository.deriveIncomingHandshakeStatus(existingStatus = null, existingHandshakeComplete = false, incomingIsResponse = false)
        )
    }

    @Test
    fun `contextual message payload decodes through the hex-then-base64 double encoding`() {
        // Real payload captured live from indexer.kasia.fyi's contextual-messages/by-sender —
        // decodes to a 65-byte EncryptedMessage (12-byte nonce + 33-byte ephemeral pubkey +
        // 20-byte ciphertext+tag), confirming comm payloads are hex(base64(bytes)), unlike
        // handshake payloads which are hex(bytes) directly.
        val hexPayload = "557a36566f3558644144555a7948304b41317a556f6d5a6e584976783562746a496d74" +
            "5176686670343938675464565a556e2f4c4b56327a6944716d74384841764472724c634d3934" +
            "4f325164597a624457504e536d383d"

        val decoded = ChatRepository.decodeContextualMessagePayload(hexPayload)

        assertEquals(65, decoded.size)
    }

    @Test
    fun `formats a whole KAS amount without trailing zeros`() {
        assertEquals("1", ChatRepository.formatKas(100_000_000L))
    }

    @Test
    fun `formats a fractional KAS amount trimmed to significant digits`() {
        assertEquals("3.98962", ChatRepository.formatKas(398_962_000L))
    }

    @Test
    fun `formats a small dust amount correctly`() {
        assertEquals("0.00000001", ChatRepository.formatKas(1L))
    }

    @Test
    fun `hex-encodes the ASCII bytes of an alias string, not its raw hex bytes`() {
        // 'a'=0x61, 'b'=0x62, '1'=0x31, '2'=0x32 — the indexer expects the alias's ASCII
        // bytes hex-encoded, not the 6 raw bytes the 12-char hex string itself represents.
        assertEquals("61623132", ChatRepository.hexEncodeAscii("ab12"))
    }

    @Test
    fun `hex-encodes a real 12-char alias`() {
        assertEquals(24, ChatRepository.hexEncodeAscii("f0313b15fd24").length)
    }

    @Test
    fun `script (P2SH) addresses are contracts, key addresses are not`() {
        val payload = ByteArray(32) { it.toByte() }
        val p2sh = com.kachat.app.util.KaspaAddress.encode("kaspatest", 0x08, payload)
        val key = com.kachat.app.util.KaspaAddress.encode("kaspatest", 0x00, payload)
        org.junit.Assert.assertTrue(ChatRepository.isScriptAddress(p2sh))
        org.junit.Assert.assertTrue(ChatRepository.isScriptAddress(com.kachat.app.util.KaspaAddress.encode("kaspa", 0x08, payload)))
        org.junit.Assert.assertFalse(ChatRepository.isScriptAddress(key))
        org.junit.Assert.assertFalse(ChatRepository.isScriptAddress(null))
        org.junit.Assert.assertFalse(ChatRepository.isScriptAddress("not an address"))
    }

    // MARK: first contact, whatever the address case (iOS 1e07e72, audit IOS-008)

    private val me = "kaspa:qrme0000000000000000000000000000000000000000000000000000000"
    private val them = "kaspa:qpthem00000000000000000000000000000000000000000000000000000"

    /** The messages table as SQLite would answer it: stored contactIds lowered in SQL. */
    private fun receivedFrom(vararg storedContactIds: String): suspend (String) -> Boolean = { lowered ->
        storedContactIds.any { it.lowercase() == lowered }
    }

    @Test
    fun `an established contact typed in upper case gets no inbox tag`() = kotlinx.coroutines.runBlocking {
        var askedWith: String? = null
        val tag = ChatRepository.firstContactInboxTagFor(
            address = them.uppercase(),
            me = me,
            state = ChatRequestState(),
            hasReceivedFrom = { askedWith = it; receivedFrom(them)(it) },
            inboxSupported = { true },
        )
        org.junit.Assert.assertNull(tag)
        assertEquals(them, askedWith) // the lookup is handed the lowercased address
    }

    @Test
    fun `a stored mixed-case conversation is found from a lower-case address`() = kotlinx.coroutines.runBlocking {
        val tag = ChatRepository.firstContactInboxTagFor(
            address = them,
            me = me,
            state = ChatRequestState(),
            hasReceivedFrom = receivedFrom("KASPA:QPTHEM00000000000000000000000000000000000000000000000000000"),
            inboxSupported = { true },
        )
        org.junit.Assert.assertNull(tag)
    }

    @Test
    fun `a true first contact still carries the tag, computed on the lowercased address`() = kotlinx.coroutines.runBlocking {
        val tag = ChatRepository.firstContactInboxTagFor(
            address = them.uppercase(),
            me = me,
            state = ChatRequestState(),
            hasReceivedFrom = receivedFrom("kaspa:qpsomeoneelse"),
            inboxSupported = { true },
        )
        assertEquals(com.kachat.app.util.InboxTag.compute(them), tag)
    }

    @Test
    fun `self, private and already-tagged addresses are untagged whatever the case`() = kotlinx.coroutines.runBlocking {
        val none = receivedFrom()
        org.junit.Assert.assertNull(ChatRepository.firstContactInboxTagFor(me.uppercase(), me, ChatRequestState(), none) { true })
        org.junit.Assert.assertNull(
            ChatRepository.firstContactInboxTagFor(them.uppercase(), me, ChatRequestState(privateChats = setOf(them)), none) { true }
        )
        org.junit.Assert.assertNull(
            ChatRepository.firstContactInboxTagFor(them.uppercase(), me, ChatRequestState(inboxTagged = setOf(them)), none) { true }
        )
    }

    // MARK: payment direction (iOS fetchPaymentsFromKaspaAPI) - what a fresh import restores

    private val payee = "kaspa:qqpayee0000000000000000000000000000000000000000000000000000"
    private val payer = "kaspa:qqpayer0000000000000000000000000000000000000000000000000000"

    @Test
    fun `a payment we sent is outgoing to its destination, not to our change`() {
        val direction = ChatRepository.classifyPaymentTx(
            me,
            inputs = listOf(me, me),
            outputs = listOf(payee to 98_868_482L, me to 5_000_000_000L)
        )
        assertEquals(PaymentTxDirection.Outgoing(payee, 98_868_482L), direction)
    }

    @Test
    fun `the smaller of two foreign outputs is the payment, the larger change`() {
        val change = "kaspa:qqchange000000000000000000000000000000000000000000000000000"
        val direction = ChatRepository.classifyPaymentTx(
            me,
            inputs = listOf(me),
            outputs = listOf(change to 9_000_000_000L, payee to 100_000_000L)
        )
        assertEquals(PaymentTxDirection.Outgoing(payee, 100_000_000L), direction)
    }

    @Test
    fun `a payment to us from someone else is incoming from the first foreign input`() {
        val direction = ChatRepository.classifyPaymentTx(
            me,
            inputs = listOf(null, payer),
            outputs = listOf(me to 100_000_000L, payer to 42L)
        )
        assertEquals(PaymentTxDirection.Incoming(payer, 100_000_000L), direction)
    }

    @Test
    fun `any input of ours makes it ours to have sent, whatever its position or case`() {
        val direction = ChatRepository.classifyPaymentTx(
            me,
            inputs = listOf(payer, me.uppercase()),
            outputs = listOf(payee to 7L, me to 100L)
        )
        assertEquals(PaymentTxDirection.Outgoing(payee, 7L), direction)
    }

    @Test
    fun `a move back to ourselves is no payment, and nothing resolved is retried`() {
        assertEquals(
            PaymentTxDirection.None,
            ChatRepository.classifyPaymentTx(me, inputs = listOf(me), outputs = listOf(me to 100L))
        )
        assertEquals(
            PaymentTxDirection.None,
            ChatRepository.classifyPaymentTx(me, inputs = listOf(payer), outputs = listOf(payee to 100L))
        )
        assertEquals(
            PaymentTxDirection.Unresolved,
            ChatRepository.classifyPaymentTx(me, inputs = listOf(null, ""), outputs = listOf(me to 100L))
        )
    }

    // MARK: KNS domain transfers in the payment scan (iOS ingestKNSTransferMessage)

    /** A reveal input's signature script, built the way the app's own reveal is
     *  (KnsInscriptionEngine + KaspaTransactionSigner.signRevealInput): push(sig + SIGHASH_ALL)
     *  then push(redeem script carrying [json]). */
    private fun revealSignatureScript(json: String): String {
        val redeem = com.kachat.app.util.KnsInscriptionScript.buildRedeemScript(ByteArray(32) { 7 }, "kns", json.toByteArray())
        val sig = byteArrayOf(0x41) + ByteArray(64) { 1 } + byteArrayOf(0x01)
        val script = sig + com.kachat.app.util.KnsInscriptionScript.canonicalPush(redeem)
        return script.joinToString("") { "%02x".format(it) }
    }

    private val transferJson = """{"op":"transfer","p":"domain","id":"asset123","to":"$payee"}"""

    @Test
    fun `a domain transfer reveal is recognised and parsed`() {
        val script = revealSignatureScript(transferJson)
        org.junit.Assert.assertTrue(ChatRepository.isKnsRevealSignatureScript(script))
        assertEquals(
            KnsTransferOperation("asset123", payee),
            ChatRepository.parseKnsTransferOperation(listOf("41" + "00".repeat(65), script))
        )
    }

    @Test
    fun `other KNS operations and ordinary inputs are no transfer`() {
        val plainSig = "41" + "11".repeat(64) + "01"
        org.junit.Assert.assertFalse(ChatRepository.isKnsRevealSignatureScript(plainSig))
        org.junit.Assert.assertNull(ChatRepository.parseKnsTransferOperation(listOf(plainSig, null)))
        val profile = revealSignatureScript("""{"op":"addProfile","id":"asset123","key":"bio","value":"hi"}""")
        org.junit.Assert.assertTrue(ChatRepository.isKnsRevealSignatureScript(profile))
        org.junit.Assert.assertNull(ChatRepository.parseKnsTransferOperation(listOf(profile)))
        val otherProtocol = revealSignatureScript("""{"op":"transfer","p":"token","id":"asset123","to":"$payee"}""")
        org.junit.Assert.assertNull(ChatRepository.parseKnsTransferOperation(listOf(otherProtocol)))
        val noId = revealSignatureScript("""{"op":"transfer","p":"domain","id":" ","to":"$payee"}""")
        org.junit.Assert.assertNull(ChatRepository.parseKnsTransferOperation(listOf(noId)))
    }

    @Test
    fun `a transfer is ours only as its recipient or signer`() {
        val mine = setOf(me.lowercase())
        // we signed it: sent, in the chat with the recipient
        assertEquals(true to payee, ChatRepository.knsTransferCounterparty(me, payee, mine, listOf(me), listOf(me)))
        // we are the recipient: received, from the first input that is not us
        assertEquals(false to payer, ChatRepository.knsTransferCounterparty(me, me, mine, listOf(payer), listOf(me)))
        // received with no foreign input: the first output that is neither us nor the recipient
        assertEquals(false to payee, ChatRepository.knsTransferCounterparty(me, me, mine, emptyList(), listOf(me, payee)))
        // a stranger's transfer seen in passing is nobody's business here (iOS leak fix)
        org.junit.Assert.assertNull(ChatRepository.knsTransferCounterparty(me, payee, mine, listOf(payer), listOf(payee, payer)))
    }

    @Test
    fun `domain transfer wording matches iOS`() {
        assertEquals("Sent alice.kas domain", ChatRepository.knsTransferMessageText("alice.kas", isOutgoing = true))
        assertEquals("Received alice.kas domain", ChatRepository.knsTransferMessageText("alice.kas", isOutgoing = false))
        assertEquals("Sent domain transfer", ChatRepository.knsTransferMessageText(null, isOutgoing = true))
        assertEquals("Received domain transfer", ChatRepository.knsTransferMessageText(" ", isOutgoing = false))
    }

    // MARK: payments held for their signature check (iOS 5806362 heldPaymentTxIds)

    @Test
    fun `held payments are due for 7 days, at most 25 a sync, then given up`() {
        val now = 10_000_000_000L
        val day = 86_400_000L
        val held = mapOf("bb" to now - day, "aa" to now - 7 * day, "cc" to now - 7 * day - 1)
        val (due, expired) = ChatRepository.heldPaymentsDue(held, now)
        assertEquals(listOf("aa", "bb"), due)
        assertEquals(listOf("cc"), expired)

        val many = (0 until 40).associate { "%02d".format(it) to now }
        val (first, none) = ChatRepository.heldPaymentsDue(many, now)
        assertEquals((0 until 25).map { "%02d".format(it) }, first)
        assertEquals(emptyList<String>(), none)
    }
}
