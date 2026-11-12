package com.kachat.app.services

import java.security.MessageDigest

/**
 * The canonical, newline-joined preimage every `/v1/push` request signs - a byte-for-byte match of
 * the server's `build_auth_preimage` (kasia-indexer push.rs) and of iOS
 * `PushNotificationManager.buildAuthPreimage`: `key=value` lines, SHA-256-hex sub-hashes over
 * canonicalized (trimmed, deduped, sorted) sets.
 *
 * Shapes:
 * - **LegacyV1** (no `watched_group_ids_hash` line): a request with no group ids.
 * - **TransitionalGroups**: the group line right after `watched_addresses_hash`, when group ids
 *   are registered (the server forces the group_v1 capability).
 * The call ring (`POST /v1/push/ring`) left with calls (iOS 7c5c22e3).
 * `capabilities_hash` and `auth_version` lines belong to V2 and are never emitted.
 */
object PushAuthPreimage {

    const val AUTH_DOMAIN_V1 = "kchat-push-auth:v1"

    fun build(
        method: String,
        path: String,
        deviceToken: String,
        watchedAddresses: List<String>,
        watchedGroupIds: List<String>,
        primaryAddress: String,
        aliases: List<String>,
        walletPubkey: String,
        walletAddress: String,
        nonce: String,
        timestampMs: Long,
        expiresAtMs: Long,
    ): String {
        val lines = mutableListOf(
            "domain=$AUTH_DOMAIN_V1",
            "nonce=${nonce.trim()}",
            "method=$method",
            "path=$path",
            // The server hashes the *normalized* device token; for FCM the normalized form is the
            // token verbatim (see push.rs normalize_device_token), so hash it as-is.
            "device_token_hash=${sha256Hex(deviceToken.trim())}",
            "watched_addresses_hash=${sha256Hex(canonicalizeAddresses(watchedAddresses).joinToString("\n"))}",
        )
        // TransitionalGroups: the server inserts this line right after watched_addresses_hash.
        // Group ids are lowercase hex, canonicalized like addresses (trim/lowercase/dedupe/sort).
        if (watchedGroupIds.isNotEmpty()) {
            lines += "watched_group_ids_hash=${sha256Hex(canonicalizeAddresses(watchedGroupIds).joinToString("\n"))}"
        }
        lines += listOf(
            "primary_address=$primaryAddress",
            "aliases_hash=${sha256Hex(canonicalizeAliases(aliases).joinToString("\n"))}",
            "wallet_pubkey=$walletPubkey",
            "wallet_address=$walletAddress",
            "timestamp_ms=$timestampMs",
            "expires_at_ms=$expiresAtMs",
        )
        return lines.joinToString("\n")
    }

    fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Addresses / group ids: trim, drop empty, lowercase, dedupe, sort ascending (canonicalize_set). */
    fun canonicalizeAddresses(values: List<String>): List<String> =
        values.map { it.trim() }.filter { it.isNotEmpty() }.map { it.lowercase() }.toSet().sorted()

    /** Aliases: trim, drop empty, case-preserved, dedupe, sort ascending. */
    fun canonicalizeAliases(values: List<String>): List<String> =
        values.map { it.trim() }.filter { it.isNotEmpty() }.toSet().sorted()
}
