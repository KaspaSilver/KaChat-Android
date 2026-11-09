package com.kachat.app.services

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic fallback for the automatic Nextcloud contacts sync (see
 * [NextcloudSyncService]) — every 6 hours, network connected + battery not low, its own unique
 * work. The debounced in-process upload is the primary path;
 * this worker only exists so an upload the process died before finishing (persisted dirty flag
 * still set) eventually lands. When nothing is owed it does no network work at all.
 *
 * Always reports success: a failed attempt re-marks the dirty flag inside the service, and the
 * next 6-hour cycle (or the next message's debounce) retries — WorkManager's own backoff/retry
 * machinery would only duplicate that.
 */
@HiltWorker
class NextcloudAutoSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    // Lazy: it needs the wallet store, so it's built only once the stores are known to open.
    private val nextcloudSyncService: dagger.Lazy<NextcloudSyncService>
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Account stores unreadable: the app shows its problem screen when opened; nothing to upload.
        if (!com.kachat.app.util.SecureStores.accountStoresOpen(applicationContext)) return Result.success()
        nextcloudSyncService.get().periodicUploadIfNeeded()
        return Result.success()
    }
}
