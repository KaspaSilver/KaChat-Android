package com.kachat.app.services

import android.content.Context
import android.util.Log
import com.kachat.app.repository.ChatRepository
import com.kachat.app.services.kachatnames.KachatNamesRegistry
import com.kachat.app.services.kachatnames.KachatNamesService
import com.kachat.app.services.kachatnames.KachatSocialImageResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * While the app is open: everyone's profile looked up again once an hour, so a new name, avatar,
 * banner or bio shows without anyone asking for it (iOS d1844582, `ContactsManager
 * .startProfileRescans`). Started and stopped with the app's foreground (KaChatApplication).
 */
@Singleton
class ContactProfileRescanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletManager: WalletManager,
    private val chatRepository: dagger.Lazy<ChatRepository>,
    private val addressBook: dagger.Lazy<AddressBookManager>,
    private val registry: dagger.Lazy<KachatNamesRegistry>,
    private val socialImages: dagger.Lazy<KachatSocialImageResolver>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("kachat_profile_rescan", Context.MODE_PRIVATE)
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch {
            // Launch and foregrounding have enough to do first.
            delay(START_DELAY_MS)
            while (isActive) {
                val wait = millisUntilRescan()
                if (wait > 0) {
                    delay(wait)
                    continue
                }
                try {
                    rescan()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Profile rescan failed", e)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private fun activeWallet(): String? =
        if (walletManager.isLoggedOut) null else walletManager.getActiveAccount()?.address

    private fun millisUntilRescan(): Long {
        val wallet = activeWallet() ?: return NO_ACCOUNT_RETRY_MS
        return prefs.getLong(wallet, 0L) + RESCAN_INTERVAL_MS - System.currentTimeMillis()
    }

    private suspend fun rescan() {
        val wallet = activeWallet() ?: return
        // Stamped first, so one that's cut off waits for its turn rather than restarting at once.
        prefs.edit().putLong(wallet, System.currentTimeMillis()).apply()
        val addresses = chatRepository.get().getContacts().first().map { it.id }.toSet() +
            addressBook.get().entries.value.map { it.address }
        for (address in addresses) {
            if (activeWallet() != wallet) return
            rescanProfile(address)
            // One at a time, with room between them: this runs alongside everything else.
            delay(BETWEEN_PROFILES_MS)
        }
    }

    /**
     * The on-chain profile (which links it uses) asked for again, each link's avatar, banner and
     * bio looked up again, and both pictures downloaded again whatever the caches hold - so a
     * change shows everywhere this person appears. What User Info > Rescan Profile does. iOS's
     * other branch, a .kas profile, is for a build with .kachat names off, which Android isn't.
     */
    private suspend fun rescanProfile(address: String) {
        if (!KachatNamesService.isEnabled) return
        val links = registry.get().refreshIdentity(address)?.profile ?: return
        val answers = socialImages.get().rescan(listOf(links.avatar, links.banner, links.bio))
        val pictures = listOfNotNull(
            links.avatar?.let { answers[it]?.avatar },
            links.banner?.let { answers[it]?.banner },
        )
        com.kachat.app.ui.screens.ProfileImageRefetch.refetch(context, pictures)
    }

    private companion object {
        const val TAG = "ContactProfileRescan"
        const val START_DELAY_MS = 30_000L
        const val RESCAN_INTERVAL_MS = 60L * 60 * 1000
        const val NO_ACCOUNT_RETRY_MS = 60_000L
        const val BETWEEN_PROFILES_MS = 300L
    }
}
