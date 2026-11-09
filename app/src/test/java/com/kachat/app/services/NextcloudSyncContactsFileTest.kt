package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NextcloudSyncContactsFileTest {
    private val me = "kaspa:qq5mm25hhy3xgde5eaqcxn7jusmryncpy5uykhx0faedla6kly0xxheqpy36r"
    private val someoneElse = "kaspa:qzkwy29a6gh0969ae59d96ya80zevjtetdftcxd4veczp6p8fczfsadq6w977"

    private fun envelope(hint: String?, version: Int = BackupCrypto.ENVELOPE_VERSION): String {
        val hintField = hint?.let { "\"walletHint\":\"$it\"," } ?: ""
        return "{\"kachatEncryptedBackup\":$version,$hintField\"nonce\":\"AA==\",\"ciphertext\":\"AA==\"}"
    }

    private val fails: (String) -> String = { throw IllegalStateException("won't decrypt") }

    @Test
    fun `this wallet's readable file opens`() {
        assertEquals("contacts", NextcloudSyncService.openContactsFile(envelope(BackupCrypto.walletHint(me)), me) { "contacts" })
    }

    @Test
    fun `this wallet's unreadable file is the one to replace`() {
        assertNull(NextcloudSyncService.openContactsFile(envelope(BackupCrypto.walletHint(me)), me, fails))
    }

    @Test
    fun `another wallet's file is never touched`() {
        assertThrows(Exception::class.java) {
            NextcloudSyncService.openContactsFile(envelope(BackupCrypto.walletHint(someoneElse)), me) { "contacts" }
        }
    }

    @Test
    fun `an unreadable file with no hint is never touched`() {
        assertThrows(Exception::class.java) { NextcloudSyncService.openContactsFile(envelope(null), me, fails) }
    }

    @Test
    fun `a plain file or a newer envelope is never touched`() {
        assertThrows(Exception::class.java) { NextcloudSyncService.openContactsFile("{\"type\":\"kachat-address-book\"}", me) { it } }
        assertThrows(Exception::class.java) { NextcloudSyncService.openContactsFile("", me) { it } }
        assertThrows(Exception::class.java) {
            NextcloudSyncService.openContactsFile(envelope(BackupCrypto.walletHint(me), BackupCrypto.ENVELOPE_VERSION + 1), me) { it }
        }
    }
}
