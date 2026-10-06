package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Group key randomness never falls back to zeros (checked against iOS 2b2a4a7, audit IOS-005).
 *
 * iOS ignored SecRandomCopyBytes' status, so a failure left the zero-filled buffer as a group
 * seed. Android's [GroupCipher] fills its buffer with `SecureRandom.nextBytes`, which has no
 * status to ignore: it fills the array or throws, and the throw propagates out of
 * [GroupCipher.generateGroupSeed] / [GroupCipher.generateDeviceId], so group creation fails
 * rather than proceeding with zeros. These tests pin that the draws are full-length, never the
 * zero buffer, and never repeat.
 */
class GroupCipherRandomnessTest {

    @Test
    fun `group seeds are 32 random bytes, never all zero, never repeated`() {
        val seeds = (1..200).map { GroupCipher.generateGroupSeed() }
        seeds.forEach { seed ->
            assertEquals(32, seed.size)
            assertFalse(seed.all { it == 0.toByte() })
        }
        assertEquals(seeds.size, seeds.map { it.toList() }.toSet().size)
    }

    @Test
    fun `device ids are 16 random bytes, never all zero, never repeated`() {
        val ids = (1..200).map { GroupCipher.generateDeviceId() }
        ids.forEach { id ->
            assertEquals(16, id.size)
            assertFalse(id.all { it == 0.toByte() })
        }
        assertEquals(ids.size, ids.map { it.toList() }.toSet().size)
    }
}
