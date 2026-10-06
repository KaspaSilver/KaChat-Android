package com.kachat.app.util

/**
 * Whether a push belongs to the other network than the one this launch runs on (audit AND-006,
 * port of iOS d946a65 / IOS-050).
 *
 * A device that moved between mainnet and testnet can still be registered with the push service
 * it left (until the superseded-service unregister reaches it), and that service keeps pushing.
 * The rule is iOS's `isOtherNetwork`: the push's `sender` address names a network by its prefix
 * (kaspa: / kaspatest:), and a push is the other network's only when that network is known and
 * differs. No sender, or one without a prefix, is not "other".
 *
 * A push may also say its network outright in a `network` field ("mainnet"/"testnet", or the
 * address prefix). The push service does not send one yet; it is read so that public-chat,
 * KaPosts and .kachat name pushes - which carry no sender - can be told apart once it does.
 */
object PushNetworkFilter {

    fun isOtherNetwork(data: Map<String, String>, launch: KaspaNetwork.Type): Boolean {
        val bySender = data["sender"]?.let { KaspaNetwork.ofAddress(it) }
        if (bySender != null && bySender != launch) return true
        val declared = networkNamed(data["network"])
        if (declared != null && declared != launch) return true
        return false
    }

    /** A `network` field's value as a network; null when absent or not one of the known names. */
    internal fun networkNamed(raw: String?): KaspaNetwork.Type? {
        val value = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return KaspaNetwork.Type.values().firstOrNull { value == it.raw || value == it.hrp }
    }
}
