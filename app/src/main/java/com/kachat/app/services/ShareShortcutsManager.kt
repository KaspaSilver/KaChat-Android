package com.kachat.app.services

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.kachat.app.MainActivity
import com.kachat.app.models.Conversation
import com.kachat.app.models.MessageEntity
import com.kachat.app.services.database.KaChatDatabase
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaNetwork
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Content handed off from a system share sheet (ACTION_SEND / ACTION_SEND_MULTIPLE) to the
 * Compose UI — same cross-screen handoff pattern as [com.kachat.app.ui.screens.KaPostsDeepLink].
 *
 * MainActivity stashes the share here (after copying any image streams into app cache, since the
 * sender's content-URI grant doesn't outlive the receiving task). Routing:
 *  - [PendingShare.targetContactId] non-null (user picked a conversation directly on the share
 *    sheet via a published direct-share shortcut): MainShell navigates straight into that chat.
 *  - null (user tapped the plain "KaChat" share target): MainShell lands on the Chats list, which
 *    shows a "choose a chat" banner; whichever chat thread the user opens next consumes the share.
 *
 * ChatThreadScreen consumes it by pre-filling the composer (text) and/or staging the shared
 * photo(s) through the normal picked-photo pipeline, then clears [pending].
 */
object ShareIntake {
    val pending = MutableStateFlow<PendingShare?>(null)

    /**
     * A share whose destination conversation is now known, staged for the in-place compose sheet
     * (see `ShareComposeSheet`) — set either straight from a direct-share pick (the contact came
     * with the intent) or by the chat the user opened for an untargeted share. The sheet is
     * rendered above everything by `KaChatApp` and clears this when it closes.
     */
    val compose = MutableStateFlow<ShareCompose?>(null)
}

/** A pending share with its chosen conversation — the input to the in-place compose sheet. */
data class ShareCompose(
    val contactId: String,
    val text: String?,
    /** file:// URIs of app-cache copies of the shared images (see [PendingShare.imageUris]). */
    val imageUris: List<Uri>
)

data class PendingShare(
    val text: String?,
    /** file:// URIs of app-cache copies of the shared images (safe to read at any later point). */
    val imageUris: List<Uri>,
    /** Contact address picked on the share sheet (from the direct-share shortcut id, and only when
     *  that shortcut belongs to the signed-in account), or null for "into KaChat, pick a chat". */
    val targetContactId: String?,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** An untargeted share the user never delivered shouldn't surprise-prefill a chat opened much later. */
    fun isExpired(): Boolean = System.currentTimeMillis() - createdAt > 10 * 60_000L
}

/**
 * Publishes the most recent conversations as dynamic sharing shortcuts
 * (https://developer.android.com/training/sharing/direct-share-targets), so sharing text or an
 * image from any other app offers recent KaChat chats as one-tap direct-share targets.
 *
 * Refreshed from ChatViewModel when a chat is opened or a message is sent — the two moments the
 * recency order can change while the app is in the foreground. Publishing is diffed against the
 * last published (id, label) list so repeat calls with an unchanged top-N are free, and the actual
 * ShortcutManager binder work runs off the main thread.
 *
 * Only the signed-in account's own chats are targets, and each shortcut names its account in its
 * id ([shortcutId]): a share to another account's chat would be sent from this one, linking the two
 * on chain (iOS d23e6f7a, share extension bound to its account). An account switch replaces them
 * with the new account's chats; signed out there are none ([syncWithActiveAccount]).
 */
@Singleton
class ShareShortcutsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager,
    private val database: KaChatDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var lastPublished: List<ShortcutEntry>? = null

    /** One ShortcutManager write at a time: an account switch's removal must not interleave with
     *  a publish of the previous account's list. */
    private val publishLock = Mutex()

    /** One publishable share target: its account, its name and its KNS avatar (else initials). */
    private data class ShortcutEntry(
        val owner: String,
        val contactId: String,
        val label: String,
        val knsAvatarUrl: String?
    ) {
        val id: String get() = shortcutId(owner, contactId)
    }

    init {
        // Fires at once with the account signed in now - which also replaces shortcuts an earlier
        // run left for another account, or in the old bare-address form - then on every switch,
        // create, import and delete.
        scope.launch {
            walletManager.activeAddressFlow.collect { syncWithActiveAccount() }
        }
    }

    /** The account whose chats may be share targets: the active one, unless signed out. */
    private fun signedInAccount(): String? =
        walletManager.activeAddressFlow.value?.takeUnless { walletManager.isLoggedOut }

    /**
     * Brings the published shortcuts in line with who is signed in: kept when they are all this
     * account's, otherwise removed and, while signed in, replaced with this account's recent chats
     * read from the database. Called on every account change (above) and by MainActivity when the
     * signed-in state changes (Log Out doesn't change the active address).
     */
    fun syncWithActiveAccount() {
        scope.launch {
            try {
                publishLock.withLock {
                    val owner = signedInAccount()
                    val existing = ShortcutManagerCompat.getDynamicShortcuts(context)
                    val allOwned = owner != null && existing.all { info ->
                        parseShortcutId(info.id)?.owner?.let { KaspaNetwork.isSameAccount(it, owner) } == true
                    }
                    if (allOwned && existing.isNotEmpty()) return@withLock
                    if (existing.isNotEmpty()) removeAll(existing.map { it.id })
                    if (owner == null) return@withLock
                    val contacts = database.contactDao().getContacts(owner).first()
                    val latest = database.messageDao()
                        .getLatestMessagePerContact(owner, MessageEntity.HIDDEN_PLACEHOLDERS).first()
                        .associateBy { it.contactId }
                    val top = entriesFor(owner, contacts.map { Conversation(it, latest[it.id], 0) }, null)
                    // the account may have changed during the reads; the next sync handles that one
                    if (owner != signedInAccount()) return@withLock
                    lastPublished = top
                    publish(top)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Failed to bring share shortcuts in line with the signed-in account", e)
            }
        }
    }

    /** Every published share target gone, cached long-lived copies included. */
    private fun removeAll(ids: List<String>) {
        lastPublished = null
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        runCatching { ShortcutManagerCompat.removeLongLivedShortcuts(context, ids) }
    }

    /** [promoteContactId] pins that conversation to the front — used at send time, before the
     *  conversations flow has reordered around the still-pending insert. */
    fun refresh(conversations: List<Conversation>, promoteContactId: String? = null) {
        val owner = signedInAccount() ?: return
        val top = entriesFor(owner, conversations, promoteContactId)
        if (top == lastPublished) return
        lastPublished = top
        scope.launch {
            try {
                publishLock.withLock {
                    // A list built before an account switch is not published over the new one's.
                    val current = signedInAccount()
                    if (current == null || !KaspaNetwork.isSameAccount(current, owner)) return@withLock
                    publish(top)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Never let launcher/shortcut quirks (rate limiting, OEM bugs) affect messaging.
                Log.w(TAG, "Failed to publish share shortcuts", e)
            }
        }
    }

    /** [owner]'s most recent chats as share targets. */
    private fun entriesFor(owner: String, conversations: List<Conversation>, promoteContactId: String?): List<ShortcutEntry> {
        return conversations
            .asSequence()
            // only this account's own chats, never one left in a list from before a switch
            .filter { KaspaNetwork.isSameAccount(it.contact.walletAddress, owner) }
            .filter { it.contact.conversationStatus != "rejected" }
            // Only the running network's chats are share targets (iOS 421a832).
            .filter { com.kachat.app.util.KaspaNetwork.isOnActiveNetwork(it.contact.id) }
            .sortedByDescending {
                if (it.contact.id == promoteContactId) Long.MAX_VALUE
                else it.lastMessage?.blockTimestamp ?: 0L
            }
            .take(MAX_SHORTCUTS)
            .map { convo ->
                // A name you gave the chat, else the Address Book's (iOS 00767a4).
                val label = convo.contact.alias?.takeIf { it.isNotBlank() }
                    ?: AddressBookManager.nameFor(convo.contact.id)
                    ?: convo.contact.knsName?.takeIf { it.isNotBlank() && com.kachat.app.services.KnsService.SHOWS_DOMAIN_NAMES_AS_IDENTITY }
                    ?: KaspaAddress.shortDisplay(convo.contact.id)
                ShortcutEntry(
                    owner = owner,
                    contactId = convo.contact.id,
                    label = label,
                    knsAvatarUrl = convo.contact.knsAvatarUrl?.takeIf { it.isNotBlank() }
                )
            }
            .toList()
    }

    private suspend fun publish(entries: List<ShortcutEntry>) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).let {
            if (it > 0) it else MAX_SHORTCUTS
        }
        val shortcuts = entries.take(max).mapIndexed { index, entry ->
            // Tapping the shortcut itself (e.g. launcher long-press menu) opens the chat via the
            // same extra a message notification uses — MainActivity already routes it.
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra(NotificationHelper.EXTRA_CONTACT_ID, entry.contactId)
            }
            ShortcutInfoCompat.Builder(context, entry.id)
                .setShortLabel(entry.label)
                .setLongLabel(entry.label)
                .setIcon(IconCompat.createWithBitmap(avatarBitmap(entry)))
                .setIntent(intent)
                .setLongLived(true)
                .setRank(index)
                .setCategories(setOf(SHARE_TARGET_CATEGORY))
                .setPerson(Person.Builder().setKey(entry.contactId).setName(entry.label).build())
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    /**
     * KNS avatar, else initials — the non-Compose mirror of `ContactAvatar`'s chain. The image
     * goes through the app's shared Coil loader, so it hits the same memory/disk caches the in-app avatars already warmed, and decoding happens
     * on Coil's own dispatcher (this whole method runs off the main thread from [refresh]'s scope).
     */
    private suspend fun avatarBitmap(entry: ShortcutEntry): Bitmap {
        for (candidate in listOfNotNull(entry.knsAvatarUrl)) {
            val loaded = try {
                val request = ImageRequest.Builder(context)
                    .data(candidate)
                    .size(AVATAR_SIZE)
                    .allowHardware(false) // must be a software Bitmap to hand to IconCompat
                    .build()
                (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap(AVATAR_SIZE, AVATAR_SIZE)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load share-target avatar", e)
                null
            }
            if (loaded != null) return circleCrop(loaded)
        }
        return initialsBitmap(entry.label)
    }

    /** Launcher share targets are shown as circles on most OEMs, but not all — crop so it's consistent. */
    private fun circleCrop(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(AVATAR_SIZE, AVATAR_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(AVATAR_SIZE / 2f, AVATAR_SIZE / 2f, AVATAR_SIZE / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, null, Rect(0, 0, AVATAR_SIZE, AVATAR_SIZE), paint)
        return output
    }

    /** Circular Kaspa-teal avatar with the contact's initials — the last resort of [avatarBitmap]. */
    private fun initialsBitmap(label: String): Bitmap {
        val size = AVATAR_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KASPA_TEAL }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint)
        val initials = label
            .split(" ", ".", ":")
            .filter { it.isNotBlank() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
            .ifEmpty { "?" }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = if (initials.length > 1) size * 0.38f else size * 0.45f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val textY = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(initials, size / 2f, textY, textPaint)
        return bitmap
    }

    /** A share shortcut's account and chat, read back from its id. */
    data class ShortcutTarget(
        /** The account the chat belongs to; null for a shortcut published before ids named it. */
        val owner: String?,
        val contactId: String,
    )

    companion object {
        /** Between the account and the chat in a shortcut id. Never part of a Kaspa address. */
        private const val ID_SEPARATOR = '|'

        /** A share shortcut's id: `"<owner>|<contactId>"`, the chat filed under its account. */
        fun shortcutId(owner: String, contactId: String): String = "$owner$ID_SEPARATOR$contactId"

        /** Reads [shortcutId]'s form, and the bare contact address older builds published (with no
         *  owner). Null for an empty id. */
        fun parseShortcutId(id: String?): ShortcutTarget? {
            val raw = id?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val cut = raw.indexOf(ID_SEPARATOR)
            if (cut < 0) return ShortcutTarget(owner = null, contactId = raw)
            val owner = raw.substring(0, cut).trim()
            val contactId = raw.substring(cut + 1).trim()
            if (contactId.isEmpty()) return null
            return ShortcutTarget(owner = owner.takeIf { it.isNotEmpty() }, contactId = contactId)
        }

        private const val TAG = "ShareShortcuts"
        const val SHARE_TARGET_CATEGORY = "com.kachat.app.category.SHARE_TARGET"
        private const val MAX_SHORTCUTS = 8
        private const val KASPA_TEAL = 0xFF49EACB.toInt()
        private const val AVATAR_SIZE = 256
    }
}
