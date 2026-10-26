package com.kachat.app.services

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bell on the Profile screen holds Kaspa arriving in one of your own addresses - the chatting
 * address or a spending address (fed by [AddressActivityNotifier]; iOS 86471dd). .kachat name news
 * left with the .kachat market on 2026-10-24 (iOS d54ea87), and group chats keep their own unread
 * counts in the Chats tab (iOS cda2715). Entries are account-scoped, persisted, deduped by id and
 * capped; opening the list marks everything seen.
 */
@Singleton
class GlobalNotificationCenterStore @Inject constructor(
    @ApplicationContext context: Context,
    private val walletManager: WalletManager,
    private val knsService: KnsService,
) {
    data class Entry(
        val id: String,
        /** "wallet" ([IN_BELL]) - older builds also stored "kaposts", "group", "broadcast" and
         *  "kachat" rows. */
        val source: String,
        val title: String,
        val body: String,
        val timestampMs: Long,
        /** group id / channel name / post txid / .kachat name / the own address a wallet receipt
         *  hit (iOS 95e2cba) - what tapping the row relates to. Null on wallet rows saved before
         *  receipts carried their address; those open Portfolio. */
        val targetId: String?,
    )

    private val prefs = context.getSharedPreferences("global_notification_center", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<Entry>>() {}.type

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _lastSeenAt = MutableStateFlow(0L)
    val lastSeenAt: StateFlow<Long> = _lastSeenAt.asStateFlow()

    private val maxEntries = 100
    private var loadedWallet: String? = null

    /** My own bare KNS domain, cached per wallet, for group @mention detection. */
    private var myDomainWallet: String? = null
    private var myDomain: String? = null

    private fun walletAddressOrNull(): String? =
        try { walletManager.getAddress() } catch (_: Exception) { null }

    private fun entriesKey(wallet: String) = "entries_$wallet"
    private fun seenKey(wallet: String) = "seen_$wallet"

    /** Loads the active wallet's feed; call before reading and on account switches. */
    @Synchronized
    fun reloadIfNeeded() {
        val wallet = walletAddressOrNull() ?: return
        if (loadedWallet == wallet) return
        loadedWallet = wallet
        val loaded = try {
            gson.fromJson(prefs.getString(entriesKey(wallet), null) ?: "[]", listType) ?: emptyList()
        } catch (_: Exception) { emptyList<Entry>() }
        // Wallet rows only. Group mentions carry their own unread counts in the Chats tab, and
        // KaPosts, Public Chats and the .kachat market are gone; anything an older build saved for
        // those is dropped here.
        val kept = loaded.filter { it.source in IN_BELL }
        _entries.value = kept
        _lastSeenAt.value = prefs.getLong(seenKey(wallet), 0L)
        if (kept.size != loaded.size) persist()
    }

    private fun persist() {
        val wallet = loadedWallet ?: return
        prefs.edit().putString(entriesKey(wallet), gson.toJson(_entries.value.take(maxEntries))).apply()
    }

    @Synchronized
    fun record(id: String, source: String, title: String, body: String, timestampMs: Long, targetId: String?) {
        // Wallet news only. Refused here rather than merely left uncalled, so a future caller
        // cannot quietly put anything else back in the bell.
        if (source !in IN_BELL) return
        reloadIfNeeded()
        if (loadedWallet == null) return
        if (id.isEmpty() || _entries.value.any { it.id == id }) return
        _entries.value = (listOf(Entry(id, source, title, body, timestampMs, targetId)) + _entries.value)
            .take(maxEntries)
        persist()
    }

    fun markAllSeen() {
        val wallet = loadedWallet ?: return
        _lastSeenAt.value = System.currentTimeMillis()
        prefs.edit().putLong(seenKey(wallet), _lastSeenAt.value).apply()
    }

    fun clearAll() {
        _entries.value = emptyList()
        persist()
    }

    // MARK: - Group @mentions

    /** Group chats carry their own unread counts and mention handling in the Chats tab, so
     *  mentions no longer go through the bell (iOS cda2715). Kept as a no-op for the call site;
     *  rows an older build recorded are dropped on load. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun recordGroupMentionIfNeeded(
        groupId: String,
        groupName: String,
        senderAddress: String,
        senderName: String,
        text: String,
        txId: String?,
        timestampMs: Long,
    ) {}

    /**
     * Whether [text] @mentions the current wallet's own primary KNS domain, using the same
     * @token grammar the group composer's mention autocomplete inserts ("@domain", see
     * [MENTION_TOKEN_REGEX]; ".kas" suffix optional, case-insensitive). This is the single
     * definition of "mentions me": the notification-center entry above and GroupRepository's
     * mentions-only banner gate both go through it, so they can never disagree on what counts
     * as a mention. Reverse resolution of our own domain is cached per wallet.
     */
    suspend fun mentionsMyDomain(text: String): Boolean {
        val myAddress = walletAddressOrNull() ?: return false
        if (myDomainWallet != myAddress) {
            myDomain = try { knsService.reverseResolve(myAddress) } catch (_: Exception) { null }
                ?.lowercase()?.removeSuffix(".kas")
            myDomainWallet = myAddress
        }
        val domain = myDomain ?: return false
        return MENTION_TOKEN_REGEX.findAll(text).any {
            it.groupValues[2].lowercase().removeSuffix(".kas") == domain
        }
    }

    companion object {
        private const val TAG = "GlobalNotifCenter"

        /** What the Profile bell lists (iOS `Entry.Source.inBell`, 4500ebc). */
        val IN_BELL = setOf("wallet")

        /** The source tag a row shows (iOS `Entry.Source.label`). */
        fun sourceLabel(source: String): String = when (source) {
            "wallet" -> "Wallet"
            "kachat" -> ".kachat"
            "group" -> "Group"
            else -> source
        }

        /** An @mention token in a message: "@bob" or "@bob.kachat". */
        val MENTION_TOKEN_REGEX = Regex("(^|[\\s(\\[{<\"'])@([a-z0-9-]+(?:\\.[a-z0-9-]+)*)", RegexOption.IGNORE_CASE)
    }
}
