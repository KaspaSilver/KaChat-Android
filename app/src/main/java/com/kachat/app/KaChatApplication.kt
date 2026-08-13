package com.kachat.app

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kachat.app.repository.GroupRepository
import com.kachat.app.services.BroadcastScanningService
import com.kachat.app.services.GroupScanningService
import com.kachat.app.services.SyncForegroundService
import com.kachat.app.services.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Application class — required by Hilt for dependency injection.
 * All singleton services are initialized here via Hilt modules in the `di` package.
 */
@HiltAndroidApp
class KaChatApplication : Application(), Configuration.Provider {

    // @Singleton instances are otherwise only created lazily the first time something actually
    // requests them — field-injecting this here forces it to exist from app startup, so its
    // self-observing "start/stop scanning based on the setting" logic (see its init block) runs
    // for the app's whole lifetime rather than only after the user happens to open a broadcast
    // screen.
    @Inject
    lateinit var broadcastScanningService: BroadcastScanningService

    // Same reasoning as broadcastScanningService above - forces GroupScanningService to exist
    // from app startup so its group-count/pending-invite observers (see its init block) run for
    // the app's whole lifetime, not just after a group chat screen happens to be opened.
    @Inject
    lateinit var groupScanningService: GroupScanningService

    // Same lazy-singleton reasoning again: PushRegistrationManager's init block observes the
    // active account / contact set / broadcast bells / hidden senders / notifications setting and
    // re-registers (or unregisters) the FCM token with the push service whenever any of it
    // changes. Field-injecting it here guarantees those observers run for the app's whole
    // lifetime, not just after the first screen that happens to request it.
    @Inject
    lateinit var pushRegistrationManager: com.kachat.app.services.PushRegistrationManager

    @Inject
    lateinit var nodePoolManager: com.kachat.app.services.NodePoolManager

    @Inject
    lateinit var groupRepository: GroupRepository

    // For the Nextcloud automatic chat-history backup below — the archive json comes from the
    // same export service ChatViewModel's manual backup uses, so both paths write byte-identical
    // backups.
    @Inject
    lateinit var nextcloudService: com.kachat.app.services.NextcloudService

    @Inject
    lateinit var chatHistoryExportImportService: com.kachat.app.services.ChatHistoryExportImportService

    @Inject
    lateinit var hiltWorkerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(hiltWorkerFactory).build()

    override fun onCreate() {
        super.onCreate()

        // Periodic fallback for SyncForegroundService — see SyncWorker's doc comment. Runs
        // independently of the foreground service's own lifecycle, so it still gets a chance
        // to sync roughly every 15 minutes even during the hours the FGS itself can't restart
        // (Android 15+'s dataSync execution-time cap). KEEP means re-registering on every app
        // launch doesn't stack duplicate periodic jobs.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        )

        // Only run the (notification-requiring) foreground sync service while the app
        // is actually backgrounded — while it's in the foreground, ChatRepository's poll
        // loop already runs unthrottled and a persistent notification would just be
        // redundant noise on top of what the user is already looking at.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // Android 15+ caps a dataSync foreground service to ~6 cumulative hours per
                // rolling 24h window — once that's exhausted, every restart attempt throws
                // ForegroundServiceStartNotAllowedException until the window frees back up.
                // That's expected/recoverable (SyncWorker's periodic fallback covers the gap
                // in the meantime), not a crash-worthy condition.
                try {
                    ContextCompat.startForegroundService(
                        this@KaChatApplication,
                        Intent(this@KaChatApplication, SyncForegroundService::class.java)
                    )
                } catch (e: Exception) {
                    android.util.Log.w("KaChatApplication", "Couldn't start SyncForegroundService (will retry via SyncWorker)", e)
                }
                // Backgrounding is the natural "done chatting" moment — run the Nextcloud
                // automatic backup then, throttled to at most once per hour. autoBackupIfDue
                // no-ops unless the toggle is on and an account is connected, and swallows its
                // own failures (the next trigger retries). Mirrors iOS's on-background backup.
                owner.lifecycleScope.launch(Dispatchers.IO) {
                    nextcloudService.autoBackupIfDue { chatHistoryExportImportService.buildArchiveJson() }
                }
            }

            override fun onStart(owner: LifecycleOwner) {
                stopService(Intent(this@KaChatApplication, SyncForegroundService::class.java))
                // A batch of gRPC connections can die silently while backgrounded/asleep (the OS
                // tears down sockets, and each KaspadConnection's own self-reconnect can be
                // suspended along with the rest of the app) - reconnect any that are dead right
                // now instead of waiting for the next 5-30s probe cycle to notice and replace them.
                nodePoolManager.reconnectStaleConnections()
                // Group invites (gctl_root) otherwise only surface via the 15-min SyncWorker
                // periodic job or the live block-scan - unlike 1:1 chat, which has its own
                // always-running poll loop, groups had no on-foreground catch-up at all, so a
                // brand-new invite could sit unseen well past 15 minutes. Mirrors iOS's
                // performCatchUpSync() on app foreground (KaChatApp.swift).
                owner.lifecycleScope.launch(Dispatchers.IO) {
                    // Never let a foreground catch-up take down the app on launch — an uncaught
                    // throw here (e.g. no active account yet on a cold start) would otherwise crash
                    // the whole process. Same non-fatal treatment as the FGS start in onStop above.
                    try {
                        groupRepository.syncGroups()
                    } catch (e: Exception) {
                        android.util.Log.w("KaChatApplication", "Foreground group catch-up sync failed", e)
                    }
                }
                // Nextcloud backup catch-up on launch/foreground: covers users who never
                // background the app cleanly (force-kill, crash, days of disuse). The day-long
                // threshold keeps this from ever competing with the hourly on-background cadence.
                owner.lifecycleScope.launch(Dispatchers.IO) {
                    nextcloudService.autoBackupIfDue(
                        minIntervalMs = com.kachat.app.services.NextcloudService.AUTO_BACKUP_CATCH_UP_INTERVAL_MS
                    ) { chatHistoryExportImportService.buildArchiveJson() }
                }
            }
        })
    }
}
