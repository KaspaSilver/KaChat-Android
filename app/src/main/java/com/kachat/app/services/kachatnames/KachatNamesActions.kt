package com.kachat.app.services.kachatnames

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.kachat.app.services.NetworkService
import com.kachat.app.services.WalletManager
import com.kachat.app.services.kachatnames.KachatNames.Codec
import com.kachat.app.services.kachatnames.KachatNames.hex
import com.kachat.app.services.kachatnames.KachatNames.unhex
import com.kachat.app.services.kachatnames.KachatNames.unhex32
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaNetwork
import com.kachat.app.util.Secp256k1
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A registration in flight: commit -> wait `tCommit` DAA -> register. Kept per wallet in
 *  `filesDir/KachatNames/testnet-10/pending-<wallet>.json` (the salt in encrypted preferences), so
 *  it resumes after a relaunch. iOS `KachatNames.PendingRegistration` (KaChat 1ed6e57). */
data class PendingRegistration(
    /** Names the salt in the encrypted preferences. */
    val id: String,
    val name: String,
    val years: Long,
    /** x-only owner key, hex */
    val owner: String,
    val commitTxId: String,
    /** the commit's P2SH script, hex */
    val commitScript: String,
    /** the commit UTXO's DAA score once seen */
    val commitDaa: Long? = null,
    val registerTxId: String? = null,
    /** The reclaim this registration sent to free a lapsed old record of the name first (iOS eea52b2). */
    val reclaimTxId: String? = null,
    val cancelTxId: String? = null,
    val stage: Stage,
    val createdAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
    /** The price the person confirmed for the whole registration (sompi). The registration never
     *  pays more; null only for a registration started before the cap existed (iOS 4f5d95e). */
    val maxPrice: Long? = null,
    /** What the price record asked when the registration stopped at [Stage.PRICE_CHANGED]. */
    val priceChangedTo: Long? = null
) {
    enum class Stage {
        /** the commit transaction was built and is being submitted */
        @SerializedName("committing") COMMITTING,
        /** the commit is on chain (or about to be); waiting until it is `tCommit` deep */
        @SerializedName("waiting") WAITING,
        /** the registration was submitted */
        @SerializedName("registering") REGISTERING,
        @SerializedName("registered") REGISTERED,
        /** someone registered the name first; the commit can be cancelled */
        @SerializedName("taken") TAKEN,
        /** the price record now asks more than the person confirmed ([priceChangedTo]): nothing is
         *  sent until they confirm the new price (iOS 4f5d95e) */
        @SerializedName("priceChanged") PRICE_CHANGED,
        @SerializedName("failed") FAILED,
        @SerializedName("cancelling") CANCELLING,
        @SerializedName("cancelled") CANCELLED
    }

    /** Still shown on the hub. */
    val isOpen: Boolean get() = stage != Stage.CANCELLED

    /** The driver has work to do. */
    val needsDriving: Boolean get() = stage in setOf(Stage.COMMITTING, Stage.WAITING, Stage.REGISTERING, Stage.CANCELLING)

    /**
     * Never pay more than the person confirmed: prices can change while the commit ages. This
     * registration stopped at [Stage.PRICE_CHANGED] when the price record asks [price] (sompi, the
     * whole registration) above [maxPrice]; null when it may go on (iOS 4f5d95e).
     */
    fun stoppedAtPrice(price: Long): PendingRegistration? {
        val cap = maxPrice ?: return null
        if (price <= cap) return null
        return copy(stage = Stage.PRICE_CHANGED, priceChangedTo = price, lastError = null)
    }

    /**
     * This registration, stopped at [Stage.PRICE_CHANGED], going on capped at the new price the
     * person just confirmed; null when it isn't stopped there. It still stops again if the price
     * goes up further (iOS 4f5d95e `acceptNewPrice`).
     */
    fun acceptingNewPrice(): PendingRegistration? {
        if (stage != Stage.PRICE_CHANGED) return null
        val price = priceChangedTo ?: return null
        return copy(maxPrice = price, priceChangedTo = null, stage = Stage.WAITING, lastError = null)
    }
}

/**
 * The `.kachat` actions: every operation the screens offer, built with the pure builders over
 * UTXOs re-read from a node, signed with the wallet key and submitted ([KachatNamesService]), plus
 * the registration driver (commit, wait, register - resumable). Testnet-10 only: each entry goes
 * through [KachatNamesService.requireLaunched] (on `isLaunched`, iOS d657ee3). Every action returns its txid and refreshes the
 * registry once the transaction is accepted. A port of iOS
 * KaChat/Services/KachatNames/KachatNamesActions.swift (KaChat 1ed6e57, 5df42b4; registry v2 extend and
 * the renewal window from 5766c00; expired offers going back from ba07975; registry v3 - prices from
 * a live price shard, offers made to the owner and capped at 7 days, decline, the seller-bound
 * declined check - from 49c0baa).
 */
@Singleton
class KachatNamesActions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: KachatNamesService,
    private val registry: KachatNamesRegistry,
    private val walletManager: WalletManager,
    private val networkService: NetworkService,
    /** KasSigner (watch-only) accounts, to tell which of this wallet's addresses holds a name
     *  ([ownAddress], iOS 881ada6). Read only: their keys never live here. */
    private val coldStorageManager: com.kachat.app.services.ColdStorageManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val lock = Any()

    private val _pending = MutableStateFlow<List<PendingRegistration>>(emptyList())
    /** The current wallet's registrations (in flight, registered, taken, failed). */
    val pending: StateFlow<List<PendingRegistration>> = _pending.asStateFlow()

    /** The registration whose progress sheet is up: the open one (one at a time, iOS 61fb0fc). */
    val openRegistration: PendingRegistration? get() = openRegistration(_pending.value)

    /** Claim sheets currently showing a registration's progress themselves; while one does, the
     *  app-level progress sheet (`KachatRegistrationPresenter`) stays down (iOS 61fb0fc
     *  `inlineProgressCount`). */
    val inlineProgressCount = MutableStateFlow(0)

    private val _virtualDaa = MutableStateFlow<Long?>(null)
    /** The virtual DAA score the driver last saw (registration progress, "refundable now"). */
    val virtualDaa: StateFlow<Long?> = _virtualDaa.asStateFlow()

    private val _returningOffers = MutableStateFlow<Set<String>>(emptySet())
    /** Expired offers this app is sending back to their buyers (see [returnExpiredOffers], iOS ba07975). */
    val returningOffers: StateFlow<Set<String>> = _returningOffers.asStateFlow()

    private val _withdrawingOffers = MutableStateFlow<Set<String>>(emptySet())
    /** Offers this app is withdrawing because the name changed hands (see [withdrawDeclinedOffers], iOS ba07975). */
    val withdrawingOffers: StateFlow<Set<String>> = _withdrawingOffers.asStateFlow()

    private val _decliningOffers = MutableStateFlow<Set<String>>(emptySet())
    /** Offers this app is declining for their seller (see [declineOpenOffers], iOS 49c0baa). */
    val decliningOffers: StateFlow<Set<String>> = _decliningOffers.asStateFlow()

    /** The shards the last [liveShard] read, to tell which one a built plan spends. */
    @Volatile private var lastShards: List<ShardInfo> = emptyList()

    @Volatile private var pendingWallet: String? = null
    @Volatile private var driver: Job? = null

    /** Action errors; English like iOS's service errors (iOS localizes these five). */
    sealed class ActionError(message: String) : Exception(message) {
        class NoWallet : ActionError("No testnet wallet is open.")
        class KeyMismatch : ActionError("This wallet's key does not match its address.")
        class InvalidKey(what: String) : ActionError("$what is not a valid key (not on the secp256k1 curve).")
        class NoSalt : ActionError("The secret for this registration is missing on this device.")
        class NotRegisterable(why: String) : ActionError(why)

        /** renew before its window: the network's time has not reached `expiresAt - renewWindowMs` (iOS 5766c00) */
        class RenewalNotOpen(val opensMs: Long) : ActionError("Renewal opens at $opensMs (unix ms).")

        /** extend past `periodStart + maxYears` periods (iOS 5766c00, 49c0baa) */
        class PeriodFull(val renewalOpensMs: Long) :
            ActionError("This name is already paid up to its longest period. Renewal opens at $renewalOpensMs (unix ms).")

        /** the record has no periodStart (an indexer without the field), so its state is unknown (iOS 5766c00) */
        class PeriodUnknown : ActionError("The names indexer didn't send this name's paid period. Pull to refresh and try again.")

        /** accept past the offer's refund time: the contract would still take it, the app doesn't (iOS ba07975) */
        class OfferExpired : ActionError("This offer has expired. It's going back to the buyer.")

        /** accept an offer made to an earlier owner of the name (iOS ba07975; the seller field since 49c0baa) */
        class OfferDeclined : ActionError("This offer was made before the name changed hands, so it's declined and going back to the buyer.")

        /** every live price shard was gone when read (spent by someone else's register / extend /
         *  renew, or not on chain with the price covenant id; iOS 49c0baa) */
        class PriceBusy : ActionError("The price record is busy right now. Try again in a moment.")

        /** an offer on a name this key owns (iOS 49c0baa) */
        class OwnName : ActionError("You can't make an offer on your own name.")

        /** an offer past the app's 7-day cap, or one already refundable (iOS 49c0baa) */
        class OfferTooLong : ActionError("An offer can run for up to 7 days.")

        /** a renewal that would still end in the past: paid for nothing, and anyone could reclaim
         *  the name right after (iOS 71128c4, IOS-056) */
        class ExpiredTooLongToRenew :
            ActionError("This name has been expired too long to renew. It can only be reclaimed and registered again.")

        /** an offer on a name that isn't active: anyone can reclaim it soon, so the buyer would
         *  pay for nothing (iOS 71128c4, IOS-055) */
        class OfferNameNotActive : ActionError("Offers can only be made on active names.")

        /** accept on a name that isn't active: the contract would hand it over, to a buyer who
         *  gets a name anyone can reclaim (iOS 71128c4, IOS-055) */
        class AcceptNameExpired :
            ActionError("This name has expired. Offers can only be accepted while the name is active. Renew it first.")

        /** the price record asks more than the price the person confirmed (iOS 4f5d95e) */
        class PriceChanged(val price: Long) :
            ActionError("The price changed to $price sompi since you confirmed. Nothing was sent. Check the new price and confirm again.")
    }

    // Wallet

    /** What the registration driver does with the registry's answer for its name (iOS ba1a734, eea52b2). */
    sealed class RegisterStep {
        /** The old record is still there, lapsed: an expired name is free to claim, so the
         *  registration frees [name] first (a reclaim), then registers once the gap shows. */
        class FreeOldName(val name: NameInfo) : RegisterStep()
        /** Registered to this wallet: done. */
        object Mine : RegisterStep()
        /** Someone else registered it first. */
        object Taken : RegisterStep()
        /** Free: register into [gap] (null while the source has no gap for it yet). */
        class Claim(val gap: GapInfo?) : RegisterStep()
    }

    class Signer(val address: String, val privateKey: ByteArray, val me: ByteArray)

    /** The current wallet's testnet address, key and x-only key (they must agree). */
    fun signer(): Signer {
        service.requireLaunched()
        val address = walletManager.getActiveAccount()?.address?.lowercase()
        if (address == null || !address.startsWith("kaspatest:")) throw ActionError.NoWallet()
        val key = try { walletManager.getPrivateKeyBytes() } catch (_: Exception) { throw ActionError.NoWallet() }
        val me = KachatNamesService.xonlyKey(key)
        if (!me.contentEquals(KachatNamesRegistry.keyOf(address))) throw ActionError.KeyMismatch()
        return Signer(address, key, me)
    }

    /**
     * The current wallet's chatting address and key on the network the app runs on - the profile
     * record's signer (iOS d36fc42 `profileSigner()`). Unlike [signer] it isn't testnet-only:
     * profiles work on mainnet before its registry launches ([KachatNamesService.profilesEnabled]).
     */
    fun profileSigner(): Signer {
        if (!KachatNamesService.profilesEnabled) throw KachatNamesService.ServiceError.TestnetOnly()
        val address = walletManager.getActiveAccount()?.address?.lowercase() ?: throw ActionError.NoWallet()
        val key = try { walletManager.getPrivateKeyBytes() } catch (_: Exception) { throw ActionError.NoWallet() }
        if (!KaspaNetwork.isOnActiveNetwork(address)) throw KachatNamesService.ServiceError.WrongAddressNetwork()
        val me = KachatNamesService.xonlyKey(key)
        // the address must be this key's Schnorr (version 0) address, on either network
        val (version, payload) = runCatching { KaspaAddress.decode(address) }.getOrNull() ?: throw ActionError.KeyMismatch()
        if (version.toInt() != 0 || !payload.contentEquals(me)) throw ActionError.KeyMismatch()
        return Signer(address, key, me)
    }

    /** Which of this wallet's own addresses holds a name (iOS 881ada6 `OwnAddress`). */
    sealed class OwnAddress {
        object Chatting : OwnAddress()
        /** A spending address: the app derives its key, so owner actions sign with it. */
        data class Spending(val index: Int, val address: String) : OwnAddress()
        /** A KasSigner (watch-only) address: owner actions need the device to sign. */
        data class KasSigner(val account: String, val index: Int, val address: String) : OwnAddress()
    }

    /**
     * Whether [owner] (an x-only key) is one of this wallet's addresses, and which: the chatting
     * address, a revealed spending address, or a KasSigner account address. Null = someone else.
     * Derives addresses (the spending chain from the seed, the KasSigner ones from their kpubs),
     * so it runs off the main thread (iOS 881ada6 `ownAddress(of:)`).
     */
    suspend fun ownAddress(owner: ByteArray): OwnAddress? = withContext(Dispatchers.IO) {
        if (myKey?.contentEquals(owner) == true) return@withContext OwnAddress.Chatting
        val account = walletManager.getActiveAccount()
        if (account != null) {
            val max = maxOf(0, account.spendingAddressIndex, account.maxSpendingAddressIndex)
            val spending = runCatching { walletManager.deriveSpendingAddresses(0..max) }.getOrNull().orEmpty()
            for ((index, address) in spending.entries.sortedBy { it.key }) {
                if (KachatNamesRegistry.keyOf(address)?.contentEquals(owner) == true) {
                    return@withContext OwnAddress.Spending(index, address)
                }
            }
        }
        val cold = runCatching { coldStorageManager.getAccounts() }.getOrNull().orEmpty()
        for (c in cold) {
            val root = com.kachat.app.util.KaspaExtendedPublicKey.parse(c.kpub).getOrNull()
                ?.let { com.kachat.app.util.KaspaExtendedPublicKey.toDeterministicKey(it) } ?: continue
            for (index in 0..maxOf(0, c.maxDerivedIndex)) {
                val address = runCatching { com.kachat.app.util.KaspaExtendedPublicKey.deriveChildAddress(root, chain = 0, index = index) }.getOrNull()
                    ?: continue
                if (KachatNamesRegistry.keyOf(address)?.contentEquals(owner) == true) {
                    return@withContext OwnAddress.KasSigner(c.name, index, address)
                }
            }
        }
        null
    }

    /**
     * The signer for [op]. Owner-only actions (transfer, list/delist, accept, release) on a name
     * held by one of this wallet's spending addresses sign - and pay their fee - from that
     * address, and so does a decline of an offer made to one (with the key the offer was made to).
     * Everything else, including extend and renew (anyone may pay those), uses the chatting
     * address (iOS 881ada6 `signer(for:)`, decline from 49c0baa).
     */
    private suspend fun signer(op: Operation): Signer {
        val heldBy: ByteArray? = when (op) {
            is Operation.Transfer -> op.name.owner
            is Operation.List -> op.name.owner
            is Operation.Release -> op.name.owner
            is Operation.Accept -> op.name.owner
            // the seller declines with the key the offer was made to
            is Operation.Decline -> op.offer.seller
            else -> null
        }
        val spending = heldBy?.let { ownAddress(it) } as? OwnAddress.Spending
        if (heldBy != null && spending != null) {
            service.requireLaunched()
            // the spending address must be a testnet one on the network the app runs on (iOS d657ee3)
            val address = spending.address.lowercase()
            if (!address.startsWith("kaspatest:") || !KaspaNetwork.isOnActiveNetwork(address)) {
                throw KachatNamesService.ServiceError.WrongAddressNetwork()
            }
            val key = try { walletManager.getSpendingPrivateKeyBytes(spending.index) } catch (_: Exception) { throw ActionError.NoWallet() }
            val me = KachatNamesService.xonlyKey(key)
            if (!me.contentEquals(heldBy)) throw ActionError.KeyMismatch()
            return Signer(address, key, me)
        }
        return signer()
    }

    /** The current wallet's x-only key, without touching the private key. */
    val myKey: ByteArray? get() = walletManager.getActiveAccount()?.address?.let { KachatNamesRegistry.keyOf(it) }

    val myAddress: String? get() = walletManager.getActiveAccount()?.address?.lowercase()

    /** `max(100, the REST API's priority fee rate)` in sompi per gram. */
    suspend fun feerate(): Double {
        val api = networkService.kaspaRestApi.value ?: return KachatNames.MIN_FEERATE
        return try {
            maxOf(KachatNames.MIN_FEERATE, api.getFeeEstimate().priorityBucket.feerate)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            KachatNames.MIN_FEERATE
        }
    }

    private class Context3(val builder: Builder, val env: Env, val wallet: List<Utxo>)

    /** Builder, environment and the wallet's funding UTXOs for one transaction. */
    private suspend fun context(s: Signer): Context3 {
        val builder = service.builder()
        val env = service.environment(s.privateKey, feerate())
        _virtualDaa.value = env.blockDaa
        val utxos = service.utxosByAddresses(listOf(s.address))
        return Context3(builder, env, KachatNamesService.fundingUtxos(utxos, s.me, env.blockDaa))
    }

    /** Reads the virtual DAA score (for "refundable now" on offers). */
    suspend fun refreshVirtualDaa() {
        service.currentVirtualDaaScore()?.let { _virtualDaa.value = it }
    }

    /** What the wallet can spend on names right now (sompi). */
    suspend fun spendable(): Long = withContext(Dispatchers.IO) {
        val s = signer()
        val utxos = service.utxosByAddresses(listOf(s.address))
        val daa = service.currentVirtualDaaScore() ?: 0L
        KachatNamesService.fundingUtxos(utxos, s.me, daa).sumOf { it.entry.amount }
    }

    // Live records

    /** A name record without a period start (an indexer without the field) is not spent: its
     *  on-chain state is unknown (iOS 5766c00). */
    private suspend fun liveName(n: NameInfo, m: Manifest): NameRecord {
        val fields = n.fields ?: throw ActionError.PeriodUnknown()
        val u = service.liveRegistryUtxo(m.name.script(fields.encoded), n.outpoint)
        return NameRecord(fields, u.entry.amount, u)
    }

    private suspend fun liveGap(g: GapInfo, m: Manifest): GapRecord {
        val u = service.liveRegistryUtxo(m.gap.script(Codec.gapState(g.lo, g.hi)), g.outpoint)
        return GapRecord(g.lo, g.hi, u.entry.amount, u)
    }

    private suspend fun liveOffer(o: OfferInfo, m: Manifest): OfferRecord {
        val u = service.liveUtxo(m.offer.script(o.fields.encoded), o.outpoint)
        return OfferRecord(o.fields, u.entry.amount, u, o.name)
    }

    /**
     * A live price shard for a register, extend or renew (registry v3, iOS 49c0baa): a random one
     * of the K, so paid operations at the same moment rarely pick the same shard, skipping [avoid]
     * (shards a previous attempt lost to someone else; tried last) and any the node no longer has
     * at that state with the price covenant id.
     */
    private suspend fun liveShard(m: Manifest, avoid: Set<Long> = emptySet()): PriceRecord {
        val all = registry.shards()
        lastShards = all
        val fresh = all.filter { it.shard !in avoid }.shuffled()
        val lost = all.filter { it.shard in avoid }.shuffled()
        for (sh in fresh + lost) {
            val u = try {
                service.livePriceUtxo(m.price.script(sh.fields.encoded), sh.outpoint)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            return PriceRecord(sh.fields, u.entry.amount, u)
        }
        throw ActionError.PriceBusy()
    }

    /** The price shard a built plan spends (register, extend, renew), so a retry can avoid it. */
    private fun shardSpent(plan: Plan): Long? {
        val u = plan.inputs.firstOrNull { it.role == BudgetRole.PRICE_USE }?.utxo ?: return null
        return lastShards.firstOrNull { it.outpoint == u.outpoint }?.shard
    }

    /** A unit of [offerTimeLeft]. */
    enum class TimeLeftUnit { DAY, HOUR, MINUTE }

    // Operations

    sealed class Operation {
        /** add periods to the current paid period (anyone, any time, up to maxYears periods past periodStart) */
        data class Extend(val name: NameInfo, val years: Long) : Operation()
        /** start the next period at the current expiry (anyone, once the renewal window opened) */
        data class Renew(val name: NameInfo, val years: Long) : Operation()
        class Transfer(val name: NameInfo, val to: ByteArray) : Operation()
        /** price 0 delists */
        data class List(val name: NameInfo, val price: Long) : Operation()
        data class Buy(val name: NameInfo) : Operation()
        /** made to the name's current owner, the only one who can accept or decline it (registry v3) */
        data class Offer(val target: NameInfo, val amount: Long, val refundAfterDaa: Long) : Operation()
        data class Withdraw(val offer: OfferInfo) : Operation()
        data class Refund(val offer: OfferInfo) : Operation()
        data class Accept(val offer: OfferInfo, val name: NameInfo) : Operation()
        /** the seller sends it back to the buyer (registry v3); the network fee comes out of the offer */
        data class Decline(val offer: OfferInfo) : Operation()
        data class Release(val name: NameInfo) : Operation()
        data class Reclaim(val name: NameInfo) : Operation()
    }

    /** Builds [op] against live UTXOs without submitting anything: the fee and outputs a sheet
     *  shows before the person confirms. */
    suspend fun plan(op: Operation): Plan = withContext(Dispatchers.IO) {
        val s = signer(op)
        build(op, s).first
    }

    private suspend fun build(op: Operation, s: Signer, avoidShards: Set<Long> = emptySet()): Pair<Plan, Env> {
        val m = registry.prepare()
        val c = context(s)
        val b = c.builder
        val env = c.env
        val wallet = c.wallet
        val plan = when (op) {
            is Operation.Extend -> {
                if (op.name.periodStart == null) throw ActionError.PeriodUnknown()
                if (op.years < 1 || op.years > op.name.extendableYears(m.params)) {
                    throw ActionError.PeriodFull(op.name.renewOpens(m.params))
                }
                b.extend(env, wallet, liveName(op.name, m), liveShard(m, avoidShards), op.years)
            }
            is Operation.Renew -> {
                // Valid only once the network's median time passes the window opening (the mempool
                // keeps no future-dated transactions): refuse before, and say when it opens.
                if (!Builder.renewWindowOpen(env, m.params, op.name.expiresAt)) {
                    throw ActionError.RenewalNotOpen(op.name.renewOpens(m.params))
                }
                // A renewal counts from the old expiry, not from today: one that would still end
                // in the past is paid for nothing, and anyone could reclaim the name right after.
                checkRenewEndsAhead(op.name.expiresAt, op.years, m.params.periodMs, env.wallMs)
                b.renew(env, wallet, liveName(op.name, m), liveShard(m, avoidShards), op.years)
            }
            is Operation.Transfer -> {
                validateKey(op.to, "The new owner")
                b.transfer(env, wallet, liveName(op.name, m), op.to)
            }
            is Operation.List -> {
                if (op.price > 0 && op.name.status(m.params.graceMs) != Status.ACTIVE) {
                    throw ActionError.NotRegisterable("An expired name can't be listed. Renew it first.")
                }
                b.list(env, wallet, liveName(op.name, m), op.price)
            }
            is Operation.Buy -> {
                validateKey(env.me, "Your key")
                b.buy(env, wallet, liveName(op.name, m))
            }
            is Operation.Offer -> {
                validateKey(env.me, "Your key")
                // an expired name can be reclaimed by anyone soon: the buyer would pay for nothing
                checkActiveForOffer(op.target, m.params.graceMs)
                if (op.target.owner.contentEquals(env.me)) throw ActionError.OwnName()
                // the app's cap: the buyer's funds come back within a week at most
                val cap = env.blockDaa + MAX_OFFER_DAYS * 86_400L * DAA_PER_SECOND
                if (op.refundAfterDaa <= env.blockDaa || op.refundAfterDaa > cap) throw ActionError.OfferTooLong()
                b.offer(env, wallet, liveName(op.target, m), op.amount, op.refundAfterDaa)
            }
            is Operation.Withdraw -> b.withdrawOffer(env, liveOffer(op.offer, m))
            is Operation.Refund -> b.refundOffer(env, liveOffer(op.offer, m))
            is Operation.Accept -> {
                // The contract would still take an expired offer; the app doesn't - it goes back (iOS ba07975).
                if (op.offer.refundable(env.blockDaa)) throw ActionError.OfferExpired()
                // The contract would hand over an expired name too; the buyer would get a name
                // anyone can reclaim. Only an active name is accepted (iOS 71128c4).
                checkActiveForAccept(op.name, m.params.graceMs)
                // Made to an earlier owner: the contract refuses it, and it goes back to the buyer (iOS 49c0baa).
                if (op.offer.isDeclined(op.name.owner)) throw ActionError.OfferDeclined()
                validateKey(op.offer.buyer, "The buyer")
                b.acceptOffer(env, liveName(op.name, m), liveOffer(op.offer, m))
            }
            is Operation.Decline -> b.declineOffer(env, liveOffer(op.offer, m))
            is Operation.Release -> {
                val (below, above) = registry.exitGaps(op.name)
                b.release(env, ExitParts(liveGap(below, m), liveName(op.name, m), liveGap(above, m)))
            }
            is Operation.Reclaim -> {
                val (below, above) = registry.exitGaps(op.name)
                b.reclaim(env, ExitParts(liveGap(below, m), liveName(op.name, m), liveGap(above, m)))
            }
        }
        return plan to env
    }

    /**
     * Builds, signs and submits [op]; returns the txid. The registry refreshes once the
     * transaction is accepted. [maxPrice] is the price the person saw and confirmed (a plan's
     * `priceFee`): a register, extend or renew never pays more. Prices can change at any time in
     * the price record (iOS 4f5d95e).
     */
    suspend fun perform(op: Operation, maxPrice: Long? = null): String = withContext(Dispatchers.IO) {
        val s = signer(op)
        val txId = submit(op, s, maxPrice)
        // An offer you withdrew or refunded yourself isn't news in the Profile bell; the ones this
        // app returns on its own (expired, made to an earlier owner) are (iOS 86471dd).
        when (op) {
            is Operation.Withdraw -> if (op.offer.id !in _withdrawingOffers.value) KachatNamesNotifier.selfClosedOffers.add(op.offer.id)
            is Operation.Refund -> if (op.offer.id !in _returningOffers.value) KachatNamesNotifier.selfClosedOffers.add(op.offer.id)
            else -> Unit
        }
        when (op) {
            // A name that leaves this owner takes no offers with it: the ones made to this owner
            // can never be accepted any more, so they go straight back to their buyers (iOS 49c0baa).
            is Operation.Transfer -> declineOpenOffers(op.name, except = null)
            is Operation.Release -> declineOpenOffers(op.name, except = null)
            is Operation.Accept -> declineOpenOffers(op.name, except = op.offer)
            else -> Unit
        }
        registry.refreshAfter(txId)
        txId
    }

    /**
     * Signs and submits [op]. A register, extend or renew that lost its price shard to someone
     * else's transaction (the node rejects it as already spent; nothing was sent) is rebuilt on
     * another shard, up to twice (iOS 49c0baa).
     */
    private suspend fun submit(op: Operation, s: Signer, maxPrice: Long?): String {
        val avoid = HashSet<Long>()
        for (attempt in 0 until 3) {
            val (plan, env) = build(op, s, avoid)
            checkPriceCap(plan.priceFee, maxPrice)
            try {
                val txId = service.signAndSubmit(plan, s.privateKey, env)
                val o = plan.newOffer
                if (op is Operation.Offer && o != null) {
                    registry.trackOffer(
                        OfferInfo(
                            o.utxo.outpoint, o.fields.key, o.name, o.fields.buyer, o.fields.seller, o.value, o.fields.refundAfter,
                            System.currentTimeMillis()
                        )
                    )
                }
                return txId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val shard = shardSpent(plan)
                if (attempt >= 2 || shard == null || !isSpentConflict(e)) throw e
                avoid.add(shard)
            }
        }
        throw KachatNames.Failure("unreachable")
    }

    // Expired offers (iOS ba07975)

    /**
     * Sends expired offers back to their buyers. Past its refund time an offer can still be
     * accepted on chain until someone refunds it, so it would otherwise hang on the name. The
     * refund needs nobody's key and its network fee comes out of the offer itself
     * ([Builder.refundOffer] spends no wallet UTXO), so whichever app sees one first - its
     * buyer's, or the owner's of the name it's on - returns it, at no cost to either. Each offer
     * is tried once per session: the id goes into [returningOffers] (checked and added under
     * [lock], so two screens loading at once can't submit it twice) and never leaves it; a refund
     * someone else got in first just fails quietly. iOS `returnExpiredOffers`.
     */
    suspend fun returnExpiredOffers(offers: List<OfferInfo>) {
        if (!KachatNamesService.isLaunched || offers.isEmpty()) return
        refreshVirtualDaa()
        val daa = _virtualDaa.value ?: return
        for (o in offers) {
            if (!o.refundable(daa)) continue
            if (!claim(_returningOffers, o.id)) continue
            scope.launch {
                try {
                    val txId = perform(Operation.Refund(o))
                    Log.i(TAG, "returned expired offer ${o.id} to its buyer: $txId")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "expired offer ${o.id} not returned: ${e.message ?: e}")
                }
            }
        }
    }

    /**
     * Sends back every open offer on [n] made to its owner, once the name leaves them (transfer,
     * release, or an accepted offer - [except] is that one). Each is the seller's `decline`, so it
     * costs the seller nothing: the network fee comes out of the offer. Each offer is tried once
     * per session ([decliningOffers]). iOS 49c0baa `declineOpenOffers`.
     */
    fun declineOpenOffers(n: NameInfo, except: OfferInfo?) {
        if (!KachatNamesService.isLaunched) return
        scope.launch {
            val all = try {
                registry.offers(n.name)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val open = all.filter { it.seller.contentEquals(n.owner) && it.id != except?.id && it.id !in _decliningOffers.value }
            for (o in open) {
                if (!claim(_decliningOffers, o.id)) continue
                try {
                    val txId = perform(Operation.Decline(o))
                    Log.i(TAG, "declined offer ${o.id} on ${n.name} (the name left this owner): $txId")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "offer ${o.id} not declined: ${e.message ?: e}")
                }
            }
        }
    }

    /**
     * Pulls this wallet's declined offers back: those made to an earlier owner of the name (the
     * contract refuses them now) or on a name since released. A withdraw, signed by the buyer -
     * you - and paid back to you. Before its refund time only the buyer or the seller can return
     * an offer, so the buyer's app does it as soon as it sees the name changed hands; after that,
     * [returnExpiredOffers] covers it from any app. Each offer is tried once per session
     * ([withdrawingOffers]; one already being returned is left to that). iOS
     * `withdrawDeclinedOffers` (the seller field since 49c0baa).
     */
    suspend fun withdrawDeclinedOffers(offers: List<OfferInfo>) {
        if (!KachatNamesService.isLaunched) return
        val me = myKey ?: return
        val mine = offers.filter {
            it.buyer.contentEquals(me) && it.id !in _withdrawingOffers.value && it.id !in _returningOffers.value
        }
        if (mine.isEmpty()) return
        // the name's current owner; null inside = the name is free (released): every offer on it is declined
        val ownerByName = HashMap<String, ByteArray?>()
        for (o in mine) {
            val name = o.name ?: continue
            if (!ownerByName.containsKey(name)) {
                val l = try {
                    registry.lookup(name)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                ownerByName[name] = (l as? Lookup.Registered)?.info?.owner
            }
            // still made to the name's current owner: it stands
            val current = ownerByName[name]
            if (current != null && !o.isDeclined(current)) continue
            // an expired one may have started going back meanwhile: leave it to that
            if (o.id in _returningOffers.value || !claim(_withdrawingOffers, o.id)) continue
            scope.launch {
                try {
                    val txId = perform(Operation.Withdraw(o))
                    Log.i(TAG, "withdrew declined offer ${o.id} (the name changed hands): $txId")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "declined offer ${o.id} not withdrawn: ${e.message ?: e}")
                }
            }
        }
    }

    /** Adds [id] to [set] unless it is already there; true when this call added it. */
    private fun claim(set: MutableStateFlow<Set<String>>, id: String): Boolean {
        synchronized(lock) {
            if (id in set.value) return false
            set.value = set.value + id
            return true
        }
    }

    // Profile record

    /** Writes the address profile (`kchat:1:profile:`): a self-transfer, network fee only. */
    /**
     * What saving [profile] will cost: the profile record is a self-transfer from the chatting
     * address, so the network fee is all it spends. Built (and signed) the way [saveProfile]
     * builds it, never sent: spent inputs minus outputs (iOS `profileFee`, 7e238e5).
     */
    suspend fun profileFee(profile: Profile): Long = withContext(Dispatchers.IO) {
        val s = profileSigner()
        val json = profile.sanitized().recordJSON()
        service.profileRecordFee(s.address, s.privateKey, json)
    }

    suspend fun saveProfile(profile: Profile): String = withContext(Dispatchers.IO) {
        val s = profileSigner()
        val clean = profile.sanitized()
        val json = clean.recordJSON()
        val txId = service.submitProfileRecord(s.address, s.privateKey, json)
        registry.noteOwnProfile(clean, s.address, txId)
        registry.refreshAfter(txId)
        txId
    }

    // Registration

    data class Quote(
        val name: String,
        val years: Long,
        /** price per year x years, left to miners */
        val price: Long,
        /** the name's bond, returned on release */
        val bond: Long,
        /** the extra registry gap the registration creates, returned on release */
        val gapDeposit: Long,
        /** the commit's value, returned into the registration */
        val commit: Long,
        val networkFee: Long,
        /** what leaves the wallet in the end: price + bond + gap deposit + network fees */
        val total: Long,
        val spendable: Long
    ) {
        val affordable: Boolean get() = spendable >= total + KachatNames.MIN_CHANGE
    }

    /** The cost of registering [name] for [years], estimated by building both transactions
     *  (nothing is signed or sent); the price from a live price shard (registry v3, iOS 49c0baa). */
    suspend fun quote(name: String, years: Long, gap: GapInfo): Quote = withContext(Dispatchers.IO) {
        val s = signer()
        val m = registry.prepare()
        val c = context(s)
        val b = c.builder
        val env = c.env
        val wallet = c.wallet
        val salt = KachatNamesService.newSalt()
        val spendable = wallet.sumOf { it.entry.amount }
        val shard = liveShard(m)
        val price = shard.price(name.toByteArray(Charsets.UTF_8).size) * years
        var commitFee = 0L
        var registerFee = 0L
        val commitPlan = runCatching { b.commit(env, wallet, name, salt) }.getOrNull()
        if (commitPlan != null) {
            commitFee = commitPlan.networkFee
            // the registration, with the commit as if it were already mature and the gap as known
            val commit = commitPlan.newCommit
            val cu = commit?.utxo
            if (commit != null && cu != null) {
                val matureDaa = if (env.blockDaa > m.params.tCommit) env.blockDaa - m.params.tCommit else 0L
                val matureCommit = CommitRecord(
                    commit.name, commit.owner, commit.salt, commit.value,
                    Utxo(cu.outpoint, UtxoEntry(cu.entry.amount, cu.entry.scriptVersion, cu.entry.script, matureDaa, cu.entry.isCoinbase, cu.entry.covenantId))
                )
                val gapUtxo = Utxo(
                    gap.outpoint,
                    UtxoEntry(
                        amount = m.params.gapValue, script = m.gap.script(Codec.gapState(gap.lo, gap.hi)),
                        blockDaaScore = env.blockDaa, covenantId = m.registryCovenantId
                    )
                )
                val rest = wallet.filter { u -> commitPlan.inputs.none { it.utxo.outpoint == u.outpoint } }
                runCatching {
                    b.register(env, rest, GapRecord(gap.lo, gap.hi, m.params.gapValue, gapUtxo), matureCommit, shard, years, Builder.registerNow(env))
                }.getOrNull()?.let { registerFee = it.networkFee }
            }
        }
        if (registerFee == 0L) registerFee = 400_000L
        if (commitFee == 0L) commitFee = 250_000L
        val fee = commitFee + registerFee
        Quote(
            name = name, years = years, price = price, bond = m.params.bond, gapDeposit = m.params.gapValue,
            commit = KachatNames.COMMIT_VALUE, networkFee = fee, total = price + m.params.bond + m.params.gapValue + fee,
            spendable = spendable
        )
    }

    /**
     * Starts registering [raw]: a fresh salt (encrypted preferences), the salted commit
     * (submitted), then the driver registers once the commit is `tCommit` deep. Returns the commit
     * txid. [maxPrice] is the price the person confirmed: the registration never pays more (iOS
     * 4f5d95e).
     */
    suspend fun startRegistration(raw: String, years: Long, maxPrice: Long): String = withContext(Dispatchers.IO) {
        val s = signer()
        val name = Codec.normalize(raw)
        Codec.validate(name)
        // One registration at a time: its progress sheet stays up until it's done (iOS 61fb0fc).
        loadPending(s.address)
        if (blocksNewRegistration(_pending.value)) throw ActionError.NotRegisterable(FINISH_CLAIMING_FIRST)
        registry.refresh()
        // Expired past grace: free to claim. The commit goes out now; the driver frees the old
        // record (a reclaim) and then registers (iOS eea52b2).
        if (!isRegisterable(registry.lookup(name), registry.graceMs)) {
            throw ActionError.NotRegisterable("$name.kachat is already registered.")
        }
        val c = context(s)
        val salt = KachatNamesService.newSalt()
        val plan = c.builder.commit(c.env, c.wallet, name, salt)
        val script = plan.newCommit?.utxo?.entry?.script ?: throw KachatNames.Failure("commit: no record")
        val id = UUID.randomUUID().toString().uppercase()
        saveSalt(salt, id, s.address)
        val now = System.currentTimeMillis()
        var record = PendingRegistration(
            id = id, name = name, years = years, owner = hex(s.me), commitTxId = plan.unsignedTx.idHex,
            commitScript = hex(script), stage = PendingRegistration.Stage.COMMITTING, createdAt = now, updatedAt = now,
            maxPrice = maxPrice
        )
        loadPending(s.address)
        upsert(record)
        try {
            val txId = service.signAndSubmit(plan, s.privateKey, c.env)
            record = record.copy(commitTxId = txId, stage = PendingRegistration.Stage.WAITING, updatedAt = System.currentTimeMillis())
            upsert(record)
        } catch (e: Exception) {
            // The node may still have taken it: keep the record (and the salt) until the driver
            // sees the commit on chain or gives up on it.
            record = record.copy(lastError = e.message ?: e.toString(), updatedAt = System.currentTimeMillis())
            upsert(record)
            startDriver()
            throw e
        }
        startDriver()
        record.commitTxId
    }

    /** Spends the commit back (the name was taken, or the person changed their mind). */
    suspend fun cancel(p: PendingRegistration): String = withContext(Dispatchers.IO) {
        val s = signer()
        val salt = loadSalt(p.id, s.address) ?: throw ActionError.NoSalt()
        val b = service.builder()
        val env = service.environment(s.privateKey, feerate())
        val commitUtxo = service.liveUtxo(unhex(p.commitScript), commitOutpoint(p))
        val plan = b.cancelCommit(env, CommitRecord(p.name, s.me, salt, commitUtxo.entry.amount, commitUtxo))
        val txId = service.signAndSubmit(plan, s.privateKey, env)
        set(p) { it.copy(cancelTxId = txId, stage = PendingRegistration.Stage.CANCELLING) }
        startDriver()
        txId
    }

    /** Try a failed registration again. */
    fun retry(p: PendingRegistration) {
        set(p) { it.copy(stage = PendingRegistration.Stage.WAITING, lastError = null, priceChangedTo = null) }
        startDriver()
    }

    /**
     * Continue a registration stopped at [PendingRegistration.Stage.PRICE_CHANGED], now capped at
     * the new price the person just confirmed (behind the device lock). It still stops again if
     * the price goes up further (iOS 4f5d95e).
     */
    fun acceptNewPrice(p: PendingRegistration) {
        if (p.acceptingNewPrice() == null) return
        set(p) { it.acceptingNewPrice() ?: it }
        startDriver()
    }

    /** Drop a finished (registered or cancelled) registration from the list. */
    fun dismiss(p: PendingRegistration) {
        val address = pendingWallet ?: return
        remove(p.id, address)
    }

    /**
     * Loads the current wallet's registrations and drives the open ones. Call when a screen
     * appears and when the app comes to the foreground (KaChatApplication, iOS app-active).
     */
    fun resume() {
        val address = myAddress
        // Launched networks only (iOS 7227d69): mainnet never drives a registration.
        if (!KachatNamesService.isLaunched || address == null) {
            driver?.cancel()
            driver = null
            synchronized(lock) {
                _pending.value = emptyList()
                pendingWallet = null
            }
            return
        }
        loadPending(address)
        startDriver()
    }

    private fun startDriver() {
        synchronized(lock) {
            if (driver?.isActive == true || _pending.value.none { it.needsDriving }) return
            driver = scope.launch {
                try {
                    while (isActive) {
                        val address = myAddress
                        // stops while the registry is being upgraded (a v1 manifest, iOS d2e0673)
                        if (!KachatNamesService.isLaunched || address == null || address != pendingWallet ||
                            service.registryUpgrading.value || _pending.value.none { it.needsDriving }
                        ) break
                        for (p in _pending.value.filter { it.needsDriving }) advance(p)
                        delay(5_000)
                    }
                } finally {
                    synchronized(lock) { if (driver === coroutineContext[Job]) driver = null }
                }
            }
        }
    }

    private fun commitOutpoint(p: PendingRegistration): Outpoint = Outpoint(unhex32(p.commitTxId), 0)

    /** The commit UTXO when a node has it (null when spent or not yet accepted). */
    private suspend fun liveCommit(p: PendingRegistration): Utxo? {
        val script = runCatching { unhex(p.commitScript) }.getOrNull() ?: return null
        val op = runCatching { commitOutpoint(p) }.getOrNull() ?: return null
        return try {
            service.liveUtxo(script, op)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Applies [change] to the newest copy of [p] and saves it. */
    private fun set(p: PendingRegistration, change: (PendingRegistration) -> PendingRegistration) {
        synchronized(lock) {
            val q = change(_pending.value.firstOrNull { it.id == p.id } ?: p).copy(updatedAt = System.currentTimeMillis())
            upsert(q)
        }
    }

    /** One step of one registration. */
    private suspend fun advance(p: PendingRegistration) {
        val now = System.currentTimeMillis()
        val age = now - p.createdAt
        val sinceUpdate = now - p.updatedAt
        when (p.stage) {
            PendingRegistration.Stage.COMMITTING, PendingRegistration.Stage.WAITING -> {
                val commit = liveCommit(p)
                if (commit == null) {
                    if (p.commitDaa == null && age < 10 * 60_000) return
                    // the commit is gone: registered by us (another device?), or never confirmed
                    if (ownsName(p.name)) {
                        finishRegistered(p)
                    } else {
                        val why = if (p.commitDaa == null) "The commit never reached the chain." else "The commit is no longer on chain."
                        set(p) { it.copy(stage = PendingRegistration.Stage.FAILED, lastError = why) }
                    }
                    return
                }
                if (p.commitDaa != commit.entry.blockDaaScore || p.stage == PendingRegistration.Stage.COMMITTING) {
                    set(p) { it.copy(commitDaa = commit.entry.blockDaaScore, stage = PendingRegistration.Stage.WAITING) }
                }
                val m = service.manifest.value ?: return
                val dag = try {
                    service.currentDagPoint()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return
                }
                _virtualDaa.value = dag.virtualDaaScore
                // a little past maturity, so the block that takes it is surely deep enough
                if (dag.virtualDaaScore < commit.entry.blockDaaScore + m.params.tCommit + 20) return
                register(p, commit)
            }
            PendingRegistration.Stage.REGISTERING -> {
                val tx = p.registerTxId
                if (tx != null && registry.isAccepted(tx)) {
                    registry.refresh()
                    if (ownsName(p.name)) finishRegistered(p)
                    return
                }
                // not accepted after two minutes and the commit is still there: register again
                if (sinceUpdate > 120_000 && liveCommit(p) != null) {
                    set(p) { it.copy(stage = PendingRegistration.Stage.WAITING, registerTxId = null) }
                }
            }
            PendingRegistration.Stage.CANCELLING -> {
                val tx = p.cancelTxId
                if (tx != null && registry.isAccepted(tx)) {
                    finishCancelled(p)
                } else if (sinceUpdate > 120_000 && liveCommit(p) == null) {
                    finishCancelled(p)
                }
            }
            PendingRegistration.Stage.REGISTERED, PendingRegistration.Stage.TAKEN,
            PendingRegistration.Stage.FAILED, PendingRegistration.Stage.CANCELLED,
            PendingRegistration.Stage.PRICE_CHANGED -> Unit
        }
    }

    /** This wallet holds [name] as a live registration (a lapsed old record of it doesn't count:
     *  that is what claiming an expired name registers over, iOS eea52b2). */
    private suspend fun ownsName(name: String): Boolean {
        val me = myKey ?: return false
        val l = try {
            registry.lookup(name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        return holdsLive(l, me, registry.graceMs)
    }

    private suspend fun register(p: PendingRegistration, commit: Utxo) {
        try {
            val s = signer()
            if (hex(s.me) != p.owner) return
            val salt = loadSalt(p.id, s.address) ?: throw ActionError.NoSalt()
            registry.refresh()
            val m = registry.prepare()
            val gap = when (val step = registerStep(registry.lookup(p.name), s.me, registry.graceMs)) {
                // An expired name is free to claim: this registration frees the old record first
                // (anyone may; its bond goes back to the old owner and the freed deposit comes to
                // you), then registers on a later tick once the registry shows the gap (iOS eea52b2).
                is RegisterStep.FreeOldName -> {
                    // (Only sending the reclaim touches the record, so `updatedAt` is when it went
                    // out; iOS 4f0bd33.)
                    val sent = p.reclaimTxId
                    if (sent != null) {
                        if (registry.isAccepted(sent)) {
                            registry.refresh()
                        } else if (reclaimRetryDue(p, System.currentTimeMillis())) {
                            set(p) { it.copy(reclaimTxId = null) } // never accepted: send it again
                        }
                        return
                    }
                    val txId = perform(Operation.Reclaim(step.name))
                    set(p) { it.copy(reclaimTxId = txId, lastError = freeingName(p.name)) }
                    return
                }
                RegisterStep.Mine -> { finishRegistered(p); return }
                RegisterStep.Taken -> {
                    set(p) { it.copy(stage = PendingRegistration.Stage.TAKEN, lastError = null) }
                    return
                }
                is RegisterStep.Claim -> step.gap ?: throw KachatNames.Failure("no gap for ${p.name} yet")
            }
            val c = context(s)
            // a random live shard each try: one someone else just spent fails this try, and the
            // next tick picks again
            val shard = liveShard(m)
            // Never pay more than the person confirmed: prices can change while the commit ages (iOS 4f5d95e).
            val price = Math.multiplyExact(shard.price(p.name.toByteArray(Charsets.UTF_8).size), maxOf(p.years, 1L))
            if (p.stoppedAtPrice(price) != null) {
                set(p) { it.stoppedAtPrice(price) ?: it }
                return
            }
            val plan = c.builder.register(
                c.env, c.wallet, liveGap(gap, m),
                CommitRecord(p.name, s.me, salt, commit.entry.amount, commit),
                shard, p.years, Builder.registerNow(c.env)
            )
            val txId = service.signAndSubmit(plan, s.privateKey, c.env)
            set(p) { it.copy(stage = PendingRegistration.Stage.REGISTERING, registerTxId = txId, lastError = null) }
            registry.refreshAfter(txId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            Log.w(TAG, "register ${p.name} failed: $message")
            // funds and a missing salt need the person; anything else (a gap that just moved, a
            // node hiccup, a price shard someone else just spent - iOS throws that one as a plain
            // Failure) is retried on the next tick
            val fatal = message.contains("insufficient funds") || (e is ActionError && e !is ActionError.PriceBusy)
            set(p) { it.copy(lastError = message, stage = if (fatal) PendingRegistration.Stage.FAILED else it.stage) }
        }
    }

    private fun finishRegistered(p: PendingRegistration) {
        pendingWallet?.let { deleteSalt(p.id, it) }
        set(p) { it.copy(stage = PendingRegistration.Stage.REGISTERED, lastError = null) }
    }

    private fun finishCancelled(p: PendingRegistration) {
        pendingWallet?.let { deleteSalt(p.id, it) }
        set(p) { it.copy(stage = PendingRegistration.Stage.CANCELLED, lastError = null) }
        pendingWallet?.let { remove(p.id, it) }
    }

    // Persistence (filesDir/KachatNames/<network>/pending-<wallet>.json)

    private fun file(address: String): String = "pending-${KachatNamesRegistry.walletSuffix(address)}.json"

    private fun loadPending(address: String) {
        val a = address.lowercase()
        synchronized(lock) {
            if (pendingWallet == a) return
            pendingWallet = a
            val data = registry.readFile(file(a))
            val list = data?.let {
                runCatching { gson.fromJson<List<PendingRegistration>>(String(it, Charsets.UTF_8), PENDING_LIST_TYPE) }.getOrNull()
            }
            // Gson leaves absent fields null whatever their Kotlin type: drop damaged records
            @Suppress("SENSELESS_COMPARISON")
            _pending.value = list.orEmpty().filter {
                it != null && it.id != null && it.name != null && it.stage != null && it.commitTxId != null && it.commitScript != null && it.owner != null
            }
        }
    }

    private fun save() {
        val address = pendingWallet ?: return
        registry.writeFile(file(address), gson.toJson(_pending.value).toByteArray(Charsets.UTF_8))
    }

    private fun upsert(p: PendingRegistration) {
        synchronized(lock) {
            val list = _pending.value
            _pending.value = if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p
            save()
        }
    }

    private fun remove(id: String, address: String) {
        deleteSalt(id, address)
        synchronized(lock) {
            _pending.value = _pending.value.filterNot { it.id == id }
            save()
        }
    }

    // Commit salts (iOS KeychainService.saveKachatCommitSalt, KaChat 1ed6e57)
    //
    // The 32-byte salt of a pending `.kachat` registration: without it the commit can neither be
    // registered nor cancelled, and anyone who learns it with the name can link the commit to the
    // name before the registration reveals it. Device-only (the app has allowBackup=false), per
    // wallet, one entry per pending registration (`id` is the registration's UUID), in their own
    // EncryptedSharedPreferences file - the store WalletManager keeps the seeds in is its own.

    private val saltPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            SALT_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun saltKey(id: String, walletAddress: String): String =
        "kachat_names_commit_salt_${KachatNamesRegistry.walletSuffix(walletAddress)}_$id"

    private fun saveSalt(salt: ByteArray, id: String, walletAddress: String) {
        if (!saltPrefs.edit().putString(saltKey(id, walletAddress), hex(salt)).commit()) {
            throw KachatNames.Failure("could not store the registration secret")
        }
    }

    private fun loadSalt(id: String, walletAddress: String): ByteArray? =
        saltPrefs.getString(saltKey(id, walletAddress), null)?.let { runCatching { unhex32(it) }.getOrNull() }

    private fun deleteSalt(id: String, walletAddress: String) {
        runCatching { saltPrefs.edit().remove(saltKey(id, walletAddress)).apply() }
    }

    companion object {
        private const val TAG = "KachatNames"
        private const val SALT_PREFS_NAME = "kachat_names_secure_prefs"
        private val PENDING_LIST_TYPE = object : TypeToken<List<PendingRegistration>>() {}.type

        fun registerStep(lookup: Lookup, me: ByteArray, graceMs: Long, nowMs: Long = KachatNames.nowMs()): RegisterStep = when (lookup) {
            is Lookup.Registered -> when {
                lookup.info.status(graceMs, nowMs) == Status.LAPSED -> RegisterStep.FreeOldName(lookup.info)
                lookup.info.owner.contentEquals(me) -> RegisterStep.Mine
                else -> RegisterStep.Taken
            }
            is Lookup.Free -> RegisterStep.Claim(lookup.gap)
        }

        /** A registration may start: the name is free, or only a lapsed record of it is left
         *  (free to claim: the driver frees it first; iOS ba1a734, eea52b2). */
        fun isRegisterable(lookup: Lookup, graceMs: Long, nowMs: Long = KachatNames.nowMs()): Boolean =
            lookup !is Lookup.Registered || lookup.info.status(graceMs, nowMs) == Status.LAPSED

        /** [me] holds the name as a live registration (not a lapsed old record of it). */
        fun holdsLive(lookup: Lookup, me: ByteArray, graceMs: Long, nowMs: Long = KachatNames.nowMs()): Boolean =
            lookup is Lookup.Registered && lookup.info.owner.contentEquals(me) && lookup.info.status(graceMs, nowMs) != Status.LAPSED

        /** Why a second registration is refused while one is in progress; English like the other
         *  action errors, localized by the screens (`kachatErrorText`, iOS 61fb0fc). */
        const val FINISH_CLAIMING_FIRST = "Finish the name you're claiming first."

        /** A registration is still in progress (open and not yet registered): no second one starts
         *  until it is done or its commit is cancelled (iOS 61fb0fc). */
        fun blocksNewRegistration(pending: List<PendingRegistration>): Boolean =
            pending.any { it.isOpen && it.stage != PendingRegistration.Stage.REGISTERED }

        /** The registration whose progress half sheet is up: the first open one (iOS 61fb0fc). */
        fun openRegistration(pending: List<PendingRegistration>): PendingRegistration? = pending.firstOrNull { it.isOpen }

        /** The driver's note while it frees an expired old record of the name; English like the
         *  driver's other messages, localized by the screens (`kachatPendingError`, iOS eea52b2). */
        fun freeingName(name: String): String = "Freeing $name.kachat for you..."

        /** A reclaim the driver sent that isn't accepted after this long is sent again (iOS eea52b2). */
        const val RECLAIM_RETRY_MS: Long = 120_000

        /**
         * The reclaim [p] sent is due to go out again: it was sent more than [RECLAIM_RETRY_MS]
         * ago and still isn't accepted (the caller asks). Measured from `updatedAt`, which only
         * sending the reclaim touches while the driver waits for it (iOS 4f0bd33).
         */
        fun reclaimRetryDue(p: PendingRegistration, nowMs: Long): Boolean =
            p.reclaimTxId != null && nowMs - p.updatedAt > RECLAIM_RETRY_MS

        /** Longest an offer can run before its buyer may take it back (the app's cap, registry v3, iOS 49c0baa). */
        const val MAX_OFFER_DAYS: Long = 7

        /** Kaspa's DAA scores per second (offer refund times are DAA scores). */
        const val DAA_PER_SECOND: Long = 10

        /**
         * A register, extend or renew never pays more than [maxPrice], the price the person saw
         * and confirmed (null: no cap, the operations without a price). A higher [priceFee] throws
         * [ActionError.PriceChanged] before anything is signed; a lower one is paid (iOS 4f5d95e).
         */
        fun checkPriceCap(priceFee: Long, maxPrice: Long?) {
            if (maxPrice != null && priceFee > maxPrice) throw ActionError.PriceChanged(priceFee)
        }

        /**
         * A renewal starts the next period at the old expiry: [years] periods from [expiresAt]
         * must end after [wallMs], or it is refused - paid for nothing otherwise, and the name
         * stays reclaimable (iOS 71128c4, IOS-056).
         */
        fun checkRenewEndsAhead(expiresAt: Long, years: Long, periodMs: Long, wallMs: Long) {
            val end = try {
                Math.addExact(expiresAt, Math.multiplyExact(years, periodMs))
            } catch (_: ArithmeticException) {
                throw ActionError.ExpiredTooLongToRenew()
            }
            if (end <= wallMs) throw ActionError.ExpiredTooLongToRenew()
        }

        /** Offers only on an active name (iOS 71128c4, IOS-055). */
        fun checkActiveForOffer(target: NameInfo, graceMs: Long, nowMs: Long = KachatNames.nowMs()) {
            if (target.status(graceMs, nowMs) != Status.ACTIVE) throw ActionError.OfferNameNotActive()
        }

        /** Accept only while the name is active (iOS 71128c4, IOS-055). */
        fun checkActiveForAccept(name: NameInfo, graceMs: Long, nowMs: Long = KachatNames.nowMs()) {
            if (name.status(graceMs, nowMs) != Status.ACTIVE) throw ActionError.AcceptNameExpired()
        }

        /** Whether a submit failed because an input was spent meanwhile (iOS 49c0baa `isSpentConflict`). */
        fun isSpentConflict(error: Throwable): Boolean {
            val lower = (error.message ?: error.toString()).lowercase()
            return "already spent" in lower || "double spend" in lower || "orphan" in lower
        }

        /**
         * What's left of an offer's time, for "Expires in 2d 4h" (iOS ba07975 `KachatOfferRow.expiresIn`):
         * from the DAA score it becomes refundable at, [daaPerSecond] per second. Days and hours from a
         * day up, hours and minutes from an hour up, else minutes (at least one); at most two units,
         * zero ones dropped. Null once it is refundable.
         */
        fun offerTimeLeft(refundAfter: Long, virtualDaa: Long, daaPerSecond: Long = 10): List<Pair<TimeLeftUnit, Long>>? {
            val end = maxOf(refundAfter, 0L)
            if (virtualDaa > end) return null
            val seconds = (end - virtualDaa) / daaPerSecond
            val s = maxOf(60L, seconds)
            val parts = when {
                seconds >= 86_400 -> listOf(TimeLeftUnit.DAY to s / 86_400, TimeLeftUnit.HOUR to (s % 86_400) / 3600)
                seconds >= 3600 -> listOf(TimeLeftUnit.HOUR to s / 3600, TimeLeftUnit.MINUTE to (s % 3600) / 60)
                else -> listOf(TimeLeftUnit.MINUTE to s / 60)
            }
            return parts.filter { it.second > 0 }
        }

        /**
         * A key a name or an offer will be locked to must be a point on the curve: the contracts
         * cannot check it, and an invalid owner locks a name until it lapses.
         */
        fun validateKey(xonly: ByteArray, what: String) {
            if (xonly.size != 32 || xonly.contentEquals(KachatNames.ZERO32)) throw ActionError.InvalidKey(what)
            try {
                Secp256k1.CURVE.decodePoint(byteArrayOf(0x02) + xonly)
            } catch (_: Exception) {
                throw ActionError.InvalidKey(what)
            }
        }
    }
}
