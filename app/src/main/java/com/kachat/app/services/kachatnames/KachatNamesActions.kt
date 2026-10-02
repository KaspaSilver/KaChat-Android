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
    val cancelTxId: String? = null,
    val stage: Stage,
    val createdAt: Long,
    val updatedAt: Long,
    val lastError: String? = null
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
        @SerializedName("failed") FAILED,
        @SerializedName("cancelling") CANCELLING,
        @SerializedName("cancelled") CANCELLED
    }

    /** Still shown on the hub. */
    val isOpen: Boolean get() = stage != Stage.CANCELLED

    /** The driver has work to do. */
    val needsDriving: Boolean get() = stage in setOf(Stage.COMMITTING, Stage.WAITING, Stage.REGISTERING, Stage.CANCELLING)
}

/**
 * The `.kachat` actions: every operation the screens offer, built with the pure builders over
 * UTXOs re-read from a node, signed with the wallet key and submitted ([KachatNamesService]), plus
 * the registration driver (commit, wait, register - resumable). Testnet-10 only: each entry goes
 * through [KachatNamesService.requireTestnet]. Every action returns its txid and refreshes the
 * registry once the transaction is accepted. A port of iOS
 * KaChat/Services/KachatNames/KachatNamesActions.swift (KaChat 1ed6e57, 5df42b4).
 */
@Singleton
class KachatNamesActions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: KachatNamesService,
    private val registry: KachatNamesRegistry,
    private val walletManager: WalletManager,
    private val networkService: NetworkService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val lock = Any()

    private val _pending = MutableStateFlow<List<PendingRegistration>>(emptyList())
    /** The current wallet's registrations (in flight, registered, taken, failed). */
    val pending: StateFlow<List<PendingRegistration>> = _pending.asStateFlow()

    private val _virtualDaa = MutableStateFlow<Long?>(null)
    /** The virtual DAA score the driver last saw (registration progress, "refundable now"). */
    val virtualDaa: StateFlow<Long?> = _virtualDaa.asStateFlow()

    @Volatile private var pendingWallet: String? = null
    @Volatile private var driver: Job? = null

    /** Action errors; English like iOS's service errors (iOS localizes these five). */
    sealed class ActionError(message: String) : Exception(message) {
        class NoWallet : ActionError("No testnet wallet is open.")
        class KeyMismatch : ActionError("This wallet's key does not match its address.")
        class InvalidKey(what: String) : ActionError("$what is not a valid key (not on the secp256k1 curve).")
        class NoSalt : ActionError("The secret for this registration is missing on this device.")
        class NotRegisterable(why: String) : ActionError(why)
    }

    // Wallet

    class Signer(val address: String, val privateKey: ByteArray, val me: ByteArray)

    /** The current wallet's testnet address, key and x-only key (they must agree). */
    fun signer(): Signer {
        service.requireTestnet()
        val address = walletManager.getActiveAccount()?.address?.lowercase()
        if (address == null || !address.startsWith("kaspatest:")) throw ActionError.NoWallet()
        val key = try { walletManager.getPrivateKeyBytes() } catch (_: Exception) { throw ActionError.NoWallet() }
        val me = KachatNamesService.xonlyKey(key)
        if (!me.contentEquals(KachatNamesRegistry.keyOf(address))) throw ActionError.KeyMismatch()
        return Signer(address, key, me)
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

    private suspend fun liveName(n: NameInfo, m: Manifest): NameRecord {
        val u = service.liveRegistryUtxo(m.name.script(n.fields.encoded), n.outpoint)
        return NameRecord(n.fields, u.entry.amount, u)
    }

    private suspend fun liveGap(g: GapInfo, m: Manifest): GapRecord {
        val u = service.liveRegistryUtxo(m.gap.script(Codec.gapState(g.lo, g.hi)), g.outpoint)
        return GapRecord(g.lo, g.hi, u.entry.amount, u)
    }

    private suspend fun liveOffer(o: OfferInfo, m: Manifest): OfferRecord {
        val u = service.liveUtxo(m.offer.script(o.fields.encoded), o.outpoint)
        return OfferRecord(o.fields, u.entry.amount, u, o.name)
    }

    // Operations

    sealed class Operation {
        data class Renew(val name: NameInfo, val years: Long) : Operation()
        class Transfer(val name: NameInfo, val to: ByteArray) : Operation()
        /** price 0 delists */
        data class List(val name: NameInfo, val price: Long) : Operation()
        data class Buy(val name: NameInfo) : Operation()
        data class Offer(val name: String, val amount: Long, val refundAfterDaa: Long, val target: NameInfo?) : Operation()
        data class Withdraw(val offer: OfferInfo) : Operation()
        data class Refund(val offer: OfferInfo) : Operation()
        data class Accept(val offer: OfferInfo, val name: NameInfo) : Operation()
        data class Release(val name: NameInfo) : Operation()
        data class Reclaim(val name: NameInfo) : Operation()
    }

    /** Builds [op] against live UTXOs without submitting anything: the fee and outputs a sheet
     *  shows before the person confirms. */
    suspend fun plan(op: Operation): Plan = withContext(Dispatchers.IO) {
        val s = signer()
        build(op, s).first
    }

    private suspend fun build(op: Operation, s: Signer): Pair<Plan, Env> {
        val m = registry.prepare()
        val c = context(s)
        val b = c.builder
        val env = c.env
        val wallet = c.wallet
        val plan = when (op) {
            is Operation.Renew -> b.renew(env, wallet, liveName(op.name, m), op.years)
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
                b.offer(env, wallet, op.name, op.amount, op.refundAfterDaa)
            }
            is Operation.Withdraw -> b.withdrawOffer(env, liveOffer(op.offer, m))
            is Operation.Refund -> b.refundOffer(env, liveOffer(op.offer, m))
            is Operation.Accept -> {
                validateKey(op.offer.buyer, "The buyer")
                b.acceptOffer(env, liveName(op.name, m), liveOffer(op.offer, m))
            }
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

    /** Builds, signs and submits [op]; returns the txid. The registry refreshes once the
     *  transaction is accepted. */
    suspend fun perform(op: Operation): String = withContext(Dispatchers.IO) {
        val s = signer()
        val (plan, env) = build(op, s)
        val txId = service.signAndSubmit(plan, s.privateKey, env)
        val o = plan.newOffer
        if (op is Operation.Offer && o != null) {
            registry.trackOffer(
                OfferInfo(o.utxo.outpoint, o.fields.key, o.name, o.fields.buyer, o.value, o.fields.refundAfter, System.currentTimeMillis())
            )
        }
        registry.refreshAfter(txId)
        txId
    }

    // Profile record

    /** Writes the address profile (`kchat:1:profile:`): a self-transfer, network fee only. */
    suspend fun saveProfile(profile: Profile): String = withContext(Dispatchers.IO) {
        val s = signer()
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
     *  (nothing is signed or sent). */
    suspend fun quote(name: String, years: Long, gap: GapInfo): Quote = withContext(Dispatchers.IO) {
        val s = signer()
        val m = registry.prepare()
        val c = context(s)
        val b = c.builder
        val env = c.env
        val wallet = c.wallet
        val salt = KachatNamesService.newSalt()
        val spendable = wallet.sumOf { it.entry.amount }
        val price = m.params.price(name.toByteArray(Charsets.UTF_8).size) * years
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
                    b.register(env, rest, GapRecord(gap.lo, gap.hi, m.params.gapValue, gapUtxo), matureCommit, years, Builder.registerNow(env))
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
     * txid.
     */
    suspend fun startRegistration(raw: String, years: Long): String = withContext(Dispatchers.IO) {
        val s = signer()
        val name = Codec.normalize(raw)
        Codec.validate(name)
        registry.refresh()
        if (registry.lookup(name) is Lookup.Registered) {
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
            commitScript = hex(script), stage = PendingRegistration.Stage.COMMITTING, createdAt = now, updatedAt = now
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
        set(p) { it.copy(stage = PendingRegistration.Stage.WAITING, lastError = null) }
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
        if (!KachatNamesService.isEnabled || address == null) {
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
                        if (!KachatNamesService.isEnabled || address == null || address != pendingWallet ||
                            _pending.value.none { it.needsDriving }
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
            PendingRegistration.Stage.FAILED, PendingRegistration.Stage.CANCELLED -> Unit
        }
    }

    private suspend fun ownsName(name: String): Boolean {
        val me = myKey ?: return false
        val l = try {
            registry.lookup(name)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        return l is Lookup.Registered && l.info.owner.contentEquals(me)
    }

    private suspend fun register(p: PendingRegistration, commit: Utxo) {
        try {
            val s = signer()
            if (hex(s.me) != p.owner) return
            val salt = loadSalt(p.id, s.address) ?: throw ActionError.NoSalt()
            registry.refresh()
            val m = registry.prepare()
            val gap = when (val l = registry.lookup(p.name)) {
                is Lookup.Registered -> {
                    if (l.info.owner.contentEquals(s.me)) {
                        finishRegistered(p)
                    } else {
                        set(p) { it.copy(stage = PendingRegistration.Stage.TAKEN, lastError = null) }
                    }
                    return
                }
                is Lookup.Free -> l.gap ?: throw KachatNames.Failure("no gap for ${p.name} yet")
            }
            val c = context(s)
            val plan = c.builder.register(
                c.env, c.wallet, liveGap(gap, m),
                CommitRecord(p.name, s.me, salt, commit.entry.amount, commit),
                p.years, Builder.registerNow(c.env)
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
            // node hiccup) is retried on the next tick
            val fatal = message.contains("insufficient funds") || e is ActionError
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
