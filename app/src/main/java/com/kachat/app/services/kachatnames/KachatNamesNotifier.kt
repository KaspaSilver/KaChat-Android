package com.kachat.app.services.kachatnames

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kachat.app.R
import com.kachat.app.services.GlobalNotificationCenterStore
import com.kachat.app.services.NotificationHelper
import com.kachat.app.util.KaspaUnit
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns registry changes into Profile-bell rows ([GlobalNotificationCenterStore], source
 * "kachat"), so a missed push still leaves a trace: an offer on one of your names, a name sold or
 * reclaimed, its renewal window opening, its expiry and lapse, and what became of your own offers
 * (accepted, declined, expired and returned). It compares what the registry says now with what
 * it saw on the last check (persisted per wallet), after every registry refresh and when the app
 * comes to the foreground (through [KachatNamesRegistry.refreshIfStale]). The first check of a
 * wallet only records where things stand. Only where the registry is live
 * ([KachatNamesService.isLaunched]). iOS `KachatNamesNotifier` (86471dd).
 */
@Singleton
class KachatNamesNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: KachatNamesRegistry,
    private val actions: KachatNamesActions,
    private val service: KachatNamesService,
    private val bell: GlobalNotificationCenterStore,
    /** Local renewal reminders for names on the wallet's other addresses (iOS d1026de, XP-024). */
    private val reminders: KachatNameReminders,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val checking = AtomicBoolean(false)

    private fun key(wallet: String) = "kachatNamesNotifier.$wallet"

    suspend fun check() {
        if (!KachatNamesService.isLaunched) return
        val me = actions.myKey ?: return
        val wallet = actions.myAddress ?: return
        val p = service.manifest.value?.params ?: return
        if (!checking.compareAndSet(false, true)) return
        try {
            // every address of this wallet, not only the chatting one: a name claimed with Buy From
            // gets its notices too (iOS d1026de, IOS-085 / XP-024)
            val own = orNull { actions.ownNamesAndOffers(heldOnly = false) } ?: return
            val owned = own.first
            val myOpenOffers = own.second
            val ownKeys = actions.allOwnKeys()
            val offersOnMine = ArrayList<OfferInfo>()
            for (n in owned) {
                if (n.status(p.graceMs) != Status.ACTIVE) continue
                offersOnMine += (orNull { registry.offers(n.name) } ?: emptyList())
                    .filter { o -> ownKeys.any { it.contentEquals(o.seller) } }
            }
            // The indexer pushes name reminders only to the chatting address: for a name on another
            // address of this wallet the phone schedules its own (XP-024).
            // a different wallet signed in meanwhile: this answer isn't its
            if (actions.myAddress != wallet) return
            reminders.schedule(owned.filter { !it.owner.contentEquals(me) }, p, wallet)

            val old = prefs.getString(key(wallet), null)?.let(Snapshot::decode)
            val now = KachatNames.nowMs()
            val (next, news) = diff(
                old = old, owned = owned, offersOnMine = offersOnMine, myOpenOffers = myOpenOffers,
                params = p, nowMs = now, selfClosed = selfClosedOffers,
                history = { name -> orNull { registry.history(name) } ?: emptyList() }
            )
            for (n in news) {
                bell.record("kachat-${n.id}", SOURCE, title(n), body(n), now, n.name)
            }
            prefs.edit().putString(key(wallet), next.encode()).apply()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.message ?: e}")
        } finally {
            checking.set(false)
        }
    }

    private suspend fun <T> orNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    // The row's words (iOS: every formatted string goes through KaspaUnit.label)

    private fun s(res: Int, vararg args: Any): String = KaspaUnit.label(context.getString(res, *args))

    private fun day(ms: Long): String = com.kachat.app.ui.screens.KachatLive.day(ms)

    private fun title(n: News): String = when (n) {
        is News.RenewOpen -> s(R.string.kn_bell_renew_title, "${n.name}.kachat")
        is News.Expired -> s(R.string.kn_bell_expired_title, "${n.name}.kachat")
        is News.Lapsed -> s(R.string.kn_bell_no_longer_yours_title, "${n.name}.kachat")
        is News.Sold, is News.SoldByOffer -> s(R.string.kn_bell_sold_title, "${n.name}.kachat")
        is News.Reclaimed -> s(R.string.kn_bell_freed_title, "${n.name}.kachat")
        is News.NewOffer -> s(R.string.kn_bell_new_offer_title, "${n.name}.kachat")
        is News.MyOfferAccepted -> s(R.string.kn_ev_offer_accepted)
        is News.MyOfferDeclined -> s(R.string.kn_bell_declined_title, "${n.name}.kachat")
        is News.MyOfferExpired -> s(R.string.kn_bell_offer_expired_title, "${n.name}.kachat")
        is News.MyOfferReturned -> s(R.string.kn_bell_returned_title, "${n.name}.kachat")
    }

    private fun body(n: News): String = when (n) {
        is News.RenewOpen -> s(R.string.kn_bell_renewal_open, day(n.expiresAt))
        is News.Expired -> s(R.string.kn_bell_expired_body, day(n.renewBy))
        is News.Lapsed -> s(R.string.kn_bell_lapsed_available_body)
        is News.Sold -> n.price?.let { s(R.string.kn_bell_paid, KaspaUnit.plain(it)) } ?: s(R.string.kn_bell_listing_bought)
        is News.SoldByOffer -> s(R.string.kn_bell_accepted_offer)
        is News.Reclaimed -> s(R.string.kn_bell_freed_body)
        is News.NewOffer -> s(R.string.kn_bell_offered, KaspaUnit.plain(n.amount))
        is News.MyOfferAccepted -> s(R.string.kn_bell_yours_now, "${n.name}.kachat")
        is News.MyOfferDeclined -> s(R.string.kn_bell_declined_body)
        is News.MyOfferExpired -> s(R.string.kn_bell_offer_expired_body)
        is News.MyOfferReturned -> s(R.string.kn_bell_returned_body)
    }

    // The registry diff (pure)

    /** What the last check saw for one wallet. */
    data class Snapshot(
        /** the names this wallet owned at the last check */
        val names: Map<String, NameNote> = emptyMap(),
        /** open offers on those names, by id */
        val offersOnMine: Set<String> = emptySet(),
        /** this wallet's own open offers: id -> name ("" when unknown) */
        val myOffers: Map<String, String> = emptyMap(),
    ) {
        /** Hand-written JSON, so no field name depends on R8 (see proguard-rules.pro on Gson stores). */
        fun encode(): String {
            val root = JsonObject()
            val names = JsonObject()
            for ((k, v) in this.names) {
                names.add(k, JsonObject().apply {
                    addProperty("expiresAt", v.expiresAt)
                    addProperty("renewNoted", v.renewNoted)
                    addProperty("graceNoted", v.graceNoted)
                    addProperty("lapsedNoted", v.lapsedNoted)
                })
            }
            root.add("names", names)
            root.add("offersOnMine", com.google.gson.JsonArray().apply { offersOnMine.forEach { add(it) } })
            root.add("myOffers", JsonObject().apply { myOffers.forEach { (k, v) -> addProperty(k, v) } })
            return root.toString()
        }

        companion object {
            /** null for anything unreadable: the next check then only takes a baseline again. */
            fun decode(raw: String): Snapshot? = runCatching {
                val root = JsonParser.parseString(raw).asJsonObject
                val names = HashMap<String, NameNote>()
                root.getAsJsonObject("names")?.entrySet()?.forEach { (k, v) ->
                    val o = v.asJsonObject
                    fun flag(f: String) = o.get(f)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                    names[k] = NameNote(o.get("expiresAt").asLong, flag("renewNoted"), flag("graceNoted"), flag("lapsedNoted"))
                }
                val offers = root.getAsJsonArray("offersOnMine")?.map { it.asString }?.toSet() ?: emptySet()
                val mine = root.getAsJsonObject("myOffers")?.entrySet()?.associate { (k, v) -> k to v.asString } ?: emptyMap()
                Snapshot(names, offers, mine)
            }.getOrNull()
        }
    }

    /** One owned name at the last check: which notices its current paid period already had. */
    data class NameNote(
        val expiresAt: Long,
        val renewNoted: Boolean = false,
        val graceNoted: Boolean = false,
        val lapsedNoted: Boolean = false,
    )

    /** One bell row's event; [id] dedupes it in the bell, [name] (bare) is what tapping it opens. */
    sealed class News {
        abstract val id: String
        abstract val name: String

        data class RenewOpen(override val name: String, val expiresAt: Long) : News() {
            override val id get() = "renew-$name-$expiresAt"
        }
        /** [renewBy] = the end of the grace period */
        data class Expired(override val name: String, val expiresAt: Long, val renewBy: Long) : News() {
            override val id get() = "grace-$name-$expiresAt"
        }
        data class Lapsed(override val name: String, val expiresAt: Long) : News() {
            override val id get() = "lapsed-$name-$expiresAt"
        }
        /** your listing bought; [price] in sompi when the history said */
        data class Sold(override val name: String, val txId: String, val price: Long?) : News() {
            override val id get() = "sold-$txId"
        }
        /** sold by accepting an offer */
        data class SoldByOffer(override val name: String, val txId: String) : News() {
            override val id get() = "sold-$txId"
        }
        data class Reclaimed(override val name: String, val txId: String) : News() {
            override val id get() = "reclaimed-$txId"
        }
        data class NewOffer(override val name: String, val offerId: String, val amount: Long) : News() {
            override val id get() = "offer-$offerId"
        }
        data class MyOfferAccepted(override val name: String, val offerId: String) : News() {
            override val id get() = "myoffer-$offerId"
        }
        data class MyOfferDeclined(override val name: String, val offerId: String) : News() {
            override val id get() = "myoffer-$offerId"
        }
        data class MyOfferExpired(override val name: String, val offerId: String) : News() {
            override val id get() = "myoffer-$offerId"
        }
        data class MyOfferReturned(override val name: String, val offerId: String) : News() {
            override val id get() = "myoffer-$offerId"
        }
    }

    companion object {
        private const val TAG = "KachatNamesNotifier"
        private const val PREFS = "kachat_names_notifier"
        const val SOURCE = "kachat"

        /** Offers this person closed themselves (Withdraw, Refund): not news when they disappear.
         *  Filled by [KachatNamesActions.perform]; for this session, as on iOS. */
        val selfClosedOffers: MutableSet<String> = ConcurrentHashMap.newKeySet()

        private val LEFT_OPS = setOf("sale", "offer_accepted", "offer_accept", "transfer", "release", "reclaim")
        private val MY_OFFER_OPS = setOf("offer_decline", "offer_refund", "offer_withdraw")

        /**
         * The news since [old] and the snapshot to keep (iOS `KachatNamesNotifier.check`). [old]
         * null = the wallet's first check: the snapshot only, no news. [history] answers a name's
         * history newest first (empty when it can't be read).
         */
        suspend fun diff(
            old: Snapshot?,
            owned: List<NameInfo>,
            offersOnMine: List<OfferInfo>,
            myOpenOffers: List<OfferInfo>,
            params: Params,
            nowMs: Long,
            selfClosed: Set<String>,
            history: suspend (String) -> List<Event>,
        ): Pair<Snapshot, List<News>> {
            val news = ArrayList<News>()
            val names = LinkedHashMap<String, NameNote>()

            // Your names: renewal open, expired (grace), lapsed - once per paid period.
            for (n in owned) {
                var s = old?.names?.get(n.name)?.takeIf { it.expiresAt == n.expiresAt } ?: NameNote(n.expiresAt)
                when (n.status(params.graceMs, nowMs)) {
                    Status.ACTIVE -> if (n.renewOpen(params, nowMs) && !s.renewNoted) {
                        news += News.RenewOpen(n.name, n.expiresAt)
                        s = s.copy(renewNoted = true)
                    }
                    Status.GRACE -> {
                        s = s.copy(renewNoted = true)
                        if (!s.graceNoted) {
                            news += News.Expired(n.name, n.expiresAt, n.expiresAt + params.graceMs)
                            s = s.copy(graceNoted = true)
                        }
                    }
                    Status.LAPSED -> {
                        s = s.copy(renewNoted = true, graceNoted = true)
                        if (!s.lapsedNoted) {
                            news += News.Lapsed(n.name, n.expiresAt)
                            s = s.copy(lapsedNoted = true)
                        }
                    }
                }
                names[n.name] = s
            }

            // Names that left this wallet: sold, bought through an offer, or reclaimed by someone.
            // A transfer or release is your own doing and needs no notice.
            for (name in old?.names?.keys ?: emptySet()) {
                if (names.containsKey(name)) continue
                val last = history(name).firstOrNull { it.op in LEFT_OPS } ?: continue
                when (last.op) {
                    "sale" -> news += News.Sold(name, last.txId, last.price)
                    "offer_accepted", "offer_accept" -> news += News.SoldByOffer(name, last.txId)
                    "reclaim" -> news += News.Reclaimed(name, last.txId)
                }
            }

            // Offers on your names.
            val onMine = LinkedHashSet<String>()
            for (o in offersOnMine) {
                onMine += o.id
                if (old?.offersOnMine?.contains(o.id) == true) continue
                val name = o.name ?: continue
                news += News.NewOffer(name, o.id, o.amount)
            }

            // Your offers: accepted, or back with you.
            val mine = LinkedHashMap<String, String>()
            for (o in myOpenOffers) mine[o.id] = o.name ?: ""
            for ((id, name) in old?.myOffers ?: emptyMap()) {
                if (mine.containsKey(id) || name.isEmpty() || id in selfClosed) continue
                if (owned.any { it.name == name }) {
                    news += News.MyOfferAccepted(name, id)
                    continue
                }
                news += when (history(name).firstOrNull { it.op in MY_OFFER_OPS }?.op) {
                    "offer_decline" -> News.MyOfferDeclined(name, id)
                    "offer_refund" -> News.MyOfferExpired(name, id)
                    else -> News.MyOfferReturned(name, id)
                }
            }

            val next = Snapshot(names, onMine, mine)
            // the first check only records where things stand
            return next to (if (old == null) emptyList() else news)
        }
    }
}

/**
 * Local reminders for .kachat names held by one of this wallet's spending addresses (Buy From):
 * the indexer's renewal and expiry pushes reach only the chatting address, so the phone schedules
 * its own - renewal opens, the name expired, and a day before grace ends - from each name's expiry
 * (iOS d1026de `KachatNameReminders`, XP-024). Nothing leaves the phone. Rescheduled on every
 * notifier check: a renewed name moves its reminders, a name that left the wallet loses them.
 * Each one is a one-time [KachatNameReminderWorker] under its own unique work name; tapping it
 * opens the name, like the pushes ([NotificationHelper.showKachatName]).
 *
 * Names are `kachat-reminder-<network>-<wallet tag>-<name>-<expiresAt>-<kind>`: each wallet and
 * network keeps its own set, so a check on one never removes another's (iOS 6128f1e, IOS-088).
 */
@Singleton
class KachatNameReminders @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private class Reminder(val date: Long, val title: String, val body: String, val name: String)

    /** [schedule] and [removeAll] read and rewrite the one saved map (iOS: @MainActor). */
    private val mutex = Mutex()

    /** A short, stable tag for a wallet in the names (the address's last 12 characters). */
    private fun walletTag(wallet: String): String = wallet.lowercase().takeLast(12)

    private fun prefix(wallet: String, network: String): String = "$ROOT$network-${walletTag(wallet)}-"

    /** A wallet's reminders on every network, for when it leaves this phone (iOS 6128f1e). */
    suspend fun removeAll(forWallet: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return@withLock
            val tag = "-${walletTag(forWallet)}-"
            val pending = scheduled()
            val ids = pending.keys.filter { it.startsWith(ROOT) && it.contains(tag) }
            if (ids.isEmpty()) return@withLock
            for (id in ids) work.cancelUniqueWork(id)
            save(pending - ids.toSet())
        }
    }

    /** The reminders scheduled so far: unique work name -> when it fires (unix ms). */
    private fun scheduled(): Map<String, Long> = runCatching {
        val root = JsonParser.parseString(prefs.getString(KEY, null) ?: return emptyMap()).asJsonObject
        root.entrySet().associate { (k, v) -> k to v.asLong }
    }.getOrNull() ?: emptyMap()

    private fun save(map: Map<String, Long>) {
        val root = JsonObject()
        for ((k, v) in map) root.addProperty(k, v)
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    private fun s(res: Int, vararg args: Any): String = KaspaUnit.label(context.getString(res, *args))

    private fun day(ms: Long): String = com.kachat.app.ui.screens.KachatLive.day(ms)

    suspend fun schedule(names: List<NameInfo>, params: Params, wallet: String) = withContext(Dispatchers.IO) {
        mutex.withLock { scheduleLocked(names, params, wallet) }
    }

    private fun scheduleLocked(names: List<NameInfo>, params: Params, wallet: String) {
        val p = params
        val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val all = scheduled()
        // the first build's names, one device-wide set: removed wherever they are found
        val legacy = all.keys.filter { it.startsWith(LEGACY_PREFIX) }
        for (id in legacy) work.cancelUniqueWork(id)
        val prefix = prefix(wallet, KachatNamesService.networkName)
        // only this wallet's reminders on this network are this call's to keep or drop
        val others = all.filterKeys { !it.startsWith(LEGACY_PREFIX) && !it.startsWith(prefix) }
        val pending = all.filterKeys { it.startsWith(prefix) }
        // notifications are off for the app: nothing is kept waiting (iOS: not authorized)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            for (id in pending.keys) work.cancelUniqueWork(id)
            if (pending.isNotEmpty() || legacy.isNotEmpty()) save(others)
            return
        }
        val now = KachatNames.nowMs()
        val wanted = LinkedHashMap<String, Reminder>()
        val bases = HashSet<String>()
        for (n in names) {
            val display = "${n.name}.kachat"
            val base = "$prefix${n.name}-${n.expiresAt}-"
            bases += base
            val renewAt = n.expiresAt - p.renewWindowMs
            if (renewAt > now) {
                wanted[base + "renew"] = Reminder(
                    renewAt, s(R.string.kn_bell_renew_title, display),
                    s(R.string.kn_bell_renewal_open, day(n.expiresAt)), n.name
                )
            }
            if (n.expiresAt > now) {
                wanted[base + "grace"] = Reminder(
                    n.expiresAt, s(R.string.kn_bell_expired_title, display),
                    s(R.string.kn_bell_expired_body, day(n.expiresAt + p.graceMs)), n.name
                )
            }
            val lastCall = n.expiresAt + p.graceMs - minOf(p.graceMs / 2, 86_400_000L)
            if (lastCall > now) {
                wanted[base + "lastcall"] = Reminder(
                    lastCall, s(R.string.kn_bell_expired_title, display),
                    s(R.string.kn_bell_expired_body, day(n.expiresAt + p.graceMs)), n.name
                )
            }
        }
        // iOS hands a due notification to the system at its time; WorkManager may run one a little
        // late (Doze), so one just past its time for a name that is still here is left to fire
        val keep = HashMap<String, Long>()
        for ((id, date) in pending) {
            if (wanted.containsKey(id)) continue
            val overdue = date <= now && now - date < 86_400_000L && bases.any { id.startsWith(it) }
            if (overdue) keep[id] = date else work.cancelUniqueWork(id)
        }
        for ((id, r) in wanted) {
            if (pending.containsKey(id)) continue
            val request = OneTimeWorkRequestBuilder<KachatNameReminderWorker>()
                .setInitialDelay(maxOf(1_000L, r.date - now), TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        KachatNameReminderWorker.KEY_ID to id,
                        KachatNameReminderWorker.KEY_TITLE to r.title,
                        KachatNameReminderWorker.KEY_BODY to r.body,
                        KachatNameReminderWorker.KEY_NAME to r.name,
                    )
                )
                .addTag(ROOT)
                .build()
            work.enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, request)
        }
        save(others + keep + wanted.mapValues { it.value.date })
    }

    private companion object {
        const val PREFS = "kachat_name_reminders"
        const val KEY = "scheduled"
        /** `kachat-reminder-<network>-<wallet tag>-<name>-<expiresAt>-<kind>` (IOS-088). */
        const val ROOT = "kachat-reminder-"
        /** The first build's names, one device-wide set: removed wherever they are found. */
        const val LEGACY_PREFIX = "kachat-local-"
    }
}

/** Shows one [KachatNameReminders] reminder when its time comes (iOS: the scheduled local
 *  notification, thread "kachat-names"); tapping it opens the name. */
@HiltWorker
class KachatNameReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val notifications: NotificationHelper,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val title = inputData.getString(KEY_TITLE) ?: return Result.success()
        val body = inputData.getString(KEY_BODY) ?: return Result.success()
        val name = inputData.getString(KEY_NAME) ?: return Result.success()
        notifications.showKachatName(title, body, name, dedupeKey = id)
        return Result.success()
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_TITLE = "title"
        const val KEY_BODY = "body"
        const val KEY_NAME = "name"
    }
}
