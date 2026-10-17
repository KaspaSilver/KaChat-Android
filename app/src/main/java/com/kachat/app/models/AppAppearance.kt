package com.kachat.app.models

/**
 * App-wide appearance override, iOS's `AppAppearance` (Models.swift): [SYSTEM] (the default)
 * follows the device's own light/dark setting, [LIGHT] and [DARK] force one appearance whatever
 * the device is set to. Stored as this enum's `name` (see AppSettingsRepository.KEY_APPEARANCE).
 */
enum class AppAppearance {
    SYSTEM,
    LIGHT,
    DARK;

    /** Whether the app draws dark, given what the device is set to. */
    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromName(name: String?): AppAppearance? = entries.firstOrNull { it.name == name }
    }
}
