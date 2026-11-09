package com.kachat.app.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationHelperTest {

    @Test
    fun `sound and vibration both on selects the default channel`() {
        assertEquals("kachat_messages_sound_vibrate", NotificationHelper.channelFor(soundEnabled = true, vibrationEnabled = true))
    }

    @Test
    fun `sound only selects the sound-only channel`() {
        assertEquals("kachat_messages_sound_only", NotificationHelper.channelFor(soundEnabled = true, vibrationEnabled = false))
    }

    @Test
    fun `vibration only selects the vibrate-only channel`() {
        assertEquals("kachat_messages_vibrate_only", NotificationHelper.channelFor(soundEnabled = false, vibrationEnabled = true))
    }

    @Test
    fun `both off selects the silent channel`() {
        assertEquals("kachat_messages_silent", NotificationHelper.channelFor(soundEnabled = false, vibrationEnabled = false))
    }

    @Test
    fun `a group name of the limit stays whole`() {
        val name = "a".repeat(60)
        assertEquals(name, NotificationHelper.inviteBannerGroupName(name))
    }

    @Test
    fun `a longer group name is cut with an ellipsis`() {
        assertEquals("a".repeat(60) + "…", NotificationHelper.inviteBannerGroupName("a".repeat(61)))
    }

    @Test
    fun `a cut group name keeps its emoji whole`() {
        val emoji = "😀"
        assertEquals(emoji.repeat(60) + "…", NotificationHelper.inviteBannerGroupName(emoji.repeat(61)))
    }

    @Test
    fun `an admin rings once in the interval`() {
        val rung = NotificationHelper.claimInviteBannerSlot(emptyMap(), "w|a", 1_000L)
        assertEquals(mapOf("w|a" to 1_000L), rung)
        assertNull(NotificationHelper.claimInviteBannerSlot(rung!!, "w|a", 1_000L + NotificationHelper.GROUP_INVITE_BANNER_INTERVAL_MS - 1))
    }

    @Test
    fun `an admin rings again after the interval`() {
        val later = 1_000L + NotificationHelper.GROUP_INVITE_BANNER_INTERVAL_MS
        assertEquals(mapOf("w|a" to later), NotificationHelper.claimInviteBannerSlot(mapOf("w|a" to 1_000L), "w|a", later))
    }

    @Test
    fun `the oldest admin is forgotten past the limit`() {
        val full = (1..NotificationHelper.GROUP_INVITE_BANNER_ADMIN_LIMIT).associate { "w|$it" to it.toLong() }
        val updated = NotificationHelper.claimInviteBannerSlot(full, "w|new", 10_000L)!!
        assertEquals(NotificationHelper.GROUP_INVITE_BANNER_ADMIN_LIMIT, updated.size)
        assertNull(updated["w|1"])
        assertEquals(10_000L, updated["w|new"])
    }
}
