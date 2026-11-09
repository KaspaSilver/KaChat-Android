package com.kachat.app.models

/**
 * Per-contact override for the "Incoming Notifications" picker in Chat Info — null means
 * "Default", i.e. follow Settings > Notifications' global sound/vibration, so this has no baked-in
 * default case to fall back to.
 */
enum class ContactNotificationMode(val displayName: String) {
    OFF("Off"),
    NO_SOUND("No Sound"),
    SOUND("Sound");

    companion object {
        fun fromName(name: String?): ContactNotificationMode? =
            entries.firstOrNull { it.name == name }
    }
}
