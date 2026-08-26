package com.kachat.app.services

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic fallback for the automatic Google Drive chat-history sync (see
 * [GoogleDriveSyncService]) — every 6 hours, network connected + battery not low. The debounced
 * in-process upload is the primary path; this worker only exists so an upload the process died
 * before finishing (persisted dirty flag still set) eventually lands. When nothing is owed it
 * does no network work at all, so an idle device costs zero Drive quota.
 *
 * Always reports success: a failed attempt re-marks the dirty flag inside the service, and the
 * next 6-hour cycle (or the next message's debounce) retries — WorkManager's own backoff/retry
 * machinery would only duplicate that.
 */
@HiltWorker
class DriveAutoSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val googleDriveSyncService: GoogleDriveSyncService
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        googleDriveSyncService.periodicUploadIfNeeded()
        return Result.success()
    }
}
