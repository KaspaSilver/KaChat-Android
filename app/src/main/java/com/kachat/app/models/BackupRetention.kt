package com.kachat.app.models

/**
 * How long chat history is kept on this device — mirrors iOS's
 * `MessageRetention` enum (`Models.swift:353-368`) in spirit, but scoped to this app's own
 * backup feature rather than always-on independent pruning. `days = null` means never prune.
 */
enum class BackupRetention(val days: Int?) {
    FOREVER(null),
    DAYS_30(30),
    DAYS_90(90);

    /** The oldest timestamp (epoch millis) still allowed to survive pruning, or null for FOREVER (never prune). Messages strictly older than this get deleted. */
    fun cutoffMillis(nowMillis: Long): Long? {
        val d = days ?: return null
        return nowMillis - d.toLong() * MILLIS_PER_DAY
    }

    companion object {
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        fun fromName(name: String?): BackupRetention =
            entries.firstOrNull { it.name == name } ?: FOREVER
    }
}
