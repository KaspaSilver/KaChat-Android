package com.kachat.app.services

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kachat.app.repository.GroupRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic background catch-up for GROUP chat only ([GroupRepository.syncGroups]). With push active
 * it stores what arrived and stays quiet: the push is the only banner source
 * (KaChatFirebaseMessagingService). Without push this ~15-minute WorkManager cadence is groups'
 * closed-app delivery path, including the group-invite (`gctl_root`) and group-message
 * notifications syncGroups posts itself. Both `gcomm`/`gctl` payloads are self-stash transactions
 * on a queryable identity address, now indexed (`group-messages/by-blinded-group-id`,
 * `group-control/by-sender`), so they fit this short-lived catch-up shape. Signed out, syncGroups
 * returns at once.
 *
 * 1:1 chat, broadcasts, and KaPosts are deliberately NOT synced here: FCM push (see
 * PushRegistrationManager) is the only background delivery path for those — a background poller
 * would mask push failures. Broadcast messages additionally have no queryable per-cursor history
 * to catch up on, only a live block subscription, which doesn't fit a periodic job anyway.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    // Lazy: it needs the wallet store, so it's built only once the stores are known to open.
    private val groupRepository: dagger.Lazy<GroupRepository>
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Account stores unreadable: the app shows its problem screen when opened; nothing to sync.
        if (!com.kachat.app.util.SecureStores.accountStoresOpen(applicationContext)) return Result.success()
        return try {
            groupRepository.get().syncGroups()
            Result.success()
        } catch (e: Exception) {
            Log.w("SyncWorker", "Periodic group sync failed, will retry next cycle", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "kachat_periodic_sync"
    }
}
