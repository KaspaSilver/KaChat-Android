package com.kachat.app.util

import com.kachat.app.services.UtxoEntry

/**
 * The KaPosts tip sheet's own rule (iOS 0e08006 `KaPostTipSheet`). Everything else about a tip is
 * a chat payment's: the Send Kaspa sheet, the 140-character memo sealed in a `kchat:1:pay:`
 * payload ([MessageProtocol.paymentMemoPayload]), the destination and the funding
 * (ChatViewModel.sendPayment).
 */
object KaPostTip {
    /** Where the tip goes - the line under the memo. */
    enum class Destination {
        /** "Goes to a fresh private address they shared" (lock, green). */
        FRESH_PRIVATE_ADDRESS,

        /** "Goes to their public chatting address" (globe, secondary). */
        PUBLIC_CHATTING_ADDRESS,
    }

    /** The same signal as the chat composer's fresh-address indicator: a fresh address the poster
     *  shared is waiting for this payment (iOS `willPayViaFreshPoolAddress`), which the send then
     *  pays (PaymentPoolService.poolPaymentDestination). */
    fun destination(paysViaFreshPoolAddress: Boolean): Destination =
        if (paysViaFreshPoolAddress) Destination.FRESH_PRIVATE_ADDRESS else Destination.PUBLIC_CHATTING_ADDRESS

    /** What the Available pill shows until the funding source's coins have loaded (or when their
     *  total isn't a real amount): iOS's `availableSompi.map(trimmedKas) ?? "--"`. */
    const val AVAILABLE_PLACEHOLDER = "--"

    /** The funding source's spendable balance, as iOS counts it: every coin except coinbase
     *  (mining-reward) coins (`utxos.filter { !$0.isCoinbase }.checkedTotalAmount`). Null when a
     *  coin's amount, or the total, isn't real ([UtxoMath.checkedTotal]). */
    fun availableSompi(utxos: List<UtxoEntry>): Long? =
        UtxoMath.checkedTotal(utxos.filter { !it.utxoEntry.isCoinbase }.map { it.utxoEntry.amount })

    /** The amount in the Available pill: [AVAILABLE_PLACEHOLDER] until [availableSompi] is known. */
    fun availableAmountText(availableSompi: Long?, format: (Long) -> String): String =
        availableSompi?.let(format) ?: AVAILABLE_PLACEHOLDER
}
