package com.kachat.app.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** Saved copies of a superseded default indexer move to indexer.kachat.app; custom ones stay. */
class IndexerUrlMigrationTest {
    private val current = AppSettingsRepository.DEFAULT_INDEXER_URL

    @Test
    fun `the default is indexer kachat app`() {
        assertEquals("https://indexer.kachat.app", current)
        assertEquals(current, AppSettingsRepository.DEFAULT_PUSH_INDEXER_URL)
    }

    @Test
    fun `superseded defaults move to the current one`() {
        assertEquals(current, AppSettingsRepository.migratedIndexerUrl("https://kachat.duckdns.org"))
        assertEquals(current, AppSettingsRepository.migratedIndexerUrl("https://kachat.duckdns.org/"))
        assertEquals(current, AppSettingsRepository.migratedIndexerUrl(" HTTPS://KACHAT.DUCKDNS.ORG "))
        assertEquals(current, AppSettingsRepository.migratedIndexerUrl("kachat.duckdns.org"))
        assertEquals(current, AppSettingsRepository.migratedIndexerUrl("https://tnkachat.duckdns.org:7443"))
        assertEquals(current, AppSettingsRepository.migratedIndexerUrl("https://indexer.kasia.fyi"))
        assertEquals(current, AppSettingsRepository.migratedIndexerUrl("https://indexer.kasia.wtf"))
    }

    @Test
    fun `the retired testnet indexer reads as none`() {
        assertEquals("", AppSettingsRepository.testnetIndexerUrl("https://tnkachat.duckdns.org:7443"))
        assertEquals("", AppSettingsRepository.testnetIndexerUrl("https://TNKACHAT.duckdns.org:7443/"))
        assertEquals("", AppSettingsRepository.testnetIndexerUrl(null))
        assertEquals("", AppSettingsRepository.testnetIndexerUrl("  "))
        assertEquals("", AppSettingsRepository.testnetIndexerUrl("tnkachat.duckdns.org:7443"))
        assertEquals(current, AppSettingsRepository.testnetIndexerUrl("https://kachat.duckdns.org"))
        assertEquals("https://my.testnet.example", AppSettingsRepository.testnetIndexerUrl("https://my.testnet.example"))
    }

    @Test
    fun `custom indexers are kept`() {
        assertEquals(current, AppSettingsRepository.migratedIndexerUrl(current))
        assertEquals("https://my.indexer.example", AppSettingsRepository.migratedIndexerUrl("https://my.indexer.example"))
        assertEquals("https://kachat.duckdns.org:8443", AppSettingsRepository.migratedIndexerUrl("https://kachat.duckdns.org:8443"))
    }
}
