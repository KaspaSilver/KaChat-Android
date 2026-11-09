package com.kachat.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardWipeTest {
    @Test
    fun `a clip Android hides from the app is wiped, as the app is in the background`() {
        assertTrue(shouldWipeClip(null))
    }

    @Test
    fun `the app's own secret is wiped`() {
        assertTrue(shouldWipeClip("KaChat secret: recovery phrase"))
    }

    @Test
    fun `something the user copied since is left alone`() {
        assertFalse(shouldWipeClip("address"))
        assertFalse(shouldWipeClip(""))
    }
}
