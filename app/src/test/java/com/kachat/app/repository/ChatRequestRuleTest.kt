package com.kachat.app.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestRuleTest {
    private val me = "kaspa:qme"
    private val them = "kaspa:qthem"
    private val state = ChatRequestState(startedAt = 1_000L)

    private fun request(
        state: ChatRequestState = this.state,
        contactId: String = them,
        addedAt: Long = 2_000L,
        firstAt: Long? = 3_000L,
        anySent: Boolean = false,
    ) = ChatRequestStore.isMessageRequest(contactId, addedAt, me, state, firstAt, anySent)

    @Test
    fun `a stranger who wrote first after Message Requests started is a request`() {
        assertTrue(request())
    }

    @Test
    fun `existing chats, your own chat and anything you wrote to are not requests`() {
        assertFalse(request(firstAt = 500L))           // first message predates the feature
        assertFalse(request(addedAt = 500L))           // contact predates the feature
        assertFalse(request(firstAt = null))           // nothing in it yet
        assertFalse(request(anySent = true))           // writing to them is consent
        assertFalse(request(contactId = me))           // your own chat
    }

    @Test
    fun `accepted, private and blocked chats are not requests`() {
        assertFalse(request(state = state.copy(accepted = setOf(them))))
        assertFalse(request(state = state.copy(privateChats = setOf(them))))
        assertFalse(request(state = state.copy(blocked = setOf(them))))
    }
}
