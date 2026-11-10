package com.kachat.app.models

import com.kachat.app.services.NfcCardBackupPayload
import com.kachat.app.services.wireName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Import's wallet-type detection (iOS da467165 `findImportAccounts`): which account it opens. */
class ImportAccountCandidateTest {

    private fun candidate(family: WalletSourceFamily, index: Int) =
        ImportAccountCandidate(family, index, "kaspa:${family.name.lowercase()}$index")

    /** What import derives: every family over its search depth, in family order. */
    private val derived = WalletSourceFamily.entries.flatMap { family ->
        family.importSearchDepth.map { candidate(family, it) }
    }

    private fun address(family: WalletSourceFamily, index: Int) = candidate(family, index).address

    @Test
    fun `nothing with funds or history gives an empty list, so import falls back to Standard #0`() {
        val ranked = ImportAccountCandidate.ranked(derived, balances = emptyMap(), withHistory = emptySet())
        assertTrue(ranked.isEmpty())
        // The pick the passphrase step keeps when the list is empty (or the search can't run).
        assertEquals(ImportAccountChoice(WalletSourceFamily.KASPA_STANDARD, 0), ImportAccountChoice())
    }

    @Test
    fun `the wallet type holding the most comes first`() {
        val balances = mapOf(
            address(WalletSourceFamily.KASPA_STANDARD, 3) to 100L,
            address(WalletSourceFamily.KASPA_LEGACY_972, 0) to 500L,
        )
        val ranked = ImportAccountCandidate.ranked(derived, balances, emptySet())
        assertEquals(
            listOf(
                ImportAccountChoice(WalletSourceFamily.KASPA_LEGACY_972, 0),
                ImportAccountChoice(WalletSourceFamily.KASPA_STANDARD, 3),
            ),
            ranked.map { it.choice },
        )
        assertEquals(500L, ranked.first().balanceSompi)
    }

    @Test
    fun `a type's total is what decides, not its largest address`() {
        val balances = mapOf(
            address(WalletSourceFamily.ONE_KEY, 0) to 300L,
            address(WalletSourceFamily.KASPA_STANDARD, 1) to 200L,
            address(WalletSourceFamily.KASPA_STANDARD, 7) to 200L,
        )
        val ranked = ImportAccountCandidate.ranked(derived, balances, emptySet())
        assertEquals(WalletSourceFamily.KASPA_STANDARD, ranked.first().family)
        assertEquals(1, ranked.first().index)
    }

    @Test
    fun `each type's addresses stay in index order`() {
        val balances = mapOf(
            address(WalletSourceFamily.KASPA_STANDARD, 9) to 1_000L,
            address(WalletSourceFamily.KASPA_STANDARD, 2) to 1L,
        )
        val ranked = ImportAccountCandidate.ranked(derived, balances, emptySet())
        assertEquals(listOf(2, 9), ranked.map { it.index })
    }

    @Test
    fun `history alone keeps an address, and ties go to the earlier type`() {
        val history = setOf(
            address(WalletSourceFamily.ONE_KEY, 0),
            address(WalletSourceFamily.CHAINGE, 0),
            address(WalletSourceFamily.KASPA_STANDARD, 0),
        )
        val ranked = ImportAccountCandidate.ranked(derived, emptyMap(), history)
        assertEquals(
            listOf(WalletSourceFamily.KASPA_STANDARD, WalletSourceFamily.CHAINGE, WalletSourceFamily.ONE_KEY),
            ranked.map { it.family },
        )
        assertTrue(ranked.all { it.hasHistory && it.balanceSompi == 0L })
    }

    @Test
    fun `funds beat history`() {
        val ranked = ImportAccountCandidate.ranked(
            derived,
            balances = mapOf(address(WalletSourceFamily.CHAINGE, 0) to 1L),
            withHistory = setOf(address(WalletSourceFamily.KASPA_STANDARD, 0)),
        )
        assertEquals(WalletSourceFamily.CHAINGE, ranked.first().family)
        assertEquals(WalletSourceFamily.KASPA_STANDARD, ranked[1].family)
    }

    @Test
    fun `search depths match iOS, and Chainge has only address #0`() {
        assertEquals(0..49, WalletSourceFamily.KASPA_STANDARD.importSearchDepth)
        assertEquals(0..19, WalletSourceFamily.KASPA_LEGACY_972.importSearchDepth)
        assertEquals(0..19, WalletSourceFamily.ONE_KEY.importSearchDepth)
        assertEquals(0..0, WalletSourceFamily.CHAINGE.importSearchDepth)
    }

    @Test
    fun `family order is iOS's allCases, which the tie-break relies on`() {
        assertEquals(
            listOf(
                WalletSourceFamily.KASPA_STANDARD,
                WalletSourceFamily.KASPA_LEGACY_972,
                WalletSourceFamily.CHAINGE,
                WalletSourceFamily.ONE_KEY,
            ),
            WalletSourceFamily.entries.toList(),
        )
    }

    @Test
    fun `a row's id tells wallet types apart at the same index`() {
        assertTrue(candidate(WalletSourceFamily.KASPA_STANDARD, 0).id != candidate(WalletSourceFamily.CHAINGE, 0).id)
    }

    @Test
    fun `an NFC card's chainge family reads back as Chainge`() {
        assertEquals("chainge", WalletSourceFamily.CHAINGE.wireName)
        val payload = NfcCardBackupPayload(
            words = emptyList(),
            chattingAddress = "kaspa:test",
            family = "chainge",
            chattingAddressIndex = 0,
            alias = "",
        )
        assertEquals(WalletSourceFamily.CHAINGE, payload.sourceFamily)
    }
}
