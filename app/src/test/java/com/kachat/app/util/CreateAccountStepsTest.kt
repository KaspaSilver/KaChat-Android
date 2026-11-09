package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Import Account's name and length steps (iOS dd0aab1). */
class CreateAccountStepsTest {

    @Test
    fun `the name step needs a name that isn't only spaces`() {
        assertFalse(CreateAccountSteps.canContinue(""))
        assertFalse(CreateAccountSteps.canContinue("   \n\t"))
        assertTrue(CreateAccountSteps.canContinue("Savings"))
        assertTrue(CreateAccountSteps.canContinue("  a "))
    }

    @Test
    fun `the name is saved trimmed`() {
        assertEquals("My Wallet", CreateAccountSteps.accountName("  My Wallet \n"))
    }

    @Test
    fun `the lengths are 12 then 24 words`() {
        assertEquals(listOf(12, 24), CreateAccountSteps.WORD_COUNTS)
    }

    @Test
    fun `import's length step waits for a length to be chosen`() {
        assertFalse(CreateAccountSteps.canChooseImportLength(null))
        assertTrue(CreateAccountSteps.canChooseImportLength(12))
        assertTrue(CreateAccountSteps.canChooseImportLength(24))
        assertFalse(CreateAccountSteps.canChooseImportLength(18))
    }

    @Test
    fun `the words screen starts at the chosen length`() {
        assertEquals(12, CreateAccountSteps.importWordCount(12))
        assertEquals(24, CreateAccountSteps.importWordCount(24))
        assertEquals(24, CreateAccountSteps.importWordCount(0))
    }
}
