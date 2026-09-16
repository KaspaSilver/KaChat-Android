package com.kachat.app.services

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * The last few hundred lines of what the call machinery did - every Talk request and answer,
 * every call state change - kept in memory for the diagnostics archive (`calls.log`).
 *
 * logcat is not enough: a phone's log buffer can be a couple of minutes long, so by the time
 * someone opens Settings and exports, the failed call is gone from it. This survives until the
 * process does. Nothing sensitive: paths, status codes, the server's short error text.
 */
object CallDiagnostics {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(tag: String, message: String) {
        Log.i(tag, message)
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast("${stamp.format(Date())} $tag: $message")
    }

    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "(no call activity this process)" else lines.joinToString("\n")
}
