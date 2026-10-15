package com.kachat.app.services

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.repository.ChatRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Payment notices waiting for the chatting address (iOS 710fdc0, IOS-086).
 *
 * A chat payment From a spending address is told to the payee by a `payment_notice` the chatting
 * address sends ([com.kachat.app.viewmodels.ChatViewModel]'s `sendSpendingPaymentNotice`). When
 * the chatting address is empty that send fails; the notice is kept here, per wallet, and sent as
 * soon as the chatting address has KAS (iOS `observeChattingBalanceForNotices`). Built at startup
 * (KaChatApplication) so the balance is watched from launch.
 */
@Singleton
class PaymentNoticeOutbox @Inject constructor(
    @ApplicationContext context: Context,
    private val walletManager: WalletManager,
    private val chatRepository: ChatRepository,
    /** Lazy: WalletService is what sends, and it is heavy to build at startup. */
    private val walletServiceLazy: dagger.Lazy<WalletService>,
) {
    data class UnsentPaymentNotice(
        val contactAddress: String,
        val payload: String,
        val txId: String,
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listLock = Any()
    private val sending = Mutex()

    init {
        // The chatting address got KAS: the notices waiting for it go out.
        scope.launch {
            // A StateFlow only emits changes already (iOS removeDuplicates).
            walletServiceLazy.get().balance
                .filter { it > 0 }
                .collect { sendUnsentPaymentNotices() }
        }
    }

    private fun keyFor(walletAddress: String) = "$KEY_PREFIX${walletAddress.trim().lowercase()}"

    private fun currentKey(): String? =
        runCatching { walletManager.getAddress() }.getOrNull()?.takeIf { it.isNotBlank() }?.let(::keyFor)

    private fun read(key: String): List<UnsentPaymentNotice> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<UnsentPaymentNotice>>(json, object : TypeToken<List<UnsentPaymentNotice>>() {}.type)
        }.getOrNull().orEmpty()
    }

    private fun write(key: String, list: List<UnsentPaymentNotice>) {
        if (list.isEmpty()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, gson.toJson(list)).apply()
    }

    /** Keeps a notice that failed to send (usually an empty chatting address), for this wallet. */
    fun keep(notice: UnsentPaymentNotice) {
        val key = currentKey() ?: return
        synchronized(listLock) {
            val list = read(key)
            if (list.any { it.txId == notice.txId }) return
            write(key, (list + notice).takeLast(MAX_KEPT))
        }
    }

    /**
     * Sends the notices that failed for want of KAS on the chatting address - called when its
     * balance turns positive. One at a time; any that still fails stays for next time, and one
     * whose contact is gone is dropped (iOS `sendUnsentPaymentNotices`).
     */
    suspend fun sendUnsentPaymentNotices() {
        if (!sending.tryLock()) return
        try {
            val key = currentKey() ?: return
            val pending = synchronized(listLock) { read(key) }
            if (pending.isEmpty()) return
            val done = mutableSetOf<String>()
            for (notice in pending) {
                if (currentKey() != key) return
                if (chatRepository.getContact(notice.contactAddress) == null) {
                    done += notice.txId
                    continue
                }
                try {
                    // Untagged, like iOS sendEnvelopeInternal: kchat:1:comm:, never a first-contact dm.
                    walletServiceLazy.get().sendKasiaMessage(notice.contactAddress, notice.payload, firstContactTag = false)
                    done += notice.txId
                    Log.i(TAG, "Sent a kept payment_notice for ${notice.txId.take(12)}")
                } catch (e: Exception) {
                    Log.w(TAG, "Kept payment_notice ${notice.txId.take(12)} still not sent: ${e.message}")
                }
            }
            synchronized(listLock) { write(key, read(key).filterNot { it.txId in done }) }
        } finally {
            sending.unlock()
        }
    }

    private companion object {
        const val TAG = "PaymentNoticeOutbox"
        const val PREFS_NAME = "kachat_unsent_payment_notices"
        const val KEY_PREFIX = "kachat_unsent_payment_notices."
        const val MAX_KEPT = 50
    }
}
