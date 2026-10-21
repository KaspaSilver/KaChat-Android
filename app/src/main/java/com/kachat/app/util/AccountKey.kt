package com.kachat.app.util

/**
 * Whether a private key is the one behind an account's chatting address (iOS
 * ChatService.isCurrentAccountKey, dfaba68): the key's x-only Schnorr public key against the
 * address's. A sync that started before an account switch still holds the old account's
 * address while the wallet hands out the NEW account's key - deriving aliases or decrypting
 * with it and filing the result under the old address mixes two accounts' conversations (with
 * two accounts on one bot, each fetched the bot's messages meant for the other). A chatting
 * address that isn't a Schnorr key (version 0, 32 bytes) can't be checked this way and passes,
 * as on iOS; an address that doesn't decode, or a key that doesn't derive, fails.
 */
object AccountKey {
    /** The last key checked and its x-only public key: a sync asks once per contact, always
     *  about the same key, and deriving it is a full point multiplication. */
    @Volatile private var memo: Pair<ByteArray, ByteArray>? = null

    fun belongsTo(privateKey: ByteArray, accountAddress: String): Boolean {
        val (version, payload) = try { KaspaAddress.decode(accountAddress.trim()) } catch (e: Exception) { return false }
        if (version != 0.toByte() || payload.size != 32) return true
        val derived = memo?.takeIf { it.first.contentEquals(privateKey) }?.second
            ?: try {
                Schnorr.publicKeyXOnly(privateKey).also { memo = privateKey.copyOf() to it }
            } catch (e: Exception) {
                return false
            }
        return derived.contentEquals(payload)
    }
}
