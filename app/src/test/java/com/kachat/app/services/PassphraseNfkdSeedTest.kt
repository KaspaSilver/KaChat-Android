package com.kachat.app.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Audit XP-013: the BIP39 seed NFKD-normalizes the passphrase, as iOS, Desktop and the extension
 * do, so the same phrase and passphrase open the same account everywhere.
 */
class PassphraseNfkdSeedTest {

    // Standard all-zero-entropy BIP39 test vector - not a real wallet, safe to hardcode.
    private val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")

    private val cafeComposed = "café"      // é as U+00E9, what Android keyboards type
    private val cafeDecomposed = "café"   // e + U+0301 COMBINING ACUTE ACCENT

    /** BIP39 written out from the spec, independent of bitcoinj: PBKDF2-HMAC-SHA512, 2048
     *  rounds, password = UTF-8 NFKD(mnemonic), salt = UTF-8 ("mnemonic" + NFKD(passphrase)). */
    private fun bip39Reference(mnemonic: String, passphrase: String): ByteArray {
        val nfkd = { s: String -> java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD) }
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(nfkd(mnemonic).toByteArray(Charsets.UTF_8), "HmacSHA512"))
        val salt = ("mnemonic" + nfkd(passphrase)).toByteArray(Charsets.UTF_8)
        // One 64-byte block is the whole seed: U1 = HMAC(salt || INT(1)), Ui = HMAC(Ui-1).
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        repeat(2047) {
            u = mac.doFinal(u)
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        return out
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `cafe with a precomposed e gives the same seed as the decomposed form and the BIP39 reference`() {
        // The reference itself, checked against the published BIP39 vector (passphrase "TREZOR").
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            hex(bip39Reference(words.joinToString(" "), "TREZOR"))
        )

        val composed = WalletManager.seedFor(words, cafeComposed)
        val decomposed = WalletManager.seedFor(words, cafeDecomposed)
        assertArrayEquals(decomposed, composed)
        assertArrayEquals(bip39Reference(words.joinToString(" "), cafeDecomposed), composed)

        // The legacy derivation (records saved before the fix) hashed the passphrase as typed,
        // which is a different seed for a precomposed character - why those records keep it.
        val raw = WalletManager.seedFor(words, cafeComposed, WalletManager.PassphraseForm.RAW)
        assertFalse(raw.contentEquals(composed))
        assertEquals(WalletManager.PassphraseForm.RAW, WalletManager.passphraseFormOf(
            WalletManager.Account(name = "Old", address = "kaspa:qa", mnemonic = words.joinToString(" "), passphrase = cafeComposed)
        ))
        assertEquals(WalletManager.PassphraseForm.NFKD, WalletManager.passphraseFormOf(
            WalletManager.Account(name = "Old", address = "kaspa:qb", mnemonic = words.joinToString(" "), passphrase = "ascii pass")
        ))
    }
}
