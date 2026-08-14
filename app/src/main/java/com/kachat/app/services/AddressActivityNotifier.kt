package com.kachat.app.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.util.KaspaExtendedPublicKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local notifications for external receipts on any of the wallet's own SPENDING or COLD STORAGE
 * addresses - no chat involved. Android port of iOS's `AddressActivityNotifier.swift`.
 *
 * iOS drives this from live utxosChanged subscription events plus a foreground catch-up diff.
 * Android has no UTXO push subscription (chat delivery is poll/FCM based), so this runs the same
 * per-wallet BASELINE + DIFF algorithm on a foreground poll loop ([POLL_INTERVAL_MS]) plus an
 * immediate pass on app-foreground ([onAppForeground]) - the catch-up half of the iOS design is
 * the whole engine here:
 *
 * - Per-wallet balance baselines persist across launches; the very first run for a wallet seeds
 *   them silently (no dredging up history as "new" receipts).
 * - A balance increase is attributed to recent transactions for that address; a tx whose inputs
 *   include ANY of our own addresses (chatting, spending, or cold) is a self-transfer and is
 *   suppressed. Unresolvable transactions notify anyway - missing real funds is worse than a
 *   rare duplicate. One notification per tx, outputs to our addresses summed.
 * - Handled txIds are persisted per wallet (cap 500) so live/catch-up passes never double-notify.
 * - [utxoActivityEvents] ALWAYS emits when watched balances change - a UI-refresh signal
 *   (payment composer's Available pill, Manage Addresses, Cold Storage) deliberately separate
 *   from the notification decision and NOT gated by the Address Activity setting.
 * - The notification itself is gated by Settings > Notifications > Wallet > "Address Activity"
 *   (default ON) plus the global notifications toggle.
 *
 * Chatting-address receipts never notify here (chat payment detection owns those), and offered
 * payment-pool reservation addresses are excluded from the NOTIFIABLE set (a pool payment
 * already produces a payment bubble + notification via its payment_notice) while still being
 * watched for the UI event and baseline bookkeeping.
 */
@Singleton
class AddressActivityNotifier @Inject constructor(
    @ApplicationContext context: Context,
    private val walletManager: WalletManager,
    private val coldStorageManager: ColdStorageManager,
    private val paymentPoolStore: PaymentPoolStore,
    private val networkService: NetworkService,
    private val settingsRepository: AppSettingsRepository,
    private val notificationHelper: NotificationHelper
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private var pollJob: Job? = null

    /** Always-emitted UI refresh event: the set of watched own addresses whose balance changed
     *  in the last diff pass. NOT gated by the Address Activity setting - consumers (Available
     *  pill, Manage Addresses, Cold Storage screens) refresh regardless of notification prefs. */
    private val _utxoActivityEvents = MutableSharedFlow<Set<String>>(extraBufferCapacity = 8)
    val utxoActivityEvents: SharedFlow<Set<String>> = _utxoActivityEvents

    // --- Lifecycle --------------------------------------------------------------------

    /** Called from the process lifecycle observer on foreground: immediate catch-up pass, then a
     *  foreground-only poll loop approximating iOS's live subscription events. */
    fun onAppForeground() {
        scope.launch { runDiffPass() }
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                try {
                    runDiffPass()
                } catch (e: Exception) {
                    Log.w(TAG, "Address activity poll failed", e)
                }
            }
        }
    }

    /** Called on background - the loop freezes with the process, matching the app's
     *  foreground-only sync design. Next foreground runs the catch-up diff. */
    fun onAppBackground() {
        pollJob?.cancel()
        pollJob = null
    }

    /** One immediate pass - used by the payment composer's post-send refresh path. */
    fun requestRefresh() {
        scope.launch {
            try {
                runDiffPass()
            } catch (e: Exception) {
                Log.w(TAG, "Address activity refresh failed", e)
            }
        }
    }

    // --- Watched sets -----------------------------------------------------------------

    private fun walletAddressOrNull(): String? = try { walletManager.getAddress() } catch (e: Exception) { null }

    /** address -> cold account label, for every derived cold address of the active wallet. */
    private fun coldLabelByAddress(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val accounts = try { coldStorageManager.getAccounts() } catch (e: Exception) { return result }
        for (account in accounts) {
            val parsed = KaspaExtendedPublicKey.parse(account.kpub).getOrNull() ?: continue
            val rootKey = try { KaspaExtendedPublicKey.toDeterministicKey(parsed) } catch (e: Exception) { continue }
            for (index in 0..account.maxDerivedIndex) {
                val address = try {
                    KaspaExtendedPublicKey.deriveChildAddress(rootKey, chain = 0, index = index)
                } catch (e: Exception) {
                    continue
                }
                result[address] = account.name
            }
        }
        return result
    }

    // --- Core diff pass ---------------------------------------------------------------

    private suspend fun runDiffPass() = runMutex.withLock {
        val walletAddress = walletAddressOrNull() ?: return@withLock
        val api = networkService.kaspaRestApi.value ?: return@withLock

        val spending = walletManager.allSpendingAddresses().toSet()
        val coldLabels = coldLabelByAddress()
        val watched = spending + coldLabels.keys
        if (watched.isEmpty()) return@withLock

        // NOTIFIABLE = watched minus chatting address minus offered pool reservations - pool
        // payments already notify via their payment_notice envelope.
        val poolReserved = paymentPoolStore.allOfferedReservationAddresses(walletAddress).toSet()
        val notifiable = watched - walletAddress - poolReserved
        // "Ours" for the self-send input test includes the chatting address too.
        val ownAll = watched + walletAddress

        // Balances - one fetch per address (Android's REST client has no batched endpoint);
        // any individual failure keeps that address's previous baseline untouched.
        val currentBalances = mutableMapOf<String, Long>()
        for (address in watched) {
            val balance = try { api.getBalance(address).balance } catch (e: Exception) { return@withLock }
            currentBalances[address] = balance
        }

        val baselines = loadBaselines(walletAddress).toMutableMap()
        val isFirstRun = baselines.isEmpty()
        val featureOn = settingsRepository.addressActivityNotificationsEnabled.first() &&
            settingsRepository.notificationsEnabled.first()

        var changed = false
        val changedAddresses = mutableSetOf<String>()
        for ((address, balance) in currentBalances) {
            val previous = baselines[address]
            if (previous == null) {
                // First sighting of this address (feature install, newly revealed slot, fresh
                // cold import) - seed silently.
                baselines[address] = balance
                changed = true
                continue
            }
            if (balance != previous) {
                changed = true
                changedAddresses.add(address)
            }
            if (balance > previous && !isFirstRun && address in notifiable) {
                attributeAndMaybeNotifyIncrease(
                    address = address,
                    delta = balance - previous,
                    walletAddress = walletAddress,
                    ownAll = ownAll,
                    coldLabels = coldLabels,
                    featureOn = featureOn,
                    api = api
                )
            }
            baselines[address] = balance
        }

        if (changed) {
            saveBaselines(baselines, walletAddress)
            if (changedAddresses.isNotEmpty()) {
                _utxoActivityEvents.tryEmit(changedAddresses)
            }
        }
    }

    /**
     * Attributes a balance increase to its transaction(s) via recent history for the address:
     * per unhandled tx paying this address, resolve the inputs - any own address among them
     * means self-transfer, suppressed silently; otherwise notify once for the tx's summed
     * outputs to this address. If history can't attribute the delta at all, a neutral
     * "Balance increased" notification fires instead (never silently missing real funds).
     */
    private suspend fun attributeAndMaybeNotifyIncrease(
        address: String,
        delta: Long,
        walletAddress: String,
        ownAll: Set<String>,
        coldLabels: Map<String, String>,
        featureOn: Boolean,
        api: KaspaRestApi
    ) {
        ensureHandledLoaded(walletAddress)
        val transactions = try {
            api.getTransactions(address, limit = 10)
        } catch (e: Exception) {
            emptyList()
        }
        if (transactions.isEmpty()) {
            if (featureOn) postBalanceIncreased(address, delta, coldLabels)
            return
        }
        var attributedAny = false
        for (tx in transactions) {
            val toAddress = tx.outputs.filter { it.scriptPublicKeyAddress == address }.sumOf { it.amount }
            if (toAddress <= 0) continue
            if (isHandled(tx.transactionId, walletAddress)) {
                attributedAny = true
                continue
            }
            val inputAddresses = tx.inputs.mapNotNull { it.previousOutpointAddress }.filter { it.isNotEmpty() }
            val isSelfSend = inputAddresses.isNotEmpty() && inputAddresses.any { it in ownAll }
            markHandled(tx.transactionId, walletAddress)
            attributedAny = true
            if (isSelfSend) {
                Log.i(TAG, "Suppressed self-send ${tx.transactionId.take(12)}")
                continue
            }
            if (featureOn) {
                postReceive(totalSompi = toAddress, address = address, coldLabels = coldLabels, dedupeKey = tx.transactionId)
            }
        }
        if (!attributedAny && featureOn) {
            postBalanceIncreased(address, delta, coldLabels)
        }
    }

    // --- Notifications ----------------------------------------------------------------

    private suspend fun postReceive(totalSompi: Long, address: String, coldLabels: Map<String, String>, dedupeKey: String) {
        if (totalSompi <= 0) return
        notificationHelper.showAddressActivity(
            title = "Received ${formatKas(totalSompi)} KAS",
            text = describe(address, coldLabels),
            dedupeKey = dedupeKey
        )
        Log.i(TAG, "Notified external receive $dedupeKey")
    }

    private suspend fun postBalanceIncreased(address: String, delta: Long, coldLabels: Map<String, String>) {
        if (delta <= 0) return
        notificationHelper.showAddressActivity(
            title = "Balance increased by ${formatKas(delta)} KAS",
            text = describe(address, coldLabels),
            dedupeKey = "bal-${address.takeLast(12)}-${System.currentTimeMillis()}"
        )
    }

    private fun describe(address: String, coldLabels: Map<String, String>): String {
        val label = coldLabels[address]
        return if (label != null) {
            "Cold storage ($label) ${shortAddress(address)}"
        } else {
            "Spending address ${shortAddress(address)}"
        }
    }

    // --- Persistence (per wallet) -----------------------------------------------------

    private fun baselinesKey(walletAddress: String) = "baselines_$walletAddress"
    private fun handledKey(walletAddress: String) = "handled_txids_$walletAddress"

    private fun loadBaselines(walletAddress: String): Map<String, Long> {
        val json = prefs.getString(baselinesKey(walletAddress), null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun saveBaselines(baselines: Map<String, Long>, walletAddress: String) {
        prefs.edit().putString(baselinesKey(walletAddress), gson.toJson(baselines)).apply()
    }

    private var handledOrder: MutableList<String> = mutableListOf()
    private var handledSet: MutableSet<String> = mutableSetOf()
    private var handledLoadedFor: String? = null

    private fun ensureHandledLoaded(walletAddress: String) {
        if (handledLoadedFor == walletAddress) return
        val json = prefs.getString(handledKey(walletAddress), null)
        val list: List<String> = if (json == null) emptyList() else try {
            gson.fromJson(json, Array<String>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }
        handledOrder = list.toMutableList()
        handledSet = list.toMutableSet()
        handledLoadedFor = walletAddress
    }

    private fun isHandled(txId: String, walletAddress: String): Boolean {
        ensureHandledLoaded(walletAddress)
        return txId in handledSet
    }

    private fun markHandled(txId: String, walletAddress: String) {
        ensureHandledLoaded(walletAddress)
        if (!handledSet.add(txId)) return
        handledOrder.add(txId)
        while (handledOrder.size > HANDLED_TX_ID_CAP) {
            handledSet.remove(handledOrder.removeAt(0))
        }
        prefs.edit().putString(handledKey(walletAddress), gson.toJson(handledOrder)).apply()
    }

    companion object {
        private const val TAG = "AddressActivity"
        private const val PREFS_NAME = "kachat_address_activity"
        private const val HANDLED_TX_ID_CAP = 500
        private const val POLL_INTERVAL_MS = 30_000L

        internal fun shortAddress(a: String): String =
            if (a.length <= 20) a else a.take(14) + "..." + a.takeLast(6)

        internal fun formatKas(sompi: Long): String {
            val kas = sompi.toDouble() / 100_000_000.0
            return String.format(java.util.Locale.US, "%.8f", kas).trimEnd('0').trimEnd('.')
        }
    }
}
