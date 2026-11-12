package com.kachat.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS CallSignal (7c5c22e3): other clients' call messages are recognized so they can be dropped. */
class CallSignalTest {

    @Test
    fun `every call envelope type is a call signal`() {
        assertTrue(CallSignal.isCallSignal("""{"type":"call_invite","callId":"c1","server":"https://cloud.example","token":"t","video":false}"""))
        assertTrue(CallSignal.isCallSignal("""{"type":"call_request","callId":"c1","video":true}"""))
        assertTrue(CallSignal.isCallSignal("""{"type":"call_response","callId":"c1","accepted":false,"reason":"no_host"}"""))
        assertTrue(CallSignal.isCallSignal("""{"callId":"c1","type":"call_end","durationSeconds":12}"""))
        // a future call_* type, and surrounding whitespace
        assertTrue(CallSignal.isCallSignal("""  {"type":"call_ping"}
"""))
    }

    @Test
    fun `ordinary messages and near misses are not`() {
        assertFalse(CallSignal.isCallSignal(null))
        assertFalse(CallSignal.isCallSignal(""))
        assertFalse(CallSignal.isCallSignal("hello"))
        assertFalse(CallSignal.isCallSignal("""say {"type":"call_end"}"""))
        assertFalse(CallSignal.isCallSignal("""{"type":"chess_move","note":"call_me"}"""))
        assertFalse(CallSignal.isCallSignal("""{"type":"chess_move","note":"\"call_me\""}"""))
        assertFalse(CallSignal.isCallSignal("""{"type":123,"x":"call_"}"""))
        assertFalse(CallSignal.isCallSignal("""{"type":"call_end""""))
        assertFalse(CallSignal.isCallSignal("""{"type":"call_end"} trailing"""))
    }

    @Test
    fun `a body of 4096 bytes or more is never parsed`() {
        val padding = "x".repeat(4_096)
        assertFalse(CallSignal.isCallSignal("""{"type":"call_end","pad":"$padding"}"""))
        // multi-byte characters count as their UTF-8 bytes, as on iOS
        val wide = "é".repeat(2_040)
        assertFalse(CallSignal.isCallSignal("""{"type":"call_end","pad":"$wide"}"""))
    }

    @Test
    fun `the cached answer is the same answer`() {
        val body = """{"type":"call_end","callId":"cache"}"""
        assertTrue(CallSignal.isCallSignal(body))
        assertTrue(CallSignal.isCallSignal(body))
    }
}
