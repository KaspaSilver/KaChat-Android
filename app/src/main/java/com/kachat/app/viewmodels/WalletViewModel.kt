package com.kachat.app.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.models.PendingKnsCommit
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.services.KaspaWalletEngine
import com.kachat.app.services.KnsInscriptionEngine
import com.kachat.app.services.KnsProfileFields
import com.kachat.app.services.KnsService
import com.kachat.app.services.SpendingAddressDiscovery
import com.kachat.app.services.UtxoEntry
import com.kachat.app.services.WalletManager
import com.kachat.app.services.WalletService
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.KaspaUtxoSelector
import com.kachat.app.util.ImagePrep
import com.kachat.app.util.KaspaAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class WalletViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val walletManager: WalletManager,
    private val walletService: WalletService,
    private val walletEngine: KaspaWalletEngine,
    private val knsService: KnsService,
    private val settings: AppSettingsRepository,
    private val spendingAddressDiscovery: SpendingAddressDiscovery,
    /** Reused purely for its address-string-keyed REST fetchers (`getTransactionHistory`/
     *  `getUtxos`) - it has no Cold-Storage-account/kpub state, so it's just as valid a data
     *  source here as it is for Cold Storage's own tx-history screen. */
    private val coldStorageAddressDiscovery: ColdStorageAddressDiscovery,
    private val kaPostsNotificationPoller: com.kachat.app.services.KaPostsNotificationPoller,
    private val pushRegistrationManager: com.kachat.app.services.PushRegistrationManager
) : ViewModel() {

    private val _sendResult = MutableStateFlow<Result<String>?>(null)
    val sendResult: StateFlow<Result<String>?> = _sendResult.asStateFlow()

    // Fires when the user taps a bottom tab that's already selected — lets that tab's screen
    // dismiss its own transient UI (e.g. a full-screen QR overlay) instead of the tap being a
    // dead no-op, since re-navigating to an already-selected destination doesn't recompose it.
    // The counter makes every tap distinct even when re-tapping the same route repeatedly.
    private val _tabReselectSignal = MutableStateFlow(0 to "")
    val tabReselectSignal: StateFlow<Pair<Int, String>> = _tabReselectSignal.asStateFlow()

    fun notifyTabReselected(route: String) {
        _tabReselectSignal.value = (_tabReselectSignal.value.first + 1) to route
    }

    // A tab route's own screen can toggle an internal full-screen state (e.g. Cold Storage's QR
    // scanner) without navigating to a new route — the floating bottom nav bar's visibility is
    // otherwise purely route-based, so it would stay overlaid on top of a full-screen camera view.
    // Screens raising this must always clear it again on dismiss (including back-press).
    private val _hideBottomBar = MutableStateFlow(false)
    val hideBottomBar: StateFlow<Boolean> = _hideBottomBar.asStateFlow()

    fun setHideBottomBar(hide: Boolean) {
        _hideBottomBar.value = hide
    }

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _hasWallet = MutableStateFlow(walletManager.hasWallet())
    val hasWallet: StateFlow<Boolean> = _hasWallet

    private val _mnemonic = MutableStateFlow<List<String>?>(null)
    val mnemonic: StateFlow<List<String>?> = _mnemonic

    private val _onMnemonicGenerated = MutableStateFlow<String?>(null)
    val onMnemonicGenerated: StateFlow<String?> = _onMnemonicGenerated

    // Armed whenever an account is added — both the create-a-new-wallet flow
    // (`BackupMnemonicScreen.onComplete`) and the import flow (`ImportWalletScreen`'s `onImported`)
    // — so the main app shows the Welcome Guide automatically. The guide always appears on
    // create/import regardless of the "show setup guides" setting (that toggle only gates the
    // replayable guide entries in Settings). Also re-armed by the guide's own language step to
    // restart it in the newly-picked language. Transient/in-memory only — the first composable to
    // notice it is expected to call `consumePendingWelcomeGuide()` right after presenting the
    // guide, so this is a one-shot signal, not a persisted flag.
    private val _pendingWelcomeGuide = MutableStateFlow(false)
    val pendingWelcomeGuide: StateFlow<Boolean> = _pendingWelcomeGuide

    fun markPendingWelcomeGuide() {
        _pendingWelcomeGuide.value = true
    }

    fun consumePendingWelcomeGuide() {
        _pendingWelcomeGuide.value = false
    }

    private val _address = MutableStateFlow<String?>(null)
    val address: StateFlow<String?> = _address

    private val _accountName = MutableStateFlow<String?>(null)
    val accountName: StateFlow<String?> = _accountName

    private val _accounts = MutableStateFlow(walletManager.getAllAccounts())
    val accounts: StateFlow<List<WalletManager.Account>> = _accounts

    val balance: StateFlow<String> = walletService.balance.map { 
        val kAs = it.toDouble() / 100_000_000.0
        "%.2f KAS".format(kAs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "0.00 KAS")

    val fullBalance: StateFlow<String> = walletService.balance.map {
        val kAs = it.toDouble() / 100_000_000.0
        "%.8f KAS".format(java.util.Locale.US, kAs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "0.00000000 KAS")

    val balanceSompi: StateFlow<Long> = walletService.balance

    // --- Spending address (separate from the identity address above) --------------------
    private val _spendingAddress = MutableStateFlow<String?>(null)
    val spendingAddress: StateFlow<String?> = _spendingAddress

    val spendingBalance: StateFlow<String> = walletService.spendingBalance.map {
        val kAs = it.toDouble() / 100_000_000.0
        "%.8f KAS".format(java.util.Locale.US, kAs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "0.00000000 KAS")

    val spendingBalanceSompi: StateFlow<Long> = walletService.spendingBalance

    /** Re-derives the current spending address and refreshes its balance — safe to call anytime the Profile screen appears, since the underlying index only ever changes via a successful send. */
    fun refreshSpendingAddress() {
        _spendingAddress.value = try { walletManager.currentSpendingAddress() } catch (e: Exception) { null }
        viewModelScope.launch { walletService.refreshSpendingBalance() }
    }

    /** Suspend variant of [refreshSpendingAddress]'s balance refresh - awaits the actual fetch
     *  instead of firing it into [viewModelScope], for callers (the Profile screen's pull-to-
     *  refresh) that need to know when it's actually done before dismissing a refresh spinner. */
    suspend fun refreshSpendingBalanceAndAwait() {
        walletService.refreshSpendingBalance()
    }

    // -------------------------------------------------------------------------
    // Manage Addresses screen — every spending-chain address derived so far, so the user can
    // find/copy an old one that might still hold a stray balance.
    // -------------------------------------------------------------------------

    private val _manageAddresses = MutableStateFlow<List<WalletService.SpendingAddressEntry>>(emptyList())
    val manageAddresses: StateFlow<List<WalletService.SpendingAddressEntry>> = _manageAddresses.asStateFlow()

    private val _manageAddressesLoading = MutableStateFlow(false)
    val manageAddressesLoading: StateFlow<Boolean> = _manageAddressesLoading.asStateFlow()

    fun loadManageAddresses() {
        viewModelScope.launch {
            _manageAddressesLoading.value = true
            _manageAddresses.value = try { walletService.getSpendingAddressList() } catch (e: Exception) { emptyList() }
            _manageAddressesLoading.value = false
        }
    }

    /**
     * Hiding is purely a display preference — the address and its label are untouched; it's just
     * filtered out of the main Manage Addresses list. Unhiding is always allowed, but an address
     * can't be hidden in the first place while it still holds a balance or is the primary
     * ("Pay in Kaspa") spending address — both are cases you'd want to keep an eye on, not tuck away.
     */
    fun setManageAddressHidden(index: Int, hidden: Boolean) {
        val entry = _manageAddresses.value.find { it.index == index } ?: return
        if (hidden && (entry.balanceSompi > 0 || entry.isCurrent)) return
        walletService.setSpendingAddressHidden(index, hidden)
        _manageAddresses.value = _manageAddresses.value.map {
            if (it.index == index) it.copy(hidden = hidden) else it
        }
    }

    /** Sets or clears (blank/null) a nickname for one spending-chain address, shown in place of "Address #N". */
    fun setManageAddressLabel(index: Int, label: String?) {
        walletService.setSpendingAddressLabel(index, label)
        _manageAddresses.value = _manageAddresses.value.map {
            if (it.index == index) it.copy(label = label?.trim()?.takeIf { l -> l.isNotBlank() }) else it
        }
    }

    /** Derives one more spending-chain address and reloads the list to show it. */
    fun generateNewSpendingAddress() {
        viewModelScope.launch {
            walletService.generateNextSpendingAddress()
            loadManageAddresses()
        }
    }

    /**
     * Makes the address at [index] the one "Pay in Kaspa" sources from going forward. The star
     * and balance move in [manageAddresses] immediately, before the real network round-trips
     * (switch + sweep) even finish, so the UI reads as live rather than stalling on them —
     * [loadManageAddresses] then reconciles with the real on-chain state once they're done.
     */
    fun setActiveSpendingAddress(index: Int) {
        val current = _manageAddresses.value
        val previous = current.firstOrNull { it.isCurrent }
        _manageAddresses.value = current.map { entry ->
            when (entry.index) {
                index -> entry.copy(isCurrent = true, balanceSompi = entry.balanceSompi + (previous?.balanceSompi ?: 0L))
                previous?.index -> entry.copy(isCurrent = false, balanceSompi = 0L)
                else -> entry
            }
        }

        viewModelScope.launch {
            walletService.setActiveSpendingAddress(index)
            refreshSpendingAddress()
            loadManageAddresses()
        }
    }

    /**
     * Sends KAS out of one specific spending-chain address (not necessarily the currently
     * active one) to [toAddress] — unlike [WalletService.sendKaspa]/`onSendClicked` (identity)
     * or the "Pay in Kaspa" sweep-all-and-rotate flow, this targets a single address by
     * [index] and leaves any leftover balance right where it is (change returns to the same
     * address rather than sweeping or rotating). Reuses [sendResult]/[isSending] — only one of
     * these send dialogs can be open at a time, so sharing that state is fine.
     */
    fun withdrawFromSpendingAddress(
        index: Int,
        toAddress: String,
        amountSompi: Long,
        feeRateOverride: Long? = null,
        manualUtxos: List<UtxoEntry>? = null
    ) {
        viewModelScope.launch {
            _isSending.value = true
            val fromAddress = walletManager.deriveSpendingAddress(index)
            val result = walletEngine.sendKaspa(
                toAddress = toAddress,
                amountSompi = amountSompi,
                fromAddress = fromAddress,
                signingPrivateKey = walletManager.getSpendingPrivateKeyBytes(index),
                changeAddress = fromAddress,
                feeRateOverride = feeRateOverride,
                manualUtxos = manualUtxos
            )
            _sendResult.value = result
            _isSending.value = false
            if (result.isSuccess) {
                loadManageAddresses()
            }
        }
    }

    /** Coin control's data source for a spending address - see `SpendingAddressSendFlow`. Never
     *  [coldStorageAddressDiscovery]/[spendingAddressUtxos] - those return a display-only DTO
     *  with no scriptPublicKey, which signing needs. */
    suspend fun fetchUtxosForCoinControl(address: String): List<UtxoEntry> = walletEngine.fetchUtxos(address)

    /**
     * Maximum sendable amount from a specific spending address - thin wrapper over
     * [estimateMaxSendableAmount], resolving the index to an address first.
     */
    suspend fun estimateMaxSpendingAddressAmount(index: Int, feeRateOverride: Long? = null, manualUtxos: List<UtxoEntry>? = null): Long =
        estimateMaxSendableAmount(walletManager.deriveSpendingAddress(index), feeRateOverride, manualUtxos)

    /**
     * Maximum sendable amount from any address this wallet holds the key for (spending-chain or
     * the identity address) - mirrors [ColdStorageSendEngine.estimateMaxAmount]. With
     * [manualUtxos] set (coin control active), "max" means max spendable from just that selected
     * subset, resolved fresh by outpoint in case it's gone stale - not the whole address's
     * balance. Shared by [SpendingAddressSendFlow]'s Max button regardless of which address it's
     * sending from.
     */
    suspend fun estimateMaxSendableAmount(address: String, feeRateOverride: Long? = null, manualUtxos: List<UtxoEntry>? = null): Long {
        val fetched = walletEngine.fetchUtxos(address)
        if (fetched.isEmpty()) return 0L

        val utxos = if (!manualUtxos.isNullOrEmpty()) {
            val freshByOutpoint = fetched.associateBy { it.outpoint }
            manualUtxos.mapNotNull { freshByOutpoint[it.outpoint] }
        } else {
            fetched
        }
        if (utxos.isEmpty()) return 0L

        val totalBalance = utxos.sumOf { it.utxoEntry.amount }
        val feeRateSompiPerGram = feeRateOverride?.coerceAtLeast(KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
            ?: walletEngine.fetchQuotedFeeRateSompiPerGram()

        val mass = KaspaMass.calculateMass(numInputs = maxOf(utxos.size, 1), outputScriptLens = listOf(34, 34), payloadSize = 0)
        val fee = KaspaMass.calculateFee(mass, feeRateSompiPerGram)
        return if (totalBalance > fee) totalBalance - fee else 0L
    }

    /**
     * The largest set of UTXOs that fit in a single mass-safe transaction (up to
     * [KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION], largest-first), plus the max self-send amount
     * for exactly that set. Kaspa caps a transaction's mass (~89 inputs), so the compound UI's "Max"
     * reflects *one transaction's worth* of consolidatable value instead of the whole (over-mass)
     * balance — the user consolidates that chunk, then repeats to reduce further. Returns the chunk
     * so the send pins exactly those inputs; null if nothing is spendable. Mirrors iOS's
     * ChatService.maxConsolidatableChunk. [address]'s UTXOs come back already maturity-filtered
     * (matured coinbase + non-coinbase) via [KaspaWalletEngine.fetchUtxos].
     */
    suspend fun maxConsolidatableChunk(address: String, feeRateOverride: Long? = null): Pair<Long, List<UtxoEntry>>? {
        val fetched = walletEngine.fetchUtxos(address)
        if (fetched.isEmpty()) return null
        val chunk = fetched.sortedByDescending { it.utxoEntry.amount }
            .take(KaspaUtxoSelector.MAX_INPUTS_PER_TRANSACTION)
        if (chunk.isEmpty()) return null

        val totalBalance = chunk.sumOf { it.utxoEntry.amount }
        val feeRateSompiPerGram = feeRateOverride?.coerceAtLeast(KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM)
            ?: walletEngine.fetchQuotedFeeRateSompiPerGram()
        val mass = KaspaMass.calculateMass(numInputs = chunk.size, outputScriptLens = listOf(34, 34), payloadSize = 0)
        val fee = KaspaMass.calculateFee(mass, feeRateSompiPerGram)
        val maxAmount = if (totalBalance > fee) totalBalance - fee else 0L
        return maxAmount to chunk
    }

    private val _spendingAddressTxHistory = MutableStateFlow<List<ColdStorageAddressDiscovery.AddressTransaction>>(emptyList())
    val spendingAddressTxHistory: StateFlow<List<ColdStorageAddressDiscovery.AddressTransaction>> = _spendingAddressTxHistory.asStateFlow()
    private val _loadingSpendingAddressTxHistory = MutableStateFlow(false)
    val loadingSpendingAddressTxHistory: StateFlow<Boolean> = _loadingSpendingAddressTxHistory.asStateFlow()

    private val _spendingAddressUtxos = MutableStateFlow<List<ColdStorageAddressDiscovery.AddressUtxo>>(emptyList())
    val spendingAddressUtxos: StateFlow<List<ColdStorageAddressDiscovery.AddressUtxo>> = _spendingAddressUtxos.asStateFlow()
    private val _loadingSpendingAddressUtxos = MutableStateFlow(false)
    val loadingSpendingAddressUtxos: StateFlow<Boolean> = _loadingSpendingAddressUtxos.asStateFlow()

    /** Transaction history for a single spending address - see `SpendingAddressTxHistoryScreen`.
     *  Reuses [ColdStorageAddressDiscovery]'s address-string-keyed REST fetch (no Cold Storage
     *  account state involved) rather than duplicating the same logic. */
    fun loadSpendingAddressTxHistory(address: String) {
        viewModelScope.launch {
            _loadingSpendingAddressTxHistory.value = true
            _spendingAddressTxHistory.value = coldStorageAddressDiscovery.getTransactionHistory(address, limit = 50)
            _loadingSpendingAddressTxHistory.value = false
        }
    }

    /** Live UTXOs for a single spending address - see `SpendingAddressTxHistoryScreen`. */
    fun loadSpendingAddressUtxos(address: String) {
        viewModelScope.launch {
            _loadingSpendingAddressUtxos.value = true
            _spendingAddressUtxos.value = coldStorageAddressDiscovery.getUtxos(address)
            _loadingSpendingAddressUtxos.value = false
        }
    }

    private val _discoveringAddresses = MutableStateFlow(false)
    val discoveringAddresses: StateFlow<Boolean> = _discoveringAddresses.asStateFlow()

    /**
     * Re-runs the same gap-limit on-chain scan used on wallet import, to pick up any spending
     * address with real history beyond what's currently shown (e.g. KAS sent to one directly,
     * before the Manage Addresses screen ever generated it locally). [onResult] receives how
     * many used addresses the scan found in total (0 if none).
     */
    fun discoverSpendingAddresses(onResult: (Int) -> Unit) {
        if (_discoveringAddresses.value) return
        viewModelScope.launch {
            _discoveringAddresses.value = true
            try {
                val discoveredCount = spendingAddressDiscovery.discoverIndex()
                if (discoveredCount > 0) {
                    walletManager.ensureMaxSpendingAddressIndexAtLeast(walletManager.getAddress(), discoveredCount - 1)
                }
                loadManageAddresses()
                onResult(discoveredCount)
            } finally {
                _discoveringAddresses.value = false
            }
        }
    }

    enum class ConsolidateStatus { IDLE, RUNNING, SUCCESS, FAILED }
    data class ConsolidateUiState(val status: ConsolidateStatus = ConsolidateStatus.IDLE, val sweptCount: Int = 0, val errorMessage: String? = null)

    private val _consolidateState = MutableStateFlow(ConsolidateUiState())
    val consolidateState: StateFlow<ConsolidateUiState> = _consolidateState.asStateFlow()

    /** Sweeps every other spending-chain address's balance into the currently active one. */
    fun consolidateSpendingAddresses() {
        if (_consolidateState.value.status == ConsolidateStatus.RUNNING) return
        viewModelScope.launch {
            _consolidateState.value = ConsolidateUiState(status = ConsolidateStatus.RUNNING)
            try {
                val count = walletService.consolidateSpendingAddressesToCurrent()
                _consolidateState.value = ConsolidateUiState(status = ConsolidateStatus.SUCCESS, sweptCount = count)
                refreshSpendingAddress()
                loadManageAddresses()
            } catch (e: Exception) {
                _consolidateState.value = ConsolidateUiState(status = ConsolidateStatus.FAILED, errorMessage = e.message ?: "Consolidation failed")
            }
        }
    }

    fun resetConsolidateState() {
        _consolidateState.value = ConsolidateUiState()
    }

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    init {
        // One-time 4.0 dock seeding (existing users: KaPosts/Broadcasts/+More enabled, dock
        // preserved via the cap/cycle; fresh installs: minimal Chats/Profile/+More dock).
        // Sentinel-guarded no-op on every later launch.
        viewModelScope.launch { settings.applyKaPostsTabDefaultsIfNeeded() }
        // KaPosts social pings while the app runs (60s poll) - iOS parity.
        kaPostsNotificationPoller.start()
        // Mirror the active account's address into DataStore so the per-account dock keys
        // (AppSettingsRepository.tabOrder/hiddenTabs) always resolve against the right account,
        // including immediately after an account switch.
        viewModelScope.launch {
            walletManager.activeAddressFlow.collect { address ->
                if (address != null) {
                    settings.setActiveAddress(address)
                    // Register (or re-register on account switch) this device's FCM token with
                    // the indexer so native push works while backgrounded. No-ops without FCM.
                    pushRegistrationManager.registerAsync()
                }
            }
        }
        if (walletManager.hasWallet()) {
            _address.value = walletManager.getAddress()
            _accountName.value = walletManager.getAccountName()
            _accounts.value = walletManager.getAllAccounts()
            refreshBalance()
            refreshSpendingAddress()

            // A cold start should land back in whichever account was already active, not the
            // Welcome screen's saved-accounts list, since nothing about closing and reopening the
            // app implies the user wants to switch or re-confirm anything — unless they've
            // explicitly turned on biometrics for account login, in which case that tap-to-unlock
            // gate (see OnboardingScreen's SavedAccountCard) is the whole point and must still fire.
            viewModelScope.launch {
                if (!settings.biometricAccountLoginEnabled.first()) {
                    _isLoggedIn.value = true
                }
            }
        }
    }

    fun refreshBalance() {
        viewModelScope.launch {
            walletService.refreshBalance()
        }
    }

    /** Suspend variant of [refreshBalance] - awaits the actual balance fetch instead of firing it
     *  into [viewModelScope] and returning immediately, for callers (the KNS create-profile
     *  wizard's funding gate, now checking the identity/chatting address since all KNS activity
     *  is funded and settled there) that need to know the balance is current before deciding
     *  whether to proceed. */
    suspend fun refreshBalanceAndAwait() {
        walletService.refreshBalance()
    }

    fun login(address: String? = null) {
        if (address != null) {
            walletManager.setActiveAccount(address)
            _address.value = walletManager.getAddress()
            _accountName.value = walletManager.getAccountName()
            refreshBalance()
            refreshSpendingAddress()
        }
        if (walletManager.hasWallet()) {
            _isLoggedIn.value = true
        }
    }

    fun logout() {
        _isLoggedIn.value = false
    }

    /**
     * Renames any saved account by address — edited from the Welcome screen's saved-accounts
     * list, not just the currently active one, since you can rename an account you're not
     * logged into.
     */
    fun renameAccount(address: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        walletManager.renameAccount(address, trimmed)
        _accounts.value = walletManager.getAllAccounts()
        if (_address.value == address) {
            _accountName.value = trimmed
        }
    }

    // Name + words captured during the seed step, carried to the passphrase step where the wallet
    // is actually committed (create) or imported. In-memory only, like the onboarding flow itself.
    private var pendingAccountName: String = ""
    private var pendingMnemonicWords: List<String> = emptyList()

    fun createWallet(name: String, wordCount: Int = 12) {
        viewModelScope.launch {
            // Generate the mnemonic for display only; the account is derived + persisted later in
            // commitCreatedWallet(), after the backup + passphrase steps, so the passphrase can
            // shape the derived account.
            val words = walletManager.generateMnemonic(wordCount)
            pendingAccountName = name
            pendingMnemonicWords = words
            _mnemonic.value = words
            _onMnemonicGenerated.value = words.joinToString(" ")
        }
    }

    /** Commits the pending new wallet with the chosen passphrase ("" = none), then logs in. The
     *  save happens before login() so the main shell sees a fully-persisted active account. */
    fun commitCreatedWallet(passphrase: String) {
        viewModelScope.launch {
            walletManager.commitCreatedWallet(pendingAccountName, pendingMnemonicWords, passphrase)
            _hasWallet.value = true
            _address.value = walletManager.getAddress()
            _accountName.value = walletManager.getAccountName()
            _accounts.value = walletManager.getAllAccounts()
            clearMnemonic()
            markPendingWelcomeGuide()
            login()
            refreshBalance()
        }
    }

    /**
     * Always returns to the Welcome/saved-accounts screen after deleting an account, even if
     * other saved accounts remain — silently falling through to whichever account happens to be
     * "next" would leave the user unsure which account they're now using, right after a
     * destructive action. An explicit re-login/account tap is clearer.
     */
    fun deleteWallet(address: String) {
        walletManager.deleteAccount(address)
        _accounts.value = walletManager.getAllAccounts()
        _hasWallet.value = walletManager.hasWallet()
        _isLoggedIn.value = false
        _address.value = null
        _accountName.value = null
    }

    enum class ImportWalletStatus { IDLE, IMPORTING, SUCCESS, FAILED }

    data class ImportWalletUiState(val status: ImportWalletStatus = ImportWalletStatus.IDLE, val errorMessage: String? = null)

    private val _importWalletState = MutableStateFlow(ImportWalletUiState())
    val importWalletState: StateFlow<ImportWalletUiState> = _importWalletState.asStateFlow()

    /**
     * Validates the seed phrase up front (so a typo surfaces on the import screen, not after the
     * passphrase step) and stashes it for [commitImport]. Returns false and surfaces the error if
     * the mnemonic is invalid. The passphrase itself can't be validated — a wrong one just derives
     * a different, empty account — so it's only collected on the next screen.
     */
    fun prepareImport(name: String, words: List<String>): Boolean {
        if (!walletManager.isValidMnemonic(words)) {
            _importWalletState.value = ImportWalletUiState(
                status = ImportWalletStatus.FAILED,
                errorMessage = "Invalid seed phrase. Please check the words and try again."
            )
            return false
        }
        pendingAccountName = name
        pendingMnemonicWords = words
        _importWalletState.value = ImportWalletUiState() // clear any prior FAILED before advancing
        return true
    }

    /** Imports the prepared wallet with the chosen passphrase ("" = none), then logs in. */
    fun commitImport(passphrase: String) {
        if (_importWalletState.value.status == ImportWalletStatus.IMPORTING) return
        viewModelScope.launch {
            _importWalletState.value = ImportWalletUiState(status = ImportWalletStatus.IMPORTING)
            try {
                walletManager.importWallet(pendingMnemonicWords, pendingAccountName, passphrase)
                _hasWallet.value = true
                _address.value = walletManager.getAddress()
                _accountName.value = walletManager.getAccountName()
                _accounts.value = walletManager.getAllAccounts()
                refreshBalance()
                _importWalletState.value = ImportWalletUiState(status = ImportWalletStatus.SUCCESS)
                markPendingWelcomeGuide()
                login()

                // Recovers this mnemonic's real spending-address index if it was already used
                // with this feature before (a different install, or after a wipe) — runs after
                // reporting import success so it doesn't add scan latency to that UX; a fresh
                // mnemonic just confirms index 0, which is already the default.
                val importedAddress = walletManager.getAddress()
                launch {
                    try {
                        val recoveredIndex = spendingAddressDiscovery.discoverIndex()
                        walletManager.setSpendingAddressIndex(importedAddress, recoveredIndex)
                    } catch (e: Exception) {
                        android.util.Log.w("WalletViewModel", "Spending address discovery failed", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("WalletViewModel", "importWallet failed", e)
                _importWalletState.value = ImportWalletUiState(
                    status = ImportWalletStatus.FAILED,
                    errorMessage = "Invalid seed phrase. Please check the words and try again."
                )
            }
        }
    }

    fun resetImportWalletState() {
        _importWalletState.value = ImportWalletUiState()
    }

    /** BIP39 English wordlist (2048 words) for the in-app import keyboard's autocomplete. */
    val bip39Words: List<String> by lazy { walletManager.bip39WordList() }

    /** True if [word] is an exact BIP39 English word. */
    fun isBip39Word(word: String): Boolean = walletManager.isValidMnemonicWord(word)

    fun clearMnemonic() {
        _mnemonic.value = null
        _onMnemonicGenerated.value = null
    }

    /**
     * Sends Kaspa to a given address (from the identity address). [manualUtxos] threads through
     * coin control the same way [withdrawFromSpendingAddress] already does for spending-chain
     * addresses - see [SpendingAddressSendFlow]'s shared coin-control UI.
     */
    fun onSendClicked(address: String, amountSompi: Long, feeRateOverride: Long? = null, manualUtxos: List<UtxoEntry>? = null) {
        viewModelScope.launch {
            _isSending.value = true
            val result = walletEngine.sendKaspa(address, amountSompi, feeRateOverride = feeRateOverride, manualUtxos = manualUtxos)
            _sendResult.value = result
            _isSending.value = false

            if (result.isSuccess) {
                refreshBalance()
            }
        }
    }

    fun clearSendResult() {
        _sendResult.value = null
    }

    fun getActiveMnemonic(): String? = walletManager.getActiveMnemonic()
    fun getPrivateKeyHex(): String = walletManager.getPrivateKeyHex()

    /** Hex-encoded private key for one spending-chain address — powers the per-address "Export" screen. */
    fun getSpendingPrivateKeyHex(index: Int): String = walletManager.getSpendingPrivateKeyHex(index)

    fun getSpendingUtxoLabels(address: String): Map<String, String> = walletManager.getSpendingUtxoLabels(address)

    fun setSpendingUtxoLabel(address: String, outpointKey: String, label: String?) {
        walletManager.setSpendingUtxoLabel(address, outpointKey, label)
    }

    /** Forward KNS domain resolution for any recipient-address field (Withdraw dialogs, the
     *  spending-address send flow) - lets typing "name.kas" resolve to a Kaspa address the same
     *  way Create Chat's own address field already does. */
    suspend fun resolveKnsDomain(domain: String): String? = knsService.resolve(domain)

    // -------------------------------------------------------------------------
    // KNS domain inscription — real on-chain commit/reveal, see WalletService.inscribeDomain
    // -------------------------------------------------------------------------

    private val _ownedDomainAssets = MutableStateFlow<List<com.kachat.app.services.KnsAsset>>(emptyList())
    val ownedDomainAssets: StateFlow<List<com.kachat.app.services.KnsAsset>> = _ownedDomainAssets.asStateFlow()
    val ownedDomains: StateFlow<List<String>> = _ownedDomainAssets
        .map { assets -> assets.mapNotNull { it.asset } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private val _primaryDomainName = MutableStateFlow<String?>(null)
    /** The wallet's explicitly-set primary domain name, or null if none has ever been set. */
    val primaryDomainName: StateFlow<String?> = _primaryDomainName.asStateFlow()

    /** The domain KNS Profile fields attach to — the explicit primary domain if still owned, else the first owned domain. */
    val activeProfileDomainName: StateFlow<String?> = combine(_ownedDomainAssets, _primaryDomainName) { assets, primary ->
        KnsService.pickActiveDomain(assets.mapNotNull { it.asset }, null, primary)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** [activeProfileDomainName]'s assetId — the target of every profile read/write call. */
    val profileDomainAssetId: StateFlow<String?> = combine(_ownedDomainAssets, activeProfileDomainName) { assets, activeName ->
        assets.firstOrNull { it.asset == activeName }?.assetId ?: assets.firstOrNull()?.assetId
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** Full refresh: domains + primary + the active domain's profile (the "active" domain can
     * change as a result of setting a new primary, transferring one away, or inscribing a new
     * one, so all three of those call this - not [refreshOwnedDomainsAndAwait]). */
    fun refreshOwnedDomains() {
        viewModelScope.launch {
            refreshOwnedDomainsAndAwait()
            refreshKnsProfile()
        }
    }

    /** Domains + primary only, no profile fetch - used by the Domains screen's pull-to-refresh,
     * which never displays `knsProfile`, so fetching it on every pull was a wasted network call. */
    suspend fun refreshOwnedDomainsAndAwait() {
        val currentAddress = address.value ?: return
        _ownedDomainAssets.value = knsService.getOwnedDomains(currentAddress)
        _primaryDomainName.value = knsService.getExplicitPrimaryDomain(currentAddress)
    }

    data class SetPrimaryDomainUiState(val assetId: String? = null, val inFlight: Boolean = false, val errorMessage: String? = null)

    private val _setPrimaryState = MutableStateFlow(SetPrimaryDomainUiState())
    val setPrimaryState: StateFlow<SetPrimaryDomainUiState> = _setPrimaryState.asStateFlow()

    /** Marks a domain as primary — off-chain and free, blocked while another set-primary call is already in flight. */
    fun setPrimaryDomain(assetId: String) {
        if (_setPrimaryState.value.inFlight) return
        viewModelScope.launch {
            _setPrimaryState.value = SetPrimaryDomainUiState(assetId = assetId, inFlight = true)
            try {
                walletService.setPrimaryDomain(assetId)
                refreshOwnedDomains()
                _setPrimaryState.value = SetPrimaryDomainUiState()
            } catch (e: Exception) {
                _setPrimaryState.value = SetPrimaryDomainUiState(assetId = assetId, inFlight = false, errorMessage = e.message ?: "Failed to set primary domain")
            }
        }
    }

    fun clearSetPrimaryError() {
        _setPrimaryState.value = SetPrimaryDomainUiState()
    }

    // -------------------------------------------------------------------------
    // Transfer domain — irreversible, so the recipient is resolved and validated live as the
    // user types (matching a ".kas" name to an address, checking it's a real/different/
    // same-network address) BEFORE the Transfer screen ever lets them confirm — iOS shows no
    // resolved-address preview at all before submitting, this is deliberately stricter.
    // -------------------------------------------------------------------------

    data class TransferRecipientPreview(
        val input: String,
        val checking: Boolean = false,
        val resolvedAddress: String? = null,
        val errorMessage: String? = null
    )

    private val _transferRecipientPreview = MutableStateFlow<TransferRecipientPreview?>(null)
    val transferRecipientPreview: StateFlow<TransferRecipientPreview?> = _transferRecipientPreview.asStateFlow()

    private var transferPreviewJob: Job? = null

    /** Debounced: resolves a ".kas" name to an address (or validates a raw address directly), then checks it's a real, different, same-network address. */
    fun checkTransferRecipient(rawInput: String) {
        transferPreviewJob?.cancel()
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            _transferRecipientPreview.value = null
            return
        }
        transferPreviewJob = viewModelScope.launch {
            delay(350)
            _transferRecipientPreview.value = TransferRecipientPreview(input = trimmed, checking = true)
            val myAddress = address.value
            try {
                val resolved = if (KnsService.looksLikeDomain(trimmed)) {
                    knsService.resolve(trimmed) ?: throw IllegalStateException("Domain not found or has no owner")
                } else {
                    trimmed
                }
                if (!KaspaAddress.isValid(resolved)) throw IllegalStateException("Invalid recipient address")
                if (resolved == myAddress) throw IllegalStateException("Recipient must be different from your own wallet")
                if (myAddress != null && resolved.substringBefore(":") != myAddress.substringBefore(":")) {
                    throw IllegalStateException("Recipient address is on the wrong network")
                }
                _transferRecipientPreview.value = TransferRecipientPreview(input = trimmed, checking = false, resolvedAddress = resolved)
            } catch (e: Exception) {
                _transferRecipientPreview.value = TransferRecipientPreview(input = trimmed, checking = false, errorMessage = e.message ?: "Invalid recipient")
            }
        }
    }

    fun clearTransferRecipientPreview() {
        transferPreviewJob?.cancel()
        _transferRecipientPreview.value = null
    }

    data class TransferDomainUiState(
        val status: KnsInscribeUiStatus = KnsInscribeUiStatus.IDLE,
        val errorMessage: String? = null,
        val result: WalletService.TransferDomainResult? = null
    )

    private val _transferDomainState = MutableStateFlow(TransferDomainUiState())
    val transferDomainState: StateFlow<TransferDomainUiState> = _transferDomainState.asStateFlow()

    /** Submits the real, irreversible on-chain transfer — only proceeds using the already-resolved+validated recipient address, never the raw typed input. */
    fun transferDomain(fullDomain: String, assetId: String, priorityFeeSompi: Long = KnsInscriptionEngine.REVEAL_PRIORITY_FEE_SOMPI) {
        val resolvedAddress = _transferRecipientPreview.value?.resolvedAddress ?: return
        val current = _transferDomainState.value.status
        if (current != KnsInscribeUiStatus.IDLE && current != KnsInscribeUiStatus.SUCCESS && current != KnsInscribeUiStatus.FAILED) return

        viewModelScope.launch {
            _transferDomainState.value = TransferDomainUiState(status = KnsInscribeUiStatus.SUBMITTING_COMMIT)
            try {
                val result = walletService.transferDomain(fullDomain, assetId, resolvedAddress, priorityFeeSompi) { step ->
                    _transferDomainState.value = _transferDomainState.value.copy(status = step.toUiStatus())
                }
                _transferDomainState.value = TransferDomainUiState(status = KnsInscribeUiStatus.SUCCESS, result = result)
                refreshOwnedDomains()
            } catch (e: Exception) {
                _transferDomainState.value = TransferDomainUiState(status = KnsInscribeUiStatus.FAILED, errorMessage = e.message ?: "Transfer failed")
            }
        }
    }

    fun resetTransferDomainState() {
        _transferDomainState.value = TransferDomainUiState()
        clearTransferRecipientPreview()
    }

    private val _knsProfile = MutableStateFlow<KnsProfileFields?>(null)
    val knsProfile: StateFlow<KnsProfileFields?> = _knsProfile.asStateFlow()

    fun refreshKnsProfile() {
        viewModelScope.launch {
            val assets = _ownedDomainAssets.value
            val activeName = KnsService.pickActiveDomain(assets.mapNotNull { it.asset }, null, _primaryDomainName.value)
            val assetId = assets.firstOrNull { it.asset == activeName }?.assetId ?: assets.firstOrNull()?.assetId ?: return@launch
            _knsProfile.value = knsService.getProfile(assetId)
        }
    }

    // -------------------------------------------------------------------------
    // Edit KNS Profile screen — avatar/banner staging + a single save-all that uploads
    // changed images and submits only changed text fields, each its own real transaction.
    // -------------------------------------------------------------------------

    enum class EditProfileStep { IDLE, UPLOADING_AVATAR, UPLOADING_BANNER, SUBMITTING_FIELD, SUCCESS, PARTIAL_FAILURE, FAILED }

    data class EditProfileFieldResult(val fieldKey: String, val success: Boolean, val errorMessage: String? = null)

    data class EditProfileUiState(
        val step: EditProfileStep = EditProfileStep.IDLE,
        val currentFieldLabel: String? = null,
        val fieldResults: List<EditProfileFieldResult> = emptyList(),
        val errorMessage: String? = null
    )

    private val _editProfileState = MutableStateFlow(EditProfileUiState())
    val editProfileState: StateFlow<EditProfileUiState> = _editProfileState.asStateFlow()

    private val _pendingAvatarUri = MutableStateFlow<Uri?>(null)
    val pendingAvatarUri: StateFlow<Uri?> = _pendingAvatarUri.asStateFlow()

    private val _pendingBannerUri = MutableStateFlow<Uri?>(null)
    val pendingBannerUri: StateFlow<Uri?> = _pendingBannerUri.asStateFlow()

    /** True once the user taps "Remove" on an existing (already on-chain) avatar/banner - distinct
     * from simply having no pending pick, since it means Save should explicitly clear the field
     * rather than leave it untouched. Reset by picking a new image or leaving the screen. */
    private val _avatarCleared = MutableStateFlow(false)
    val avatarCleared: StateFlow<Boolean> = _avatarCleared.asStateFlow()

    private val _bannerCleared = MutableStateFlow(false)
    val bannerCleared: StateFlow<Boolean> = _bannerCleared.asStateFlow()

    fun setPendingAvatar(uri: Uri?) {
        _pendingAvatarUri.value = uri
        if (uri != null) _avatarCleared.value = false
    }

    fun setPendingBanner(uri: Uri?) {
        _pendingBannerUri.value = uri
        if (uri != null) _bannerCleared.value = false
    }

    fun clearExistingAvatar() {
        _pendingAvatarUri.value = null
        _avatarCleared.value = true
    }

    fun clearExistingBanner() {
        _pendingBannerUri.value = null
        _bannerCleared.value = true
    }

    fun resetEditProfileState() {
        _editProfileState.value = EditProfileUiState()
        _pendingAvatarUri.value = null
        _pendingBannerUri.value = null
        _avatarCleared.value = false
        _bannerCleared.value = false
    }

    /**
     * Uploads any newly-picked avatar/banner, then submits only the text fields that actually
     * changed from [knsProfile]'s current values — each image/field is still its own real
     * commit/reveal transaction (~2/1 KAS), matching iOS's `saveKNSProfile` order exactly:
     * avatar first, then banner, then changed text fields. Reports a partial-failure state
     * rather than silently swallowing individual failures if some succeed and others don't.
     */
    fun saveKnsProfile(textFields: Map<String, String>) {
        val assetId = profileDomainAssetId.value ?: return
        val step = _editProfileState.value.step
        if (step != EditProfileStep.IDLE && step != EditProfileStep.SUCCESS && step != EditProfileStep.PARTIAL_FAILURE && step != EditProfileStep.FAILED) return

        viewModelScope.launch {
            val results = mutableListOf<EditProfileFieldResult>()
            val currentProfile = _knsProfile.value

            _pendingAvatarUri.value?.let { uri ->
                _editProfileState.value = _editProfileState.value.copy(step = EditProfileStep.UPLOADING_AVATAR)
                try {
                    val bytes = withContext(Dispatchers.Default) { ImagePrep.prepareForUpload(appContext, uri) }
                    walletService.uploadKnsProfileImage(assetId, "avatar", bytes)
                    results.add(EditProfileFieldResult("avatarUrl", true))
                } catch (e: Exception) {
                    results.add(EditProfileFieldResult("avatarUrl", false, e.message))
                }
                // Published incrementally (not just once at the end) so the details screen can
                // show a live per-field checkmark as each one actually finishes.
                _editProfileState.value = _editProfileState.value.copy(fieldResults = results.toList())
            } ?: if (_avatarCleared.value && !currentProfile?.avatarUrl.isNullOrEmpty()) {
                _editProfileState.value = _editProfileState.value.copy(step = EditProfileStep.SUBMITTING_FIELD, currentFieldLabel = "avatarUrl")
                try {
                    walletService.updateKnsProfileField(assetId, "avatarUrl", "")
                    results.add(EditProfileFieldResult("avatarUrl", true))
                } catch (e: Exception) {
                    results.add(EditProfileFieldResult("avatarUrl", false, e.message))
                }
                _editProfileState.value = _editProfileState.value.copy(fieldResults = results.toList())
            } else Unit

            _pendingBannerUri.value?.let { uri ->
                _editProfileState.value = _editProfileState.value.copy(step = EditProfileStep.UPLOADING_BANNER)
                try {
                    val bytes = withContext(Dispatchers.Default) { ImagePrep.prepareForUpload(appContext, uri) }
                    walletService.uploadKnsProfileImage(assetId, "banner", bytes)
                    results.add(EditProfileFieldResult("bannerUrl", true))
                } catch (e: Exception) {
                    results.add(EditProfileFieldResult("bannerUrl", false, e.message))
                }
                _editProfileState.value = _editProfileState.value.copy(fieldResults = results.toList())
            } ?: if (_bannerCleared.value && !currentProfile?.bannerUrl.isNullOrEmpty()) {
                _editProfileState.value = _editProfileState.value.copy(step = EditProfileStep.SUBMITTING_FIELD, currentFieldLabel = "bannerUrl")
                try {
                    walletService.updateKnsProfileField(assetId, "bannerUrl", "")
                    results.add(EditProfileFieldResult("bannerUrl", true))
                } catch (e: Exception) {
                    results.add(EditProfileFieldResult("bannerUrl", false, e.message))
                }
                _editProfileState.value = _editProfileState.value.copy(fieldResults = results.toList())
            } else Unit

            for ((fieldKey, rawValue) in textFields) {
                val trimmed = rawValue.trim()
                val existing = WalletService.fieldValue(currentProfile, fieldKey) ?: ""
                if (trimmed == existing) continue
                _editProfileState.value = _editProfileState.value.copy(step = EditProfileStep.SUBMITTING_FIELD, currentFieldLabel = fieldKey)
                try {
                    walletService.updateKnsProfileField(assetId, fieldKey, trimmed)
                    results.add(EditProfileFieldResult(fieldKey, true))
                } catch (e: Exception) {
                    results.add(EditProfileFieldResult(fieldKey, false, e.message))
                }
                _editProfileState.value = _editProfileState.value.copy(fieldResults = results.toList())
            }

            refreshKnsProfile()
            _pendingAvatarUri.value = null
            _pendingBannerUri.value = null
            _avatarCleared.value = false
            _bannerCleared.value = false

            val finalStep = when {
                results.isEmpty() -> EditProfileStep.SUCCESS
                results.all { it.success } -> EditProfileStep.SUCCESS
                results.any { it.success } -> EditProfileStep.PARTIAL_FAILURE
                else -> EditProfileStep.FAILED
            }
            _editProfileState.value = EditProfileUiState(step = finalStep, fieldResults = results)
        }
    }

    enum class KnsInscribeUiStatus { IDLE, CHECKING_AVAILABILITY, FETCHING_FEE, SUBMITTING_COMMIT, SUBMITTING_REVEAL, VERIFYING, SUCCESS, FAILED }

    data class KnsInscribeUiState(
        val status: KnsInscribeUiStatus = KnsInscribeUiStatus.IDLE,
        val errorMessage: String? = null,
        val result: WalletService.DomainInscribeResult? = null
    )

    data class DomainAvailabilityPreview(
        val label: String,
        val checking: Boolean = false,
        val available: Boolean? = null,
        val isReserved: Boolean = false,
        val revealKas: Double? = null,
        val commitKas: Double? = null,
        val errorMessage: String? = null
    )

    private val _knsInscribeState = MutableStateFlow(KnsInscribeUiState())
    val knsInscribeState: StateFlow<KnsInscribeUiState> = _knsInscribeState.asStateFlow()

    private val _domainPreview = MutableStateFlow<DomainAvailabilityPreview?>(null)
    val domainPreview: StateFlow<DomainAvailabilityPreview?> = _domainPreview.asStateFlow()

    val pendingKnsCommit: StateFlow<PendingKnsCommit?> = settings.pendingKnsCommit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** Route strings, in the user's chosen bottom-tab order — see AppSettingsRepository.tabOrder. */
    val tabOrder: StateFlow<List<String>> = settings.tabOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), AppSettingsRepository.DEFAULT_TAB_ORDER)

    val kaspaExplorer: StateFlow<com.kachat.app.models.KaspaExplorer> = settings.kaspaExplorer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), com.kachat.app.models.KaspaExplorer.default)

    fun setTabOrder(routes: List<String>) {
        viewModelScope.launch { settings.setTabOrder(routes) }
    }

    /** Bottom-tab routes the user has hidden from the nav bar via Settings > Customization > Menu. */
    val hiddenTabs: StateFlow<Set<String>> = settings.hiddenTabs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptySet())

    /** null until DataStore loads - the wizard must not flash while the real value is unknown. */
    val dockWizardDismissed: StateFlow<Boolean?> = settings.dockWizardDismissed
        .map { it as Boolean? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    fun dismissDockWizard() {
        viewModelScope.launch { settings.setDockWizardDismissed() }
    }

    fun setTabHidden(route: String, hidden: Boolean) {
        viewModelScope.launch { settings.setTabHidden(route, hidden) }
    }

    val darkModeEnabled: StateFlow<Boolean> = settings.darkModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)

    fun setDarkModeEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setDarkModeEnabled(enabled) }
    }

    /** Settings > Customization > Currency — lowercase ISO 4217 code (e.g. "usd", "eur"). Global,
     *  not per-account (see [AppSettingsRepository.currency]). */
    val currency: StateFlow<String> = settings.currency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "usd")

    fun setCurrency(code: String) {
        viewModelScope.launch { settings.setCurrency(code) }
    }

    /** Settings > Security — whether viewing the seed phrase requires device authentication first. */
    val biometricSeedPhraseEnabled: StateFlow<Boolean> = settings.biometricSeedPhraseEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)

    fun setBiometricSeedPhraseEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setBiometricSeedPhraseEnabled(enabled) }
    }

    /** Settings > Security — whether unlocking a saved account after logout requires device authentication first. */
    val biometricAccountLoginEnabled: StateFlow<Boolean> = settings.biometricAccountLoginEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)

    fun setBiometricAccountLoginEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setBiometricAccountLoginEnabled(enabled) }
    }

    /** Settings > Security — whether the "Export" button on a spending address's own screen requires device authentication first. */
    val biometricSpendingKeyEnabled: StateFlow<Boolean> = settings.biometricSpendingKeyEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)

    fun setBiometricSpendingKeyEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setBiometricSpendingKeyEnabled(enabled) }
    }

    /**
     * Settings > Customization — whether the "Setup Guide" re-entry points (the Profile screen's
     * "Welcome Guide" row, the "Edit KNS Profile" screen's "Setup Guide" section) are shown.
     * Scoped to the currently active account's address (see [AppSettingsRepository.showSetupGuides]),
     * so it re-derives whenever [address] changes rather than sticking to whichever account was
     * active when this StateFlow was first collected.
     */
    val showSetupGuides: StateFlow<Boolean> = address
        .flatMapLatest { addr -> if (addr != null) settings.showSetupGuides(addr) else flowOf(true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), true)

    fun setShowSetupGuides(enabled: Boolean) {
        val addr = _address.value ?: return
        viewModelScope.launch { settings.setShowSetupGuides(addr, enabled) }
    }

    private var previewJob: Job? = null

    /** Debounced live availability + fee-tier lookup as the user types a label — cancels any in-flight check for the previous label. */
    fun checkDomainLabel(rawLabel: String) {
        previewJob?.cancel()
        val label = KnsService.normalizeDomainLabel(rawLabel)
        if (label == null) {
            _domainPreview.value = null
            return
        }
        previewJob = viewModelScope.launch {
            delay(350)
            _domainPreview.value = DomainAvailabilityPreview(label = label, checking = true)
            try {
                val address = walletManager.getAddress()
                val availability = knsService.checkDomainAvailability(address, "$label.kas")
                if (!availability.available) {
                    _domainPreview.value = DomainAvailabilityPreview(label = label, checking = false, available = false)
                    return@launch
                }
                val feeTiers = knsService.fetchInscribeFeeTiers()
                val tier = KnsService.feeTierForLabel(label)
                val tierFee = KnsService.feeForTier(tier, feeTiers)
                val revealKas = KnsService.revealAmountKas(tierFee, availability.isReservedDomain)
                val commitKas = KnsService.commitAmountKas(revealKas)
                _domainPreview.value = DomainAvailabilityPreview(
                    label = label,
                    checking = false,
                    available = true,
                    isReserved = availability.isReservedDomain,
                    revealKas = revealKas,
                    commitKas = commitKas
                )
            } catch (e: Exception) {
                _domainPreview.value = DomainAvailabilityPreview(label = label, checking = false, errorMessage = e.message ?: "Check failed")
            }
        }
    }

    fun clearDomainPreview() {
        previewJob?.cancel()
        _domainPreview.value = null
    }

    /** Starts a real domain registration — blocked while one is already in flight, matching iOS's `guard !isSubmitting`. */
    fun inscribeDomain(label: String) {
        if (_knsInscribeState.value.status.let { it != KnsInscribeUiStatus.IDLE && it != KnsInscribeUiStatus.SUCCESS && it != KnsInscribeUiStatus.FAILED }) return
        viewModelScope.launch {
            _knsInscribeState.value = KnsInscribeUiState(status = KnsInscribeUiStatus.CHECKING_AVAILABILITY)
            try {
                val result = walletService.inscribeDomain(label) { step ->
                    _knsInscribeState.value = _knsInscribeState.value.copy(status = step.toUiStatus())
                }
                _knsInscribeState.value = KnsInscribeUiState(status = KnsInscribeUiStatus.SUCCESS, result = result)
                refreshOwnedDomains()
            } catch (e: Exception) {
                _knsInscribeState.value = KnsInscribeUiState(status = KnsInscribeUiStatus.FAILED, errorMessage = e.message ?: "Inscription failed")
            }
        }
    }

    fun resetKnsInscribeState() {
        _knsInscribeState.value = KnsInscribeUiState()
    }

    /** Retries just the reveal half of a commit that broadcast but never finished — see WalletService.retryPendingKnsReveal. */
    fun retryPendingKnsReveal() {
        val pending = pendingKnsCommit.value ?: return
        viewModelScope.launch {
            _knsInscribeState.value = KnsInscribeUiState(status = KnsInscribeUiStatus.SUBMITTING_REVEAL)
            try {
                walletService.retryPendingKnsReveal(pending)
                _knsInscribeState.value = KnsInscribeUiState(status = KnsInscribeUiStatus.SUCCESS)
            } catch (e: Exception) {
                _knsInscribeState.value = KnsInscribeUiState(status = KnsInscribeUiStatus.FAILED, errorMessage = e.message ?: "Retry failed")
            }
        }
    }

    private fun WalletService.KnsInscribeStep.toUiStatus(): KnsInscribeUiStatus = when (this) {
        WalletService.KnsInscribeStep.CHECKING_AVAILABILITY -> KnsInscribeUiStatus.CHECKING_AVAILABILITY
        WalletService.KnsInscribeStep.FETCHING_FEE -> KnsInscribeUiStatus.FETCHING_FEE
        WalletService.KnsInscribeStep.SUBMITTING_COMMIT -> KnsInscribeUiStatus.SUBMITTING_COMMIT
        WalletService.KnsInscribeStep.SUBMITTING_REVEAL -> KnsInscribeUiStatus.SUBMITTING_REVEAL
        WalletService.KnsInscribeStep.VERIFYING -> KnsInscribeUiStatus.VERIFYING
    }
}
