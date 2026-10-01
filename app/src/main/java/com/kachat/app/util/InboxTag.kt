package com.kachat.app.util

import java.security.MessageDigest

/**
 * No-handshake first contact (NO_HANDSHAKE_MESSAGING.md, iOS 1ff3006).
 *
 * The tag a first-contact message carries so its recipient can find it: the first 16 bytes of
 * SHA-256("kachat-inbox:v1:" + the recipient's lowercased address), as lowercase hex. Every
 * platform must produce the same 32 characters as iOS's `InboxTag.compute(for:)`.
 */
object InboxTag {
    fun compute(address: String): String {
        val input = "kachat-inbox:v1:" + address.trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.copyOfRange(0, 16).joinToString("") { "%02x".format(it) }
    }
}
