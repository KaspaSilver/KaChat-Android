package com.kachat.app.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every alias a contact has been seen sending to us under, per wallet.
 *
 * A 1:1 message is found on the indexer by (sender, alias), so an alias this device does not know
 * is a conversation it cannot hear. The contact row keeps ONE alias - the last handshake's - but
 * a peer can hold several: iOS keeps every legacy alias it has ever had for a conversation and
 * sends under the alphabetically first, which need not be the last one it announced, and aliases
 * restored or generated later are never announced at all. iOS's own receive path queries all of
 * its incoming aliases (ChatService.incomingAliases); this is Android's copy of that set, filled
 * from every handshake and from the peer's own transactions (see ChatRepository.discoverPeerAliases).
 */
@Singleton
class PeerAliasStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("kachat_peer_aliases", Context.MODE_PRIVATE)

    private fun key(wallet: String, contact: String) = "${wallet.lowercase()}|${contact.lowercase()}"

    fun aliases(wallet: String, contact: String): Set<String> =
        prefs.getStringSet(key(wallet, contact), null)?.toSet().orEmpty()

    /** Adds [alias]; returns true when it was new. */
    @Synchronized
    fun add(wallet: String, contact: String, alias: String): Boolean {
        val clean = alias.trim()
        if (clean.isEmpty()) return false
        val current = aliases(wallet, contact)
        if (clean in current) return false
        prefs.edit().putStringSet(key(wallet, contact), current + clean).apply()
        return true
    }

    // The slow lane for old-style aliases (iOS 00d4919): when the contact last sent on an old
    // (pre-deterministic) alias, and whether they have ever used the deterministic one.

    /** Block time of the contact's newest message on an old-style alias, 0 when none seen. */
    fun lastLegacyIncomingAtMs(wallet: String, contact: String): Long =
        prefs.getLong("legacy_in|" + key(wallet, contact), 0L)

    @Synchronized
    fun noteLegacyIncoming(wallet: String, contact: String, blockTimeMs: Long) {
        if (blockTimeMs > lastLegacyIncomingAtMs(wallet, contact)) {
            prefs.edit().putLong("legacy_in|" + key(wallet, contact), blockTimeMs).apply()
        }
    }

    /** True once a message from the contact arrived on the deterministic alias. */
    fun usesDeterministic(wallet: String, contact: String): Boolean =
        prefs.getBoolean("det|" + key(wallet, contact), false)

    fun noteDeterministicIncoming(wallet: String, contact: String) {
        if (!usesDeterministic(wallet, contact)) prefs.edit().putBoolean("det|" + key(wallet, contact), true).apply()
    }
}
