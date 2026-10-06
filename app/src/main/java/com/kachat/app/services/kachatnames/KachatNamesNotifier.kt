package com.kachat.app.services.kachatnames

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kachat.app.R
import com.kachat.app.services.GlobalNotificationCenterStore
import com.kachat.app.util.KaspaUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
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
            val owned = orNull { registry.names(me, includeInactive = true) } ?: return
            val myOpenOffers = orNull { registry.myOffers(me) } ?: return
            val offersOnMine = ArrayList<OfferInfo>()
            for (n in owned) {
                if (n.status(p.graceMs) != Status.ACTIVE) continue
                offersOnMine += (orNull { registry.offers(n.name) } ?: emptyList()).filter { it.seller.contentEquals(me) }
            }
            // a different wallet signed in meanwhile: this answer isn't its
            if (actions.myAddress != wallet) return

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
        is News.Lapsed -> s(R.string.kn_bell_lapsed_title, "${n.name}.kachat")
        is News.Sold, is News.SoldByOffer -> s(R.string.kn_bell_sold_title, "${n.name}.kachat")
        is News.Reclaimed -> s(R.string.kn_bell_reclaimed_title, "${n.name}.kachat")
        is News.NewOffer -> s(R.string.kn_bell_new_offer_title, "${n.name}.kachat")
        is News.MyOfferAccepted -> s(R.string.kn_ev_offer_accepted)
        is News.MyOfferDeclined -> s(R.string.kn_bell_declined_title, "${n.name}.kachat")
        is News.MyOfferExpired -> s(R.string.kn_bell_offer_expired_title, "${n.name}.kachat")
        is News.MyOfferReturned -> s(R.string.kn_bell_returned_title, "${n.name}.kachat")
    }

    private fun body(n: News): String = when (n) {
        is News.RenewOpen -> s(R.string.kn_bell_renewal_open, day(n.expiresAt))
        is News.Expired -> s(R.string.kn_bell_expired_body, day(n.renewBy))
        is News.Lapsed -> s(R.string.kn_bell_lapsed_moved_body)
        is News.Sold -> n.price?.let { s(R.string.kn_bell_paid, KaspaUnit.plain(it)) } ?: s(R.string.kn_bell_listing_bought)
        is News.SoldByOffer -> s(R.string.kn_bell_accepted_offer)
        is News.Reclaimed -> s(R.string.kn_bell_reclaimed_body)
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
