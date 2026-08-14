package com.kachat.app.services

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.kachat.app.MainActivity
import com.kachat.app.models.Conversation
import com.kachat.app.util.KaspaAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
    /** Contact address picked on the share sheet (direct-share shortcut id), or null for "into KaChat, pick a chat". */
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
 */
@Singleton
class ShareShortcutsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var lastPublished: List<Pair<String, String>>? = null

    /** [promoteContactId] pins that conversation to the front — used at send time, before the
     *  conversations flow has reordered around the still-pending insert. */
    fun refresh(conversations: List<Conversation>, promoteContactId: String? = null) {
        val top = conversations
            .asSequence()
            .filter { it.contact.conversationStatus != "rejected" }
            .sortedByDescending {
                if (it.contact.id == promoteContactId) Long.MAX_VALUE
                else it.lastMessage?.blockTimestamp ?: 0L
            }
            .take(MAX_SHORTCUTS)
            .map { convo ->
                val label = convo.contact.alias?.takeIf { it.isNotBlank() }
                    ?: convo.contact.knsName?.takeIf { it.isNotBlank() }
                    ?: KaspaAddress.shortDisplay(convo.contact.id)
                convo.contact.id to label
            }
            .toList()
        if (top == lastPublished) return
        lastPublished = top
        scope.launch {
            try {
                publish(top)
            } catch (e: Exception) {
                // Never let launcher/shortcut quirks (rate limiting, OEM bugs) affect messaging.
                Log.w(TAG, "Failed to publish share shortcuts", e)
            }
        }
    }

    private fun publish(entries: List<Pair<String, String>>) {
        val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).let {
            if (it > 0) it else MAX_SHORTCUTS
        }
        val shortcuts = entries.take(max).mapIndexed { index, (contactId, label) ->
            // Tapping the shortcut itself (e.g. launcher long-press menu) opens the chat via the
            // same extra a message notification uses — MainActivity already routes it.
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra(NotificationHelper.EXTRA_CONTACT_ID, contactId)
            }
            ShortcutInfoCompat.Builder(context, contactId)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(IconCompat.createWithBitmap(initialsBitmap(label)))
                .setIntent(intent)
                .setLongLived(true)
                .setRank(index)
                .setCategories(setOf(SHARE_TARGET_CATEGORY))
                .setPerson(Person.Builder().setKey(contactId).setName(label).build())
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    /** Circular Kaspa-teal avatar with the contact's initials — matches the in-app avatar look. */
    private fun initialsBitmap(label: String): Bitmap {
        val size = 256
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

    companion object {
        private const val TAG = "ShareShortcuts"
        const val SHARE_TARGET_CATEGORY = "com.kachat.app.category.SHARE_TARGET"
        private const val MAX_SHORTCUTS = 8
        private const val KASPA_TEAL = 0xFF49EACB.toInt()
    }
}
