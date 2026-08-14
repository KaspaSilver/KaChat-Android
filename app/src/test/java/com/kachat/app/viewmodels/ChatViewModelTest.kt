package com.kachat.app.viewmodels

import com.kachat.app.models.MessageEntity
import com.kachat.app.util.MessageProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelTest {

    private fun message(type: String, direction: String, deliveryStatus: String = "sent") = MessageEntity(
        id = "$type-$direction-${System.identityHashCode(Any())}",
        contactId = "kaspa:test",
        walletAddress = "kaspa:me",
        type = type,
        direction = direction,
        plaintextBody = "text",
        encryptedPayload = "deadbeef",
        amountSompi = null,
        blockTimestamp = 0L,
        deliveryStatus = deliveryStatus
    )

    @Test
    fun `warning shows on a brand-new chat, before anything has been sent`() {
        // Not gated on having already messaged them — the warning exists to be read *before*
        // typing into the void, so it's up from the moment the chat opens.
        assertTrue(ChatViewModel.shouldShowUnnotifiedWarning(emptyList()))
    }

    @Test
    fun `warning shows after sending a comm message with no reply`() {
        val messages = listOf(message(MessageProtocol.TYPE_COMM, "sent"))
        assertTrue(ChatViewModel.shouldShowUnnotifiedWarning(messages))
    }

    @Test
    fun `warning still shows when only they have messaged us`() {
        // Evidence in one direction isn't reciprocity — we still have no proof they can see us.
        val messages = listOf(message(MessageProtocol.TYPE_COMM, "received"))
        assertTrue(ChatViewModel.shouldShowUnnotifiedWarning(messages))
    }

    @Test
    fun `warning clears once both sides have sent a real message`() {
        val messages = listOf(
            message(MessageProtocol.TYPE_COMM, "sent"),
            message(MessageProtocol.TYPE_COMM, "received")
        )
        assertFalse(ChatViewModel.shouldShowUnnotifiedWarning(messages))
    }

    @Test
    fun `a handshake in either direction is not evidence of communication`() {
        val sentHandshake = listOf(
            message(MessageProtocol.TYPE_COMM, "sent"),
            message(MessageProtocol.TYPE_HANDSHAKE, "sent")
        )
        val receivedHandshake = listOf(
            message(MessageProtocol.TYPE_COMM, "sent"),
            message(MessageProtocol.TYPE_HANDSHAKE, "received")
        )
        val bothHandshakes = listOf(
            message(MessageProtocol.TYPE_HANDSHAKE, "sent"),
            message(MessageProtocol.TYPE_HANDSHAKE, "received")
        )
        assertTrue(ChatViewModel.shouldShowUnnotifiedWarning(sentHandshake))
        assertTrue(ChatViewModel.shouldShowUnnotifiedWarning(receivedHandshake))
        assertTrue(ChatViewModel.shouldShowUnnotifiedWarning(bothHandshakes))
    }

    @Test
    fun `a payment counts as a real message even with no note`() {
        val messages = listOf(
            message(MessageProtocol.TYPE_COMM, "sent"),
            message(MessageProtocol.TYPE_PAY, "received").copy(plaintextBody = null)
        )
        assertFalse(ChatViewModel.shouldShowUnnotifiedWarning(messages))
    }

    @Test
    fun `a payment in one direction alone does not clear the warning`() {
        val messages = listOf(message(MessageProtocol.TYPE_PAY, "received"))
        assertTrue(ChatViewModel.shouldShowUnnotifiedWarning(messages))
    }

    @Test
    fun `an empty-bodied comm message is not evidence of communication`() {
        val messages = listOf(
            message(MessageProtocol.TYPE_COMM, "sent"),
            message(MessageProtocol.TYPE_COMM, "received").copy(plaintextBody = "  ")
        )
        assertTrue(ChatViewModel.shouldShowUnnotifiedWarning(messages))
    }

    @Test
    fun `retry is offered for a failed sent comm message`() {
        val failed = message(MessageProtocol.TYPE_COMM, "sent", deliveryStatus = "failed")
        assertTrue(ChatViewModel.shouldShowRetryOption(failed))
    }

    @Test
    fun `retry is not offered for a sent message that is still pending`() {
        val pending = message(MessageProtocol.TYPE_COMM, "sent", deliveryStatus = "pending")
        assertFalse(ChatViewModel.shouldShowRetryOption(pending))
    }

    @Test
    fun `retry is not offered for a successfully sent message`() {
        val sent = message(MessageProtocol.TYPE_COMM, "sent", deliveryStatus = "sent")
        assertFalse(ChatViewModel.shouldShowRetryOption(sent))
    }

    @Test
    fun `retry is never offered for a failed payment`() {
        val failedPayment = message(MessageProtocol.TYPE_PAY, "sent", deliveryStatus = "failed")
        assertFalse(ChatViewModel.shouldShowRetryOption(failedPayment))
    }

    @Test
    fun `retry is never offered for a received message regardless of status`() {
        val received = message(MessageProtocol.TYPE_COMM, "received", deliveryStatus = "failed")
        assertFalse(ChatViewModel.shouldShowRetryOption(received))
    }

    @Test
    fun `alias can be auto-set to a domain when it was never set`() {
        assertTrue(ChatViewModel.canAutoUpdateAliasToDomain(null))
    }

    @Test
    fun `a real custom nickname is never auto-overwritten`() {
        assertFalse(ChatViewModel.canAutoUpdateAliasToDomain("Mom"))
        assertFalse(ChatViewModel.canAutoUpdateAliasToDomain("My Best Friend"))
    }

    @Test
    fun `once any domain is associated with a contact, auto-refresh never touches it again`() {
        // Regression: previously this returned true because "oldname.kas" still "looks like" a
        // domain, so refreshKnsNamesForAllContacts would silently revert an explicit non-primary
        // domain selection back to the contact's on-chain primary.
        assertFalse(ChatViewModel.canAutoUpdateAliasToDomain("oldname.kas", knsName = "oldname.kas"))
        assertFalse(ChatViewModel.canAutoUpdateAliasToDomain("chosen.kas", knsName = "chosen.kas"))
    }

    @Test
    fun `a contact linked to a system contact is never auto-overwritten, even with no alias`() {
        assertFalse(ChatViewModel.canAutoUpdateAliasToDomain(null, systemContactId = "lookup-key-1"))
    }

    @Test
    fun `a contact linked to a system contact is never auto-overwritten, even if the alias looks like a kns domain`() {
        assertFalse(ChatViewModel.canAutoUpdateAliasToDomain("oldname.kas", systemContactId = "lookup-key-1"))
    }
}
