package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only the notification's own token answers a call; nothing another app could guess does. */
class CallAnswerTokenTest {

    @Test
    fun `a missing or guessed token never matches`() {
        assertFalse(CallAnswerToken.matches(null))
        assertFalse(CallAnswerToken.matches(""))
        assertFalse(CallAnswerToken.matches("0".repeat(64)))
        assertFalse(CallAnswerToken.matches("kachat_call_id"))
    }

    @Test
    fun `the process token is 32 random bytes in hex, stable, and matches itself only`() {
        val token = CallAnswerToken.token
        assertEquals(64, token.length)
        assertTrue(token.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(token, CallAnswerToken.token)
        assertTrue(CallAnswerToken.matches(token))
        assertFalse(CallAnswerToken.matches(token.dropLast(1)))
        assertFalse(CallAnswerToken.matches(token.uppercase()))
    }
}
