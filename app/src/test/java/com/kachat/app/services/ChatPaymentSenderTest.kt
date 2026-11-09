package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS's deferral of a chat payment with no confirmed coins (ChatService `nextNoInputRetryDelay`,
 *  `isNoConfirmedInputsError`). */
class ChatPaymentSenderTest {

    @Test
    fun `the retry delay doubles from one second up to sixty, plus its jitter`() {
        assertEquals(1.0, ChatPaymentSender.noInputRetryDelaySeconds(1, 0.0), 0.0)
        assertEquals(2.0, ChatPaymentSender.noInputRetryDelaySeconds(2, 0.0), 0.0)
        assertEquals(32.0, ChatPaymentSender.noInputRetryDelaySeconds(6, 0.0), 0.0)
        assertEquals(60.0, ChatPaymentSender.noInputRetryDelaySeconds(7, 0.0), 0.0)
        assertEquals(60.0, ChatPaymentSender.noInputRetryDelaySeconds(10_000, 0.0), 0.0)
        assertEquals(1.25, ChatPaymentSender.noInputRetryDelaySeconds(1, 0.25), 1e-9)
    }

    @Test
    fun `only having no confirmed coins defers`() {
        assertTrue(ChatPaymentSender.isNoConfirmedInputsError("No spendable UTXOs available"))
        assertTrue(ChatPaymentSender.isNoConfirmedInputsError(ChatPaymentSender.NO_SPENDABLE_FUNDS_YET))
        assertFalse(ChatPaymentSender.isNoConfirmedInputsError("Insufficient funds for payment"))
        assertFalse(ChatPaymentSender.isNoConfirmedInputsError("Selected UTXOs are no longer available - please reselect"))
        assertFalse(ChatPaymentSender.isNoConfirmedInputsError(null))
    }
}
