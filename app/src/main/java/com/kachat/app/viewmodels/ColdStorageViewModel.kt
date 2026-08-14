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
        val hidden: Boolean = false
    )

    private val _addresses = MutableStateFlow<List<AddressRow>>(emptyList())
    val addresses: StateFlow<List<AddressRow>> = _addresses.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    /**
     * Gap-limit scan for used/funded addresses, plus every index up to [ColdStorageManager.ColdAccount.maxDerivedIndex]
     * regardless of whether the scan itself reached that far — an index a user manually generated
     * via [generateMoreAddresses] sits past where a fresh unused-account scan would ever stop,
     * so it'd otherwise vanish again on the very next refresh.
     */
    fun refreshAddresses(accountId: String, onResult: (Int) -> Unit = {}) {
        val account = coldStorageManager.getAccounts().find { it.id == accountId } ?: return
        val previousCount = _addresses.value.size
        viewModelScope.launch {
            _isDiscovering.value = true
            try {
                val parsed = KaspaExtendedPublicKey.parse(account.kpub).getOrThrow()
                val rootKey = KaspaExtendedPublicKey.toDeterministicKey(parsed)
                val labels = coldStorageManager.getAddressLabels(accountId)
                val hiddenIndices = coldStorageManager.getHiddenIndices(accountId)
                val byIndex = mutableMapOf<Int, ColdStorageAddressDiscovery.DiscoveredAddress>()

                addressDiscovery.discoverAddresses(rootKey).forEach { byIndex[it.index] = it }

                for (index in 0..account.maxDerivedIndex) {
                    if (index !in byIndex) {
                        addressDiscovery.checkAddress(rootKey, chain = 0, index = index)?.let {
                            byIndex[it.index] = it
                        }
                    }
                }

                val maxIndex = maxOf(account.maxDerivedIndex, byIndex.keys.maxOrNull() ?: 0)
                coldStorageManager.ensureMaxDerivedIndexAtLeast(accountId, maxIndex)

                // Single commit once everything's ready, not one per address as each REST check
                // resolved — that made rows visibly trickle in one at a time instead of the whole
                // list appearing together like iOS (whose balance fetch is one batched gRPC call,
                // so it has nothing to trickle). Newest (highest index) first — a just-generated
                // address should be immediately visible at the top.
                _addresses.value = byIndex.values.sortedByDescending { it.index }.map {
                    AddressRow(it.index, it.address, it.balanceSompi, it.hasHistory, labels[it.index], it.index in hiddenIndices)
                }
                onResult((_addresses.value.size - previousCount).coerceAtLeast(0))
                // "Contains domain" tags: batched cached KNS lookups after the rows are visible.
                refreshDomainOwningAddresses(_addresses.value.map { it.address })
            } catch (e: Exception) {
                _addresses.value = emptyList()
                onResult(0)
            } finally {
                _isDiscovering.value = false
            }
        }
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

    /**
     * Derives and shows one more address past whatever's currently listed — for pulling up a
     * fresh unused address on demand, without waiting for it to gain history first. Checks only
     * this one new index rather than going through [refreshAddresses]'s full gap-limit rescan
     * (which sequentially re-checks every already-known address too) — that made the new address
     * take as long to appear as a full account re-scan, when only one address actually changed.
     */
    fun generateMoreAddresses(accountId: String) {
        val account = coldStorageManager.getAccounts().find { it.id == accountId } ?: return
        val nextIndex = (_addresses.value.maxOfOrNull { it.index } ?: -1) + 1
        coldStorageManager.ensureMaxDerivedIndexAtLeast(accountId, nextIndex)
        _accounts.value = coldStorageManager.getAccounts()

        viewModelScope.launch {
            _isDiscovering.value = true
            try {
                val parsed = KaspaExtendedPublicKey.parse(account.kpub).getOrThrow()
                val rootKey = KaspaExtendedPublicKey.toDeterministicKey(parsed)
                val discovered = addressDiscovery.checkAddress(rootKey, chain = 0, index = nextIndex)
                if (discovered != null) {
                    val labels = coldStorageManager.getAddressLabels(accountId)
                    val hiddenIndices = coldStorageManager.getHiddenIndices(accountId)
                    val newRow = AddressRow(
                        discovered.index,
                        discovered.address,
                        discovered.balanceSompi,
                        discovered.hasHistory,
                        labels[discovered.index],
                        discovered.index in hiddenIndices
                    )
                    _addresses.value = (_addresses.value.filterNot { it.index == nextIndex } + newRow)
                        .sortedByDescending { it.index }
                }
            } catch (e: Exception) {
                // Leave the existing list as-is — the next full refreshAddresses (e.g. re-entering
                // this screen) will pick up the new address if this one-off check failed.
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
        val row = _addresses.value.find { it.index == index } ?: return
        if (hidden && row.balanceSompi > 0) return
        coldStorageManager.setAddressHidden(accountId, index, hidden)
        _addresses.value = _addresses.value.map {
            if (it.index == index) it.copy(hidden = hidden) else it
        }
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
