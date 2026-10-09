package com.kachat.app.repository

import android.content.Context
import com.google.gson.Gson
import com.kachat.app.services.WalletManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One wallet's Message Requests state (NO_HANDSHAKE_MESSAGING.md, iOS `ChatRequestState`), stored
 * per wallet beside the chats. Addresses are lowercased.
 */
data class ChatRequestState(
    val accepted: Set<String> = emptySet(),
    /** Chats started as Private: never sent with the inbox tag. */
    val privateChats: Set<String> = emptySet(),
    /** Rejected: ignored until the user writes to them. */
    val blocked: Set<String> = emptySet(),
    /** Addresses we have already sent our one inbox-tagged first message to. */
    val inboxTagged: Set<String> = emptySet(),
    /** Newest inbox message seen, for the next lookup. */
    val inboxCursor: Long = 0L,
    /** When this wallet first ran with Message Requests. A chat whose first message is older is
     *  an ordinary chat - nothing that existed before becomes a request. */
    val startedAt: Long = System.currentTimeMillis(),
)

@Singleton
class ChatRequestStore @Inject constructor(
    @ApplicationContext context: Context,
    private val walletManager: WalletManager,
) {
    private val prefs = context.getSharedPreferences("kachat_chat_requests", Context.MODE_PRIVATE)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ChatRequestState>()
    private val lock = Any()

    /** Bumped on every accept / reject / private / block change, so lists re-filter. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Gson leaves a missing field null whatever Kotlin says, so state saved before a field
     *  existed is read through this nullable shape (iOS decodes the same way). */
    private data class Stored(
        val accepted: List<String>? = null,
        val privateChats: List<String>? = null,
        val blocked: List<String>? = null,
        val inboxTagged: List<String>? = null,
        val inboxCursor: Long? = null,
        val startedAt: Long? = null,
    )

    private fun key(wallet: String) = "kachat_chat_requests_v1." + wallet.lowercase()

    private fun activeWallet(): String? = runCatching { walletManager.getAddress() }.getOrNull()?.takeIf { it.isNotBlank() }

    /** The wallet's state, loaded (or started) on first use. */
    fun state(wallet: String? = activeWallet()): ChatRequestState {
        val w = wallet?.lowercase() ?: return ChatRequestState()
        cache[w]?.let { return it }
        synchronized(lock) {
            cache[w]?.let { return it }
            val raw = prefs.getString(key(w), null)
            if (raw != null && isForeignRecord(raw)) {
                // Written by a release build that renamed the fields (audit AND-001). Its four
                // lists share one type, so reading it could file the blocked list as the
                // accepted one; it is reset instead, once, and rebuilt like a first run below.
                android.util.Log.w("ChatRequestStore", "Reset Message Requests state saved in an unreadable shape")
            }
            val state = raw?.let { decode(it) } ?: run {
                // First run with Message Requests: everything already here stays an ordinary chat.
                ChatRequestState().also { save(w, it) }
            }
            cache[w] = state
            return state
        }
    }

    private fun save(wallet: String, state: ChatRequestState) {
        prefs.edit().putString(key(wallet), encode(state)).apply()
    }

    fun update(wallet: String? = activeWallet(), change: (ChatRequestState) -> ChatRequestState) {
        val w = wallet?.lowercase() ?: return
        synchronized(lock) {
            val updated = change(state(w))
            cache[w] = updated
            save(w, updated)
        }
        _revision.value = _revision.value + 1
    }

    /** The active wallet's state, re-emitted on a wallet switch or any change. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stateFlow(): Flow<ChatRequestState> =
        combine(walletManager.activeAddressFlow, revision) { address, _ -> state(address) }

    fun isBlocked(address: String): Boolean = state().blocked.contains(address.lowercase())
    fun isPrivate(address: String): Boolean = state().privateChats.contains(address.lowercase())

    /** Accept: writing to someone, an explicit Accept, or starting a chat - and it lifts a block. */
    fun accept(address: String) {
        val k = address.lowercase()
        val current = state()
        if (current.accepted.contains(k) && !current.blocked.contains(k)) return
        update { it.copy(accepted = it.accepted + k, blocked = it.blocked - k) }
    }

    fun block(address: String) {
        val k = address.lowercase()
        update { it.copy(blocked = it.blocked + k, accepted = it.accepted - k, privateChats = it.privateChats - k) }
    }

    fun setPrivate(address: String, isPrivate: Boolean) {
        val k = address.lowercase()
        update {
            it.copy(
                privateChats = if (isPrivate) it.privateChats + k else it.privateChats - k,
                accepted = it.accepted + k,
                blocked = it.blocked - k,
            )
        }
    }

    /**
     * Deleting a chat forgets that it was accepted (or Private): a new chat from that person,
     * after the deletion, lands in Message Requests. A block stays as it was (iOS 4b00a5f
     * forgetChatAcceptance).
     */
    fun forgetChatAcceptance(address: String, wallet: String? = activeWallet()) {
        val k = address.lowercase()
        val current = state(wallet)
        if (!current.accepted.contains(k) && !current.privateChats.contains(k)) return
        update(wallet) { it.copy(accepted = it.accepted - k, privateChats = it.privateChats - k) }
    }

    fun markInboxTagged(address: String) {
        val k = address.lowercase()
        update { it.copy(inboxTagged = it.inboxTagged + k) }
    }

    fun setInboxCursor(wallet: String, cursor: Long) {
        update(wallet) { if (cursor > it.inboxCursor) it.copy(inboxCursor = cursor) else it }
    }

    // Push: whom the "New message request" notification has already rung, once per sender
    // (iOS keeps this in the notification extension as `chat_request_notified`).

    private fun notifiedKey(wallet: String) = "kachat_chat_request_notified." + wallet.lowercase()

    /** Records [address] as rung; false when it already was. */
    fun markRequestNotified(address: String, wallet: String? = activeWallet()): Boolean {
        val w = wallet ?: return false
        synchronized(lock) {
            val set = prefs.getStringSet(notifiedKey(w), emptySet()).orEmpty()
            val k = address.lowercase()
            if (k in set) return false
            prefs.edit().putStringSet(notifiedKey(w), set + k).apply()
            return true
        }
    }

    companion object {
        private val gson = Gson()

        /** [Stored]'s field names, as Gson writes them. app/proguard-rules.pro keeps them in
         *  release builds; ChatRequestStoreTest checks the set against the class. */
        internal val STORED_FIELDS = setOf("accepted", "privateChats", "blocked", "inboxTagged", "inboxCursor", "startedAt")

        internal fun encode(state: ChatRequestState): String = gson.toJson(
            Stored(
                accepted = state.accepted.toList(),
                privateChats = state.privateChats.toList(),
                blocked = state.blocked.toList(),
                inboxTagged = state.inboxTagged.toList(),
                inboxCursor = state.inboxCursor,
                startedAt = state.startedAt,
            )
        )

        /** A record written with renamed fields (a release build without the keep rule). */
        internal fun isForeignRecord(json: String): Boolean =
            com.kachat.app.util.PersistedJson.hasForeignFields(json, STORED_FIELDS)

        /** The saved state; null when the record does not parse or [isForeignRecord] - the
         *  caller then starts over rather than misread it. */
        internal fun decode(json: String): ChatRequestState? {
            if (isForeignRecord(json)) return null
            val stored = try { gson.fromJson(json, Stored::class.java) } catch (e: Exception) { null } ?: return null
            return ChatRequestState(
                accepted = stored.accepted.orEmpty().toSet(),
                privateChats = stored.privateChats.orEmpty().toSet(),
                blocked = stored.blocked.orEmpty().toSet(),
                inboxTagged = stored.inboxTagged.orEmpty().toSet(),
                inboxCursor = stored.inboxCursor ?: 0L,
                startedAt = stored.startedAt ?: System.currentTimeMillis(),
            )
        }

        /**
         * A conversation someone else started that the user hasn't accepted (iOS
         * `isMessageRequest`). Writing to them, a manually added contact, an explicit Accept, or a
         * chat that already existed when Message Requests arrived (its first message, or the
         * contact itself, predates [ChatRequestState.startedAt]) all make it an ordinary chat.
         */
        fun isMessageRequest(
            contactId: String,
            contactAddedAt: Long,
            myAddress: String?,
            state: ChatRequestState,
            firstMessageAt: Long?,
            anySent: Boolean,
        ): Boolean {
            val address = contactId.lowercase()
            if (myAddress != null && address == myAddress.lowercase()) return false
            if (address in state.accepted || address in state.privateChats || address in state.blocked) return false
            if (anySent) return false
            val first = firstMessageAt ?: return false
            return first >= state.startedAt && contactAddedAt >= state.startedAt
        }
    }
}
