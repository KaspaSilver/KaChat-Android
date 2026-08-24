package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.ColdStorageAddressDiscovery
import com.kachat.app.services.ColdStorageManager
import com.kachat.app.services.ColdStorageSendEngine
import com.kachat.app.services.KnsService
import com.kachat.app.services.UtxoEntry
import com.kachat.app.util.KaspaExtendedPublicKey
import com.kachat.app.util.KsptCodec
import com.kachat.app.util.QrFrameChunker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Cold Storage — a fully separate area of the app for interacting with a KasSigner air-gapped
 * device via QR exchange. Deliberately does not touch [WalletManager]/[WalletViewModel] at all:
 * this ViewModel only ever knows about watch-only kpub accounts, never a mnemonic or private key.
 */
@HiltViewModel
class ColdStorageViewModel @Inject constructor(
    private val coldStorageManager: ColdStorageManager,
    private val addressDiscovery: ColdStorageAddressDiscovery,
    private val sendEngine: ColdStorageSendEngine,
    private val settings: AppSettingsRepository,
    private val knsService: KnsService
) : ViewModel() {

    /** Forward KNS domain resolution for the send form's recipient field - lets typing "name.kas"
     *  resolve to a Kaspa address the same way Create Chat's own address field already does. */
    suspend fun resolveKnsDomain(domain: String): String? = knsService.resolve(domain)

    private val _accounts = MutableStateFlow(coldStorageManager.getAccounts())
    val accounts: StateFlow<List<ColdStorageManager.ColdAccount>> = _accounts.asStateFlow()

    /** Which block explorer website "Go to Explorer" opens — shared preference, set in Settings > Kaspa Explorer. */
    val kaspaExplorer: StateFlow<com.kachat.app.models.KaspaExplorer> = settings.kaspaExplorer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.kachat.app.models.KaspaExplorer.default)

    enum class ImportStatus { IDLE, VALIDATING, SUCCESS, INVALID_KPUB }
    data class ImportUiState(val status: ImportStatus = ImportStatus.IDLE, val errorMessage: String? = null)

    private val _importState = MutableStateFlow(ImportUiState())
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    /** [scannedText] is whatever the QR scanner handed back — validated as a real kpub before saving. */
    fun importKpub(scannedText: String, name: String) {
        _importState.value = ImportUiState(status = ImportStatus.VALIDATING)
        coldStorageManager.saveAccount(name.ifBlank { "Cold Storage" }, scannedText.trim()).fold(
            onSuccess = {
                _accounts.value = coldStorageManager.getAccounts()
                _importState.value = ImportUiState(status = ImportStatus.SUCCESS)
            },
            onFailure = { e ->
                _importState.value = ImportUiState(
                    status = ImportStatus.INVALID_KPUB,
                    errorMessage = e.message ?: "Not a valid kpub"
                )
            }
        )
    }

    fun resetImportState() {
        _importState.value = ImportUiState()
    }

    fun renameAccount(id: String, newName: String) {
        coldStorageManager.renameAccount(id, newName)
        _accounts.value = coldStorageManager.getAccounts()
    }

    fun deleteAccount(id: String) {
        coldStorageManager.deleteAccount(id)
        _accounts.value = coldStorageManager.getAccounts()
    }

    // -------------------------------------------------------------------------
    // Detail screen — derived addresses + live balances for a single account
    // -------------------------------------------------------------------------

    data class AddressRow(
        val index: Int,
        val address: String,
        val balanceSompi: Long,
        val hasHistory: Boolean,
        val label: String? = null,
        val hidden: Boolean = false,
        // Whether THIS session's live load (the batched balance pass or a per-address check)
        // actually confirmed the row's balance. Snapshot-painted rows come back false — the
        // spending list's identical rule — so visibility toggles know when they can trust the
        // row instantly versus when they must fall back to a fail-closed live check.
        val liveChecked: Boolean = false
    )

    private val _addresses = MutableStateFlow<List<AddressRow>>(emptyList())
    val addresses: StateFlow<List<AddressRow>> = _addresses.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    /**
     * Loads the account's address list the way iOS does (ColdStorageManager.getAddressList +
     * ColdStorageView.loadEntries): instant paint from the persisted snapshot of the last full
     * load, then every index up to [ColdStorageManager.ColdAccount.maxDerivedIndex] derived
     * locally with ONE batched balance call — funded addresses always land in that first live
     * commit — then a gap-limit scan past the bound for addresses used before they were ever
     * shown here, and finally a background Used backfill. The old shape (a full sequential
     * 2-calls-per-index scan, committed only at the very end, wiped to an empty list on any
     * error) meant one failed or throttled history lookup at index 0 showed NOTHING at all.
     */
    fun refreshAddresses(accountId: String, onResult: (Int) -> Unit = {}) {
        val account = coldStorageManager.getAccounts().find { it.id == accountId } ?: return
        val previousCount = _addresses.value.size
        viewModelScope.launch {
            _isDiscovering.value = true
            var loaded: List<AddressRow>? = null
            try {
                val labels = coldStorageManager.getAddressLabels(accountId)
                val hiddenIndices = coldStorageManager.getHiddenIndices(accountId)
                // Instant paint from the snapshot (balances may be a refresh stale — the live
                // pass replaces them). Only seeds an empty screen so live data never regresses;
                // labels/hidden always come from their own live stores, not the snapshot.
                if (_addresses.value.isEmpty()) {
                    snapshotRows(accountId)?.let { rows ->
                        _addresses.value = rows.map {
                            // A snapshot row's confirmation belonged to a previous session:
                            // untrusted until the live pass below re-confirms it.
                            it.copy(label = labels[it.index], hidden = it.index in hiddenIndices, liveChecked = false)
                        }
                    }
                }
                val rootKey = rootKeyFor(accountId)
                if (rootKey == null) {
                    onResult(0)
                    return@launch
                }

                // Pass 1 — fast: derive 0..maxDerivedIndex locally, one batched balance round
                // trip for all of them (iOS's single getUtxosByAddresses call).
                val known = _addresses.value.associateBy { it.index }
                val derived = (0..account.maxDerivedIndex).mapNotNull { index ->
                    runCatching { KaspaExtendedPublicKey.deriveChildAddress(rootKey, chain = 0, index = index) }
                        .getOrNull()?.let { index to it }
                }
                val balances = addressDiscovery.fetchBalances(derived.map { it.second })
                if (balances == null) {
                    // Nothing could be checked live. Keep whatever is painted (snapshot or
                    // previous rows) — a stale list beats a blank screen claiming no addresses.
                    onResult(0)
                    return@launch
                }
                _addresses.value = derived.map { (index, address) ->
                    val liveBalance = balances[address]
                    val balance = liveBalance ?: known[index]?.balanceSompi ?: 0L
                    AddressRow(
                        index, address, balance,
                        // Used-ness is monotonic, and a live balance also proves it; the
                        // backfill below fills in the rest.
                        hasHistory = known[index]?.hasHistory == true || balance > 0L,
                        label = labels[index],
                        hidden = index in hiddenIndices,
                        liveChecked = liveBalance != null
                    )
                }.sortedByDescending { it.index }

                // Pass 2 — discovery: gap-limit scan BEYOND the derived bound for addresses
                // funded/used before they were ever shown here (plus the scan's trailing fresh
                // rows, same as before). A failed lookup stops only this scan; pass 1's rows
                // stay put.
                val beyond = addressDiscovery.discoverAddresses(rootKey, startIndex = account.maxDerivedIndex + 1)
                if (beyond.isNotEmpty()) {
                    // Raise the persisted bound only to cover USED/funded finds — raising it to
                    // the scan's trailing unused rows would grow it by another gap every refresh.
                    beyond.filter { it.hasHistory || it.balanceSompi > 0 }.maxOfOrNull { it.index }?.let { usedMax ->
                        coldStorageManager.ensureMaxDerivedIndexAtLeast(accountId, usedMax)
                        _accounts.value = coldStorageManager.getAccounts()
                    }
                    _addresses.value = (
                        _addresses.value.filterNot { row -> beyond.any { it.index == row.index } } +
                            beyond.map {
                                AddressRow(
                                    it.index, it.address, it.balanceSompi, it.hasHistory,
                                    labels[it.index], it.index in hiddenIndices,
                                    liveChecked = true
                                )
                            }
                        ).sortedByDescending { it.index }
                }
                loaded = _addresses.value
                onResult((_addresses.value.size - previousCount).coerceAtLeast(0))
                // "Contains domain" tags: batched cached KNS lookups after the rows are visible.
                refreshDomainOwningAddresses(_addresses.value.map { it.address })
            } catch (e: Exception) {
                // Never wipe the list on failure — that was exactly the "cold wallet shows
                // nothing" bug.
                onResult(0)
            } finally {
                _isDiscovering.value = false
            }

            // Pass 3 — background Used backfill for zero-balance rows, sequential on purpose
            // (same rate-limit reasoning as iOS's loadEntries backfill), then persist the
            // snapshot so the next open paints instantly.
            val rows = loaded ?: return@launch
            for (row in rows) {
                if (row.balanceSompi > 0L || row.hasHistory) continue
                val used = addressDiscovery.hasHistory(row.address) ?: continue
                if (used) {
                    _addresses.value = _addresses.value.map { if (it.index == row.index) it.copy(hasHistory = true) else it }
                }
            }
            try { coldStorageManager.setAddressSnapshot(accountId, gson.toJson(_addresses.value)) } catch (e: Exception) { /* best-effort */ }
        }
    }

    private val gson = com.google.gson.Gson()

    /** Last fully-loaded row list for [accountId], from [ColdStorageManager.getAddressSnapshot] —
     *  the instant-paint cache behind [refreshAddresses]. Null when absent or unreadable. */
    private fun snapshotRows(accountId: String): List<AddressRow>? {
        val json = coldStorageManager.getAddressSnapshot(accountId) ?: return null
        val type = object : com.google.gson.reflect.TypeToken<List<AddressRow>>() {}.type
        return try { gson.fromJson<List<AddressRow>>(json, type) } catch (e: Exception) { null }
    }

    // -------------------------------------------------------------------------
    // KNS domains on cold addresses: the "Contains domain" list tags and the per-address
    // "KNS Domains (n)" tab (LIST-ONLY here - a KNS transfer's reveal input spends a P2SH
    // redeem script, and the KSPT QR format only carries plain single-sig Schnorr inputs,
    // so KasSigner can't sign inscription transactions).
    // -------------------------------------------------------------------------

    private val _domainOwningAddresses = MutableStateFlow<Set<String>>(emptySet())
    val domainOwningAddresses: StateFlow<Set<String>> = _domainOwningAddresses.asStateFlow()

    private fun refreshDomainOwningAddresses(addresses: List<String>) {
        if (addresses.isEmpty()) return
        viewModelScope.launch {
            _domainOwningAddresses.value = try { knsService.domainOwningAddresses(addresses) } catch (e: Exception) { emptySet() }
        }
    }

    private val _addressKnsDomains = MutableStateFlow<List<com.kachat.app.services.KnsAsset>>(emptyList())
    val addressKnsDomains: StateFlow<List<com.kachat.app.services.KnsAsset>> = _addressKnsDomains.asStateFlow()
    private val _addressKnsDomainsLoading = MutableStateFlow(false)
    val addressKnsDomainsLoading: StateFlow<Boolean> = _addressKnsDomainsLoading.asStateFlow()

    fun loadAddressKnsDomains(address: String) {
        viewModelScope.launch {
            _addressKnsDomainsLoading.value = true
            _addressKnsDomains.value = try { knsService.getOwnedDomains(address) } catch (e: Exception) { emptyList() }
            _addressKnsDomainsLoading.value = false
        }
    }

    // Parsed kpub root keys, cached per account — the Address Visibility pager derives 50
    // addresses per page on demand, and re-parsing the kpub for each would be wasted work
    // (mirrors WalletManager.spendingChainKey's caching on the spending side).
    private val rootKeyCache = mutableMapOf<String, org.bitcoinj.crypto.DeterministicKey>()

    private fun rootKeyFor(accountId: String): org.bitcoinj.crypto.DeterministicKey? {
        rootKeyCache[accountId]?.let { return it }
        val account = coldStorageManager.getAccounts().find { it.id == accountId } ?: return null
        return KaspaExtendedPublicKey.parse(account.kpub).getOrNull()
            ?.let { KaspaExtendedPublicKey.toDeterministicKey(it) }
            ?.also { rootKeyCache[accountId] = it }
    }

    /** On-demand watch-only derivation of a single receive address — the cold twin of
     *  WalletViewModel.spendingAddressAt, used by the visibility pager's beyond-bound rows. */
    fun coldAddressAt(accountId: String, index: Int): String? {
        val rootKey = rootKeyFor(accountId) ?: return null
        return runCatching { KaspaExtendedPublicKey.deriveChildAddress(rootKey, chain = 0, index = index) }.getOrNull()
    }

    /** One-off used check for a pager-derived index (balance or history). Degrades to false
     *  on network failure, matching WalletService.hasSpendingAddressBeenUsed. */
    suspend fun hasColdAddressBeenUsed(accountId: String, index: Int): Boolean {
        val rootKey = rootKeyFor(accountId) ?: return false
        val discovered = addressDiscovery.checkAddress(rootKey, chain = 0, index = index)
        return discovered != null && (discovered.hasHistory || discovered.balanceSompi > 0)
    }

    /**
     * Reveals a specific index from the Address Visibility pager, extending the derived chain
     * when the index is beyond the current bound — intermediate newly-covered indices are marked
     * hidden so checking ONE far-out row doesn't flood the main list with everything below it.
     * The cold twin of WalletViewModel.revealSpendingAddress.
     */
    fun revealColdAddress(accountId: String, index: Int) {
        val account = coldStorageManager.getAccounts().find { it.id == accountId } ?: return
        val currentMax = maxOf(account.maxDerivedIndex, _addresses.value.maxOfOrNull { it.index } ?: -1)
        if (index > currentMax) {
            coldStorageManager.ensureMaxDerivedIndexAtLeast(accountId, index)
            for (i in (currentMax + 1) until index) coldStorageManager.setAddressHidden(accountId, i, true)
            _accounts.value = coldStorageManager.getAccounts()
        }
        coldStorageManager.setAddressHidden(accountId, index, false)
        if (_addresses.value.any { it.index == index }) {
            _addresses.value = _addresses.value.map { if (it.index == index) it.copy(hidden = false) else it }
            return
        }
        // Stamp a placeholder row into the loaded list so the detail screen (which shares this
        // ViewModel) shows it the moment the sheet closes, then backfill its real balance/history.
        val address = coldAddressAt(accountId, index) ?: return
        val label = coldStorageManager.getAddressLabels(accountId)[index]
        _addresses.value = (_addresses.value + AddressRow(index, address, 0L, false, label, false))
            .sortedByDescending { it.index }
        viewModelScope.launch {
            val rootKey = rootKeyFor(accountId) ?: return@launch
            addressDiscovery.checkAddress(rootKey, chain = 0, index = index)?.let { d ->
                _addresses.value = _addresses.value.map {
                    if (it.index == index) it.copy(balanceSompi = d.balanceSompi, hasHistory = d.hasHistory, liveChecked = true) else it
                }
            }
        }
    }

    /**
     * Visibility-checklist toggle that also works for rows the loaded list has never seen
     * (a hidden intermediate whose one-off check failed, say) — [setAddressHidden] requires a
     * loaded row.
     *
     * THE TOGGLE RULE (same as the spending checklist's): the screen batch-loads live balances
     * when it opens, so a hide against a row that load confirmed ([AddressRow.liveChecked])
     * commits instantly and optimistically — no per-toggle network round trip; the funded guard
     * is enforced from that same fresh row. Only when no live-confirmed row exists (list painted
     * from the snapshot, live load failed, or the row was never loaded) does hiding fall back to
     * the fail-closed live check: one balance fetch, and no network answer means no hide.
     * Unhiding is always instant. [onResult] reports whether the flag actually committed.
     */
    fun setColdVisibilityHidden(accountId: String, index: Int, hidden: Boolean, onResult: (Boolean) -> Unit = {}) {
        val row = _addresses.value.find { it.index == index }
        if (!hidden) {
            coldStorageManager.setAddressHidden(accountId, index, false)
            if (row != null) {
                _addresses.value = _addresses.value.map { if (it.index == index) it.copy(hidden = false) else it }
            }
            onResult(true)
            return
        }
        if (row != null && row.balanceSompi > 0) {
            onResult(false)
            return
        }
        if (row != null && row.liveChecked) {
            coldStorageManager.setAddressHidden(accountId, index, true)
            _addresses.value = _addresses.value.map { if (it.index == index) it.copy(hidden = true) else it }
            onResult(true)
            return
        }
        viewModelScope.launch {
            val rootKey = rootKeyFor(accountId)
            if (rootKey == null) {
                onResult(false)
                return@launch
            }
            val discovered = addressDiscovery.checkAddress(rootKey, chain = 0, index = index)
            if (discovered == null) {
                onResult(false)
                return@launch
            }
            if (discovered.balanceSompi > 0) {
                if (row != null) {
                    _addresses.value = _addresses.value.map {
                        if (it.index == index) it.copy(balanceSompi = discovered.balanceSompi, hasHistory = discovered.hasHistory, liveChecked = true) else it
                    }
                }
                onResult(false)
                return@launch
            }
            coldStorageManager.setAddressHidden(accountId, index, true)
            _addresses.value = _addresses.value.map {
                if (it.index == index) it.copy(hidden = true, balanceSompi = discovered.balanceSompi, hasHistory = discovered.hasHistory, liveChecked = true) else it
            }
            onResult(true)
        }
    }

    /**
     * "Generate More Addresses", recycling-aware (the same fix the spending chain's Generate
     * got): picks the LOWEST listed index that is truly unused — zero balance, no on-chain
     * history — un-hiding it rather than growing the chain; only when every listed index is
     * spoken for does it derive one past the end. Reports the ready index via [onResult].
     */
    fun generateMoreAddresses(accountId: String, onResult: (Int) -> Unit = {}) {
        val account = coldStorageManager.getAccounts().find { it.id == accountId } ?: return
        viewModelScope.launch {
            _isDiscovering.value = true
            try {
                // Recycle only rows this session live-confirmed as empty and unused — a
                // snapshot-painted row could be funded since; deriving past the end (below) is
                // always safe, so unconfirmed rows are skipped rather than trusted.
                val recycled = _addresses.value.sortedBy { it.index }
                    .firstOrNull { it.liveChecked && it.balanceSompi == 0L && !it.hasHistory }
                if (recycled != null) {
                    coldStorageManager.setAddressHidden(accountId, recycled.index, false)
                    _addresses.value = _addresses.value.map {
                        if (it.index == recycled.index) it.copy(hidden = false) else it
                    }
                    onResult(recycled.index)
                    return@launch
                }
                val nextIndex = maxOf(account.maxDerivedIndex, _addresses.value.maxOfOrNull { it.index } ?: -1) + 1
                coldStorageManager.ensureMaxDerivedIndexAtLeast(accountId, nextIndex)
                coldStorageManager.setAddressHidden(accountId, nextIndex, false)
                _accounts.value = coldStorageManager.getAccounts()
                // iOS parity (ColdStorageView.generateMore awaits its reload before toasting):
                // the new row lands on screen NOW — its address derives locally — so the toast
                // never names a row the list doesn't show. The live check afterwards only
                // backfills balance/used flags; its failure no longer makes the row vanish.
                val address = coldAddressAt(accountId, nextIndex)
                if (address != null) {
                    val labels = coldStorageManager.getAddressLabels(accountId)
                    _addresses.value = (
                        _addresses.value.filterNot { it.index == nextIndex } +
                            AddressRow(nextIndex, address, 0L, false, labels[nextIndex], hidden = false)
                        ).sortedByDescending { it.index }
                }
                onResult(nextIndex)
                val rootKey = rootKeyFor(accountId)
                val discovered = rootKey?.let { addressDiscovery.checkAddress(it, chain = 0, index = nextIndex) }
                if (discovered != null) {
                    _addresses.value = _addresses.value.map {
                        if (it.index == nextIndex) {
                            it.copy(balanceSompi = discovered.balanceSompi, hasHistory = discovered.hasHistory, liveChecked = true)
                        } else it
                    }
                }
            } finally {
                _isDiscovering.value = false
            }
        }
    }

    fun setAddressLabel(accountId: String, index: Int, label: String) {
        coldStorageManager.setAddressLabel(accountId, index, label)
        _addresses.value = _addresses.value.map {
            if (it.index == index) it.copy(label = label.trim().ifBlank { null }) else it
        }
    }

    fun getUtxoLabels(address: String): Map<String, String> = coldStorageManager.getUtxoLabels(address)

    fun setUtxoLabel(address: String, outpointKey: String, label: String) {
        coldStorageManager.setUtxoLabel(address, outpointKey, label)
    }

    /**
     * Hiding is purely a display preference — the address and its label are untouched, and it
     * always shows back up under "Hidden Addresses" to be unhidden. Unhiding is always allowed,
     * but an address can't be hidden in the first place while it still holds a balance — that's
     * a case you'd want to keep an eye on, not tuck away.
     */
    fun setAddressHidden(accountId: String, index: Int, hidden: Boolean) {
        // Same live-balance fail-closed rule as the checklist path; one implementation.
        setColdVisibilityHidden(accountId, index, hidden)
    }

    // -------------------------------------------------------------------------
    // Address transaction history
    // -------------------------------------------------------------------------

    private val _txHistory = MutableStateFlow<List<ColdStorageAddressDiscovery.AddressTransaction>>(emptyList())
    val txHistory: StateFlow<List<ColdStorageAddressDiscovery.AddressTransaction>> = _txHistory.asStateFlow()

    private val _isLoadingTxHistory = MutableStateFlow(false)
    val isLoadingTxHistory: StateFlow<Boolean> = _isLoadingTxHistory.asStateFlow()

    // In-memory, per-address caches so re-visiting the same address's tx-history screen shows
    // what was already fetched instantly instead of a blank blocking spinner every single time -
    // the real gap this screen had vs. Kaspium's persistent-cache approach (a full local DB is
    // overkill for a watch-only REST screen, but "don't re-blank on every visit" isn't). Each
    // `load*` call serves the cached value immediately (if any) while still kicking off a fresh
    // fetch in the background to keep it current - stale-while-revalidate, not stale-forever.
    private val txHistoryCache = mutableMapOf<String, List<ColdStorageAddressDiscovery.AddressTransaction>>()
    private val utxoCache = mutableMapOf<String, List<ColdStorageAddressDiscovery.AddressUtxo>>()

    fun loadTxHistory(address: String) {
        val cached = txHistoryCache[address]
        if (cached != null) {
            _txHistory.value = cached
        }
        viewModelScope.launch {
            if (cached == null) _isLoadingTxHistory.value = true
            try {
                val fresh = addressDiscovery.getTransactionHistory(address)
                txHistoryCache[address] = fresh
                _txHistory.value = fresh
            } finally {
                _isLoadingTxHistory.value = false
            }
        }
    }

    private val _utxos = MutableStateFlow<List<ColdStorageAddressDiscovery.AddressUtxo>>(emptyList())
    val utxos: StateFlow<List<ColdStorageAddressDiscovery.AddressUtxo>> = _utxos.asStateFlow()

    private val _isLoadingUtxos = MutableStateFlow(false)
    val isLoadingUtxos: StateFlow<Boolean> = _isLoadingUtxos.asStateFlow()

    fun loadUtxos(address: String) {
        val cached = utxoCache[address]
        if (cached != null) {
            _utxos.value = cached
        }
        viewModelScope.launch {
            if (cached == null) _isLoadingUtxos.value = true
            try {
                val fresh = addressDiscovery.getUtxos(address)
                utxoCache[address] = fresh
                _utxos.value = fresh
            } finally {
                _isLoadingUtxos.value = false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Send flow — build an unsigned tx from one address, show it as an animated KSPT QR, scan
    // the signed response back, and broadcast it. [ColdStorageSendEngine] does the actual tx
    // building/KSPT encoding/broadcast; this just sequences the UI-facing steps.
    // -------------------------------------------------------------------------

    enum class ColdSendStep { IDLE, BUILDING, SHOWING_QR, BROADCASTING, SUCCESS, FAILED }

    data class ColdSendUiState(
        val step: ColdSendStep = ColdSendStep.IDLE,
        val qrFrames: List<ByteArray> = emptyList(),
        val feeSompi: Long = 0L,
        val txId: String? = null,
        val errorMessage: String? = null
    )

    private val _sendState = MutableStateFlow(ColdSendUiState())
    val sendState: StateFlow<ColdSendUiState> = _sendState.asStateFlow()

    // Held between "show the unsigned QR" and "scan the signed one back" — broadcastSigned needs
    // the original tx to verify the signed response's outputs/inputs weren't tampered with.
    private var pendingUnsignedTx: ColdStorageSendEngine.UnsignedColdTx? = null

    fun startColdSend(
        fromAddress: String,
        toAddress: String,
        amountSompi: Long,
        feeRateOverride: Long? = null,
        manualUtxos: List<UtxoEntry>? = null
    ) {
        val step = _sendState.value.step
        if (step != ColdSendStep.IDLE && step != ColdSendStep.SUCCESS && step != ColdSendStep.FAILED) return

        _sendState.value = ColdSendUiState(step = ColdSendStep.BUILDING)
        viewModelScope.launch {
            sendEngine.buildUnsignedTransaction(fromAddress, toAddress, amountSompi, feeRateOverride, manualUtxos).fold(
                onSuccess = { unsigned ->
                    pendingUnsignedTx = unsigned
                    val kspt = sendEngine.toKspt(unsigned)
                    _sendState.value = ColdSendUiState(
                        step = ColdSendStep.SHOWING_QR,
                        qrFrames = QrFrameChunker.chunk(kspt),
                        feeSompi = unsigned.feeSompi
                    )
                },
                onFailure = { e ->
                    _sendState.value = ColdSendUiState(step = ColdSendStep.FAILED, errorMessage = e.message ?: "Failed to build transaction")
                }
            )
        }
    }

    /** [scannedBytes] is the fully reassembled signed-KSPT payload from [com.kachat.app.ui.screens.MultiFrameQrScannerOverlay]. */
    fun onSignedKsptScanned(scannedBytes: ByteArray) {
        val unsigned = pendingUnsignedTx ?: return
        _sendState.value = _sendState.value.copy(step = ColdSendStep.BROADCASTING)
        viewModelScope.launch {
            val decoded = KsptCodec.decode(scannedBytes).getOrElse { e ->
                _sendState.value = _sendState.value.copy(
                    step = ColdSendStep.FAILED,
                    errorMessage = e.message ?: "Couldn't read the signed transaction"
                )
                return@launch
            }
            sendEngine.broadcastSigned(unsigned, decoded).fold(
                onSuccess = { txId ->
                    pendingUnsignedTx = null
                    _sendState.value = ColdSendUiState(step = ColdSendStep.SUCCESS, txId = txId)
                },
                onFailure = { e ->
                    _sendState.value = _sendState.value.copy(step = ColdSendStep.FAILED, errorMessage = e.message ?: "Broadcast failed")
                }
            )
        }
    }

    fun resetColdSendState() {
        pendingUnsignedTx = null
        _sendState.value = ColdSendUiState()
    }

    suspend fun estimateMaxAmount(fromAddress: String, feeRateOverride: Long? = null, manualUtxos: List<UtxoEntry>? = null): Long =
        sendEngine.estimateMaxAmount(fromAddress, feeRateOverride, manualUtxos)

    suspend fun fetchUtxosForCoinControl(fromAddress: String): List<UtxoEntry> = sendEngine.fetchUtxos(fromAddress)

    suspend fun compoundInputs(fromAddress: String): ColdStorageSendEngine.CompoundInputs =
        sendEngine.compoundInputs(fromAddress)

    suspend fun previewAutomaticSelection(fromAddress: String, amountSompi: Long, feeRateSompiPerGram: Long): ColdStorageSendEngine.AutomaticSelectionPreview? =
        sendEngine.previewAutomaticSelection(fromAddress, amountSompi, feeRateSompiPerGram)

    suspend fun fetchQuotedFeeRateSompiPerGram(): Long = sendEngine.fetchQuotedFeeRateSompiPerGram()

    /**
     * Refreshes immediately, then again after a short delay — a just-broadcast transaction's
     * UTXO changes aren't always reflected in the very next balance query, so one refresh right
     * after a send can still show the stale pre-send balance. The second pass catches it without
     * making the user pull-to-refresh themselves.
     */
    fun refreshAddressesSoonAfterSend(accountId: String) {
        refreshAddresses(accountId)
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            refreshAddresses(accountId)
        }
    }
}
