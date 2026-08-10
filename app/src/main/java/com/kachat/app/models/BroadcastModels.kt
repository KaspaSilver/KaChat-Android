package com.kachat.app.models

import androidx.room.Entity

/** Per-channel local message retention — how long a broadcast room's cached messages stick around before being pruned. */
object BroadcastRetention {
    val MAX_MILLIS = 3L * 24 * 60 * 60 * 1000L
    val DEFAULT_MILLIS = 3L * 60 * 60 * 1000L

    /** A unit the retention settings dialog lets the user enter an amount in — each capped so amount * millisPerUnit can never exceed MAX_MILLIS. */
    enum class Unit(val label: String, val millisPerUnit: Long) {
        SECONDS("seconds", 1_000L),
        MINUTES("minutes", 60_000L),
        HOURS("hours", 60L * 60 * 1000L),
        DAYS("days", 24L * 60 * 60 * 1000L);

        val maxAmount: Long get() = MAX_MILLIS / millisPerUnit
    }

    /**
     * Splits a stored millis value back into an (amount, unit) pair for pre-filling the retention
     * dialog — picks the largest unit that divides it evenly (so e.g. 3 days shows as "3 days"
     * rather than "72 hours"), falling back to seconds for anything odd (like the 10-second test
     * default above, which isn't a whole number of minutes/hours/days).
     */
    fun toAmountAndUnit(millis: Long): Pair<Long, Unit> {
        for (unit in listOf(Unit.DAYS, Unit.HOURS, Unit.MINUTES)) {
            if (millis % unit.millisPerUnit == 0L && millis / unit.millisPerUnit in 1..unit.maxAmount) {
                return (millis / unit.millisPerUnit) to unit
            }
        }
        return (millis / Unit.SECONDS.millisPerUnit).coerceAtLeast(1L) to Unit.SECONDS
    }
}

/** Curated rooms shown in the Broadcasts "Popular" tab — recommended channels baked into the app, not derived from any usage/activity data. */
object FeaturedBroadcastChannels {
    val NAMES: List<String> = listOf("kaspa", "kachat-bugs")
}

/**
 * A broadcast channel the active account has locally joined — "creating" and "joining" a channel
 * are the same action (there's no ownership/membership protocol, matching Kasia). Scoped per
 * wallet address, like [ContactEntity], since different accounts may want different subscriptions
 * even though the underlying channel/message data itself is public and account-agnostic.
 */
@Entity(tableName = "broadcast_channels", primaryKeys = ["channelName", "walletAddress"])
data class BroadcastChannelEntity(
    val channelName: String,
    val walletAddress: String,
    val joinedAt: Long = System.currentTimeMillis(),
    // Per-channel opt-in to background scanning — replaces a single global "listen for
    // broadcasts" setting, so the user can choose exactly which channels stay live while the
    // app is backgrounded rather than all-or-nothing.
    val alwaysListen: Boolean = false,
    // Per-channel opt-in to a system notification for new messages while the app is running in
    // the background. Only meaningful when alwaysListen is also true (a channel that isn't being
    // scanned never has new messages to notify about) — enabling this always turns alwaysListen
    // on too, and turning alwaysListen off always turns this off too (see BroadcastRepository).
    val notifyEnabled: Boolean = false,
    // Per-channel override of how long this room's cached messages stick around, set via the
    // settings icon next to a channel — capped at BroadcastRetention.MAX_MILLIS (see
    // BroadcastRepository.setRetentionMillis).
    val retentionMillis: Long = BroadcastRetention.DEFAULT_MILLIS
)

/**
 * A single broadcast message seen while scanning was enabled — stored for *every* channel the
 * device has observed, not just joined ones, so joining a channel later immediately surfaces
 * whatever was captured during the rolling retention window. Deliberately not scoped per wallet
 * address: it's a raw capture of public chain data, the same regardless of which local account is
 * active.
 */
@Entity(tableName = "broadcast_messages", primaryKeys = ["id"])
data class BroadcastMessageEntity(
    val id: String,                 // Kaspa transaction ID, or a synthetic "pending_<uuid>" while a send is in flight
    val channelName: String,
    val senderAddress: String,
    val content: String,
    val blockTimestamp: Long,
    // "sent" | "pending" | "failed" — only ever meaningful for the active account's own outgoing
    // messages (see BroadcastRepository.sendBroadcast); messages observed via block scanning are
    // always real on-chain confirmed data, so they're inserted as "sent" too, trivially.
    val deliveryStatus: String = "sent"
)

/**
 * An address the active account has chosen to hide across every broadcast room — set via "Hide
 * User" on their avatar. Scoped per wallet address like [BroadcastChannelEntity], since a hide
 * preference is personal to whichever account made it. Hiding is enforced in two places: filtered
 * out of [BroadcastMessageEntity] queries (never shown, including already-cached messages from
 * before the hide), and skipped entirely at scan-time insertion (see BroadcastScanningService, so
 * hidden senders' messages are never stored going forward either).
 */
@Entity(tableName = "broadcast_hidden_senders", primaryKeys = ["senderAddress", "walletAddress", "channelName"])
data class HiddenBroadcastSenderEntity(
    val senderAddress: String,
    val walletAddress: String,
    /**
     * PER-ROOM since 4.0 (matches iOS): a hide applies to one channel. "" is the legacy
     * every-room scope - rows migrated from before this column keep hiding everywhere.
     */
    val channelName: String = "",
    val hiddenAt: Long = System.currentTimeMillis()
)
