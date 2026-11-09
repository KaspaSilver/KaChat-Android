package com.kachat.app.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineExceptionHandler
import java.util.concurrent.CopyOnWriteArraySet

/**
 * What keeps a failing side feature from closing the whole app at launch.
 *
 * A Play build 5.2 stopped opening on a GrapheneOS phone with no crash log: 5.2 builds more
 * services at startup (the Address Book, the portfolio's price backfill, the .kachat caches), and
 * on a hardened ROM the Android Keystore behind EncryptedSharedPreferences can throw
 * (KeyStoreException, AEADBadTagException, any GeneralSecurityException). Thrown during startup,
 * that ends the process before any screen. These helpers turn such a failure in a non-essential
 * feature into a log line and a feature that is missing for this session.
 *
 * Nothing here changes what happens when nothing throws.
 */
object StartupSafety {

    /**
     * Runs one non-essential startup step: an exception is logged and the app carries on without
     * it. Cancellation and the VM's own errors (out of memory, stack overflow) still propagate.
     */
    inline fun step(tag: String, what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            Log.w(tag, "Startup step failed, continuing without it: $what", e)
        }
    }

    /**
     * For the coroutine scopes of the side features built at startup: an exception escaping one
     * of their background jobs is logged instead of reaching the thread's uncaught-exception
     * handler (which ends the process).
     */
    fun logOnlyHandler(tag: String): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, e -> Log.w(tag, "Background task failed", e) }

    /**
     * The EncryptedSharedPreferences file [fileName], made exactly as the stores always made it
     * (the default AES256-GCM master key, AES256-SIV keys, AES256-GCM values). Only for stores that
     * are not wallet or seed storage: when the Keystore refuses (GeneralSecurityException,
     * KeyStoreException, AEADBadTagException, IOException, or a RuntimeException such as
     * ProviderException), the feature gets an empty in-memory store for this session instead.
     *
     * The file on disk is never touched in that case - not deleted, not reset, not written - so
     * nothing the user saved is lost, and the next launch tries the real store again.
     */
    fun encryptedPrefsOrInMemory(context: Context, fileName: String, tag: String): SharedPreferences =
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // GeneralSecurityException (KeyStoreException, AEADBadTagException), IOException and
            // RuntimeException are all Exceptions.
            Log.e(tag, "Encrypted store '$fileName' could not be opened; using memory for this session (the file is left as it is)", e)
            InMemorySharedPreferences()
        }
}

/**
 * The stores everything else depends on. Unlike a side feature's, [accountStoresOpen] failing means
 * there is no app to open: the wallet and group stores throw as they're built, before any screen.
 */
object SecureStores {
    private val ACCOUNT_STORES = listOf("kachat_secure_prefs", "kachat_group_secure_prefs")
    private val ALL_STORES = ACCOUNT_STORES + listOf("nextcloud_secure_prefs", "kachat_names_secure_prefs")
    private val accountStoresCheck = TrueOnceCache()

    /**
     * Whether the account stores open. Asked by the activity and by every background entry point
     * (pushes, workers) before anything that builds the wallet; a yes is kept for the process, so
     * a push doesn't reopen the stores, while a no is asked again, so a Keystore that recovers is
     * noticed without a restart.
     */
    fun accountStoresOpen(context: Context): Boolean = accountStoresCheck.get { openAccountStores(context) }

    private fun openAccountStores(context: Context): Boolean = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        for (name in ACCOUNT_STORES) {
            EncryptedSharedPreferences.create(
                context,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        true
    } catch (e: Exception) {
        Log.e("SecureStores", "The account stores could not be opened", e)
        false
    }

    /** Only on the user's say-so, since a Keystore fault can pass: removes the stores and their key,
     *  so the next launch starts clean and accounts come back from their words. */
    fun startOver(context: Context) {
        for (name in ALL_STORES) context.deleteSharedPreferences(name)
        runCatching {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }.onFailure { Log.w("SecureStores", "Could not remove the master key", it) }
    }
}

/** A yes/no check whose yes is kept: once [get] has seen true it returns true without asking again;
 *  false is never kept. */
internal class TrueOnceCache {
    @Volatile private var knownTrue = false

    fun get(check: () -> Boolean): Boolean {
        if (knownTrue) return true
        return check().also { if (it) knownTrue = true }
    }
}

/**
 * A SharedPreferences that lives only in memory - the stand-in [StartupSafety.encryptedPrefsOrInMemory]
 * hands a feature whose encrypted store could not be opened. Reads see what was written this
 * session; nothing reaches disk.
 */
class InMemorySharedPreferences : SharedPreferences {
    private val values = HashMap<String?, Any?>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = synchronized(values) {
        HashMap<String, Any?>().also { copy -> values.forEach { (k, v) -> if (k != null) copy[k] = v } }
    }

    override fun getString(key: String?, defValue: String?): String? =
        synchronized(values) { values[key] as? String ?: defValue }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(values) { (values[key] as? Set<String>)?.toMutableSet() ?: defValues }

    override fun getInt(key: String?, defValue: Int): Int =
        synchronized(values) { values[key] as? Int ?: defValue }

    override fun getLong(key: String?, defValue: Long): Long =
        synchronized(values) { values[key] as? Long ?: defValue }

    override fun getFloat(key: String?, defValue: Float): Float =
        synchronized(values) { values[key] as? Float ?: defValue }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        synchronized(values) { values[key] as? Boolean ?: defValue }

    override fun contains(key: String?): Boolean = synchronized(values) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = MemoryEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners.remove(listener)
    }

    private inner class MemoryEditor : SharedPreferences.Editor {
        private val puts = HashMap<String?, Any?>()
        private val removes = HashSet<String?>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            put(key, values?.toSet())
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            synchronized(this) {
                // A null value means remove, as in the platform's own implementation.
                if (value == null) {
                    removes.add(key)
                    puts.remove(key)
                } else {
                    puts[key] = value
                    removes.remove(key)
                }
            }
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            synchronized(this) {
                removes.add(key)
                puts.remove(key)
            }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            synchronized(this) { clearAll = true }
            return this
        }

        override fun commit(): Boolean {
            val changed = ArrayList<String?>()
            synchronized(this) {
                synchronized(values) {
                    if (clearAll) {
                        changed.addAll(values.keys)
                        values.clear()
                    }
                    for (key in removes) {
                        if (values.containsKey(key)) {
                            values.remove(key)
                            changed.add(key)
                        }
                    }
                    for ((key, value) in puts) {
                        values[key] = value
                        changed.add(key)
                    }
                }
                puts.clear()
                removes.clear()
                clearAll = false
            }
            for (key in changed.distinct()) {
                for (listener in listeners) listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, key)
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
