package com.kachat.app

import android.app.Application
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
import com.kachat.app.services.KaPostsNotificationPoller
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

    // Foreground-only KaPosts pings (started/stopped by the process lifecycle observer below) —
    // while the app is closed, the push service is the only KaPosts notification source.
    @Inject
    lateinit var kaPostsNotificationPoller: KaPostsNotificationPoller

    // Address-activity notifications (external receipts on spending/cold addresses): per-wallet
    // baseline + diff engine, foreground poll + on-foreground catch-up. See its class doc.
    @Inject
    lateinit var addressActivityNotifier: com.kachat.app.services.AddressActivityNotifier

    // For the Nextcloud automatic chat-history backup below — the archive json comes from the
    // same export service ChatViewModel's manual backup uses, so both paths write byte-identical
    // backups.
    @Inject
    lateinit var nextcloudService: com.kachat.app.services.NextcloudService

    @Inject
    lateinit var chatHistoryExportImportService: com.kachat.app.services.ChatHistoryExportImportService

    // Same lazy-singleton reasoning as the scanners above: GoogleDriveSyncService's init block
    // observes the active wallet (automatic Drive restore on wallet activation) and the Drive
    // sign-in + auto-sync toggles (schedules/cancels the 6h WorkManager fallback). Field-
    // injecting it here guarantees those observers run from app startup, not only after the
    // Google Drive storage screen happens to be opened.
    @Inject
    lateinit var googleDriveSyncService: com.kachat.app.services.GoogleDriveSyncService

    // For mirroring the persisted "Verbose API Logging" toggle into ApiLogging.verbose (a plain
    // volatile flag the OkHttp logging interceptor reads per request) — see onCreate below.
    @Inject
    lateinit var appSettingsRepository: com.kachat.app.repository.AppSettingsRepository

    @Inject
    lateinit var hiltWorkerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(hiltWorkerFactory).build()

    override fun onCreate() {
        super.onCreate()

        // Background catch-up for GROUP chat only (see SyncWorker's doc comment): groups have no
        // remote push (the push registration is the LegacyV1 shape with no watched_group_ids), so
        // this 15-minute WorkManager cadence is their sole closed-app notification path. All
        // push-covered surfaces (1:1 DMs, broadcasts, KaPosts) are delivered by FCM when the app
        // is backgrounded or killed. KEEP means re-registering on every app launch doesn't stack
        // duplicate periodic jobs.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        )

        // Keep ApiLogging.verbose (read by the OkHttp interceptors in AppModule on every request)
        // in sync with the persisted Verbose API Logging toggle for the process's whole lifetime.
        // A volatile flag instead of collecting the Flow inside the interceptor because that code
        // runs on OkHttp threads for every request of the 2s sync loop.
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            appSettingsRepository.verboseApiLogging.collect { com.kachat.app.util.ApiLogging.verbose = it }
        }

        // Foreground/background transitions. There is deliberately NO mechanism here keeping the
        // process alive while backgrounded: FCM push (see PushRegistrationManager) is the only
        // delivery path for 1:1 DMs, broadcasts, and KaPosts once the app leaves the foreground,
        // so a push-delivery problem surfaces as missing notifications instead of being silently
        // masked by a background poller. The in-process pollers (ChatRepository's loop, the
        // broadcast/group scanners) simply freeze with the process and resume on foreground.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // In-app KaPosts pings are a foreground concern only — the push service covers
                // KaPosts while backgrounded/closed.
                kaPostsNotificationPoller.stop()
                addressActivityNotifier.onAppBackground()
                // Backgrounding is the natural "done chatting" moment — run the Nextcloud
                // automatic backup then, throttled to at most once per hour. autoBackupIfDue
                // no-ops unless the toggle is on and an account is connected, and swallows its
                // own failures (the next trigger retries). Mirrors iOS's on-background backup.
                owner.lifecycleScope.launch(Dispatchers.IO) {
                    nextcloudService.autoBackupIfDue { remote -> chatHistoryExportImportService.buildBackupJson(remote) }
                }
            }

            override fun onStart(owner: LifecycleOwner) {
                // KaPosts pings while the app is actually open (60s poll) — iOS parity.
                kaPostsNotificationPoller.start()
                // Immediate catch-up diff for address activity (external receipts while the app
                // was closed, first-run silent baseline seeding), then its foreground poll loop.
                addressActivityNotifier.onAppForeground()
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
                    ) { remote -> chatHistoryExportImportService.buildBackupJson(remote) }
                }
            }
        })
    }
}
