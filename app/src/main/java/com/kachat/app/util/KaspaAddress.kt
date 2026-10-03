package com.kachat.app.util

/**
 * Kaspa Bech32 (CashAddr) implementation.
 */
object KaspaAddress {
    private const val ALPHABET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

    /**
     * Encodes a Kaspa address.
     * @param prefix The network prefix (e.g., "kaspa", "kaspatest")
     * @param version The version byte (0x00 for Schnorr, 0x01 for ECDSA, 0x08 for P2SH)
     * @param payload The raw public key or script hash bytes
     */
    fun encode(prefix: String, version: Byte, payload: ByteArray): String {
        val versionAndPayload = byteArrayOf(version) + payload
        val data5Bit = convertBits(versionAndPayload, 8, 5, true)
        val checksum = calculateChecksum(prefix, data5Bit)

        val combined = data5Bit + checksum
        val encodedData = combined.map { ALPHABET[it.toInt()].toString() }.joinToString("")

        return "$prefix:$encodedData"
    }

    /**
     * Shortens a full address to "prefix:xxxx....xxxx" for display when there's no alias/KNS
     * name to show instead — e.g. "kaspa:qypx....a83f".
     */
    fun shortDisplay(address: String): String {
        val parts = address.split(":", limit = 2)
        if (parts.size != 2) return address
        val (prefix, body) = parts
        return if (body.length <= 12) "$prefix:$body" else "$prefix:${body.take(4)}....${body.takeLast(4)}"
    }

    /**
     * Basic validation of a Kaspa address string.
     */
    /**
     * What a scanned QR code (or a pasted payment link) holds, as an address to put in a field
     * (iOS handleScannedQRCode):
     * trimmed, and a payment URI's query (`kaspa:ADDRESS?amount=X`, `kaspatest:...`) dropped -
     * wallets and faucets put those in their QR codes, and the raw text never validated, so a
     * scanned address sat in the field with Add greyed out. A code written all in capitals
     * (QR alphanumeric mode) is lowercased, since bech32 addresses are lowercase.
     */
    fun fromScanned(code: String): String {
        var address = code.trim()
        // KaChat for iPhone writes its receive / chatting-address QR codes as "kaspa:" + address
        // unless the address already starts with "kaspa:" - a testnet address doesn't, so its
        // code reads "kaspa:kaspatest:...". A "kaspa:" in front of another prefix is dropped.
        while (true) {
            val l = address.lowercase()
            if (l.startsWith("kaspa:") && (l.startsWith("kaspa:kaspatest:") || l.startsWith("kaspa:kaspa:"))) {
                address = address.substring("kaspa:".length)
            } else break
        }
        val lower = address.lowercase()
        if (lower.startsWith("kaspa:") || lower.startsWith("kaspatest:")) {
            address = address.substringBefore('?')
            if (address == address.uppercase()) address = address.lowercase()
        }
        return address
    }

    fun isValid(address: String): Boolean {
        return try {
            decode(address)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Decodes a Kaspa address.
     * @return Pair(version, payload)
     */
    fun decode(address: String): Pair<Byte, ByteArray> {
        val parts = address.split(":")
        if (parts.size != 2) throw IllegalArgumentException("Invalid address format")
        
        val prefix = parts[0]
        val encodedData = parts[1]
        
        val data5Bit = encodedData.map { char ->
            val index = ALPHABET.indexOf(char)
            if (index == -1) throw IllegalArgumentException("Invalid character: $char")
            index.toByte()
        }.toByteArray()
        
        if (data5Bit.size < 8) throw IllegalArgumentException("Address too short")
        
        val checksum = data5Bit.takeLast(8).toByteArray()
        val dataWithoutChecksum = data5Bit.dropLast(8).toByteArray()
        
        val calculatedChecksum = calculateChecksum(prefix, dataWithoutChecksum)
        if (!checksum.contentEquals(calculatedChecksum)) {
            throw IllegalArgumentException("Checksum mismatch")
        }
        
        val versionAndPayload = convertBits(dataWithoutChecksum, 5, 8, false)
        if (versionAndPayload.isEmpty()) throw IllegalArgumentException("Empty payload")
        
        return versionAndPayload[0] to versionAndPayload.drop(1).toByteArray()
    }

    /**
     * Returns the scriptPublicKey hex for a given address.
     */
    fun getScriptPublicKey(address: String): String {
        val (version, payload) = decode(address)
        val payloadHex = payload.joinToString("") { "%02x".format(it) }

        return when (version.toInt()) {
            // Version 0 (Schnorr): <push-32> <32-byte pubkey> OP_CHECKSIG. The push opcode
            // (0x20 = "push the next 32 bytes") doubles as the length byte here since it's a
            // direct push (opcodes 0x01-0x4b directly encode their own byte count).
            0 -> "20" + payloadHex + "ac"
            // Version 1 (ECDSA): <push-33> <33-byte pubkey> OP_CHECKSIG(ECDSA)
            1 -> "21" + payloadHex + "ab"
            // Version 8 (P2SH): OP_BLAKE2B <push-N> <N-byte script hash> OP_EQUAL — unlike the
            // P2PK cases, OP_BLAKE2B (0xAA) is a real opcode, not a push, so the push-length byte
            // must be explicit. Verified against the iOS reference's identical construction
            // (`Bech32.swift`'s `scriptPublicKey(from:)`, `.scriptHash` case) — this was missing
            // here and caused the network to reject every P2SH output as "non-standard script form".
            8 -> "aa" + "%02x".format(payload.size) + payloadHex + "87"
            else -> throw IllegalArgumentException("Unsupported address version: $version")
        }
    }

    /**
     * Inverse of [getScriptPublicKey]'s version-0 (Schnorr) case: recovers the address from a raw
     * scriptPublicKey hex string of the standard `<push-32><32-byte pubkey>OP_CHECKSIG` form.
     * Used to resolve a broadcast message's sender — a broadcast is a self-stash transaction, so
     * its own output's scriptPublicKey directly encodes the sender's address. Returns null for
     * anything else (ECDSA/P2SH outputs, or malformed scripts) — broadcast senders are expected to
     * use ordinary Schnorr addresses like any other KaChat/Kasia wallet.
     */
    fun addressFromScriptPublicKey(scriptPublicKeyHex: String, prefix: String = KaspaNetwork.hrp): String? {
        if (scriptPublicKeyHex.length != 68) return null // "20" + 64 hex chars + "ac" = 68
        if (!scriptPublicKeyHex.startsWith("20") || !scriptPublicKeyHex.endsWith("ac")) return null
        val pubKeyHex = scriptPublicKeyHex.substring(2, 66)
        val pubKeyBytes = try {
            pubKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            return null
        }
        return encode(prefix, 0x00, pubKeyBytes)
    }

    private fun calculateChecksum(prefix: String, data: ByteArray): ByteArray {
        val expandedPrefix = expandPrefix(prefix)
        val polyInput = expandedPrefix + byteArrayOf(0) + data + ByteArray(8)
        val poly = polyMod(polyInput)

        val checksum = ByteArray(8)
        for (i in 0 until 8) {
            checksum[i] = ((poly shr (5 * (7 - i))) and 0x1F).toByte()
        }
        return checksum
    }

    private fun polyMod(data: ByteArray): Long {
        var c: Long = 1
        for (v in data) {
            val c0 = (c shr 35).toInt()
            c = ((c and 0x07FFFFFFFFL) shl 5) xor v.toLong()

            if (c0 and 0x01 != 0) c = c xor 0x98f2bc8e61L
            if (c0 and 0x02 != 0) c = c xor 0x79b76d99e2L
            if (c0 and 0x04 != 0) c = c xor 0xf33e5fb3c4L
            if (c0 and 0x08 != 0) c = c xor 0xae2eabe2a8L
            if (c0 and 0x10 != 0) c = c xor 0x1e4f43e470L
        }
        return c xor 1
    }

    private fun expandPrefix(prefix: String): ByteArray {
        return ByteArray(prefix.length) { (prefix[it].code and 0x1F).toByte() }
    }

    private fun convertBits(data: ByteArray, from: Int, to: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Byte>()
        val maxv = (1 shl to) - 1
        for (value in data) {
            val b = value.toInt() and 0xff
            acc = (acc shl from) or b
            bits += from
            while (bits >= to) {
                bits -= to
                result.add(((acc shr bits) and maxv).toByte())
            }
        }
        if (pad) {
            if (bits > 0) result.add(((acc shl (to - bits)) and maxv).toByte())
        }
        return result.toByteArray()
    }
}
