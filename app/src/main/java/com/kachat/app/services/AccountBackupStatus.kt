package com.kachat.app.services

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.util.KaspaNetwork
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which accounts on this device still owe the backup steps (`AccountBackupScreen`). An account
 * created here starts with it owed, since nobody has seen its seed phrase yet; an imported one
 * never does, since whoever imported it has its seed. Kept for an account's addresses on both
 * networks, so a switch to testnet doesn't lose or bring back the card (iOS d063b897).
 *
 * Finishing the backup steps or importing the account also starts a timer: six months later the
 * Backup Hygiene card asks for a few of the words again (`BackupHygieneScreen`), and passing that or
 * redoing the backup starts it over. An account that has done neither has no timer (iOS 836b62bc).
 */
@Singleton
class AccountBackupStatus @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _pendingAddresses = MutableStateFlow(prefs.getStringSet(KEY_PENDING, null).orEmpty().toSet())
    val pendingAddresses: StateFlow<Set<String>> = _pendingAddresses.asStateFlow()

    /** Address -> when its backup was last finished or checked, in ms since 1970. */
    private val _confirmedAt = MutableStateFlow(readConfirmedAt())
    val confirmedAt: StateFlow<Map<String, Long>> = _confirmedAt.asStateFlow()

    private fun readConfirmedAt(): Map<String, Long> = runCatching {
        gson.fromJson<Map<String, Long>>(
            prefs.getString(KEY_CONFIRMED_AT, null) ?: "{}",
            object : TypeToken<Map<String, Long>>() {}.type,
        )
    }.getOrNull().orEmpty()

    fun isPending(address: String?): Boolean = address != null && address in _pendingAddresses.value

    /** Six calendar months since this account was imported, or its backup last finished or checked. */
    fun isHygieneDue(address: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (address == null || isPending(address)) return false
        val stamp = _confirmedAt.value[address] ?: return false
        val due = Calendar.getInstance().apply {
            timeInMillis = stamp
            add(Calendar.MONTH, HYGIENE_MONTHS)
        }
        return nowMs >= due.timeInMillis
    }

    fun markPending(address: String) {
        saveAddresses(_pendingAddresses.value + KaspaNetwork.accountAddressVariants(address))
    }

    fun markDone(address: String) {
        saveAddresses(_pendingAddresses.value - KaspaNetwork.accountAddressVariants(address).toSet())
    }

    /** The account was imported, the backup steps finished or the words checked: nothing is owed,
     *  and the six months start over. */
    fun markConfirmed(address: String) {
        markDone(address)
        val now = System.currentTimeMillis()
        saveConfirmedAt(_confirmedAt.value + KaspaNetwork.accountAddressVariants(address).associateWith { now })
    }

    /** The same account now lives at another address, after its chatting address changed. */
    fun moveStatus(from: String, to: String) {
        if (isPending(from)) markPending(to)
        _confirmedAt.value[from]?.let { stamp ->
            saveConfirmedAt(_confirmedAt.value + KaspaNetwork.accountAddressVariants(to).associateWith { stamp })
        }
        forget(from)
    }

    /** The account is gone from this device. */
    fun forget(address: String) {
        markDone(address)
        saveConfirmedAt(_confirmedAt.value - KaspaNetwork.accountAddressVariants(address).toSet())
    }

    private fun saveAddresses(next: Set<String>) {
        _pendingAddresses.value = next
        prefs.edit().putStringSet(KEY_PENDING, next).apply()
    }

    private fun saveConfirmedAt(next: Map<String, Long>) {
        _confirmedAt.value = next
        prefs.edit().putString(KEY_CONFIRMED_AT, gson.toJson(next)).apply()
    }

    private companion object {
        const val PREFS_NAME = "kachat_account_backup"
        const val KEY_PENDING = "kachat_backup_pending_addresses"
        const val KEY_CONFIRMED_AT = "kachat_backup_confirmed_at"
        const val HYGIENE_MONTHS = 6
    }
}
