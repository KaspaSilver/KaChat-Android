package com.kachat.app.util

import org.bitcoinj.base.Base58
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.crypto.HDPath
import org.bitcoinj.crypto.LazyECPoint
import java.nio.ByteBuffer

/**
 * Parses a Kaspium-compatible "kpub..." extended public key (Base58Check, custom BIP32 version
 * bytes `0x038f332e` — confirmed against KasSigner's `bootloader/src/wallet/xpub.rs`) and derives
 * watch-only child addresses from it. No private key material is ever involved — this is the
 * entire point of the Cold Storage feature: KaChat never holds or needs the signing key.
 *
 * Bitcoinj's own `DeterministicKey.deserializeB58`/`deserialize` hard-reject any version-byte
 * header not tied to one of its built-in `Network`s, so a Kaspa kpub can't go through those —
 * instead the raw 78-byte BIP32 payload is decoded manually and fed into `DeterministicKey`'s
 * lower-level constructor that builds a public-only key from its raw components directly.
 */
object KaspaExtendedPublicKey {
    private val KPUB_VERSION = byteArrayOf(0x03, 0x8f.toByte(), 0x33, 0x2e)

    // version(4) + depth(1) + parentFingerprint(4) + childNumber(4) + chainCode(32) + pubkey(33)
    private const val PAYLOAD_SIZE = 78

    data class ParsedKpub(
        val depth: Int,
        val parentFingerprint: Int,
        val childNumber: Int,
        val chainCode: ByteArray,
        val compressedPubKey: ByteArray
    )

    fun parse(kpubString: String): Result<ParsedKpub> {
        return try {
            val payload = Base58.decodeChecked(kpubString.trim())
            if (payload.size != PAYLOAD_SIZE) {
                return Result.failure(IllegalArgumentException("Unexpected kpub payload size: ${payload.size}"))
            }
            if (!payload.copyOfRange(0, 4).contentEquals(KPUB_VERSION)) {
                return Result.failure(IllegalArgumentException("Not a Kaspa kpub (wrong version bytes)"))
            }
            // BIP32 extended-key fields are big-endian (unlike Kaspa's own little-endian tx wire format).
            val buffer = ByteBuffer.wrap(payload)
            Result.success(
                ParsedKpub(
                    depth = payload[4].toInt() and 0xFF,
                    parentFingerprint = buffer.getInt(5),
                    childNumber = buffer.getInt(9),
                    chainCode = payload.copyOfRange(13, 45),
                    compressedPubKey = payload.copyOfRange(45, 78)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isValidKpub(kpubString: String): Boolean = parse(kpubString).isSuccess

    /** A pubkey-only bitcoinj key rooted at the imported kpub's own level — relative child indices from here on. */
    fun toDeterministicKey(parsed: ParsedKpub): DeterministicKey {
        return DeterministicKey(
            HDPath.M(),
            parsed.chainCode,
            LazyECPoint(parsed.compressedPubKey),
            null,
            parsed.depth,
            parsed.parentFingerprint
        )
    }

    /**
     * Derives address `chain/index` (chain 0 = external/receive, 1 = internal/change) from the
     * kpub's own root — non-hardened public derivation only, exactly what BIP32 allows without
     * ever needing a private key. Mirrors [com.kachat.app.services.WalletManager]'s own
     * pubkey-to-address conversion (strip the leading parity byte off the 33-byte compressed
     * key, encode the remaining x-only 32 bytes as a Schnorr (version 0) address).
     */
    fun deriveChildAddress(rootKey: DeterministicKey, chain: Int, index: Int, prefix: String = KaspaNetwork.hrp): String {
        val chainKey = HDKeyDerivation.deriveChildKey(rootKey, ChildNumber(chain, false))
        val addressKey = HDKeyDerivation.deriveChildKey(chainKey, ChildNumber(index, false))
        val pubKey = addressKey.pubKey
        val xOnlyPubKey = if (pubKey.size == 33) pubKey.sliceArray(1..32) else pubKey
        return KaspaAddress.encode(prefix, 0x00, xOnlyPubKey)
    }
}
