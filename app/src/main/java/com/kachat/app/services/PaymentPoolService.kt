package com.kachat.app.services

import android.util.Log
import com.kachat.app.models.ContactEntity
import com.kachat.app.models.ContactNotificationMode
import com.kachat.app.models.MessageEntity
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.ChatRepository
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.MessageProtocol
import com.kachat.app.util.PaymentPoolProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Protocol logic for the fresh-address payment pool privacy feature (MESSAGING.md,
 * "Fresh-Address Payment Pools") - the Android port of iOS's `ChatService+PaymentPools.swift`.
 *
 * Contacts exchange batches of fresh, never-used spending-chain receive addresses through the
 * normal encrypted contextual channel (`addr_pool`), Send Kaspa pays one of those instead of the
 * contact's chatting address, and a `payment_notice` envelope keeps the recipient's chat showing
 * a payment bubble (their payment detection only watches the chatting address). All three
 * envelope types are invisible - intercepted in `ChatRepository.processContextualMessage` before
 * they could ever render, exactly like reactions. Persistent state lives in [PaymentPoolStore]
 * (device-local, per wallet).
 *
 * Every pool operation with side effects is serialized through [poolMutex], and every gate
 * (offered marker, serve throttle, privacy toggle) is re-checked INSIDE the serialized section -
 * several envelope handlers can queue offers for the same contact before the first one runs, and
 * the markers only flip once a send actually happens (the MESSAGING.md "re-checked at actual
 * send time" requirement).
 */
@Singleton
class PaymentPoolService @Inject constructor(
    private val store: PaymentPoolStore,
    private val walletManager: WalletManager,
    private val settingsRepository: AppSettingsRepository,
    private val networkService: NetworkService,
    private val notificationHelper: NotificationHelper,
    private val pushState: PushState,
    private val database: KaChatDatabase,
    // Lazy on both: WalletService and ChatRepository each sit above this service in the Dagger
    // graph via ChatRepository's own Lazy<PaymentPoolService> (the interception hook) - Lazy
    // breaks the constructor cycle.
    private val walletServiceLazy: dagger.Lazy<WalletService>,
    private val chatRepositoryLazy: dagger.Lazy<ChatRepository>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val poolMutex = Mutex()

    private fun walletAddressOrNull(): String? = try { walletManager.getAddress() } catch (e: Exception) { null }

    /** The active account's Chats Payment Privacy value - per account, default ON. */
    suspend fun isChatsPrivacyEnabled(): Boolean {
        val address = walletAddressOrNull() ?: return true
        return settingsRepository.chatsPaymentPrivacyEnabled(address).first()
    }

    // ------------------------------------------------------------------------------------
    // Conversation-open hooks (the lazy once-per-contact offer + low-water request)
    // ------------------------------------------------------------------------------------

    /** Called when a 1:1 conversation is opened - lazily offers our pool once per contact (for
     *  established conversations) and tops up our stored pool of theirs if it has run low. */
    fun onConversationOpened(contactId: String) {
        scope.launch {
            poolMutex.withLock {
                try {
                    offerAddressPoolIfNeededLocked(contactId, replace = true, toggleTransition = false)
                } catch (e: Exception) {
                    Log.w(TAG, "Lazy pool offer failed for ${contactId.takeLast(10)}", e)
                }
                try {
                    maybeRequestMorePoolAddressesLocked(contactId)
                } catch (e: Exception) {
                    Log.w(TAG, "Pool request failed for ${contactId.takeLast(10)}", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Offering our pool
    // ------------------------------------------------------------------------------------

    /** All gates re-checked here, inside the mutex. `replace = true` for initial/lazy offers and
     *  toggle-on re-offers; `replace = false` for request-driven top-ups. */
    private suspend fun offerAddressPoolIfNeededLocked(contactId: String, replace: Boolean, toggleTransition: Boolean) {
        val walletAddress = walletAddressOrNull() ?: return
        // Per-account toggle: while OFF this account stops sharing fresh addresses (also covers
        // the reciprocity path, which routes through here).
        if (!settingsRepository.chatsPaymentPrivacyEnabled(walletAddress).first()) return
        if (replace && store.hasOfferedPool(contactId, walletAddress)) return
        if (!store.canServePoolOffer(contactId, walletAddress, toggleTransition)) {
            Log.d(TAG, "Pool offer to ${contactId.takeLast(10)} suppressed by serve throttle/caps")
            return
        }
        if (!isEstablishedConversation(contactId, walletAddress)) return
        val contact = database.contactDao().getContact(contactId, walletAddress) ?: return

        // replace:true (initial offer or post-revoke re-offer) may re-send previously offered
        // but never-funded reservations - the recipient's pool was empty/discarded, re-sending
        // creates no reuse, and it keeps a toggle off/on cycle from burning five new indices
        // against the lifetime cap every time. Append top-ups only ever send never-yet-offered
        // addresses (the recipient dedupes, but resending their live pool would be waste).
        var pending = if (replace) {
            store.reofferableReservations(contactId, walletAddress)
        } else {
            store.unofferedReservations(contactId, walletAddress)
        }
        if (pending.size > PaymentPoolStore.OFFER_BATCH_SIZE) {
            pending = pending.take(PaymentPoolStore.OFFER_BATCH_SIZE)
        }
        // Never reserve past the per-contact lifetime cap, even mid-batch.
        val lifetimeHeadroom = PaymentPoolStore.MAX_LIFETIME_RESERVATIONS_PER_CONTACT -
            store.lifetimeReservationCount(contactId, walletAddress)
        val missing = minOf(PaymentPoolStore.OFFER_BATCH_SIZE - pending.size, lifetimeHeadroom)
        if (missing > 0) {
            val fresh = walletManager.allocateFreshSpendingIndices(missing)
            if (fresh.isEmpty()) {
                Log.w(TAG, "Pool offer aborted - could not reserve fresh spending addresses")
                return
            }
            // Pool reservations are born VISIBLE: they are real spending-chain addresses a
            // contact may fund at any time, so Manage Addresses shows them tagged
            // "Chat privacy address" instead of hiding them. While offered they cannot be
            // hidden at all (authoritative backstop in WalletManager.setSpendingAddressHidden);
            // a revoke releases them back to normal hideable rows.
            val entries = fresh.map { (index, address) ->
                PaymentPoolStore.ReservedAddress(address = address, index = index, offered = false, funded = null)
            }
            store.recordMyReservations(entries, contactId, walletAddress)
            pending = pending + entries
        }
        if (pending.isEmpty()) return

        val payload = PaymentPoolProtocol.encode(
            PaymentPoolProtocol.AddressPoolContent(addresses = pending.map { it.address }, replace = replace)
        )
        sendInvisibleEnvelope(contact.id, payload, walletAddress)

        if (replace) {
            // A replace batch is the contact's ENTIRE live pool from here on - unfunded
            // reservations left out of it are superseded and drop out of the active
            // "Chat privacy address" set (they revert to normal hideable rows).
            store.markReservationsOfferedExclusive(pending.map { it.address }, contactId, walletAddress)
        } else {
            store.markReservationsOffered(pending.map { it.address }, contactId, walletAddress)
        }
        // Actively offered reservations are always visible: freshly allocated indices were never
        // hidden, but a re-offered one may have been hidden while released (privacy off /
        // post-revoke) or by the old born-hidden design - a successful offer clears any
        // lingering hidden flag.
        pending.forEach { walletManager.setSpendingAddressHidden(walletAddress, it.index, false) }
        store.markPoolOffered(contactId, walletAddress)
        store.recordPoolOfferServed(contactId, walletAddress)
        store.clearPoolRevocation(contactId, walletAddress)
        Log.i(TAG, "Offered ${pending.size} fresh pool addresses to ${contactId.takeLast(10)} (replace=$replace)")
    }

    // ------------------------------------------------------------------------------------
    // Chats Payment Privacy toggle propagation
    // ------------------------------------------------------------------------------------

    /**
     * Called from the Settings toggle AFTER the per-account flag is persisted. Both directions
     * propagate PROACTIVELY - the toggle is the switch, not conversation-opening:
     *
     * - OFF revokes our pool at every contact holding one (empty replace:true - the wire
     *   revocation primitive) so their very next payment falls back to our chatting address.
     * - ON clears revocation markers and immediately broadcasts fresh offers to every
     *   established contact not currently holding a live pool - contacts we revoked AND
     *   established contacts never offered before. (The lazy conversation-open offer remains as
     *   backstop for conversations that become established later.)
     *
     * Toggle broadcasts clear the short 60s transition gap per contact rather than the full
     * 10-minute serve throttle, so deliberate flips propagate promptly while rapid flapping
     * stays bounded to one broadcast per contact per gap.
     */
    fun handleChatsPrivacyToggleChanged(enabled: Boolean) {
        val walletAddress = walletAddressOrNull() ?: return
        scope.launch {
            // Mirror the toggle into the pool store FIRST: toggle off releases every offered
            // reservation instantly (tags drop, rows become hideable) without waiting for the
            // per-contact revoke envelopes below - which can be slow, gap-deferred, or fail.
            // The historical mapping and address watching are untouched either way.
            store.setPoolsReleased(!enabled, walletAddress)
            if (enabled) {
                store.clearAllPoolRevocations(walletAddress)
                reofferPoolsForToggleOn(walletAddress)
            } else {
                revokePoolsForToggleOff(walletAddress)
            }
        }
    }

    private suspend fun reofferPoolsForToggleOn(walletAddress: String) {
        val contacts = try {
            database.contactDao().getContactsByStatus("active", walletAddress)
        } catch (e: Exception) {
            emptyList()
        }
        val targets = contacts.map { it.id }.filter { contactId ->
            isEstablishedConversation(contactId, walletAddress) && !store.hasOfferedPool(contactId, walletAddress)
        }
        if (targets.isEmpty()) return
        Log.i(TAG, "Chats Payment Privacy on - re-offering pools to ${targets.size} contacts")
        for (contactId in targets) {
            // The toggle may flip back OFF mid-broadcast - stop offering.
            if (!settingsRepository.chatsPaymentPrivacyEnabled(walletAddress).first()) return
            poolMutex.withLock {
                try {
                    offerAddressPoolIfNeededLocked(contactId, replace = true, toggleTransition = true)
                } catch (e: Exception) {
                    Log.w(TAG, "Toggle-on pool offer to ${contactId.takeLast(10)} failed (lazy offer remains)", e)
                }
            }
        }
    }

    private suspend fun revokePoolsForToggleOff(walletAddress: String) {
        val targets = store.contactsHoldingOurPool(walletAddress)
        if (targets.isEmpty()) return
        Log.i(TAG, "Chats Payment Privacy off - revoking offered pools at ${targets.size} contacts")
        for (contactId in targets) {
            // The toggle may flip back ON mid-broadcast - stop revoking, the remaining contacts
            // keep their (again welcome) pools.
            if (settingsRepository.chatsPaymentPrivacyEnabled(walletAddress).first()) return
            poolMutex.withLock {
                // Re-checked once serialized, same reasoning as offers.
                if (settingsRepository.chatsPaymentPrivacyEnabled(walletAddress).first()) return
                if (store.isPoolRevoked(contactId, walletAddress)) return@withLock
                // Flap bound: revokes bypass the reservation caps (a revoke must always be
                // allowed out) but honor the 60s transition gap per contact. A gap-skipped
                // contact keeps its markers, so a later toggle-off retries it; meanwhile the
                // residual-drain backstop applies.
                if (store.isWithinPoolServeGap(contactId, walletAddress, toggleTransition = true)) {
                    Log.d(TAG, "Revoke to ${contactId.takeLast(10)} deferred by transition gap")
                    return@withLock
                }
                try {
                    val payload = PaymentPoolProtocol.encode(
                        PaymentPoolProtocol.AddressPoolContent(addresses = emptyList(), replace = true)
                    )
                    sendInvisibleEnvelope(contactId, payload, walletAddress)
                    store.markPoolRevoked(contactId, walletAddress)
                    store.recordPoolOfferServed(contactId, walletAddress)
                    Log.i(TAG, "Revoked pool at ${contactId.takeLast(10)}")
                } catch (e: Exception) {
                    Log.w(TAG, "Pool revoke to ${contactId.takeLast(10)} failed (non-fatal, residual drain applies)", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Requesting more of THEIR pool
    // ------------------------------------------------------------------------------------

    private suspend fun maybeRequestMorePoolAddressesLocked(contactId: String) {
        val walletAddress = walletAddressOrNull() ?: return
        // Privacy OFF: we aren't consuming pool addresses, so never ask for more.
        if (!settingsRepository.chatsPaymentPrivacyEnabled(walletAddress).first()) return
        if (!store.shouldRequestMoreAddresses(contactId, walletAddress)) return
        try {
            val payload = PaymentPoolProtocol.encode(PaymentPoolProtocol.AddressPoolRequestContent())
            sendInvisibleEnvelope(contactId, payload, walletAddress)
            store.recordPoolRequestSent(contactId, walletAddress)
            Log.i(TAG, "Requested fresh pool addresses from ${contactId.takeLast(10)}")
        } catch (e: Exception) {
            Log.w(TAG, "addr_pool_request send failed", e)
        }
    }

    // ------------------------------------------------------------------------------------
    // Paying into a contact's pool (called by the send path)
    // ------------------------------------------------------------------------------------

    /**
     * The destination address for a payment to [contactId]: an unused address from their stored
     * pool if one exists (consumed immediately - persisted, never offered to another payment
     * even if this one fails), else the chatting address (exact pre-pool behavior). A retry of
     * the same payment (same [pendingTxId]) reuses the address already consumed for it.
     *
     * Deliberately NOT gated on the sender's Chats Payment Privacy toggle: the RECIPIENT'S
     * privacy governs the destination - if they shared fresh addresses, money arrives on one
     * no matter the sender's setting. The sender's toggle only governs the FUNDING side.
     */
    suspend fun poolPaymentDestination(contactId: String, pendingTxId: String): String {
        val walletAddress = walletAddressOrNull() ?: return contactId
        store.paymentDestination(pendingTxId)?.let { return it }
        val poolAddress = store.nextUnusedPoolAddress(contactId, walletAddress) ?: return contactId
        if (!KaspaAddress.isValid(poolAddress)) return contactId
        store.markPoolAddressUsed(poolAddress, contactId, walletAddress)
        store.rememberPaymentDestination(poolAddress, pendingTxId)
        Log.i(TAG, "Payment to ${contactId.takeLast(10)} will use fresh pool address ${poolAddress.takeLast(10)}")
        return poolAddress
    }

    /** True when the NEXT payment to this contact would go to a fresh pool address - drives the
     *  subtle fresh-address indicator in the payment composer. Matches [poolPaymentDestination]:
     *  recipient-governed, independent of the sender's privacy toggle. */
    suspend fun willPayViaFreshPoolAddress(contactId: String): Boolean {
        val walletAddress = walletAddressOrNull() ?: return false
        return store.nextUnusedPoolAddress(contactId, walletAddress) != null
    }

    /**
     * Called by the payment send path after the payment tx is accepted: for a pool-address
     * payment, sends the `payment_notice` envelope (fire-and-forget) so the recipient's chat
     * shows the payment bubble their chain-side detection would miss, then checks the low-water
     * mark. No-op for chatting-address payments - existing detection already covers those.
     */
    fun handlePoolPaymentSubmitted(contactId: String, txId: String, amountSompi: Long, destinationAddress: String, pendingTxId: String) {
        store.forgetPaymentDestination(pendingTxId)
        if (destinationAddress == contactId) return
        scope.launch {
            val walletAddress = walletAddressOrNull() ?: return@launch
            poolMutex.withLock {
                try {
                    val payload = PaymentPoolProtocol.encode(
                        PaymentPoolProtocol.PaymentNoticeContent(txId = txId, amountSompi = amountSompi, address = destinationAddress)
                    )
                    sendInvisibleEnvelope(contactId, payload, walletAddress)
                    Log.i(TAG, "Sent payment_notice for ${txId.take(12)}")
                } catch (e: Exception) {
                    // The payment itself succeeded; a lost notice only means the recipient's
                    // bubble waits for a manual sync. Not retried automatically.
                    Log.w(TAG, "payment_notice send failed for ${txId.take(12)}", e)
                }
                try {
                    maybeRequestMorePoolAddressesLocked(contactId)
                } catch (e: Exception) {
                    Log.w(TAG, "Post-payment pool request failed", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Receiving envelopes (front door, called from ChatRepository's message interception)
    // ------------------------------------------------------------------------------------

    /**
     * Front door for all three pool envelope types, intercepted in
     * `ChatRepository.processContextualMessage` before a MessageEntity is ever created - these
     * never become bubbles (except the payment bubble a `payment_notice` deliberately creates).
     * Replay-guarded by envelope txId: history re-fetch replays the same envelopes and must not
     * re-trigger reservation sends or pool merges.
     */
    suspend fun handleIncomingEnvelope(envelope: PaymentPoolProtocol.Envelope, txId: String, blockTime: Long, contact: ContactEntity, myAddress: String) {
        if (store.isEnvelopeHandled(txId, myAddress)) return
        store.markEnvelopeHandled(txId, myAddress)

        when (envelope) {
            is PaymentPoolProtocol.Envelope.Pool -> {
                // Deliberately NOT gated by Chats Payment Privacy: an incoming pool is harmless
                // to store and ready the moment the user re-enables the toggle. (The reciprocity
                // offer it may trigger IS gated, inside the offer path.)
                if (!isEstablishedConversation(contact.id, myAddress)) {
                    Log.d(TAG, "Ignoring addr_pool from non-established conversation ${contact.id.takeLast(10)}")
                    return
                }
                acceptIncomingAddressPool(envelope.content, contact, myAddress)
            }

            is PaymentPoolProtocol.Envelope.Request -> {
                // Privacy OFF: silently ignore (same no-error semantics as the rate limits).
                if (!settingsRepository.chatsPaymentPrivacyEnabled(myAddress).first()) {
                    Log.d(TAG, "Ignoring addr_pool_request from ${contact.id.takeLast(10)} - Chats Payment Privacy off")
                    return
                }
                if (!isEstablishedConversation(contact.id, myAddress)) return
                // Inbound abuse gate: every reply costs us a reservation batch AND an on-chain
                // tx fee - a spamming contact gets at most one top-up per 10 minutes, and
                // nothing once the lifetime/outstanding-unfunded caps are hit (re-checked inside
                // the serialized offer once the mutex admits us).
                if (!store.canServePoolOffer(contact.id, myAddress)) {
                    Log.d(TAG, "Ignoring addr_pool_request from ${contact.id.takeLast(10)} - serve throttle/caps")
                    return
                }
                scope.launch {
                    poolMutex.withLock {
                        try {
                            // Top-up batch: append semantics, the recipient dedupes.
                            offerAddressPoolIfNeededLocked(contact.id, replace = false, toggleTransition = false)
                        } catch (e: Exception) {
                            Log.w(TAG, "Request-driven pool top-up failed", e)
                        }
                    }
                }
            }

            is PaymentPoolProtocol.Envelope.Notice -> {
                // Deliberately NOT gated by the toggle: previously offered addresses remain
                // valid whatever the toggle says now, so payments to them must keep rendering.
                createPaymentBubbleFromNotice(envelope.content, contact, myAddress, blockTime)
            }
        }
    }

    /** Validates and stores a received `addr_pool` as "addresses I can pay this contact at". */
    private suspend fun acceptIncomingAddressPool(content: PaymentPoolProtocol.AddressPoolContent, contact: ContactEntity, myAddress: String) {
        val expectedPrefix = "kaspa:"
        val accepted = mutableListOf<String>()
        for (raw in content.addresses.take(PaymentPoolStore.MAX_STORED_POOL_SIZE)) {
            val address = raw.trim()
            val valid = address.startsWith(expectedPrefix) &&
                KaspaAddress.isValid(address) &&
                address != myAddress &&
                !store.isReservedAddress(address, myAddress) &&
                !walletManager.isOwnSpendingAddress(address)
            if (!valid) {
                Log.w(TAG, "Rejected pool address from ${contact.id.takeLast(10)}: ${address.takeLast(14)}")
                continue
            }
            accepted.add(address)
        }

        // REVOCATION PRIMITIVE (must be honored - see MESSAGING.md): a replace:true pool that is
        // empty after validation clears this contact's stored pool entirely. The contact turned
        // off their privacy toggle (or is retracting an offer) - our next payment to them falls
        // back to their chatting address, and willPayViaFreshPoolAddress goes false the moment
        // the empty pool is stored. No reciprocity on a revoke.
        if (content.replace == true && accepted.isEmpty()) {
            store.mergeTheirPool(emptyList(), replace = true, contactAddress = contact.id, walletAddress = myAddress)
            Log.i(TAG, "Pool REVOKED by ${contact.id.takeLast(10)} - cleared stored pool")
            return
        }
        if (accepted.isEmpty()) return

        store.mergeTheirPool(accepted, replace = content.replace == true, contactAddress = contact.id, walletAddress = myAddress)
        Log.i(TAG, "Stored ${accepted.size} pool addresses for ${contact.id.takeLast(10)} (replace=${content.replace == true})")

        // Reciprocity: they shared theirs - if they've never gotten ours, offer now (all gates,
        // including the privacy toggle and serve throttle, are re-checked inside).
        if (!store.hasOfferedPool(contact.id, myAddress)) {
            scope.launch {
                poolMutex.withLock {
                    try {
                        offerAddressPoolIfNeededLocked(contact.id, replace = true, toggleTransition = false)
                    } catch (e: Exception) {
                        Log.w(TAG, "Reciprocal pool offer failed", e)
                    }
                }
            }
        }
    }

    /**
     * Renders a received `payment_notice` as a normal incoming payment bubble, deduped by the
     * payment's txId. Rendering is NOT blocked on chain verification (the notice arrived over the
     * sender-authenticated encrypted channel), but a background check against the REST API
     * corrects the amount from chain data and flags the bubble with a warning state if the
     * referenced tx has no output to the claimed address.
     */
    private suspend fun createPaymentBubbleFromNotice(content: PaymentPoolProtocol.PaymentNoticeContent, contact: ContactEntity, myAddress: String, noticeBlockTime: Long) {
        val txId = content.txId.trim().lowercase()
        if (txId.isEmpty() || content.amountSompi <= 0) return

        // The notice names the reserved address the contact paid - record it funded so the
        // outstanding-unfunded-offers cap reflects genuine pool usage (no-op if the address
        // isn't one of our reservations for this contact).
        store.markReservationFunded(content.address, contact.id, myAddress)
        // The reserved address now holds money - funded addresses are always visible.
        // Reservations are born visible now; this self-heals rows hidden by the old
        // born-hidden design (the list loader's migration purge covers the rest).
        store.reservationIndex(content.address, myAddress)?.let { index ->
            walletManager.setSpendingAddressHidden(myAddress, index, false)
        }

        if (database.messageDao().exists(txId, myAddress)) return

        // See AppSettingsRepository.liveNotificationBaseline: a notice older than this account's
        // first sync on this device is history being replayed (e.g. right after a seed import) -
        // insert it already-read and skip the notification, matching every other backfill insert
        // path (ChatRepository.isBackfill).
        val backfill = noticeBlockTime < settingsRepository.liveNotificationBaseline(myAddress)
        val displayText = "Received ${ChatRepository.formatKas(content.amountSompi)} KAS"
        chatRepositoryLazy.get().insertMessage(
            MessageEntity(
                id = txId,
                contactId = contact.id,
                walletAddress = myAddress,
                type = MessageProtocol.TYPE_PAY,
                direction = "received",
                plaintextBody = displayText,
                encryptedPayload = "",
                amountSompi = content.amountSompi,
                blockTimestamp = noticeBlockTime,
                isRead = backfill || notificationHelper.isViewingContact(contact.id)
            )
        )
        Log.i(TAG, "Created payment bubble from payment_notice ${txId.take(12)}")

        if (!backfill && !pushState.isActive) { // payment pushes come from the server in remote-push mode
            notificationHelper.show(
                contactId = contact.id,
                title = "Payment received",
                text = displayText,
                notificationOverride = ContactNotificationMode.fromName(contact.notificationOverride)
            )
        }

        verifyPaymentNoticeAgainstChain(txId, content.address, content.amountSompi, myAddress)
    }

    /** Best-effort background verification of a `payment_notice` against the on-chain tx. Silent
     *  on network failure (verification is opportunistic by design). */
    private suspend fun verifyPaymentNoticeAgainstChain(txId: String, claimedAddress: String, claimedAmount: Long, myAddress: String) {
        val api = networkService.kaspaRestApi.value ?: return
        val tx = try { api.getTransaction(txId) } catch (e: Exception) { return }
        val paidToClaimed = tx.outputs.filter { it.scriptPublicKeyAddress == claimedAddress }.sumOf { it.amount }
        if (paidToClaimed == 0L) {
            Log.w(TAG, "payment_notice ${txId.take(12)} FAILED verification - no output to claimed address")
            database.messageDao().updatePaymentVerification(
                id = txId,
                walletAddress = myAddress,
                body = "Received ${ChatRepository.formatKas(claimedAmount)} KAS",
                amountSompi = claimedAmount,
                status = "warning"
            )
        } else if (paidToClaimed != claimedAmount) {
            // Chain is authoritative for the amount.
            database.messageDao().updatePaymentVerification(
                id = txId,
                walletAddress = myAddress,
                body = "Received ${ChatRepository.formatKas(paidToClaimed)} KAS",
                amountSompi = paidToClaimed,
                status = "sent"
            )
        }
    }

    // ------------------------------------------------------------------------------------
    // Shared plumbing
    // ------------------------------------------------------------------------------------

    /** A conversation counts as established for pool purposes once both directions have spoken
     *  (at least one incoming AND one outgoing message) - the same bar for offering our pool and
     *  for accepting a contact's. */
    private suspend fun isEstablishedConversation(contactId: String, walletAddress: String): Boolean {
        val dao = database.messageDao()
        return dao.hasMessageWithDirection(contactId, walletAddress, "sent") &&
            dao.hasMessageWithDirection(contactId, walletAddress, "received")
    }

    /**
     * Sends an invisible pool envelope through the normal encrypted contextual-message pipeline
     * ([WalletService.sendKasiaMessage] - the exact tx shape a chat message uses), minus any
     * bubble/DB bookkeeping - these envelopes must never surface in the conversation. Marks the
     * submitted txId handled so an indexer replay of our own envelope is dropped by the guard.
     */
    private suspend fun sendInvisibleEnvelope(contactId: String, payload: String, walletAddress: String) {
        val result = walletServiceLazy.get().sendKasiaMessage(contactId, payload)
        store.markEnvelopeHandled(result.txId, walletAddress)
    }

    /** Every reservation address ever recorded for the active wallet - included in the
     *  own-address watch set (see AddressActivityNotifier) so incoming pool payments are noticed
     *  promptly. Uses the HISTORICAL mapping, never the active offered set: a contact may pay a
     *  reservation racing a revoke/supersession, and that payment must still be noticed.
     *  Reservations also live on the spending chain within the revealed index range, so
     *  [WalletManager.allSpendingAddresses] covers them structurally as well. */
    fun offeredReservationAddresses(): List<String> {
        val walletAddress = walletAddressOrNull() ?: return emptyList()
        return store.allReservationAddresses(walletAddress)
    }

    companion object {
        private const val TAG = "PaymentPoolService"
    }
}
