package com.kachat.app.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS ChatMessage.isHiddenPlaceholder (c130dc1): cross-device and undecryptable rows never show. */
class HiddenPlaceholderTest {

    private fun row(body: String?) = MessageEntity(
        id = "tx", contactId = "kaspa:contact", walletAddress = "kaspa:me", type = "comm",
        direction = "received", plaintextBody = body, encryptedPayload = "", amountSompi = null,
        blockTimestamp = 1L,
    )

    @Test
    fun `the cross-device and undecryptable placeholders are hidden`() {
        assertTrue(MessageEntity.isHiddenPlaceholder(MessageEntity.SENT_VIA_OTHER_DEVICE_PLACEHOLDER))
        assertTrue(MessageEntity.isHiddenPlaceholder("[Encrypted message]"))
        assertTrue(row("[Encrypted message]").isHiddenPlaceholder)
    }

    @Test
    fun `real text, near misses and no body are not hidden`() {
        assertFalse(MessageEntity.isHiddenPlaceholder("hello"))
        assertFalse(MessageEntity.isHiddenPlaceholder("[Encrypted message] "))
        assertFalse(MessageEntity.isHiddenPlaceholder("[encrypted message]"))
        assertFalse(MessageEntity.isHiddenPlaceholder(""))
        assertFalse(MessageEntity.isHiddenPlaceholder(null))
        assertFalse(row(null).isHiddenPlaceholder)
    }

    @Test
    fun `only the cross-device one is a sent placeholder`() {
        assertFalse(MessageEntity.isSentPlaceholder(MessageEntity.UNREADABLE_PLACEHOLDER))
        assertTrue(MessageEntity.isSentPlaceholder(MessageEntity.SENT_VIA_OTHER_DEVICE_PLACEHOLDER))
    }

    @Test
    fun `the SQL filter list holds exactly the hidden bodies`() {
        assertEquals(
            setOf(MessageEntity.SENT_VIA_OTHER_DEVICE_PLACEHOLDER, MessageEntity.UNREADABLE_PLACEHOLDER),
            MessageEntity.HIDDEN_PLACEHOLDERS.toSet(),
        )
        assertTrue(MessageEntity.HIDDEN_PLACEHOLDERS.all { MessageEntity.isHiddenPlaceholder(it) })
    }
}
