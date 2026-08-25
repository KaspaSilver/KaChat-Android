package com.kachat.app.services

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global notification center backing the bell on the Profile screen - ONE feed aggregating
 * KaPosts activity (likes/replies/quotes/follows/@mentions), group-chat @mentions of your own
 * KNS domain, and live broadcast messages. Direct port of iOS's GlobalNotificationCenter /
 * desktop's top-bar bell: entries are account-scoped, persisted, deduped by id, capped;
 * opening the list marks everything seen.
 *
 * Sources push in via [record]:
 *  - KaPosts: [KaPostsNotificationPoller] records every fresh notification (independent of the
 *    per-kind banner gates - the center always lists activity).
 *  - Group mentions: GroupRepository calls [recordGroupMentionIfNeeded] on incoming messages.
 *  - Broadcasts: BroadcastRepository records live (session-gated) incoming channel rows.
 */
@Singleton
class GlobalNotificationCenterStore @Inject constructor(
    @ApplicationContext context: Context,
    private val walletManager: WalletManager,
    private val knsService: KnsService,
) {
    data class Entry(
        val id: String,
        /** "kaposts" | "group" | "broadcast" */
        val source: String,
        val title: String,
        val body: String,
        val timestampMs: Long,
        /** group id / channel name / post txid - what tapping the row relates to. */
        val targetId: String?,
    )

    private val prefs = context.getSharedPreferences("global_notification_center", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<Entry>>() {}.type

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val _lastSeenAt = MutableStateFlow(0L)
    val lastSeenAt: StateFlow<Long> = _lastSeenAt.asStateFlow()

    /** Broadcast rows older than app launch are history, not live arrivals - never listed. */
    private val sessionStartMs = System.currentTimeMillis()

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
        _entries.value = try {
            gson.fromJson(prefs.getString(entriesKey(wallet), null) ?: "[]", listType) ?: emptyList()
        } catch (_: Exception) { emptyList() }
        _lastSeenAt.value = prefs.getLong(seenKey(wallet), 0L)
    }

    private fun persist() {
        val wallet = loadedWallet ?: return
        prefs.edit().putString(entriesKey(wallet), gson.toJson(_entries.value.take(maxEntries))).apply()
    }

    @Synchronized
    fun record(id: String, source: String, title: String, body: String, timestampMs: Long, targetId: String?) {
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

    // MARK: - Group @mentions (called from GroupRepository on incoming messages)

    /** Records a center entry when `text` @mentions the current wallet's own KNS domain. */
    suspend fun recordGroupMentionIfNeeded(
        groupId: String,
        groupName: String,
        senderAddress: String,
        senderName: String,
        text: String,
        txId: String?,
        timestampMs: Long,
    ) {
        val myAddress = walletAddressOrNull() ?: return
        if (senderAddress == myAddress) return
        if (!mentionsMyDomain(text)) return
        record(
            id = "group-mention-${txId ?: "$groupId-$timestampMs"}",
            source = "group",
            title = "$senderName mentioned you in $groupName",
            body = text.take(90),
            timestampMs = timestampMs,
            targetId = groupId,
        )
    }

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

    // MARK: - Broadcasts (called from BroadcastRepository on merged rows)

    fun recordBroadcastIfLive(channel: String, senderAddress: String, senderName: String, content: String, txId: String, blockTimeMs: Long) {
        if (blockTimeMs < sessionStartMs) return
        if (senderAddress == walletAddressOrNull()) return
        record(
            id = "broadcast-$txId",
            source = "broadcast",
            title = "$senderName in #$channel",
            body = content.take(90),
            timestampMs = blockTimeMs,
            targetId = channel,
        )
    }

    companion object {
        private const val TAG = "GlobalNotifCenter"

        /** Same @token rule as the KaPosts mention parser. */
        val MENTION_TOKEN_REGEX = Regex("(^|[\\s(\\[{<\"'])@([a-z0-9-]+(?:\\.[a-z0-9-]+)*)", RegexOption.IGNORE_CASE)
    }
}
