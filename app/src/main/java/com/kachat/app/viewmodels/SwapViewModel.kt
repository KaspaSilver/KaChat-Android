package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.models.CURATED_SWAP_COINS
import com.kachat.app.models.KAS_SWAP_COIN
import com.kachat.app.models.SwapCoin
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.SwapRepository
import com.kachat.app.services.ChangeNowTransactionResponse
import com.kachat.app.services.WalletManager
import com.kachat.app.services.WalletService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SwapViewModel @Inject constructor(
    private val repository: SwapRepository,
    private val walletService: WalletService,
    private val walletManager: WalletManager,
    private val settings: AppSettingsRepository
) : ViewModel() {

    val swapHistory = repository.getSwapHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One-time ChangeNOW terms/liability disclaimer, shown the first time Swap is opened. */
    val swapDisclaimerAgreed: StateFlow<Boolean> = settings.swapDisclaimerAgreed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun agreeToSwapDisclaimer() {
        viewModelScope.launch { settings.setSwapDisclaimerAgreed(true) }
    }

    // Where swap-received KAS lands. Defaults to a preview of the next never-used address (same
    // index math as WalletManager.generateNextSpendingAddress, just not reserved/persisted until
    // executeSwap actually calls it) — set to a specific index to override that and reuse an
    // existing address instead, e.g. one already received into from a prior swap.
    private val _toAddressOverrideIndex = MutableStateFlow<Int?>(null)
    val toAddressOverrideIndex: StateFlow<Int?> = _toAddressOverrideIndex.asStateFlow()
    private val _toAddressPreviewTick = MutableStateFlow(0)

    val toAddress: StateFlow<String> = combine(_toAddressOverrideIndex, _toAddressPreviewTick) { overrideIndex, _ ->
        walletManager.deriveSpendingAddress(overrideIndex ?: nextFreshSpendingIndex())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private fun nextFreshSpendingIndex(): Int {
        val account = walletManager.getActiveAccount() ?: return 0
        return maxOf(account.maxSpendingAddressIndex, account.spendingAddressIndex) + 1
    }

    fun selectToSpendingAddress(index: Int) {
        _toAddressOverrideIndex.value = index
    }

    fun clearToSpendingAddressOverride() {
        _toAddressOverrideIndex.value = null
    }

    // True = KAS is what you're sending (the curated coin is what you receive); false = the
    // reverse. Matches the original static shell's kasIsSendSide flip-button model — KAS is
    // always one side of the pair, only which side flips. Defaults to false - most people opening
    // Swap are looking to acquire KAS, not sell it, so "You Send" starts on the other coin and
    // "You Get" starts on KAS.
    private val _kasIsSendSide = MutableStateFlow(false)
    val kasIsSendSide: StateFlow<Boolean> = _kasIsSendSide.asStateFlow()

    private val _otherCoin = MutableStateFlow(CURATED_SWAP_COINS.first())
    val otherCoin: StateFlow<SwapCoin> = _otherCoin.asStateFlow()

    /** The "You Send" amount. Typed by the user when [editedSide] is SEND; otherwise filled in
     *  by the derived quote for whatever they typed under "You Get". */
    private val _amountText = MutableStateFlow("")
    val amountText: StateFlow<String> = _amountText.asStateFlow()

    /** The "You Get" amount - the mirror of [amountText]: typed when [editedSide] is GET, else
     *  the direct quote's answer. */
    private val _receiveAmountText = MutableStateFlow("")
    val receiveAmountText: StateFlow<String> = _receiveAmountText.asStateFlow()

    /** Which amount the user last typed. The other one is a quote and gets overwritten by every
     *  estimate; the typed one is the input the swap is created from. */
    enum class AmountSide { SEND, GET }
    private val _editedSide = MutableStateFlow(AmountSide.SEND)
    val editedSide: StateFlow<AmountSide> = _editedSide.asStateFlow()

    /** Where ChangeNOW should deliver the "to" coin — only asked for when that coin isn't KAS (KAS always comes back to this wallet automatically). */
    private val _payoutAddressText = MutableStateFlow("")
    val payoutAddressText: StateFlow<String> = _payoutAddressText.asStateFlow()

    enum class EstimateStatus { IDLE, LOADING, SUCCESS, FAILED }
    data class EstimateUiState(
        val status: EstimateStatus = EstimateStatus.IDLE,
        /** Both sides of the quote once it succeeds - the typed one echoed back, the other one
         *  ChangeNOW's answer. */
        val fromAmount: Double? = null,
        val toAmount: Double? = null,
        val errorMessage: String? = null
    )

    private val _estimateState = MutableStateFlow(EstimateUiState())
    val estimateState: StateFlow<EstimateUiState> = _estimateState.asStateFlow()

    enum class CreateSwapStatus { IDLE, CREATING, SUCCESS, FAILED }
    data class CreateSwapUiState(
        val status: CreateSwapStatus = CreateSwapStatus.IDLE,
        val result: ChangeNowTransactionResponse? = null,
        val errorMessage: String? = null
    )

    private val _createSwapState = MutableStateFlow(CreateSwapUiState())
    val createSwapState: StateFlow<CreateSwapUiState> = _createSwapState.asStateFlow()

    private var estimateJob: Job? = null

    fun flipDirection() {
        _kasIsSendSide.value = !_kasIsSendSide.value
        clearQuotedAmount()
        rescheduleEstimate()
    }

    fun setOtherCoin(coin: SwapCoin) {
        _otherCoin.value = coin
        clearQuotedAmount()
        rescheduleEstimate()
    }

    /** The pair changed, so the quoted side's figure is for a different pair - blank it until
     *  the new quote lands; the typed side stays as the input. */
    private fun clearQuotedAmount() {
        when (_editedSide.value) {
            AmountSide.SEND -> _receiveAmountText.value = ""
            AmountSide.GET -> _amountText.value = ""
        }
    }

    /** "You Send" typed: the "You Get" figure is now stale, so it clears until the quote lands. */
    fun setAmountText(text: String) {
        _amountText.value = text
        _editedSide.value = AmountSide.SEND
        _receiveAmountText.value = ""
        rescheduleEstimate()
    }

    /** "You Get" typed: the derived quote will fill "You Send" with what that costs. */
    fun setReceiveAmountText(text: String) {
        _receiveAmountText.value = text
        _editedSide.value = AmountSide.GET
        _amountText.value = ""
        rescheduleEstimate()
    }

    /** The amount the user typed, whichever card it was. */
    private fun editedAmountText(): String =
        if (_editedSide.value == AmountSide.SEND) _amountText.value else _receiveAmountText.value

    fun setPayoutAddressText(text: String) {
        _payoutAddressText.value = text
    }

    private fun fromCoin() = if (_kasIsSendSide.value) KAS_SWAP_COIN else _otherCoin.value
    private fun toCoin() = if (_kasIsSendSide.value) _otherCoin.value else KAS_SWAP_COIN

    /** Debounced live quote — re-fires on every relevant field change rather than needing an explicit "Get Rate" tap. */
    private fun rescheduleEstimate() {
        estimateJob?.cancel()
        val side = _editedSide.value
        val amountStr = editedAmountText()
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0.0) {
            _estimateState.value = EstimateUiState()
            return
        }
        val from = fromCoin()
        val to = toCoin()
        estimateJob = viewModelScope.launch {
            delay(500)
            _estimateState.value = EstimateUiState(status = EstimateStatus.LOADING)
            // Direct quote answers "You Get" for a typed "You Send"; the derived reverse quote
            // answers "You Send" for a typed "You Get". Either way the quoted side's card is
            // filled from the response so both figures are ChangeNOW's, not ours.
            val result = when (side) {
                AmountSide.SEND -> repository.getEstimate(from, to, amountStr)
                AmountSide.GET -> reverseQuote(from, to, amount)
            }
            result.fold(
                onSuccess = { response ->
                    when (side) {
                        AmountSide.SEND -> _receiveAmountText.value = formatQuotedAmount(response.toAmount)
                        AmountSide.GET -> _amountText.value = formatQuotedAmount(response.fromAmount)
                    }
                    _estimateState.value = EstimateUiState(
                        status = EstimateStatus.SUCCESS,
                        fromAmount = response.fromAmount,
                        toAmount = response.toAmount
                    )
                },
                onFailure = { e -> _estimateState.value = EstimateUiState(status = EstimateStatus.FAILED, errorMessage = e.message) }
            )
        }
    }

    // ---- Reverse quote on the standard flow ----
    //
    // ChangeNOW has no reverse quote on the standard flow (`type=reverse` answers "unsupported
    // now in standard flow"; the fixed-rate flow has one, with its own worse rate, higher
    // minimum and strict deposit). A standard-flow quote is a straight line in the send amount:
    // to = slope × from + intercept (rate applied after the deposit fee, then the withdrawal fee
    // off the top) - probes at 5, 50 and 500 USDC gave the same slope to seven figures. Two
    // direct quotes pin the line for a pair; cached briefly so retyping the target costs one
    // confirming quote, not three. Mirrors iOS's SwapService.

    private data class LinearQuote(
        val slope: Double,
        val intercept: Double,
        val minFrom: Double,
        val fetchedAtMillis: Long
    ) {
        fun fromForTarget(target: Double): Double = (target - intercept) / slope
        fun toForFrom(from: Double): Double = slope * from + intercept
    }

    private val linearQuoteCache = mutableMapOf<String, LinearQuote>()

    private suspend fun linearQuote(from: SwapCoin, to: SwapCoin): Result<LinearQuote> {
        val key = "${from.ticker}/${from.network}>${to.ticker}/${to.network}"
        linearQuoteCache[key]?.let { cached ->
            if (System.currentTimeMillis() - cached.fetchedAtMillis < LINEAR_QUOTE_TTL_MILLIS) return Result.success(cached)
        }
        val range = repository.getRange(from, to).getOrElse { return Result.failure(it) }
        val minFrom = range.minAmount ?: return Result.failure(Exception("ChangeNOW did not report a minimum for this pair"))
        // Two probes well inside the accepted range, a decade apart so fee rounding cannot
        // tilt the slope.
        val lowQuote = repository.getEstimate(from, to, formatQuotedAmount(minFrom * 2)).getOrElse { return Result.failure(it) }
        val highQuote = repository.getEstimate(from, to, formatQuotedAmount(minFrom * 20)).getOrElse { return Result.failure(it) }
        val slope = (highQuote.toAmount - lowQuote.toAmount) / (highQuote.fromAmount - lowQuote.fromAmount)
        if (slope <= 0.0 || !slope.isFinite()) {
            return Result.failure(Exception("ChangeNOW returned an unusable rate for this pair"))
        }
        val model = LinearQuote(
            slope = slope,
            intercept = lowQuote.toAmount - slope * lowQuote.fromAmount,
            minFrom = minFrom,
            fetchedAtMillis = System.currentTimeMillis()
        )
        linearQuoteCache[key] = model
        return Result.success(model)
    }

    /**
     * What to send to receive [target], on the standard flow: solve the pair's line for the
     * send amount, then confirm it with a real direct quote at that amount - the response's
     * toAmount is what ChangeNOW itself says lands, and that is the figure shown in the rate
     * line. One refinement if the confirmation is off by more than 0.2% (a rate tick between
     * the probes and now); the send amount is rounded up at the eighth place so the payout
     * errs toward the target, not under it. A target under the pair's minimum says what the
     * minimum gets you instead of failing on the create.
     */
    private suspend fun reverseQuote(from: SwapCoin, to: SwapCoin, target: Double): Result<com.kachat.app.services.ChangeNowEstimateResponse> {
        val model = linearQuote(from, to).getOrElse { return Result.failure(it) }
        var sendAmount = roundUpSompi(model.fromForTarget(target))
        if (sendAmount < model.minFrom) {
            val minTo = model.toForFrom(model.minFrom)
            return Result.failure(Exception("Minimum you can get is about ${formatQuotedAmount(minTo)} ${to.displayName}"))
        }
        var confirmed = repository.getEstimate(from, to, formatQuotedAmount(sendAmount)).getOrElse { return Result.failure(it) }
        if (kotlin.math.abs(confirmed.toAmount - target) / target > 0.002) {
            sendAmount += (target - confirmed.toAmount) / model.slope
            sendAmount = maxOf(model.minFrom, roundUpSompi(sendAmount))
            confirmed = repository.getEstimate(from, to, formatQuotedAmount(sendAmount)).getOrElse { return Result.failure(it) }
        }
        return Result.success(confirmed)
    }

    private fun roundUpSompi(value: Double): Double = kotlin.math.ceil(value * 100_000_000.0) / 100_000_000.0

    fun executeSwap() {
        val amount = _amountText.value
        if (amount.toDoubleOrNull() == null) return
        val from = fromCoin()
        val to = toCoin()

        viewModelScope.launch {
            _createSwapState.value = CreateSwapUiState(status = CreateSwapStatus.CREATING)

            // Swapping into KAS lands in a fresh, never-used spending address by default (rather
            // than the active one, so exchange-received coins can't be chain-linked to everyday
            // spending out of this wallet) unless the user explicitly picked a different address
            // to reuse via selectToSpendingAddress. Swapping out of KAS needs somewhere else to
            // send the other coin, since this wallet doesn't hold it.
            val payoutAddress = if (to.ticker == "kas") {
                val overrideIndex = _toAddressOverrideIndex.value
                if (overrideIndex != null) {
                    walletManager.deriveSpendingAddress(overrideIndex)
                } else {
                    val freshIndex = walletService.generateNextSpendingAddress()
                    _toAddressPreviewTick.value++
                    walletManager.deriveSpendingAddress(freshIndex)
                }
            } else {
                _payoutAddressText.value.trim()
            }
            if (payoutAddress.isBlank()) {
                _createSwapState.value = CreateSwapUiState(
                    status = CreateSwapStatus.FAILED,
                    errorMessage = "Enter an address to receive the ${to.displayName}"
                )
                return@launch
            }

            // `amount` is either what the user typed under "You Send" or the derived quote's
            // answer for what they typed under "You Get" - the exchange is always created from
            // the send amount, so a "You Get" target rides on the standard flow: the deposit is
            // the quoted figure and the payout floats with the rate, as with any typed send.
            repository.createSwap(from, to, amount, payoutAddress).fold(
                onSuccess = { response ->
                    _createSwapState.value = CreateSwapUiState(status = CreateSwapStatus.SUCCESS, result = response)
                    _amountText.value = ""
                    _receiveAmountText.value = ""
                    _editedSide.value = AmountSide.SEND
                    _estimateState.value = EstimateUiState()
                    _toAddressOverrideIndex.value = null
                },
                onFailure = { e ->
                    _createSwapState.value = CreateSwapUiState(
                        status = CreateSwapStatus.FAILED,
                        errorMessage = e.message ?: "Something went wrong"
                    )
                }
            )
        }
    }

    fun resetCreateSwapState() {
        _createSwapState.value = CreateSwapUiState()
    }

    fun refreshSwapStatus(id: String) {
        viewModelScope.launch { repository.refreshStatus(id) }
    }

    fun markSwapAddedToPortfolio(id: String) {
        viewModelScope.launch { repository.markSwapAddedToPortfolio(id) }
    }

    fun deleteSwap(id: String) {
        viewModelScope.launch { repository.deleteSwap(id) }
    }

    companion object {
        private const val LINEAR_QUOTE_TTL_MILLIS = 60_000L

        /** Plain decimal text for a quoted amount: up to 8 places, trailing zeros trimmed, never
         *  scientific notation - it is shown in the card and, for a "You Get" target, sent back
         *  to ChangeNOW as the fromAmount the exchange is created with. */
        fun formatQuotedAmount(value: Double): String {
            var text = "%.8f".format(java.util.Locale.US, value)
            while (text.endsWith("0")) text = text.dropLast(1)
            if (text.endsWith(".")) text = text.dropLast(1)
            return text
        }
    }
}
