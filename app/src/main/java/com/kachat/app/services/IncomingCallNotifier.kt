package com.kachat.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.kachat.app.MainActivity
import com.kachat.app.R
import dagger.hilt.InstallIn
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ringing phone, as the system sees it: while a call is coming in KaChat posts a call
 * notification with a full-screen intent, so the call screen takes over a locked or busy phone
 * and Answer / Decline are there without opening the app first. This is Android's answer to
 * iOS's CallKit, and the reason a closed app can be rung at all - the push wakes the process,
 * [CallService] starts ringing, and this is what the user actually sees.
 *
 * Deliberately silent: the channel has no sound and no vibration because [CallService] plays the
 * ringtone and drives the vibrator itself, exactly as it does when the app is already open. One
 * ring, whatever woke the phone.
 */
@Singleton
class IncomingCallNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rings when someone calls you on KaChat"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    /** The call screen, opened over the lock screen. Also what the Answer button uses, with the
     *  call id attached so [MainActivity] can pick up without another tap. */
    private fun openCallIntent(callId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (callId != null) putExtra(EXTRA_ANSWER_CALL_ID, callId)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun declineIntent(callId: String): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java).apply {
            action = ACTION_DECLINE
            putExtra(EXTRA_CALL_ID, callId)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_DECLINE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Rings: posts the call notification for [callerName] and asks the system to show the call
     *  screen full-screen. Safe to call twice for the same call - it replaces itself. */
    fun showIncoming(callId: String, callerName: String, video: Boolean) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val answer = openCallIntent(callId, REQUEST_ANSWER)
        val decline = declineIntent(callId)
        val caller = Person.Builder().setName(callerName).setImportant(true).build()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_kachat_logo)
            .setContentTitle(callerName)
            .setContentText(if (video) "Incoming KaChat video call" else "Incoming KaChat call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setContentIntent(openCallIntent(null, REQUEST_OPEN))
            // The whole point: a locked or busy phone shows the call screen rather than a banner.
            // No call id on this one - the system opens it by itself, and that is the phone
            // ringing in front of you, not you picking up.
            .setFullScreenIntent(openCallIntent(null, REQUEST_FULL_SCREEN), true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
            .onFailure { Log.w(TAG, "Could not post the incoming call notification", it) }
    }

    /** Answered, declined, missed or gone: take the ringing notification down. */
    fun clear() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    companion object {
        private const val TAG = "IncomingCallNotifier"
        const val CHANNEL_ID = "kachat_incoming_call"
        /** Only one call at a time, so one id. */
        const val NOTIFICATION_ID = 0xCA11
        const val ACTION_DECLINE = "com.kachat.app.action.CALL_DECLINE"
        const val EXTRA_CALL_ID = "kachat_call_id"
        /** [MainActivity] reads this to answer the call the notification was for. */
        const val EXTRA_ANSWER_CALL_ID = "kachat_answer_call_id"
        private const val REQUEST_OPEN = 8100
        private const val REQUEST_ANSWER = 8101
        private const val REQUEST_DECLINE = 8102
        private const val REQUEST_FULL_SCREEN = 8103
    }
}

/**
 * Declining from the notification, which needs neither the app on screen nor any permission -
 * so it is handled here rather than by opening the call screen. Answering does open the app:
 * picking up needs the microphone, and that permission can only be asked for by an Activity.
 */
class CallActionReceiver : BroadcastReceiver() {
    /** The call service is reached through an entry point rather than field injection: a
     *  receiver has no lifecycle for Hilt to inject into before [onReceive] runs. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun callService(): CallService
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IncomingCallNotifier.ACTION_DECLINE) return
        val callService = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).callService()
        }.getOrNull() ?: return
        val callId = intent.getStringExtra(IncomingCallNotifier.EXTRA_CALL_ID)
        if (callId != null && callService.session.value?.id != callId) return
        callService.declineIncoming()
    }
}
