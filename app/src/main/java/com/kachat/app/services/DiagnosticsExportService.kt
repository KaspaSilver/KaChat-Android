package com.kachat.app.services

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import com.google.gson.GsonBuilder
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.ChatRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android counterpart to iOS's "Export Diagnostics Archive" (`SettingsView.exportDiagnosticsArchive`)
 * — a zip with a `diagnostics.json` snapshot (app/device info, non-secret settings, local message
 * store counts, node pool state) plus `app.log` (this process's own recent logcat output). Deliberately
 * excludes anything sensitive: no private key/mnemonic material, no decrypted message content, only
 * the active wallet's public address and connection/settings metadata.
 *
 * Android has no equivalent to iOS's in-process OSLogStore, so `app.log` is captured via `logcat`
 * filtered to this process's own pid — apps can only read their own UID's log entries this way since
 * Android 4.1, so this needs no extra permission.
 */
@Singleton
class DiagnosticsExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val walletManager: WalletManager,
    private val settingsRepository: AppSettingsRepository,
    private val nodePoolManager: NodePoolManager,
    private val pushState: PushState
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    data class DiagnosticsArchive(
        val generatedAt: String,
        val app: AppInfo,
        val device: DeviceInfo,
        val settings: Map<String, String?>,
        val messageStore: MessageStoreDiagnostics,
        val nodePool: NodePoolSummary
    ) {
        data class AppInfo(val packageName: String, val versionName: String?, val versionCode: Long)
        data class DeviceInfo(val manufacturer: String, val model: String, val androidRelease: String, val sdkInt: Int)
        data class MessageStoreDiagnostics(val contactCount: Int, val totalMessages: Int, val outgoingCount: Int, val incomingCount: Int, val pendingCount: Int)
        data class NodePoolSummary(val counts: Map<String, Int>, val totalRecords: Int, val activeRecords: Int)
        data class NodeRecordSummary(val ip: String, val type: String, val status: String, val latency: String, val daaScore: String)
    }

    /** Builds the diagnostics zip in app-private cache and returns a content:// URI ready for a share sheet. */
    suspend fun exportDiagnostics(): Uri {
        val exportDir = File(context.cacheDir, "diagnostics_exports").apply { mkdirs() }
        val fileTimestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-")
        val zipFile = File(exportDir, "kachat-diagnostics-$fileTimestamp.zip")

        val diagnosticsJson = gson.toJson(buildDiagnosticsArchive())
        val logs = collectAppLogs()

        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("diagnostics.json"))
            zip.write(diagnosticsJson.toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("app.log"))
            zip.write(logs.toByteArray())
            zip.closeEntry()
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
    }

    private suspend fun buildDiagnosticsArchive(): DiagnosticsArchive {
        val packageInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode ?: -1L
        } else {
            @Suppress("DEPRECATION") (packageInfo?.versionCode?.toLong() ?: -1L)
        }

        // Exporting must also work from the accounts screen with NO active account (the App
        // Settings gear there includes Diagnostics) - getAllMessages() throws in that state, so
        // fall back to an empty store summary instead of failing the whole archive.
        val messages = runCatching { chatRepository.getAllMessages() }.getOrDefault(emptyList())
        val messageStore = DiagnosticsArchive.MessageStoreDiagnostics(
            contactCount = chatRepository.getContacts().first().size,
            totalMessages = messages.size,
            outgoingCount = messages.count { it.direction == "sent" },
            incomingCount = messages.count { it.direction == "received" },
            pendingCount = messages.count { it.deliveryStatus == "pending" }
        )

        val allNodes = nodePoolManager.allNodes.value
        val nodePool = DiagnosticsArchive.NodePoolSummary(
            counts = allNodes.groupingBy { it.status }.eachCount(),
            totalRecords = allNodes.size,
            activeRecords = nodePoolManager.activeNodes.value.size
        )

        val settings = mapOf(
            "network" to settingsRepository.network.first(),
            "indexerUrl" to settingsRepository.indexerUrl.first(),
            "knsApiUrl" to settingsRepository.knsApiUrl.first(),
            "kaspaRestUrl" to settingsRepository.kaspaRestUrl.first(),
            // Same no-active-account tolerance as above - "none" rather than a thrown export.
            "activeAddress" to (settingsRepository.activeAddress.first()
                ?: runCatching { walletManager.getAddress() }.getOrDefault("none")),
            "notificationsEnabled" to settingsRepository.notificationsEnabled.first().toString(),
            "syncSystemContactsEnabled" to settingsRepository.syncSystemContactsEnabled.first().toString(),
            "autoCreateSystemContactsEnabled" to settingsRepository.autoCreateSystemContactsEnabled.first().toString(),
            "googleBackupEnabled" to settingsRepository.googleBackupEnabled.first().toString(),
            "backupRetention" to settingsRepository.backupRetention.first().name,
            "broadcastPopularEnabled" to settingsRepository.broadcastPopularEnabled.first().toString(),
            "broadcastShowKnsAvatars" to settingsRepository.broadcastShowKnsAvatars.first().toString(),
            // Push diagnostics — background DM/broadcast/KaPosts delivery is push-only (no
            // polling fallback), so this is the state to check when notifications don't arrive.
            "pushActive" to pushState.isActive.toString(),
            "pushLastAttemptAtMs" to (pushState.diagnostics.value.lastAttemptAtMs?.toString() ?: "never"),
            "pushLastAction" to (pushState.diagnostics.value.lastAction ?: "none"),
            "pushLastAttemptSucceeded" to (pushState.diagnostics.value.lastAttemptSucceeded?.toString() ?: "n/a"),
            "pushLastError" to (pushState.diagnostics.value.lastError ?: "none"),
            "pushFcmTokenPresent" to pushState.diagnostics.value.fcmTokenPresent.toString()
        )

        return DiagnosticsArchive(
            generatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            app = DiagnosticsArchive.AppInfo(
                packageName = context.packageName,
                versionName = packageInfo?.versionName,
                versionCode = versionCode
            ),
            device = DiagnosticsArchive.DeviceInfo(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                androidRelease = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT
            ),
            settings = settings,
            messageStore = messageStore,
            nodePool = nodePool
        )
    }

    /** This process's own recent logcat output — capped since a long-running session's buffer can be large. */
    private fun collectAppLogs(maxLines: Int = 5000): String {
        return try {
            val pid = Process.myPid()
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "--pid=$pid"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            val lines = output.lines()
            if (lines.size > maxLines) lines.takeLast(maxLines).joinToString("\n") else output
        } catch (e: Exception) {
            "Failed to collect logs: ${e.message}"
        }
    }
}
