package com.kachat.app.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When photos from each contact start to show (iOS d1844582, `Contact.photosUnlockDate`), per
 * wallet. Kept once known, so trimming old messages can't hide their photos again. See
 * [com.kachat.app.repository.ChatRepository.photosUnlockAt].
 */
@Singleton
class PhotoUnlockStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("kachat_photo_unlock", Context.MODE_PRIVATE)

    private fun key(wallet: String, contact: String) = "${wallet.lowercase()}|${contact.lowercase()}"

    /** The stored date, epoch millis, or null when it isn't known yet. */
    fun unlockAt(wallet: String, contact: String): Long? {
        val key = key(wallet, contact)
        return if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    }

    fun store(wallet: String, contact: String, unlockAt: Long) {
        prefs.edit().putLong(key(wallet, contact), unlockAt).apply()
    }

    fun remove(wallet: String, contact: String) {
        prefs.edit().remove(key(wallet, contact)).apply()
    }

    /** Everything kept for [wallet]: it's going off this device (iOS bfe9f022). */
    fun removeAllData(wallet: String) {
        val prefix = "${wallet.lowercase()}|"
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }
}
