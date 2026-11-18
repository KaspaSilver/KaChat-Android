package com.kachat.app.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What was typed and not sent, per conversation - left in the composer when the reader leaves,
 * put back when they return, cleared by sending. Mirrors iOS's ChatService drafts: 1:1 chats
 * are keyed by the contact's address, group chats "group:<id>", public rooms "room:<name>"
 * (iOS 360e5d2).
 *
 * Kept per account (iOS 83806b3d, `kachat_message_drafts_<address>`): one store for every account
 * let the next account to sign in find the last one's half-typed messages. The drafts in memory
 * are the active account's, reloaded when it changes; Log Out and Delete Account erase an
 * account's ([discardDrafts]).
 *
 * Reads and writes hit the in-memory map, so every keystroke is cheap; the disk write is
 * debounced off the typing path, as iOS does.
 */
@Singleton
class DraftStore @Inject constructor(
    @ApplicationContext context: Context,
    private val walletManager: WalletManager,
) {
    private val prefs = context.getSharedPreferences("kachat_drafts", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    private val drafts = ConcurrentHashMap<String, String>()

    /** The account (lowercased address) whose drafts [drafts] holds; null when none is loaded. */
    @Volatile private var owner: String? = null

    fun draft(key: String): String {
        ensureDraftsBelongToActiveAccount()
        return drafts[key].orEmpty()
    }

    fun setDraft(key: String, text: String) {
        ensureDraftsBelongToActiveAccount()
        if (owner == null) return
        if (text.isBlank()) {
            if (drafts.remove(key) == null) return
        } else {
            if (drafts[key] == text) return
            drafts[key] = text
        }
        scheduleSave()
    }

    /**
     * Erases [walletAddress]'s drafts (Delete Account, Log Out, iOS 83806b3d `discardDrafts`): its
     * stored copy, and the copy in memory with any write still pending, so nothing writes them back
     * afterwards. Another account's drafts are untouched.
     */
    @Synchronized
    fun discardDrafts(walletAddress: String) {
        val account = walletAddress.trim().lowercase()
        if (account.isEmpty()) return
        if (owner == account) {
            saveJob?.cancel()
            saveJob = null
            drafts.clear()
            owner = null
        }
        prefs.edit().remove(storageKey(account)).apply()
    }

    /** The drafts in memory belong to the active account: reloaded when it changes (an account
     *  switch, a sign-in), after the last account's pending write goes to its own key. */
    @Synchronized
    private fun ensureDraftsBelongToActiveAccount() {
        val active = walletManager.activeAddressFlow.value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (active == owner) return
        flushPendingSave()
        load(active)
    }

    private fun load(account: String?) {
        drafts.clear()
        owner = account
        if (account == null) return
        val stored = prefs.getString(storageKey(account), null)
        val legacy = prefs.getString(LEGACY_KEY, null)
        val json = when {
            stored != null -> stored
            // The old single store: only one account on this phone can have written it.
            legacy != null && walletManager.getAllAccounts().size <= 1 -> legacy.also {
                prefs.edit().putString(storageKey(account), it).apply()
            }
            else -> null
        }
        runCatching {
            val obj = JSONObject(json ?: "{}")
            obj.keys().forEach { key -> drafts[key] = obj.getString(key) }
        }
        // With more than one account the old store's owner is unknown: dropped, never shown.
        if (legacy != null) prefs.edit().remove(LEGACY_KEY).apply()
    }

    /** Writes the drafts in memory to their account's key now, in place of a pending write. */
    private fun flushPendingSave() {
        val pending = saveJob ?: return
        pending.cancel()
        saveJob = null
        val account = owner ?: return
        write(account, snapshot())
    }

    private fun snapshot(): Map<String, String> = HashMap(drafts)

    private fun write(account: String, values: Map<String, String>) {
        if (values.isEmpty()) {
            prefs.edit().remove(storageKey(account)).apply()
            return
        }
        val json = JSONObject()
        values.forEach { (key, text) -> json.put(key, text) }
        prefs.edit().putString(storageKey(account), json.toString()).apply()
    }

    @Synchronized
    private fun scheduleSave() {
        val account = owner ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            synchronized(this@DraftStore) {
                // An account switch or a discard since: that path already wrote (or erased) them.
                if (owner != account) return@launch
                write(account, snapshot())
            }
        }
    }

    companion object {
        /** The store every account shared before 5.3; read once (one account only), then removed. */
        private const val LEGACY_KEY = "drafts"
        private const val SAVE_DEBOUNCE_MS = 500L

        /** One account's drafts (iOS `draftsStorageKey(forWallet:)`). */
        internal fun storageKey(walletAddress: String) = "drafts_${walletAddress.trim().lowercase()}"

        fun groupKey(groupId: String) = "group:$groupId"
        fun roomKey(channelName: String) = "room:${channelName.trim().lowercase()}"
    }
}
