package com.kachat.app.services

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-local persistence for the fresh-address payment pool feature (MESSAGING.md,
 * "Fresh-Address Payment Pools"; protocol logic in [PaymentPoolService]). Port of iOS's
 * `PaymentPoolStore.swift` - same state shape, same per-wallet scoping.
 *
 * Two directions of state, both scoped per wallet (keyed by the wallet's chatting address):
 *
 * - **My reservations**: spending-chain addresses THIS wallet revealed and offered to a specific
 *   contact so that contact can pay us privately. CRITICAL INVARIANT: an address reserved for
 *   contact X is never offered to any other contact and never re-offered - reservations are
 *   recorded here per contact, and [WalletManager.allocateFreshSpendingIndices] only ever hands
 *   out indices past the all-time max, so the two together make double-offering impossible.
 * - **Their pools**: addresses a contact shared with us via `addr_pool` - "addresses I can pay
 *   this contact at". Each is single-use: consumed (marked used) when a payment send selects it.
 *
 * Deliberately device-local (NOT backed up/synced): a restore onto a new device simply loses it
 * and the apps re-exchange pools - re-offering is safe because the initial offer always uses
 * `replace: true`.
 */
@Singleton
class PaymentPoolStore @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        /** How many fresh addresses each `addr_pool` offer contains - also the level automatic
         *  replenishment tops the contact's pool back up to (MESSAGING.md: pool of 2 with
         *  additive replenish). */
        const val OFFER_BATCH_SIZE = 2
        /** Send an `addr_pool_request` when the unused remainder of a contact's pool drops to
         *  this or lower. Scaled to the batch of 2 (a fresh pool must sit ABOVE the mark or
         *  every open would immediately re-request); mostly a backstop now that the offering
         *  side replenishes automatically when it detects a reservation was funded. */
        const val LOW_WATER_MARK = 1
        /** Reject received pools that would grow a contact's stored pool beyond this. */
        const val MAX_STORED_POOL_SIZE = 20
        /** Cap on the remembered handled-envelope txId list (replay guard). */
        private const val MAX_HANDLED_TX_IDS = 500
        /** Minimum spacing between `addr_pool_request` sends to the same contact. */
        private const val REQUEST_THROTTLE_MS = 10L * 60L * 1000L

        // Inbound abuse limits (part of the protocol contract - see MESSAGING.md)

        /** Serve at most one addr_pool send (top-up reply, reciprocity, or initial offer) per
         *  contact per this interval. */
        const val POOL_SERVE_THROTTLE_MS = 10L * 60L * 1000L
        /** Transition-aware minimum gap for TOGGLE-driven broadcasts (revoke on OFF, re-offer on
         *  ON) - deliberate flips propagate promptly, rapid flapping stays bounded to one
         *  broadcast per contact per gap. */
        const val TOGGLE_TRANSITION_GAP_MS = 60L * 1000L
        /** Hard lifetime cap on addresses ever reserved for a single contact. */
        const val MAX_LIFETIME_RESERVATIONS_PER_CONTACT = 50
        /** Stop serving top-ups once this many offered addresses are outstanding without ever
         *  having received funds. */
        const val MAX_OUTSTANDING_UNFUNDED_OFFERS = 15

        private const val PREFS_NAME = "kachat_payment_pool_state"
    }

    data class ReservedAddress(
        val address: String,
        val index: Int,
        /** True once the addr_pool envelope carrying this address was actually submitted. */
        val offered: Boolean,
        /** True once a payment_notice from the contact named this address as a payment destination. */
        val funded: Boolean? = null,
        /** True once Generate reclaimed this reverted reservation as a personal fresh address.
         *  Reclaimed entries are never re-offered to their original contact on a privacy
         *  re-enable; the entry stays so watching and payment rendering keep covering it. */
        val reclaimed: Boolean? = null
    )

    data class TheirPoolAddress(
        val address: String,
        val used: Boolean
    )

    private data class State(
        /** contactAddress -> my reserved spending addresses offered to that contact. */
        var myReservations: MutableMap<String, MutableList<ReservedAddress>> = mutableMapOf(),
        /** contactAddress -> that contact's fresh addresses I can pay them at. */
        var theirPools: MutableMap<String, MutableList<TheirPoolAddress>> = mutableMapOf(),
        /** Contacts we have already sent our initial pool to (the lazy once-per-contact offer marker). */
        var offeredContacts: MutableSet<String> = mutableSetOf(),
        /** Envelope txIds already processed (replay guard). Ordered oldest-first so capping drops the oldest. */
        var handledEnvelopeTxIds: MutableList<String> = mutableListOf(),
        /** contactAddress -> last time (epoch ms) we sent addr_pool_request (throttle). */
        var lastPoolRequestAt: MutableMap<String, Long> = mutableMapOf(),
        /** contactAddress -> last time (epoch ms) we SERVED an addr_pool send (inbound abuse throttle). */
        var lastPoolServeAt: MutableMap<String, Long> = mutableMapOf(),
        /** Contacts whose pool of OUR addresses we revoked (empty replace:true sent) on toggle-off. */
        var revokedContacts: MutableSet<String> = mutableSetOf(),
        /** Contacts who revoked OUR pool at THEM (incoming empty replace:true - their Chats
         *  Payment Privacy went off). While marked, proactive sends (auto-replenish, toggle-on
         *  re-offer) never push addresses at them - their active count is 0 by revocation, not
         *  by consumption. Cleared when they show renewed interest: an addr_pool_request, a
         *  non-empty pool offer from them, or any successful offer of ours. Gson defaults old
         *  persisted state to an empty set. */
        var contactsRevokedAtUs: MutableSet<String> = mutableSetOf(),
        /** True while this wallet's Chats Payment Privacy toggle is OFF: every offered
         *  reservation is RELEASED - the whole active offered set empties at once, instantly,
         *  while the per-contact revoke envelopes still go out behind it (see
         *  PaymentPoolService.revokePoolsForToggleOff). Mirrored from the settings toggle by
         *  handleChatsPrivacyToggleChanged. Gson defaults old persisted state to false
         *  (privacy on), matching the toggle's per-account default. */
        var poolsReleased: Boolean = false
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** Cache of the loaded state per wallet, so every query isn't a decode round trip. */
    private val cachedState = mutableMapOf<String, State>()

    /**
     * Payment-destination memory for in-flight sends, keyed by the payment's pending id so a
     * retry reuses the pool address already consumed for that payment instead of burning another.
     * Deliberately in-memory only - after an app restart a retried payment just consumes a fresh
     * pool address, which is safe (addresses are never reused, only occasionally skipped).
     */
    private val pendingPaymentDestinations = mutableMapOf<String, String>()

    private fun key(walletAddress: String) = "pool_state_$walletAddress"

    @Synchronized
    private fun state(walletAddress: String): State {
        cachedState[walletAddress]?.let { return it }
        val loaded = prefs.getString(key(walletAddress), null)?.let { json ->
            try { gson.fromJson(json, State::class.java) } catch (e: Exception) { null }
        } ?: State()
        cachedState[walletAddress] = loaded
        return loaded
    }

    @Synchronized
    private fun save(state: State, walletAddress: String) {
        cachedState[walletAddress] = state
        prefs.edit().putString(key(walletAddress), gson.toJson(state)).apply()
    }

    // --- Offer marker -------------------------------------------------------------------

    @Synchronized
    fun hasOfferedPool(contactAddress: String, walletAddress: String): Boolean =
        state(walletAddress).offeredContacts.contains(contactAddress)

    @Synchronized
    fun markPoolOffered(contactAddress: String, walletAddress: String) {
        val s = state(walletAddress)
        s.offeredContacts.add(contactAddress)
        save(s, walletAddress)
    }

    // --- My reservations (addresses I offered so a contact can pay ME) ------------------

    @Synchronized
    fun recordMyReservations(entries: List<ReservedAddress>, contactAddress: String, walletAddress: String) {
        if (entries.isEmpty()) return
        val s = state(walletAddress)
        val existing = s.myReservations.getOrPut(contactAddress) { mutableListOf() }
        val known = existing.map { it.address }.toSet()
        existing.addAll(entries.filter { it.address !in known })
        save(s, walletAddress)
    }

    /** Reservations whose addr_pool send never succeeded - a retried offer re-uses these before revealing new indices. */
    @Synchronized
    fun unofferedReservations(contactAddress: String, walletAddress: String): List<ReservedAddress> =
        (state(walletAddress).myReservations[contactAddress] ?: emptyList()).filter { !it.offered }

    /** Reservations that can be (re-)offered in a `replace:true` batch: everything never funded,
     *  whether or not it was offered before - so a toggle off/on cycle doesn't burn five new
     *  indices each time (never-funded means re-offering creates no address reuse). */
    @Synchronized
    fun reofferableReservations(contactAddress: String, walletAddress: String): List<ReservedAddress> =
        (state(walletAddress).myReservations[contactAddress] ?: emptyList()).filter { it.funded != true && it.reclaimed != true }

    /** Generate recycled a reverted reservation for personal use: never re-offer it to its
     *  original contact. No-op for addresses that were never reservations. */
    @Synchronized
    fun markReclaimed(address: String, walletAddress: String) {
        val s = state(walletAddress)
        var changed = false
        for ((contact, entries) in s.myReservations) {
            val updated = entries.map { entry ->
                if (entry.address == address && entry.reclaimed != true) {
                    changed = true
                    entry.copy(reclaimed = true)
                } else entry
            }
            if (changed) s.myReservations[contact] = updated.toMutableList()
        }
        if (changed) save(s, walletAddress)
    }

    /**
     * User-initiated: take this address out of Chats Payment Privacy for good.
     *
     * Distinct from [markReservationFundedByAddress], which records that a payment arrived. This
     * says nothing about payments - it is someone looking at the Chat Privacy tab and deciding an
     * address should be an ordinary spending address again, usually because it is holding a
     * balance the automatic detection never noticed.
     *
     * Clears [ReservedAddress.offered] so it leaves the live pool ([activeOfferedReservationAddresses]
     * and [isIndexOfferedForPrivacy] both filter on it), and marks it reclaimed so a privacy
     * re-enable never offers it back to its original contact.
     */
    @Synchronized
    fun releaseReservation(address: String, walletAddress: String): Boolean {
        val s = state(walletAddress)
        var released = false
        for ((contact, entries) in s.myReservations) {
            val i = entries.indexOfFirst { it.address == address }
            if (i < 0) continue
            entries[i] = entries[i].copy(offered = false, reclaimed = true)
            s.myReservations[contact] = entries
            released = true
            break
        }
        if (released) save(s, walletAddress)
        return released
    }

    @Synchronized
    fun markReservationsOffered(addresses: List<String>, contactAddress: String, walletAddress: String) {
        if (addresses.isEmpty()) return
        val s = state(walletAddress)
        val entries = s.myReservations[contactAddress] ?: return
        val target = addresses.toSet()
        for (i in entries.indices) {
            if (entries[i].address in target) entries[i] = entries[i].copy(offered = true)
        }
        // A successful offer supersedes any standing revoked-at-us marker - the contact holds
        // live addresses of ours again, so proactive replenishes are meaningful again.
        s.contactsRevokedAtUs.remove(contactAddress)
        save(s, walletAddress)
    }

    /** Replace-offer bookkeeping: [addresses] is now the contact's ENTIRE live pool, so any
     *  unfunded reservation NOT in the batch was just superseded (the contact's replace:true
     *  pool no longer includes it) - its offered flag drops, taking it out of the active
     *  "Chat privacy address" set, while the entry itself stays in the historical mapping (a
     *  payment racing the supersession still renders and gets noticed). Funded entries keep
     *  their flags untouched. */
    @Synchronized
    fun markReservationsOfferedExclusive(addresses: List<String>, contactAddress: String, walletAddress: String) {
        val s = state(walletAddress)
        val entries = s.myReservations[contactAddress] ?: return
        val target = addresses.toSet()
        for (i in entries.indices) {
            val e = entries[i]
            entries[i] = when {
                e.address in target -> e.copy(offered = true)
                e.funded != true -> e.copy(offered = false)
                else -> e
            }
        }
        // Same renewed-interest clear as markReservationsOffered: the contact holds a live
        // pool of ours again.
        s.contactsRevokedAtUs.remove(contactAddress)
        save(s, walletAddress)
    }

    @Synchronized
    fun lifetimeReservationCount(contactAddress: String, walletAddress: String): Int =
        state(walletAddress).myReservations[contactAddress]?.size ?: 0

    /** Marks one of our reservations funded (a payment_notice named it as a destination) - feeds
     *  the outstanding-unfunded-offers cap; no-op if the address isn't one of our reservations.
     *  Returns true only when the flag NEWLY flipped - the caller uses that as the auto-replenish
     *  trigger (a replayed notice or an already-funded address must not re-trigger a top-up). */
    @Synchronized
    fun markReservationFunded(address: String, contactAddress: String, walletAddress: String): Boolean {
        val s = state(walletAddress)
        val entries = s.myReservations[contactAddress] ?: return false
        val i = entries.indexOfFirst { it.address == address }
        if (i < 0 || entries[i].funded == true) return false
        entries[i] = entries[i].copy(funded = true)
        save(s, walletAddress)
        return true
    }

    /** [markReservationFunded] variant for the UTXO-watch funding path, where only the address is
     *  known: finds which contact the reservation belongs to and returns that contactAddress when
     *  the funded flag newly flipped, null otherwise (unknown address or already funded). */
    @Synchronized
    fun markReservationFundedByAddress(address: String, walletAddress: String): String? {
        val s = state(walletAddress)
        for ((contact, entries) in s.myReservations) {
            val i = entries.indexOfFirst { it.address == address }
            if (i < 0) continue
            if (entries[i].funded == true) return null
            entries[i] = entries[i].copy(funded = true)
            save(s, walletAddress)
            return contact
        }
        return null
    }

    /** How many addresses the contact currently holds that are still fresh and payable: offered,
     *  never funded, not revoked/released. The auto-replenish target compares this against
     *  [OFFER_BATCH_SIZE] and tops up the shortfall. */
    @Synchronized
    fun activeFreshReservationCount(contactAddress: String, walletAddress: String): Int {
        val s = state(walletAddress)
        if (s.poolsReleased || contactAddress in s.revokedContacts) return 0
        return s.myReservations[contactAddress]?.count { it.offered && it.funded != true } ?: 0
    }

    /** The spending-chain index of one of our reservations, by address - used to make sure the
     *  address is visible once a payment_notice marks it funded (reservations are born visible
     *  now, but rows hidden under the old born-hidden design self-heal through this). */
    @Synchronized
    fun reservationIndex(address: String, walletAddress: String): Int? =
        state(walletAddress).myReservations.values.flatten().firstOrNull { it.address == address }?.index

    /** Every reservation address ever recorded for this wallet - the HISTORICAL mapping for the
     *  own-address watched set. Deliberately ignores offered/funded/revoked/released state: a
     *  contact that ever held one of these addresses may still pay it (a payment racing a revoke
     *  or supersession), and that payment must be noticed promptly no matter what the active
     *  offered set says today. */
    @Synchronized
    fun allReservationAddresses(walletAddress: String): List<String> =
        state(walletAddress).myReservations.values.flatten().map { it.address }

    /** True if [address] is reserved (for ANY contact) by this wallet. */
    @Synchronized
    fun isReservedAddress(address: String, walletAddress: String): Boolean =
        state(walletAddress).myReservations.values.any { entries -> entries.any { it.address == address } }

    /**
     * Addresses ACTIVELY offered to a contact for private payments right now - the
     * "Chat privacy address" set: tagged and locked visible in Manage Addresses, refused by every
     * hide path, and excluded from Generate's recycling. Fully DERIVED from existing persisted
     * pool state (no parallel set), and deliberately NOT monotonic - an address leaves it when:
     *
     * - our Chats Payment Privacy toggle goes off ([setPoolsReleased] empties the whole set
     *   instantly, ahead of the per-contact revoke envelopes),
     * - its contact's pool was individually revoked ([markPoolRevoked]),
     * - a replace:true re-offer superseded it (the contact's live pool no longer includes it -
     *   [markReservationsOfferedExclusive] drops its offered flag), or
     * - it got funded (funded rows stay un-hideable through the balance rule; the tag drops).
     *
     * After leaving the set the address is a normal row: no tag, hideable, reclaimable by
     * Generate once hidden. The HISTORICAL reservation mapping ([reservationIndex],
     * [markReservationFunded], [allReservationAddresses]) is untouched by every one of these
     * transitions, so a payment racing a revoke still lands, renders, and gets noticed.
     */
    @Synchronized
    fun activeOfferedReservationAddresses(walletAddress: String): Set<String> {
        val s = state(walletAddress)
        if (s.poolsReleased) return emptySet()
        return s.myReservations
            .filterKeys { it !in s.revokedContacts }
            .values
            .flatMap { entries -> entries.filter { it.offered && it.funded != true }.map { it.address } }
            .toSet()
    }

    /** Index variant of [activeOfferedReservationAddresses] - the authoritative "is this row
     *  locked visible for chat privacy" check every hide path's backstop queries (the pool store
     *  decides, never a cached row flag). */
    @Synchronized
    fun isIndexOfferedForPrivacy(index: Int, walletAddress: String): Boolean {
        val s = state(walletAddress)
        if (s.poolsReleased) return false
        return s.myReservations.any { (contact, entries) ->
            contact !in s.revokedContacts && entries.any { it.index == index && it.offered && it.funded != true }
        }
    }

    /** Mirrors the wallet's Chats Payment Privacy toggle into the pool state: released = toggle
     *  OFF. Releasing empties the active offered set at once; un-releasing restores whatever the
     *  per-contact state still says (revocations are cleared separately on toggle-on). */
    @Synchronized
    fun setPoolsReleased(released: Boolean, walletAddress: String) {
        val s = state(walletAddress)
        if (s.poolsReleased == released) return
        s.poolsReleased = released
        save(s, walletAddress)
    }

    // --- Revocation lifecycle (Chats Payment Privacy toggle) ----------------------------

    /** Contacts currently holding a live pool of OUR addresses - the toggle-off revoke target
     *  list. Union of the offered-marker set and contacts holding offered-flagged reservations
     *  (belt-and-suspenders against marker drift), minus already-revoked. */
    @Synchronized
    fun contactsHoldingOurPool(walletAddress: String): List<String> {
        val s = state(walletAddress)
        val holders = s.offeredContacts.toMutableSet()
        for ((contact, entries) in s.myReservations) {
            if (entries.any { it.offered }) holders.add(contact)
        }
        return holders.filter { it !in s.revokedContacts }.sorted()
    }

    @Synchronized
    fun isPoolRevoked(contactAddress: String, walletAddress: String): Boolean =
        state(walletAddress).revokedContacts.contains(contactAddress)

    /** Records a successful revoke: clears the offered marker too, which is what lets the normal
     *  lazy offer re-fire after the toggle comes back on. Reservations themselves are untouched. */
    @Synchronized
    fun markPoolRevoked(contactAddress: String, walletAddress: String) {
        val s = state(walletAddress)
        s.revokedContacts.add(contactAddress)
        s.offeredContacts.remove(contactAddress)
        save(s, walletAddress)
    }

    /** Cleared when we next successfully offer to this contact. */
    @Synchronized
    fun clearPoolRevocation(contactAddress: String, walletAddress: String) {
        val s = state(walletAddress)
        if (s.revokedContacts.remove(contactAddress)) save(s, walletAddress)
    }

    /** Toggle-on housekeeping: forget all revocations so the offers are unencumbered.
     *  Deliberately does NOT touch [State.contactsRevokedAtUs] - a contact who revoked our pool
     *  at them keeps waiting for re-engagement no matter how often we flip our own toggle. */
    @Synchronized
    fun clearAllPoolRevocations(walletAddress: String) {
        val s = state(walletAddress)
        if (s.revokedContacts.isEmpty()) return
        s.revokedContacts.clear()
        save(s, walletAddress)
    }

    /** The contact revoked at us (incoming empty replace:true addr_pool): records the
     *  revoked-at-us marker so no proactive path (auto-replenish, toggle-on re-offer) pushes
     *  fresh addresses at a contact who just signalled disinterest, and drops the offered flag
     *  on their unfunded reservations - our offers to them leave the ACTIVE set (tag and
     *  hide-lock drop, rows revert to normal hideable addresses), exactly like a superseding
     *  replace batch would. The entries stay in the historical mapping (still watched, a
     *  payment_notice naming one still renders), and the offered-contacts marker, revocation
     *  set, and throttles are untouched so the normal offer lifecycle is unaffected. */
    @Synchronized
    fun markOffersInactive(contactAddress: String, walletAddress: String) {
        val s = state(walletAddress)
        s.contactsRevokedAtUs.add(contactAddress)
        s.myReservations[contactAddress]?.let { entries ->
            for (i in entries.indices) {
                val e = entries[i]
                if (e.offered && e.funded != true) entries[i] = e.copy(offered = false)
            }
        }
        save(s, walletAddress)
    }

    /** True while [contactAddress] has revoked our pool at them and hasn't shown renewed
     *  interest since - gates the proactive sends only (request-driven and reciprocity sends
     *  clear the marker on arrival). */
    @Synchronized
    fun didContactRevokeAtUs(contactAddress: String, walletAddress: String): Boolean =
        state(walletAddress).contactsRevokedAtUs.contains(contactAddress)

    /** The contact showed renewed pool interest (sent addr_pool_request, offered us a
     *  non-empty pool, or accepted a successful offer of ours) - proactive sends are welcome
     *  again. */
    @Synchronized
    fun clearContactRevokedAtUs(contactAddress: String, walletAddress: String) {
        val s = state(walletAddress)
        if (s.contactsRevokedAtUs.remove(contactAddress)) save(s, walletAddress)
    }

    /** Gate for EVERY addr_pool send (initial offer, reciprocity, request-driven top-ups,
     *  auto-replenishes): one send per contact per [POOL_SERVE_THROTTLE_MS], nothing once the
     *  lifetime reservation cap or the outstanding-unfunded-offers cap is hit.
     *  `toggleTransition = true` (a genuine state change: a Chats Payment Privacy flip, or a
     *  replenish after a reservation was detected funded) swaps the 10-minute throttle for the
     *  60s transition gap - MESSAGING.md documents both exemptions. */
    @Synchronized
    fun canServePoolOffer(contactAddress: String, walletAddress: String, toggleTransition: Boolean = false): Boolean {
        if (isWithinPoolServeGap(contactAddress, walletAddress, toggleTransition)) return false
        val reservations = state(walletAddress).myReservations[contactAddress] ?: emptyList()
        if (reservations.size >= MAX_LIFETIME_RESERVATIONS_PER_CONTACT) return false
        val outstandingUnfunded = reservations.count { it.offered && it.funded != true }
        return outstandingUnfunded < MAX_OUTSTANDING_UNFUNDED_OFFERS
    }

    /** Pure spacing check against the last addr_pool broadcast (offer OR revoke - both stamp the
     *  serve timestamp). Split out because revokes bypass the reservation caps but still honor
     *  the flap-bounding gap. */
    @Synchronized
    fun isWithinPoolServeGap(contactAddress: String, walletAddress: String, toggleTransition: Boolean): Boolean {
        val last = state(walletAddress).lastPoolServeAt[contactAddress] ?: return false
        val gap = if (toggleTransition) TOGGLE_TRANSITION_GAP_MS else POOL_SERVE_THROTTLE_MS
        return System.currentTimeMillis() - last < gap
    }

    @Synchronized
    fun recordPoolOfferServed(contactAddress: String, walletAddress: String) {
        val s = state(walletAddress)
        s.lastPoolServeAt[contactAddress] = System.currentTimeMillis()
        save(s, walletAddress)
    }

    // --- Their pools (addresses I can pay a contact at) ---------------------------------

    /** Merges a received `addr_pool` into the stored pool for [contactAddress]. `replace == true`
     *  discards the previous pool (carrying over `used` flags for any address that reappears, so
     *  a replayed/overlapping replace can never resurrect a spent address); otherwise appends,
     *  deduped. Capped at [MAX_STORED_POOL_SIZE], oldest kept first. */
    @Synchronized
    fun mergeTheirPool(addresses: List<String>, replace: Boolean, contactAddress: String, walletAddress: String) {
        val s = state(walletAddress)
        val existing = s.theirPools[contactAddress] ?: mutableListOf()
        val usedByAddress = mutableMapOf<String, Boolean>()
        for (entry in existing) usedByAddress[entry.address] = (usedByAddress[entry.address] ?: false) || entry.used

        val merged = if (replace) mutableListOf() else existing.toMutableList()
        val seen = merged.map { it.address }.toMutableSet()
        for (address in addresses) {
            if (address in seen) continue
            seen.add(address)
            merged.add(TheirPoolAddress(address = address, used = usedByAddress[address] ?: false))
        }
        s.theirPools[contactAddress] = if (merged.size > MAX_STORED_POOL_SIZE) {
            merged.subList(0, MAX_STORED_POOL_SIZE).toMutableList()
        } else merged
        save(s, walletAddress)
    }

    @Synchronized
    fun hasPool(contactAddress: String, walletAddress: String): Boolean =
        (state(walletAddress).theirPools[contactAddress] ?: emptyList()).isNotEmpty()

    @Synchronized
    fun unusedPoolCount(contactAddress: String, walletAddress: String): Int =
        (state(walletAddress).theirPools[contactAddress] ?: emptyList()).count { !it.used }

    @Synchronized
    fun nextUnusedPoolAddress(contactAddress: String, walletAddress: String): String? =
        (state(walletAddress).theirPools[contactAddress] ?: emptyList()).firstOrNull { !it.used }?.address

    /** Marks a pool address consumed. Persisted immediately - a consumed address is never offered
     *  to a payment again, even if that payment ultimately fails (burning an address is safe;
     *  reusing one is not). */
    @Synchronized
    fun markPoolAddressUsed(address: String, contactAddress: String, walletAddress: String) {
        val s = state(walletAddress)
        val pool = s.theirPools[contactAddress] ?: return
        val i = pool.indexOfFirst { it.address == address }
        if (i < 0) return
        pool[i] = pool[i].copy(used = true)
        save(s, walletAddress)
    }

    // --- Replay guard -------------------------------------------------------------------

    @Synchronized
    fun isEnvelopeHandled(txId: String, walletAddress: String): Boolean =
        state(walletAddress).handledEnvelopeTxIds.contains(txId)

    @Synchronized
    fun markEnvelopeHandled(txId: String, walletAddress: String) {
        val s = state(walletAddress)
        if (s.handledEnvelopeTxIds.contains(txId)) return
        s.handledEnvelopeTxIds.add(txId)
        while (s.handledEnvelopeTxIds.size > MAX_HANDLED_TX_IDS) s.handledEnvelopeTxIds.removeAt(0)
        save(s, walletAddress)
    }

    // --- Request throttle ---------------------------------------------------------------

    @Synchronized
    fun shouldRequestMoreAddresses(contactAddress: String, walletAddress: String): Boolean {
        val s = state(walletAddress)
        if ((s.theirPools[contactAddress] ?: emptyList()).isEmpty()) return false
        if (unusedPoolCount(contactAddress, walletAddress) > LOW_WATER_MARK) return false
        val last = s.lastPoolRequestAt[contactAddress]
        if (last != null && System.currentTimeMillis() - last < REQUEST_THROTTLE_MS) return false
        return true
    }

    @Synchronized
    fun recordPoolRequestSent(contactAddress: String, walletAddress: String) {
        val s = state(walletAddress)
        s.lastPoolRequestAt[contactAddress] = System.currentTimeMillis()
        save(s, walletAddress)
    }

    // --- In-flight payment destinations (in-memory) -------------------------------------

    @Synchronized
    fun rememberPaymentDestination(address: String, pendingTxId: String) {
        pendingPaymentDestinations[pendingTxId] = address
    }

    @Synchronized
    fun paymentDestination(pendingTxId: String): String? = pendingPaymentDestinations[pendingTxId]

    @Synchronized
    fun forgetPaymentDestination(pendingTxId: String) {
        pendingPaymentDestinations.remove(pendingTxId)
    }
}
