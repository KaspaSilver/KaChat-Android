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

    // MARK: .kachat contract vs quantum (KIP-22) P2SH (iOS 0b4e27e)

    // Quantum addresses 0 and 1 of the public all-"abandon ... art" test mnemonic and their
    // redeem scripts (QuantumAddressTest's Python-checked vectors), and two key addresses.
    private val quantum0 = "kaspa:pp4csja82vqaa9hstf6ned49w8j2tl79jmwjk5v79999qxsrfkj9zvq7y9wme"
    private val redeem0 = "2008ddc68dcaad0b901868b764afe822a54e09e930064b21cc0846b4d08620136eac"
    private val redeem1 = "203bc3d2f941e0e3f87bdff4ac632974ed3378ccebf0cd2821ed7496334bccbdf8ac"
    private val keyA = "kaspa:qppt49jerlp2vjquvhfjcpzkna7g26jzu2r88wwsnt3zndq4qvnnywxtsehh8"
    private val keyB = "kaspa:qphqs4c490zy80q6rjlnetvyv3hclg8zz6h8l774rsprdprryz88xk3qp39k7"
    private val covenant = com.kachat.app.util.KaspaAddress.encode("kaspa", 0x08, ByteArray(32) { 7 })

    /** A KIP-22 spend's signature script: 0x41 <64-byte sig> <sighash> 0x22 <redeem>. */
    private fun kip22Script(redeemHex: String) = "41" + "ab".repeat(64) + "01" + "22" + redeemHex

    private fun input(address: String?, script: String) =
        com.kachat.app.services.TransactionInput("00".repeat(32), 0, address, 100_000_000L, script)

    private fun output(address: String, amount: Long = 1L) =
        com.kachat.app.services.TransactionOutput(amount, "", address)

    private fun tx(inputs: List<com.kachat.app.services.TransactionInput>, outputs: List<String>, payload: String? = null) =
        com.kachat.app.services.TransactionResponse("ff".repeat(32), inputs, outputs.map { output(it) }, 0L, payload)

    private fun asciiHex(text: String) = text.toByteArray().joinToString("") { "%02x".format(it) }

    private val schnorrScript = "41" + "cd".repeat(64) + "01"

    @Test
    fun `a payment paid from a quantum address is not a contract for the payee`() {
        val t = tx(listOf(input(quantum0, kip22Script(redeem0))), listOf(keyA, quantum0))
        org.junit.Assert.assertTrue(ChatRepository.isKip22Spend(t.inputs[0]))
        org.junit.Assert.assertFalse(ChatRepository.isKachatContractTransaction(t) { setOf(keyA.lowercase()) })
    }

    @Test
    fun `a covenant-sized P2SH input is a contract`() {
        val reveal = "41" + "ab".repeat(64) + "01" + "4c" + "50" + "51".repeat(80)
        val t = tx(listOf(input(covenant, reveal)), listOf(keyA))
        org.junit.Assert.assertFalse(ChatRepository.isKip22Spend(t.inputs[0]))
        org.junit.Assert.assertTrue(ChatRepository.isKachatContractTransaction(t) { setOf(keyA.lowercase()) })
    }

    @Test
    fun `a KIP-22-shaped script whose redeem hashes to another address is a contract`() {
        val t = tx(listOf(input(quantum0, kip22Script(redeem1))), listOf(keyA))
        org.junit.Assert.assertFalse(ChatRepository.isKip22Spend(t.inputs[0]))
        org.junit.Assert.assertTrue(ChatRepository.isKachatContractTransaction(t) { setOf(keyA.lowercase()) })
        // and one byte off the exact layout is not a KIP-22 spend either
        val badPush = "41" + "ab".repeat(64) + "01" + "23" + redeem0
        org.junit.Assert.assertFalse(ChatRepository.isKip22Spend(input(quantum0, badPush)))
        org.junit.Assert.assertFalse(ChatRepository.isKip22Spend(input(quantum0, kip22Script(redeem0) + "00")))
        org.junit.Assert.assertFalse(ChatRepository.isKip22Spend(input(null, kip22Script(redeem0))))
    }

    @Test
    fun `a received payment with P2SH change is not a contract`() {
        val t = tx(listOf(input(keyB, schnorrScript)), listOf(keyA, quantum0))
        org.junit.Assert.assertFalse(ChatRepository.isKachatContractTransaction(t) { setOf(keyA.lowercase()) })
    }

    @Test
    fun `our own send whose only outside output is P2SH is a contract (the commit)`() {
        val own = setOf(keyA.lowercase(), quantum0.lowercase())
        val commit = tx(listOf(input(keyA, schnorrScript)), listOf(covenant, keyA))
        org.junit.Assert.assertTrue(ChatRepository.isKachatContractTransaction(commit) { own })
        // the commit paid from a quantum address, change back to it
        val fromQuantum = tx(listOf(input(quantum0, kip22Script(redeem0))), listOf(covenant, quantum0))
        org.junit.Assert.assertTrue(ChatRepository.isKachatContractTransaction(fromQuantum) { own })
        // our payment to a key address with quantum change is a payment
        val payment = tx(listOf(input(quantum0, kip22Script(redeem0))), listOf(keyB, quantum0))
        org.junit.Assert.assertFalse(ChatRepository.isKachatContractTransaction(payment) { own })
        // and a send to ourselves only (no outside output) is not a contract
        val toSelf = tx(listOf(input(keyA, schnorrScript)), listOf(quantum0))
        org.junit.Assert.assertFalse(ChatRepository.isKachatContractTransaction(toSelf) { own })
    }

    @Test
    fun `a name or offer payload is a contract`() {
        val own = setOf(keyA.lowercase())
        val inputs = listOf(input(keyB, schnorrScript))
        org.junit.Assert.assertTrue(ChatRepository.isKachatContractTransaction(tx(inputs, listOf(keyA), asciiHex("kchat:1:name:register"))) { own })
        org.junit.Assert.assertTrue(ChatRepository.isKachatContractTransaction(tx(inputs, listOf(keyA), asciiHex("kchat:1:offer:make"))) { own })
        org.junit.Assert.assertFalse(ChatRepository.isKachatContractTransaction(tx(inputs, listOf(keyA), asciiHex("kchat:1:pay:x"))) { own })
    }

    @Test
    fun `the own set is never read when there is no P2SH output (iOS d80c497)`() {
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val own = { reads.incrementAndGet(); setOf(keyA.lowercase()) }
        // our own all-key send, a received payment, a plain payload: no P2SH output, no read
        org.junit.Assert.assertFalse(ChatRepository.isKachatContractTransaction(tx(listOf(input(keyA, schnorrScript)), listOf(keyB, keyA)), own))
        org.junit.Assert.assertFalse(ChatRepository.isKachatContractTransaction(tx(listOf(input(keyB, schnorrScript)), listOf(keyA)), own))
        org.junit.Assert.assertFalse(ChatRepository.isKachatContractTransaction(tx(listOf(input(keyB, schnorrScript)), listOf(keyA), asciiHex("kchat:1:pay:x")), own))
        // a quantum-paid payment to a key address only - the P2SH input is a KIP-22 spend
        org.junit.Assert.assertFalse(ChatRepository.isKachatContractTransaction(tx(listOf(input(quantum0, kip22Script(redeem0))), listOf(keyA)), own))
        org.junit.Assert.assertEquals(0, reads.get())
        // a P2SH output does read it
        org.junit.Assert.assertTrue(ChatRepository.isKachatContractTransaction(tx(listOf(input(keyA, schnorrScript)), listOf(covenant, keyA)), own))
        org.junit.Assert.assertEquals(1, reads.get())
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

    // MARK: run 17 (iOS 5a78d06) - "paid to me" from the script (AND-045), the node-only sender (AND-044)

    private fun fixture(txId: String): com.kachat.app.services.TransactionResponse {
        val stream = javaClass.classLoader!!.getResourceAsStream("kaspa-transactions/$txId.json") ?: error("missing fixture $txId")
        return stream.reader().use { com.google.gson.Gson().fromJson(it, com.kachat.app.services.TransactionResponse::class.java) }
    }

    /** A real mainnet payment (KaspaSignatureVerifierTest's v0 payment fixture). */
    private val realPayment = "1feb8b58162de1cac138a2061ce43c11f4d63d7b4195d4362016f3025d13e410"

    /** A wallet that is in no fixture, as a real address (its script is derived). */
    private val wallet = com.kachat.app.util.KaspaAddress.encode("kaspa", 0, ByteArray(32) { 5 })

    @Test
    fun `a real payment relabelled as ours only in the address label is not paid to us`() {
        val tx = fixture(realPayment)
        val payer = tx.inputs[0].previousOutpointAddress!!
        val relabelled = tx.copy(outputs = tx.outputs.mapIndexed { i, o -> if (i == 0) o.copy(scriptPublicKeyAddress = wallet) else o })
        // the labels are covered by nothing: every signature still verifies...
        assertEquals(
            com.kachat.app.util.KaspaSignatureVerifier.SignatureCheck.Valid,
            kotlinx.coroutines.runBlocking {
                com.kachat.app.util.KaspaSignatureVerifier.checkReceivedTransaction(relabelled, wallet) { _, _ -> null }.check
            }
        )
        // ...but the script says the output isn't ours: no payment to us
        assertEquals(PaymentTxDirection.None, ChatRepository.classifyPaymentTx(wallet, relabelled))
        // an output whose script IS ours pays us, whatever its label says
        val ours = tx.copy(outputs = tx.outputs.mapIndexed { i, o ->
            if (i == 0) o.copy(scriptPublicKey = com.kachat.app.util.KaspaAddress.getScriptPublicKey(wallet).uppercase(), scriptPublicKeyAddress = payer) else o
        })
        assertEquals(PaymentTxDirection.Incoming(payer, tx.outputs[0].amount), ChatRepository.classifyPaymentTx(wallet, ours))
        // with no script to read, the label is all there is
        val noScript = relabelled.copy(outputs = relabelled.outputs.mapIndexed { i, o -> if (i == 0) o.copy(scriptPublicKey = "") else o })
        assertEquals(PaymentTxDirection.Incoming(payer, tx.outputs[0].amount), ChatRepository.classifyPaymentTx(wallet, noScript))
    }

    @Test
    fun `an output labelled ours whose script isn't counts for nobody`() {
        val mine = "20" + "05".repeat(32) + "ac"
        val theirs = "20" + "09".repeat(32) + "ac"
        // our send: the relabelled output is neither our change nor the destination
        assertEquals(
            PaymentTxDirection.Outgoing(payee, 7L),
            ChatRepository.classifyPaymentTx(
                me, inputs = listOf(me), outputs = listOf(me to 1_000L, payee to 7L),
                outputScripts = listOf(theirs, theirs), myScriptHex = mine,
            )
        )
        assertEquals(
            PaymentTxDirection.None,
            ChatRepository.classifyPaymentTx(
                me, inputs = listOf(payer), outputs = listOf(me to 1_000L),
                outputScripts = listOf(theirs), myScriptHex = mine,
            )
        )
    }

    @Test
    fun `a node-confirmed payment files only with the indexer's agreeing sender`() {
        // no indexer row: held
        assertEquals(null, ChatRepository.nodeOnlyPaymentSender(payer, null))
        // the indexer names someone else: held
        assertEquals(null, ChatRepository.nodeOnlyPaymentSender(payer, payee))
        // both agree (case aside): filed from that sender
        assertEquals(payer, ChatRepository.nodeOnlyPaymentSender(payer.uppercase(), payer)?.lowercase())
        // REST named none: the indexer's
        assertEquals(payee, ChatRepository.nodeOnlyPaymentSender(null, payee))
    }

    @Test
    fun `the indexer's sender is read from the row with the same txid`() {
        val rows = listOf(
            com.kachat.app.services.PaymentIndexerResponse(txId = "AA11", sender = " ${payee.uppercase()} "),
            com.kachat.app.services.PaymentIndexerResponse(txId = "bb22", sender = null),
            com.kachat.app.services.PaymentIndexerResponse(txId = "cc33", sender = "  "),
            com.kachat.app.services.PaymentIndexerResponse(txId = null, sender = payer),
        )
        assertEquals(payee, ChatRepository.indexedPaymentSender(rows, "aa11"))
        assertEquals(null, ChatRepository.indexedPaymentSender(rows, "bb22"))
        assertEquals(null, ChatRepository.indexedPaymentSender(rows, "cc33"))
        assertEquals(null, ChatRepository.indexedPaymentSender(rows, "dd44"))
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
