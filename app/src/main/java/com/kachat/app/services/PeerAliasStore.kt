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
}
