package com.kachat.app.services

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import com.google.gson.Gson
import com.kachat.app.models.WalletSourceFamily
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client for a KaChat-NFC card (iOS 8d93d7ab, `NFCCard`): an optional backup of a seed phrase the
 * user already has, saved and restored by tapping a Java Card. The card never generates or uses a
 * key. It holds bytes this file has already encrypted, behind a PIN. The commands and backup format
 * are public, in docs/PROTOCOL.md of https://github.com/KaspaSilver/KaChat-NFC, so a card works
 * across apps.
 */
object NfcCard {
    private val APPLET_AID = byteArrayOf(
        0xF0.toByte(), 0x4B, 0x41, 0x43, 0x48, 0x41, 0x54, 0x4E, 0x46, 0x43, 0x01,
    )

    const val PIN_LENGTH = 6
    private const val SCAN_TIMEOUT_MS = 60_000L
    private const val CARD_TIMEOUT_MS = 5_000

    /** A phone with no NFC can't read these cards (iOS bfe9f022: in production builds since 2026-11-04). */
    fun isAvailable(context: Context): Boolean =
        NfcAdapter.getDefaultAdapter(context) != null

    fun isValidPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

    /**
     * Runs [work] against one card, for the length of one tap. Slow work such as key derivation
     * belongs outside it. Android has no system sheet for this as iOS does, so the caller shows
     * the tap prompt while this runs and cancels the coroutine to stop waiting.
     */
    suspend fun <T> run(activity: Activity, work: suspend (NfcCardConnection) -> T): T {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: throw NfcCardException.Unavailable()
        if (!adapter.isEnabled) throw NfcCardException.NfcOff()
        val found = CompletableDeferred<Tag>()
        adapter.enableReaderMode(
            activity,
            { tag -> found.complete(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
        try {
            val tag = withTimeoutOrNull(SCAN_TIMEOUT_MS) { found.await() } ?: throw NfcCardException.TimedOut()
            val card = IsoDep.get(tag) ?: throw NfcCardException.NotLoaded()
            return withContext(Dispatchers.IO) {
                try {
                    card.connect()
                    card.timeout = CARD_TIMEOUT_MS
                    work(NfcCardConnection(card, APPLET_AID))
                } catch (e: IOException) {
                    throw NfcCardException.CardRemoved()
                } finally {
                    runCatching { card.close() }
                }
            }
        } finally {
            runCatching { adapter.disableReaderMode(activity) }
        }
    }
}

sealed class NfcCardException(
    @androidx.annotation.StringRes private val messageRes: Int,
    private vararg val formatArgs: Any,
) : Exception() {
    /** The sentence shown to the person, in the phone's language. */
    fun userMessage(context: android.content.Context): String = context.getString(messageRes, *formatArgs)

    class Unavailable : NfcCardException(com.kachat.app.R.string.nfc_err_unavailable)
    class NfcOff : NfcCardException(com.kachat.app.R.string.nfc_err_nfc_off)
    class TimedOut : NfcCardException(com.kachat.app.R.string.nfc_err_timed_out)
    class CardRemoved : NfcCardException(com.kachat.app.R.string.nfc_err_card_removed)
    class NotLoaded : NfcCardException(com.kachat.app.R.string.nfc_err_not_loaded)
    class WrongPin(val triesRemaining: Int) : NfcCardException(
        if (triesRemaining == 1) com.kachat.app.R.string.nfc_err_wrong_pin_one
        else com.kachat.app.R.string.nfc_err_wrong_pin_n,
        triesRemaining,
    )
    class PinBlocked : NfcCardException(com.kachat.app.R.string.nfc_err_pin_blocked)
    class NoBackup : NfcCardException(com.kachat.app.R.string.nfc_err_no_backup)
    class NoPin : NfcCardException(com.kachat.app.R.string.nfc_err_no_pin)
    class CardChanged : NfcCardException(com.kachat.app.R.string.nfc_err_card_changed)
    class BackupTooLarge : NfcCardException(com.kachat.app.R.string.nfc_err_backup_too_large)
    class WrongPassphrase : NfcCardException(com.kachat.app.R.string.nfc_err_wrong_passphrase)
    class NoPassphrase : NfcCardException(com.kachat.app.R.string.nfc_err_no_passphrase)
    class UnreadableBackup : NfcCardException(com.kachat.app.R.string.nfc_err_unreadable_backup)
    class SaveNotConfirmed : NfcCardException(com.kachat.app.R.string.nfc_err_save_not_confirmed)
    class SeedUnavailable : NfcCardException(com.kachat.app.R.string.nfc_err_seed_unavailable)
    class CardFailed(detail: String) : NfcCardException(com.kachat.app.R.string.nfc_err_card_failed, detail)
}

/** The applet's answer to SELECT. */
data class NfcCardStatus(
    val pinSet: Boolean,
    val hasBackup: Boolean,
    val pinBlocked: Boolean,
    val triesRemaining: Int,
    val maxBackup: Int,
)

/** One card, for the length of one tap. */
class NfcCardConnection internal constructor(
    private val card: IsoDep,
    private val appletAid: ByteArray,
) {
    private class Reply(val data: ByteArray, val status: Int)

    private fun send(apdu: ByteArray): Reply {
        val raw = card.transceive(apdu)
        if (raw.size < 2) throw NfcCardException.CardFailed("short reply")
        val status = (raw[raw.size - 2].toInt() and 0xFF shl 8) or (raw[raw.size - 1].toInt() and 0xFF)
        return Reply(raw.copyOf(raw.size - 2), status)
    }

    /** A short-form APDU. [expectsResponse] asks for up to 256 bytes back (Le 00). */
    private fun apdu(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray, expectsResponse: Boolean): ByteArray {
        val header = byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte())
        val body = if (data.isEmpty()) ByteArray(0) else byteArrayOf(data.size.toByte()) + data
        val le = if (expectsResponse) byteArrayOf(0) else ByteArray(0)
        return header + body + le
    }

    private fun command(ins: Int, p1p2: Int = 0, data: ByteArray = ByteArray(0), expectsResponse: Boolean = false): ByteArray {
        val reply = send(apdu(0x80, ins, (p1p2 shr 8) and 0xFF, p1p2 and 0xFF, data, expectsResponse))
        return when (reply.status) {
            0x9000 -> reply.data
            in 0x63C0..0x63CF -> throw NfcCardException.WrongPin(reply.status and 0x000F)
            0x6983 -> throw NfcCardException.PinBlocked()
            0x6A88 -> throw NfcCardException.NoBackup()
            else -> throw NfcCardException.CardFailed("%04X".format(reply.status))
        }
    }

    fun select(): NfcCardStatus {
        val reply = send(apdu(0x00, 0xA4, 0x04, 0x00, appletAid, expectsResponse = true))
        if (reply.status == 0x6A82) throw NfcCardException.NotLoaded()
        if (reply.status != 0x9000) throw NfcCardException.CardFailed("%04X".format(reply.status))
        val bytes = reply.data
        if (bytes.size != 7 || bytes[0].toInt() != 1) throw NfcCardException.UnreadableBackup()
        val flags = bytes[3].toInt()
        return NfcCardStatus(
            pinSet = flags and 0x01 != 0,
            hasBackup = flags and 0x02 != 0,
            pinBlocked = flags and 0x04 != 0,
            triesRemaining = bytes[4].toInt() and 0xFF,
            maxBackup = (bytes[5].toInt() and 0xFF shl 8) or (bytes[6].toInt() and 0xFF),
        )
    }

    fun setPin(pin: String) {
        command(0x21, data = pin.toByteArray(Charsets.UTF_8))
    }

    fun verifyPin(pin: String) {
        command(0x20, data = pin.toByteArray(Charsets.UTF_8))
    }

    /** The card checks the hash before the new copy replaces the old one, so an interrupted save
     *  leaves the previous backup in place. */
    fun writeBackup(backup: ByteArray) {
        command(0xD0, p1p2 = backup.size)
        var offset = 0
        while (offset < backup.size) {
            val end = minOf(offset + WRITE_CHUNK_SIZE, backup.size)
            command(0xD1, p1p2 = offset, data = backup.copyOfRange(offset, end))
            offset = end
        }
        command(0xD2, data = MessageDigest.getInstance("SHA-256").digest(backup))
    }

    fun readBackup(): ByteArray {
        var backup = ByteArray(0)
        while (true) {
            val chunk = command(0xB0, p1p2 = backup.size, expectsResponse = true)
            if (chunk.isEmpty()) return backup
            backup += chunk
        }
    }

    fun wipe() {
        command(0xE0)
    }

    private companion object {
        const val WRITE_CHUNK_SIZE = 200
    }
}

/**
 * What's encrypted on the card: everything an account needs beyond the words, because some accounts
 * can't be restored correctly from the words alone. The BIP39 passphrase is deliberately not here:
 * it's what protects the account if someone gets past the PIN. [chattingAddress] lets a restore
 * catch a mistyped passphrase, which would otherwise open a different, empty account. [family] is
 * iOS's name for it ([wireName]), since a card works across apps.
 */
data class NfcCardBackupPayload(
    val version: Int = 2,
    val words: List<String>,
    val chattingAddress: String,
    val family: String,
    val chattingAddressIndex: Int,
    val alias: String,
) {
    /** Null for a family this app doesn't have (iOS's Chainge). */
    val sourceFamily: WalletSourceFamily?
        get() = WalletSourceFamily.entries.firstOrNull { it.wireName == family }
}

/** The card format's name for this family, shared with iOS (docs/PROTOCOL.md). */
val WalletSourceFamily.wireName: String
    get() = when (this) {
        WalletSourceFamily.KASPA_STANDARD -> "kaspaStandard"
        WalletSourceFamily.KASPA_LEGACY_972 -> "kaspaLegacy972"
        WalletSourceFamily.ONE_KEY -> "oneKey"
    }

/**
 * The backup format in docs/PROTOCOL.md: magic, PBKDF2 iterations, salt, nonce, then AES-256-GCM.
 * The key comes from the card's PIN, so there's nothing extra to remember. A six-digit PIN can be
 * guessed offline by anyone who extracts the chip, which is why the passphrase stays off the card.
 * Key derivation takes a moment, so call these away from the main thread.
 */
object NfcCardBackupFormat {
    private val MAGIC = "KNB1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 600_000
    private const val MAXIMUM_ITERATIONS = 10_000_000L
    private const val SALT_LENGTH = 16
    private const val NONCE_LENGTH = 12
    private const val TAG_LENGTH = 16
    private const val HEADER_LENGTH = 4 + 4 + SALT_LENGTH + NONCE_LENGTH
    private val gson = Gson()

    fun seal(payload: NfcCardBackupPayload, pin: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val nonce = ByteArray(NONCE_LENGTH).also(random::nextBytes)
        val header = MAGIC + ByteBuffer.allocate(4).putInt(ITERATIONS).array() + salt + nonce
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(pin, salt, ITERATIONS), GCMParameterSpec(TAG_LENGTH * 8, nonce))
        cipher.updateAAD(header)
        // doFinal gives the ciphertext with the tag after it, as the format lays them out.
        return header + cipher.doFinal(gson.toJson(payload).toByteArray(Charsets.UTF_8))
    }

    fun open(backup: ByteArray, pin: String): NfcCardBackupPayload {
        if (backup.size <= HEADER_LENGTH + TAG_LENGTH || !backup.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw NfcCardException.UnreadableBackup()
        }
        val count = ByteBuffer.wrap(backup, 4, 4).int.toLong() and 0xFFFFFFFFL
        if (count < ITERATIONS || count > MAXIMUM_ITERATIONS) throw NfcCardException.UnreadableBackup()
        val salt = backup.copyOfRange(8, 8 + SALT_LENGTH)
        val nonce = backup.copyOfRange(8 + SALT_LENGTH, HEADER_LENGTH)
        val payload = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(pin, salt, count.toInt()), GCMParameterSpec(TAG_LENGTH * 8, nonce))
            cipher.updateAAD(backup, 0, HEADER_LENGTH)
            val plaintext = cipher.doFinal(backup, HEADER_LENGTH, backup.size - HEADER_LENGTH)
            gson.fromJson(String(plaintext, Charsets.UTF_8), NfcCardBackupPayload::class.java)
        } catch (e: Exception) {
            throw NfcCardException.UnreadableBackup()
        }
        // Gson fills a missing field with null whatever the type says.
        @Suppress("SENSELESS_COMPARISON")
        val complete = payload != null && payload.version == 2 && payload.words != null &&
            payload.chattingAddress != null && payload.family != null && payload.alias != null
        if (!complete) throw NfcCardException.UnreadableBackup()
        return payload
    }

    private fun deriveKey(pin: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        // NFKD, so the same password typed on another device or keyboard gives the same key.
        val password = Normalizer.normalize(pin, Normalizer.Form.NFKD).toCharArray()
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
            return SecretKeySpec(key, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
