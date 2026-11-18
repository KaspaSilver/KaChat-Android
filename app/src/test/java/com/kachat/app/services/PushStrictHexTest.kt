package com.kachat.app.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** iOS 83806b3d: the hex parser takes hex digits only - no sign, no odd length. */
class PushStrictHexTest {
    @Test
    fun `hex digits of either case parse`() {
        assertArrayEquals(byteArrayOf(0x0a, 0xff.toByte(), 0x10), PushRegistrationManager.strictHexToBytes("0aFf10"))
        assertArrayEquals(byteArrayOf(), PushRegistrationManager.strictHexToBytes(""))
    }

    @Test
    fun `a sign is refused`() {
        assertThrows(IllegalArgumentException::class.java) { PushRegistrationManager.strictHexToBytes("+a") }
        assertThrows(IllegalArgumentException::class.java) { PushRegistrationManager.strictHexToBytes("-a00") }
    }

    @Test
    fun `odd length and non-hex are refused`() {
        assertThrows(IllegalArgumentException::class.java) { PushRegistrationManager.strictHexToBytes("abc") }
        assertThrows(IllegalArgumentException::class.java) { PushRegistrationManager.strictHexToBytes("zz") }
        // full-width digits are digits to Character.digit, not hex
        assertThrows(IllegalArgumentException::class.java) { PushRegistrationManager.strictHexToBytes("１２") }
    }
}
