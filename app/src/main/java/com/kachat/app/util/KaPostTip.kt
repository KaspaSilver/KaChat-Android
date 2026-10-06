package com.kachat.app.util

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
}
