package com.kachat.app.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Shared date/time formatting for message lists (1:1 chats and broadcast rooms) — day dividers plus per-message times revealed by swiping the list left. */
object ChatTimeFormat {
    fun isSameDay(t1: Long, t2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = t1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = t2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    /** "Today" / "Yesterday" / "July 6, 2026" — shown as a divider between days' worth of messages.
     *  Callers pass the localized "Today"/"Yesterday" (iOS fc6aec6). */
    fun formatDateDivider(timestamp: Long, today: String = "Today", yesterday: String = "Yesterday"): String {
        val now = System.currentTimeMillis()
        return when {
            isSameDay(timestamp, now) -> today
            isSameDay(timestamp, now - 24L * 60 * 60 * 1000) -> yesterday
            else -> SimpleDateFormat("MMMM d, yyyy", Locale.US).format(Date(timestamp))
        }
    }

    /** "10:57 AM" — iOS SharedFormatting.chatTime ("h:mm a", en_US_POSIX). */
    fun formatMessageTime(timestamp: Long): String {
        return SimpleDateFormat("h:mm a", Locale.US).format(Date(timestamp))
    }

    /** The time under a message (iOS MessageTimeLine). With [showsDay] (public chat rooms) a
     *  message from another day reads "Yesterday, 10:57 AM" or "Sep 28, 10:57 AM". */
    fun formatTimeLine(timestamp: Long, showsDay: Boolean, yesterday: String): String {
        val time = formatMessageTime(timestamp)
        val now = System.currentTimeMillis()
        if (!showsDay || isSameDay(timestamp, now)) return time
        val day = if (isSameDay(timestamp, now - 24L * 60 * 60 * 1000)) yesterday
            else SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        return "$day, $time"
    }
}
