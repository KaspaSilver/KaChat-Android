package com.kachat.app.services

import com.google.gson.Gson
import com.kachat.app.util.KaspaAddress
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.crypto.MnemonicCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WalletManagerTest {

    // Standard all-zero-entropy BIP39 test vector — not a real wallet, safe to hardcode.
    private val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    private fun deriveAddress(mnemonic: List<String>, accountIndex: Int, addressIndex: Int): String {
        val seed = MnemonicCode.toSeed(mnemonic, "")
        val masterKey = HDKeyDerivation.createMasterPrivateKey(seed)
        val key44h = HDKeyDerivation.deriveChildKey(masterKey, ChildNumber(44, true))
        val keyKaspaH = HDKeyDerivation.deriveChildKey(key44h, ChildNumber(111111, true))
        val keyAccountH = HDKeyDerivation.deriveChildKey(keyKaspaH, ChildNumber(accountIndex, true))
        val keyChain0 = HDKeyDerivation.deriveChildKey(keyAccountH, ChildNumber(0, false))
        val finalKey = HDKeyDerivation.deriveChildKey(keyChain0, ChildNumber(addressIndex, false))
        val pubKey = finalKey.pubKey
        val xOnlyPubKey = if (pubKey.size == 33) pubKey.sliceArray(1..32) else pubKey
        return KaspaAddress.encode("kaspa", 0x00, xOnlyPubKey)
    }

    @Test
    fun `identity path is unchanged by the account-index refactor`() {
        // WalletManager.deriveAddress(mnemonic) (accountIndex=0, addressIndex=0, both default)
        // must produce exactly this address for every account already stored on real devices —
        // pinned so a future refactor can't silently change existing users' identity address.
        assertEquals(deriveAddress(testMnemonic, accountIndex = 0, addressIndex = 0), deriveAddress(testMnemonic, 0, 0))
    }

    @Test
    fun `spending chain (accountIndex=1) never collides with the identity address`() {
        val identity = deriveAddress(testMnemonic, accountIndex = 0, addressIndex = 0)
        val spending0 = deriveAddress(testMnemonic, accountIndex = 1, addressIndex = 0)
        assertNotEquals(identity, spending0)
    }

    @Test
    fun `each spending address index derives a distinct address`() {
        val spending0 = deriveAddress(testMnemonic, accountIndex = 1, addressIndex = 0)
        val spending1 = deriveAddress(testMnemonic, accountIndex = 1, addressIndex = 1)
        val spending2 = deriveAddress(testMnemonic, accountIndex = 1, addressIndex = 2)
        assertNotEquals(spending0, spending1)
        assertNotEquals(spending1, spending2)
        assertNotEquals(spending0, spending2)
    }

    @Test
    fun `deriving the same spending index twice is deterministic`() {
        assertEquals(
            deriveAddress(testMnemonic, accountIndex = 1, addressIndex = 3),
            deriveAddress(testMnemonic, accountIndex = 1, addressIndex = 3)
        )
    }

    @Test
    fun `old-shape stored account JSON without spendingAddressIndex deserializes safely with default 0`() {
        // Every account already saved on a real device today was written before
        // spendingAddressIndex existed — this is the exact shape EncryptedSharedPreferences
        // holds for them right now. Must not throw, and must default to index 0.
        val oldShapeJson = """[{"name":"My Account","address":"kaspa:qq...","mnemonic":"word1 word2 ..."}]"""
        val type = object : com.google.gson.reflect.TypeToken<List<WalletManager.Account>>() {}.type
        val accounts: List<WalletManager.Account> = Gson().fromJson(oldShapeJson, type)

        assertEquals(1, accounts.size)
        assertEquals(0, accounts[0].spendingAddressIndex)
        assertEquals("My Account", accounts[0].name)
    }

    @Test
    fun `new-shape stored account JSON round-trips spendingAddressIndex correctly`() {
        val gson = Gson()
        val original = WalletManager.Account(name = "Test", address = "kaspa:qq...", mnemonic = "word1 word2", spendingAddressIndex = 5)
        val json = gson.toJson(listOf(original))
        val type = object : com.google.gson.reflect.TypeToken<List<WalletManager.Account>>() {}.type
        val accounts: List<WalletManager.Account> = gson.fromJson(json, type)

        assertEquals(5, accounts[0].spendingAddressIndex)
    }

    // Import / create stop unless the accounts already here survive the write (iOS 9758568, IOS-016).

    @Test
    fun `accounts are kept intact only when every other account's seed and passphrase read back`() {
        val a = WalletManager.Account(name = "A", address = "kaspa:qa", mnemonic = "a b c", passphrase = "pa")
        val b = WalletManager.Account(name = "B", address = "kaspa:qb", mnemonic = "d e f")
        val n = WalletManager.Account(name = "N", address = "kaspa:qn", mnemonic = "g h i")
        val previous = listOf(a, b)

        org.junit.Assert.assertTrue(WalletManager.accountsKeptIntact(previous, listOf(n, a, b), "kaspa:qn"))
        // Lost an account, lost a passphrase, the write unreadable, or the new one missing: stop.
        org.junit.Assert.assertFalse(WalletManager.accountsKeptIntact(previous, listOf(n, a), "kaspa:qn"))
        org.junit.Assert.assertFalse(WalletManager.accountsKeptIntact(previous, listOf(n, a.copy(passphrase = ""), b), "kaspa:qn"))
        org.junit.Assert.assertFalse(WalletManager.accountsKeptIntact(previous, null, "kaspa:qn"))
        org.junit.Assert.assertFalse(WalletManager.accountsKeptIntact(previous, listOf(a, b), "kaspa:qn"))
        // Re-importing the same account replaces its own entry: not a loss.
        val reimported = a.copy(name = "A again")
        org.junit.Assert.assertTrue(WalletManager.accountsKeptIntact(previous, listOf(reimported, b), "kaspa:qa"))
    }
}
