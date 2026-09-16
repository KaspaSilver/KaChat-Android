package com.kachat.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kachat.app.MainActivity
import com.kachat.app.R

/**
 * Keeps a live call's microphone (and camera) usable while the app is in the background - the
 * Android counterpart of iOS's background audio mode. Android 14 cuts off mic and camera access
 * for a backgrounded process unless a foreground service of the matching type is running, so
 * [CallService] starts this when a call begins and stops it when the call ends. The notification
 * it must show returns to the app.
 */
class CallForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "kachat_calls"
        private const val NOTIFICATION_ID = 0x4CA11
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_VIDEO = "video"

        fun start(context: Context, title: String, video: Boolean) {
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_VIDEO, video)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CallForegroundService::class.java)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "KaChat call"
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) ?: false
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "An ongoing call"
                    setSound(null, null)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kachat_logo)
            .setContentTitle(title)
            .setContentText(if (video) "Video call in progress" else "Voice call in progress")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or (if (video) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        } else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
        return START_NOT_STICKY
    }
}
