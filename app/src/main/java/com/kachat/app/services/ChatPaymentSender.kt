package com.kachat.app.services

import android.util.Log
import com.kachat.app.models.MessageEntity
import com.kachat.app.repository.ChatRepository
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMass
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.KaspaUtxoSelector
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.PaymentPoolProtocol
import com.kachat.app.util.SendFailureHint
import com.kachat.app.util.SendFeeModel
import com.kachat.app.util.StartupSafety
import com.kachat.app.util.UtxoMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * A payment from a chat (iOS `ChatService.sendPayment` / `sendPaymentInternal`): it spends only
 * confirmed coins, so with none yet it is DEFERRED, not failed - the bubble stays pending and a
 * timer tries again, for as long as the bubble is there, however the screen that started it has
 * moved on. A singleton for that reason, as iOS's retry state lives on its `ChatService`.
 */
@Singleton
class ChatPaymentSender @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val chatRepository: ChatRepository,
    private val walletService: WalletService,
    private val walletEngine: KaspaWalletEngine,
    private val walletManager: WalletManager,
    private val addressActivityNotifier: AddressActivityNotifier,
    /** Payment notices the empty chatting address could not send yet (iOS 710fdc0, IOS-086). */
    private val paymentNoticeOutbox: PaymentNoticeOutbox,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + StartupSafety.logOnlyHandler("ChatPaymentSender"))

    /** iOS `noInputRetryCounts`: how many times each pending payment found no confirmed coins. */
    private val noInputRetryCounts = ConcurrentHashMap<String, Int>()

    /** iOS `scheduledSendRetries`: the pending payments with a retry already on the timer. */
    private val scheduledSendRetries = ConcurrentHashMap.newKeySet<String>()

    data class Request(
        val contactId: String,
        val amountSompi: Long,
        val memo: String,
        val sourceSpendingIndex: Int?,
        val feeRateOverride: Long?,
        val extraFeeSompi: Long,
        val manualUtxos: List<UtxoEntry>?,
        /** The fee the Review showed (iOS 4f8e14bb, as `sendWithdrawal`): a build that would pay
         *  more sends nothing. Null holds it to nothing - a deferred retry has no Review on screen
         *  to go back to. */
        val expectedFeeSompi: Long? = null,
    )

    sealed class Outcome {
        data class Sent(val txId: String) : Outcome()

        /** No confirmed coins yet: the bubble is in the chat and the timer owns it. */
        data object Deferred : Outcome()

        data class Failed(val message: String?) : Outcome()
    }

    /** Sends [request]. A caller that goes away does not stop it. */
    suspend fun send(request: Request): Outcome = scope.async { firstAttempt(request) }.await()

    private suspend fun firstAttempt(request: Request): Outcome {
        val pendingId = "pending_${java.util.UUID.randomUUID()}"
        // The account this payment was queued under: it never signs as another one (iOS 4f8e14bb).
        val owner = try {
            walletManager.getAddress()
        } catch (e: Exception) {
            return Outcome.Failed(e.message)
        }
        try {
            balanceRefusal(request)?.let { return Outcome.Failed(it) }
            chatRepository.insertMessage(
                MessageEntity(
                    id = pendingId,
                    contactId = request.contactId,
                    walletAddress = owner,
                    type = "pay",
                    direction = "sent",
                    plaintextBody = bubbleText(request),
                    encryptedPayload = "",
                    amountSompi = request.amountSompi,
                    blockTimestamp = System.currentTimeMillis(),
                    deliveryStatus = "pending"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error starting payment", e)
            return Outcome.Failed(e.message)
        }
        return attempt(request, pendingId, owner)
    }

    /** The memo goes in now: it is encrypted to the recipient, so this device can never read it
     *  back from the chain - the local bubble is the only place it can show. The amount as sent,
     *  not as typed ("1,5" or "01.50" both read "1.5"). */
    private fun bubbleText(request: Request): String =
        if (request.memo.isEmpty()) {
            KaspaUnit.label(context.getString(com.kachat.app.R.string.sent_amount_kas)).format(KaspaUnit.plain(request.amountSompi))
        } else {
            KaspaUnit.label(context.getString(com.kachat.app.R.string.sent_amount_kas_memo)).format(KaspaUnit.plain(request.amountSompi), request.memo)
        }

    /**
     * iOS `ensureSufficientBalanceForPaymentSend`, before any bubble exists: the reason a payment
     * can't go, or null to go on. With no confirmed coins but some on the way that is the deferral,
     * not a refusal; a read that fails says nothing about the balance, and the send itself will
     * report it. Coin control and the extra aren't priced here, as on iOS.
     */
    private suspend fun balanceRefusal(request: Request): String? {
        val sourceAddress = request.sourceSpendingIndex?.let { walletManager.deriveSpendingAddress(it) } ?: walletManager.getAddress()
        val utxos = try {
            walletEngine.fetchUtxosOrThrow(sourceAddress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "Payment balance precheck unavailable, continuing send: ${e.message}")
            return null
        }
        val spendable = utxos.filter { it.utxoEntry.blockDaaScore > 0L && !it.utxoEntry.isCoinbase }
        val totalNonCoinbase = UtxoMath.checkedTotal(utxos.filterNot { it.utxoEntry.isCoinbase }.map { it.utxoEntry.amount }) ?: 0L
        val available = UtxoMath.checkedTotal(spendable.map { it.utxoEntry.amount }) ?: 0L
        val payloadSize = MessageProtocol.estimatedPaymentPayloadSize(request.memo, request.amountSompi)
        val recipientScriptLen = KaspaAddress.getScriptPublicKey(request.contactId).length / 2
        val senderScriptLen = KaspaAddress.getScriptPublicKey(sourceAddress).length / 2
        val estimate = runCatching {
            SendFeeModel.estimate(
                utxos = spendable,
                manual = null,
                amountSompi = request.amountSompi,
                payloadSize = payloadSize,
                recipientScriptLen = recipientScriptLen,
                changeScriptLen = senderScriptLen,
            )
        }
        // The fee when it can be priced, else iOS's estimateSendAllFee over the coins there are.
        val estimatedFee = estimate.getOrNull()?.networkFeeSompi ?: (
            KaspaMass.calculateFee(
                KaspaMass.calculateMass(spendable.size, listOf(recipientScriptLen, senderScriptLen), payloadSize),
                KaspaMass.MINIMUM_FEE_RATE_SOMPI_PER_GRAM,
            ) + KaspaUtxoSelector.FEE_BUFFER_SOMPI
            )
        val plannedSpend = request.amountSompi + estimatedFee
        val insufficient = String.format(
            Locale.US,
            KaspaUnit.label(SendFailureHint.INSUFFICIENT_BALANCE_TEMPLATE),
            KaspaUnit.plain(plannedSpend),
            KaspaUnit.plain(available),
        )
        if (spendable.isEmpty()) {
            if (totalNonCoinbase > 0L) {
                Log.i(TAG, "Payment send precheck deferred: $NO_SPENDABLE_FUNDS_YET")
                return null
            }
            return insufficient
        }
        val refused = estimate.exceptionOrNull() ?: return null
        return if (isInsufficientFundsError(refused)) insufficient else refused.message
    }

    /** One try of the payment whose bubble is [pendingId]: sent, deferred, or marked failed. */
    private suspend fun attempt(request: Request, pendingId: String, owner: String): Outcome {
        try {
            val myAddress = walletManager.getAddress()
            // It runs when its turn comes, and the person may have switched accounts since: refused
            // rather than signed with the new account's key. Its row went with the old account's
            // messages, where the leftover check fails it at the next launch (iOS 4f8e14bb).
            if (myAddress != owner) {
                Log.i(TAG, "Refused a queued payment: the account changed")
                clearNoInputRetryState(pendingId)
                return Outcome.Failed(context.getString(com.kachat.app.R.string.not_sent_you_switched_accounts))
            }
            val fromChattingAddress = request.sourceSpendingIndex == null
            // From the chatting address it is a chat payment, which always carries its
            // `kchat:1:pay:` payload - an empty memo too (iOS buildPaymentTx): that payload is
            // what opens our chat on the payee's side when they don't know us yet (iOS 1eb4708).
            // From a spending address it is a plain transfer, and the chatting address tells the
            // chat about it right after ([sendSpendingPaymentNotice]): the payee has never seen
            // that address, so a chat payment from it would open a stranger's Message Request on
            // their side instead of landing in this chat (iOS 927322b, XP-022).
            val payload = if (!fromChattingAddress) null else MessageProtocol.paymentMemoPayload(
                memo = request.memo,
                amountSompi = request.amountSompi,
                recipientXOnlyPubKey = KaspaAddress.decode(request.contactId).second,
                // the seed reads the memo back on another device (iOS bfe9f022)
                senderPrivateKey = walletManager.getPrivateKeyBytes()
            )
            // Paid to the contact's chatting address, the memo sealed to its key (iOS 6333dd3: no
            // payment-pool destinations any more). FUNDING SOURCE - the From the person chose (iOS
            // sendPaymentInternal): the chatting address (null, the default) is funded with the
            // identity key, change back to it, sharing the exact same UTXO/outpoint bookkeeping
            // message sends use (KaspaWalletEngine's pending-spent tracking), so an immediately-
            // following message can't race onto the just-spent outpoints; a spending address is
            // signed with its own key and its change goes to a never-used address.
            val txId = if (request.sourceSpendingIndex != null) {
                walletService.payInKaspa(
                    toAddress = request.contactId,
                    amountSompi = request.amountSompi,
                    sourceSpendingIndex = request.sourceSpendingIndex,
                    feeRateOverride = request.feeRateOverride,
                    payloadBytes = payload,
                    manualUtxos = request.manualUtxos,
                    extraFeeSompi = request.extraFeeSompi,
                )
            } else {
                // [WalletService.sendKaspa]'s chat-payment call, with the Review's fee held to.
                walletEngine.sendKaspa(
                    toAddress = request.contactId,
                    amountSompi = request.amountSompi,
                    payloadBytes = payload,
                    feeRateOverride = request.feeRateOverride,
                    manualUtxos = request.manualUtxos,
                    extraFeeSompi = request.extraFeeSompi,
                    excludeCoinbase = true,
                    confirmedOnly = true,
                    greedyAmongManual = true,
                    expectedFeeSompi = request.expectedFeeSompi,
                ).getOrThrow().also { walletService.refreshBalance() }
            }
            if (!fromChattingAddress) sendSpendingPaymentNotice(request.contactId, txId, request.amountSompi, request.memo, owner)

            // The From's balance lags the send - retry it shortly after, then again once the
            // change output has typically landed (~1.5s and ~4s).
            scope.launch {
                delay(1_500)
                walletService.refreshSpendingBalance()
                walletService.refreshBalance()
                delay(2_500)
                walletService.refreshSpendingBalance()
                walletService.refreshBalance()
                addressActivityNotifier.requestRefresh()
            }

            chatRepository.finalizeProvisionalMessage(
                pendingId,
                MessageEntity(
                    id = txId,
                    contactId = request.contactId,
                    walletAddress = myAddress,
                    type = "pay",
                    direction = "sent",
                    plaintextBody = bubbleText(request),
                    encryptedPayload = "",
                    amountSompi = request.amountSompi,
                    blockTimestamp = System.currentTimeMillis(),
                    deliveryStatus = "sent"
                )
            )
            clearNoInputRetryState(pendingId)
            return Outcome.Sent(txId)
        } catch (e: CancellationException) {
            throw e
        } catch (unknown: SendOutcomeUnknownException) {
            // It may have landed: never Failed with Retry, which could pay twice. Not confirmed
            // ("warning") under the transaction's own id, until the payment is read back
            // (iOS d23e6f7a `markSendUnconfirmed`).
            Log.w(TAG, "Payment outcome unknown: marked not confirmed")
            finalizeUnconfirmed(request, pendingId, unknown.txId)
            clearNoInputRetryState(pendingId)
            return Outcome.Failed(context.getString(com.kachat.app.R.string.send_outcome_unknown))
        } catch (changed: SendFeeChangedException) {
            // Nothing went out and the Review shows the new fee: the next swipe makes its own
            // bubble, so this one goes rather than staying behind as a Failed payment (iOS 4f8e14bb).
            Log.i(TAG, "Payment not sent: the fee changed since the Review")
            chatRepository.deleteMessage(pendingId)
            clearNoInputRetryState(pendingId)
            return Outcome.Failed(KaspaUnit.label(context.getString(com.kachat.app.R.string.send_fee_changed, KaspaUnit.plain(changed.feeSompi))))
        } catch (e: Exception) {
            if (isNoConfirmedInputsError(e.message) && (noInputRetryCounts[pendingId] ?: 0) < MAX_DEFERRED_PAYMENT_RETRIES) {
                val delaySeconds = nextNoInputRetryDelay(pendingId)
                Log.i(TAG, "Payment deferred retry ${pendingId.take(12)} in ${"%.0f".format(Locale.US, delaySeconds)}s (no confirmed inputs)")
                chatRepository.holdDeferredPayment(pendingId)
                scheduleRetry(request, pendingId, delaySeconds, owner)
                // Deferred, not sent - nothing to confirm yet.
                return Outcome.Deferred
            }
            Log.e(TAG, "Error sending payment", e)
            // Past the balance check a payment that fails stays in the chat, marked failed (iOS
            // markPendingMessageFailed); the caller surfaces the reason on a first try.
            chatRepository.updateMessageStatus(pendingId, "failed")
            clearNoInputRetryState(pendingId)
            return Outcome.Failed(e.message)
        }
    }

    /** The payment bubble [pendingId] of a send whose outcome is unknown takes [txId] and
     *  "warning". A row already gone (deleted meanwhile) stays gone. */
    private suspend fun finalizeUnconfirmed(request: Request, pendingId: String, txId: String) {
        try {
            val row = chatRepository.getMessage(pendingId) ?: return
            chatRepository.finalizeProvisionalMessage(
                pendingId,
                row.copy(
                    id = txId,
                    plaintextBody = row.plaintextBody ?: bubbleText(request),
                    amountSompi = row.amountSompi ?: request.amountSompi,
                    deliveryStatus = "warning",
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't mark a payment not confirmed", e)
            chatRepository.updateMessageStatus(pendingId, "warning")
        }
    }

    /** iOS `scheduleOutgoingRetry`: after [delaySeconds] the payment tries again, unless its
     *  bubble has gone (or belongs to another account now) or has been sent. */
    private fun scheduleRetry(request: Request, pendingId: String, delaySeconds: Double, owner: String) {
        if (!scheduledSendRetries.add(pendingId)) return
        scope.launch {
            delay((delaySeconds * 1_000).toLong())
            scheduledSendRetries.remove(pendingId)
            val row = chatRepository.getMessage(pendingId)
            // Sent, or Not confirmed (it may have landed): never sent again from here.
            if (row == null || row.deliveryStatus == "sent" || row.deliveryStatus == "warning") {
                clearNoInputRetryState(pendingId)
                return@launch
            }
            chatRepository.updateMessageStatus(pendingId, "pending")
            // Individual attempts decide whether to reschedule or fail. No Review to hold it to.
            attempt(request.copy(expectedFeeSompi = null), pendingId, owner)
        }
    }

    private fun nextNoInputRetryDelay(pendingId: String): Double {
        val nextAttempt = (noInputRetryCounts[pendingId] ?: 0) + 1
        noInputRetryCounts[pendingId] = nextAttempt
        return noInputRetryDelaySeconds(nextAttempt, Random.nextDouble(JITTER_MIN, JITTER_MAX))
    }

    private fun clearNoInputRetryState(pendingId: String) {
        noInputRetryCounts.remove(pendingId)
        scheduledSendRetries.remove(pendingId)
        chatRepository.releaseDeferredPayment(pendingId)
    }

    /**
     * Tells the chat about a payment just sent From a spending address (iOS 927322b
     * `sendSpendingPaymentNotice`): an encrypted `payment_notice` from the chatting address, so
     * the payee's app files the payment (and its memo, which travels only here) in the chat with
     * us. Our own bubble is already there. Fire-and-forget: if it fails (usually an empty chatting
     * address) it is kept and sent once the chatting address has KAS ([PaymentNoticeOutbox], iOS
     * 710fdc0, IOS-086); meanwhile the payee still has the KAS, shown in their self-chat.
     */
    private fun sendSpendingPaymentNotice(contactId: String, txId: String, amountSompi: Long, memo: String, owner: String) {
        val payload = PaymentPoolProtocol.encode(
            PaymentPoolProtocol.PaymentNoticeContent(
                txId = txId.lowercase(),
                amountSompi = amountSompi,
                address = contactId,
                memo = PaymentPoolProtocol.noticeMemo(memo)
            )
        )
        scope.launch(NonCancellable) {
            try {
                // Queued under the account that paid: never signed by another (iOS 4f8e14bb).
                if (walletManager.getAddress() != owner) return@launch
                // Untagged, like iOS sendEnvelopeInternal: kchat:1:comm:, never a first-contact dm.
                walletService.sendKasiaMessage(contactId, payload, firstContactTag = false)
                Log.i(TAG, "Sent payment_notice for ${txId.take(12)}")
            } catch (e: Exception) {
                // usually an empty chatting address: kept, and sent once it has KAS (IOS-086)
                Log.w(TAG, "payment_notice send failed for ${txId.take(12)}, kept for later: ${e.message}")
                paymentNoticeOutbox.keep(
                    PaymentNoticeOutbox.UnsentPaymentNotice(
                        contactAddress = contactId, payload = payload, txId = txId
                    )
                )
            }
        }
    }

    companion object {
        private const val TAG = "ChatPaymentSender"
        private const val JITTER_MIN = 0.10
        private const val JITTER_MAX = 0.35
        private const val MAX_BASE_DELAY_SECONDS = 60.0

        /** How many times a payment waiting for confirmed coins is tried again (about 8 minutes,
         *  the delays doubling to a minute) before it is marked Failed (iOS 4f8e14bb). */
        internal const val MAX_DEFERRED_PAYMENT_RETRIES = 12

        /** iOS `noSpendableFundsYetMessage`. */
        const val NO_SPENDABLE_FUNDS_YET = "No spendable funds available yet. Wait for confirmations and try again."

        /** iOS `nextNoInputRetryDelay`: 1, 2, 4 .. seconds, at most 60, plus [jitter] of it. */
        internal fun noInputRetryDelaySeconds(attempt: Int, jitter: Double): Double {
            val base = min(MAX_BASE_DELAY_SECONDS, 2.0.pow((attempt - 1).coerceAtLeast(0).toDouble()))
            return base + base * jitter
        }

        /** iOS `isNoConfirmedInputsError`. */
        internal fun isNoConfirmedInputsError(message: String?): Boolean {
            val lowered = message?.lowercase() ?: return false
            return lowered.contains("no spendable funds available yet") ||
                lowered.contains("no confirmed spendable utxos available") ||
                lowered.contains("no spendable utxos available") ||
                lowered.contains("no utxos available")
        }

        /** iOS `isInsufficientFundsError`. */
        internal fun isInsufficientFundsError(error: Throwable): Boolean =
            error.message?.lowercase()?.contains("insufficient funds") == true
    }
}
