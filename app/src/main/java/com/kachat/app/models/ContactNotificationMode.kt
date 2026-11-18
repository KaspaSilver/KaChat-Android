package com.kachat.app.models

import androidx.annotation.StringRes
import com.kachat.app.R

/**
 * Per-contact override for the "Incoming Notifications" picker in Chat Info — null means
 * "Default", i.e. follow Settings > Notifications' global sound/vibration, so this has no baked-in
 * default case to fall back to. [labelRes] is the picker's name for it (iOS
 * `ContactNotificationMode.displayName`, localized).
 */
enum class ContactNotificationMode(@StringRes val labelRes: Int) {
    OFF(R.string.notification_mode_off),
    NO_SOUND(R.string.notification_mode_no_sound),
    SOUND(R.string.notification_mode_sound);

    companion object {
        fun fromName(name: String?): ContactNotificationMode? =
            entries.firstOrNull { it.name == name }

        /** What "Default" stands for right now (iOS ChatInfoView's "Default (Sound)"): the
         *  master switch off is Off, otherwise Settings > Notifications > Chats' Play Sound. */
        fun globalDefault(notificationsEnabled: Boolean, soundEnabled: Boolean): ContactNotificationMode = when {
            !notificationsEnabled -> OFF
            soundEnabled -> SOUND
            else -> NO_SOUND
        }
    }
}
