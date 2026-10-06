package com.kachat.app.util

/**
 * Which alias a 1:1 chat's outgoing handshake and messages carry (audit XP-003,
 * DETERMINISTIC_ALIASES.md §4.4: a handshake's `alias` is the sender's actual outgoing alias, and
 * for a deterministic chat that is `deterministicTheirAlias`).
 *
 * - A new chat - and every chat with no alias of its own yet - uses the deterministic alias
 *   (WalletManager.theirDeterministicAlias): Desktop fetches a contact's messages under that one
 *   alias only, and it can be derived again from the seed after a reinstall. A random alias is
 *   never generated for a new chat any more.
 * - A chat that already has a legacy random alias of ours (written by a handshake from an older
 *   build) keeps it, so a peer already reading it is not cut off.
 */
object HandshakeAliasPolicy {

    /** The alias a handshake to a contact carries, and that becomes the contact's `myAlias`:
     *  its existing alias when it has one, otherwise [deterministicAlias]. */
    fun handshakeAlias(existingMyAlias: String?, deterministicAlias: String): String =
        existingMyAlias?.trim()?.takeIf { it.isNotEmpty() } ?: deterministicAlias

    /** The alias a message to a contact carries: the legacy alias of a pre-existing handshake chat,
     *  otherwise [deterministicAlias] (which a new handshake now also stores as `myAlias`). */
    fun messageAlias(handshakeComplete: Boolean, myAlias: String?, deterministicAlias: String): String =
        if (handshakeComplete) handshakeAlias(myAlias, deterministicAlias) else deterministicAlias

    /** True when [myAlias] is a legacy random alias, i.e. set and not the deterministic one. */
    fun isLegacyAlias(myAlias: String?, deterministicAlias: String): Boolean =
        !myAlias.isNullOrBlank() && myAlias.trim() != deterministicAlias
}
