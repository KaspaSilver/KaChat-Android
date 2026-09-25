package com.kachat.app.services

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Keeps a record of the app's own crashes, so a user whose app "closes on the splash screen"
 * has something to send instead of a description.
 *
 * Installed from [com.kachat.app.KaChatApplication.attachBaseContext] - the earliest hook there
 * is, before Hilt builds the object graph - as the process's default uncaught-exception handler.
 * On a crash it writes one plain-text file per crash under `files/crashes/` (app version, device,
 * thread, full stack trace, keeping the newest [MAX_KEPT]) and then hands the throwable to the
 * handler that was there before, so the OS still ends the process exactly as it would have.
 *
 * The files are read by two things: the diagnostics archive (Settings > Diagnostics > Export)
 * bundles them, and [MainActivity] shows a one-time notice on the next launch after a crash
 * with a Share button that sends the newest file straight to a share sheet - the report can
 * reach the developer even from a phone the user cannot connect to a computer.
 *
 * A Java exception is not the only way a process ends. A native crash (a signal below the
 * Java layer) or the OS killing the process never reaches this handler, and the app just
 * "closes with no pop-up". For those, [noteProcessExits] asks the system on the next launch
 * how the previous process ended - Android keeps that history (reason, a description, and for
 * an ANR the trace) - and writes an abnormal end to the same folder, so it is shared and
 * bundled exactly like a recorded exception.
 *
 * Nothing sensitive is written: only the exception and where it happened.
 */
object CrashRecorder {
    private const val TAG = "CrashRecorder"
    private const val DIR = "crashes"
    private const val MAX_KEPT = 5
    private const val PREFS = "kachat_crash_recorder"
    private const val PREF_UNSEEN = "unseen_crash_file"
    private const val PREF_LAST_EXIT_TS = "last_exit_timestamp"

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                record(appContext, thread, throwable)
            } catch (e: Throwable) {
                Log.e(TAG, "Could not record crash", e)
            }
            previous?.uncaughtException(thread, throwable) ?: run {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }

    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(":", "-")
        val file = File(dir, "crash-$stamp.txt")
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            "${com.kachat.app.util.AppVersion.display} [versionCode $code]"
        }.getOrDefault("unknown")
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        file.writeText(
            buildString {
                appendLine("KaChat crash report")
                appendLine("time: $stamp")
                appendLine("app: ${context.packageName} $version")
                appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("thread: ${thread.name}")
                appendLine()
                append(trace)
            }
        )
        // Newest MAX_KEPT stay; the rest go.
        crashFiles(context).drop(MAX_KEPT).forEach { runCatching { it.delete() } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_UNSEEN, file.name).commit()
        Log.e(TAG, "Recorded crash to ${file.name}", throwable)
    }

    /**
     * Records how the previous process ended when it was not a clean exit and this handler did
     * not already write it. Android 11+ only (older releases keep no such history). Call once
     * per launch; each system record is considered once.
     */
    fun noteProcessExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastSeen = prefs.getLong(PREF_LAST_EXIT_TS, 0L)
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .filter { it.timestamp > lastSeen }
                .sortedByDescending { it.timestamp }
            if (exits.isEmpty()) return
            prefs.edit().putLong(PREF_LAST_EXIT_TS, exits.first().timestamp).apply()
            val abnormal = exits.firstOrNull { it.reason in ABNORMAL_EXIT_REASONS } ?: return
            // A Java crash is already on file from the handler; the system record would only
            // repeat it. Everything else - native crash, ANR, a kill - is news.
            if (abnormal.reason == ApplicationExitInfo.REASON_CRASH && crashFiles(context).any { it.lastModified() > abnormal.timestamp - 60_000 }) return
            writeExitRecord(context, abnormal)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read process exit history", e)
        }
    }

    /** Ends worth a record. The OS reclaiming a backgrounded process for memory is routine and
     *  goes into the archive only; the rest are the "closes with no pop-up" cases and prompt. */
    private val ABNORMAL_EXIT_REASONS = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED,
    )
    private val PROMPT_EXIT_REASONS = ABNORMAL_EXIT_REASONS - setOf(
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )

    private fun writeExitRecord(context: Context, exit: ApplicationExitInfo) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(exit.timestamp)).replace(":", "-")
        val file = File(dir, "crash-exit-$stamp.txt")
        val reasonName = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH -> "CRASH (Java exception)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (signal below the Java layer)"
            ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (killed by the OS)"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE (killed by the OS)"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (killed by a signal)"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            else -> "reason ${exit.reason}"
        }
        // An ANR record carries the thread dump the system took; a native crash record carries
        // the tombstone summary on some releases. Both are the whole point of the file.
        val trace = runCatching {
            exit.traceInputStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            "${com.kachat.app.util.AppVersion.display} [versionCode $code]"
        }.getOrDefault("unknown")
        file.writeText(
            buildString {
                appendLine("KaChat process exit report (from the system's exit history)")
                appendLine("time: $stamp")
                appendLine("app: ${context.packageName} $version")
                appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("reason: $reasonName")
                appendLine("status: ${exit.status}")
                appendLine("description: ${exit.description ?: "(none)"}")
                appendLine("importance: ${exit.importance}, pss: ${exit.pss} kB, rss: ${exit.rss} kB")
                appendLine("process: ${exit.processName} (pid ${exit.pid})")
                // A Java crash reaching this record at all means the app's own handler never
                // wrote one (this file is only written when it did not - see noteProcessExits),
                // and the system keeps no stack trace for a Java crash. Say both, rather than
                // leaving the reader of the report to infer them from what is missing.
                if (exit.reason == ApplicationExitInfo.REASON_CRASH) {
                    appendLine()
                    appendLine("NO JAVA TRACE CAPTURED - the app's uncaught-exception handler did not record this crash,")
                    appendLine("so it happened before the handler was installed, on a thread or in a process it does not")
                    appendLine("cover, or the process died before the file was written. The system keeps no stack trace")
                    appendLine("for a Java crash either. The full trace is in Play Console > Android vitals for a Play")
                    appendLine("build, or in `adb logcat -b crash` if it can be reproduced with the device at hand.")
                }
                if (!trace.isNullOrBlank()) {
                    appendLine()
                    appendLine("---- system trace ----")
                    append(trace.take(200_000))
                }
            }
        )
        crashFiles(context).drop(MAX_KEPT).forEach { runCatching { it.delete() } }
        if (exit.reason in PROMPT_EXIT_REASONS) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_UNSEEN, file.name).apply()
        }
        Log.e(TAG, "Previous process ended abnormally: $reasonName - recorded to ${file.name}")
    }

    /** Every recorded crash, newest first. */
    fun crashFiles(context: Context): List<File> {
        val dir = File(context.filesDir, DIR)
        return dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }?.sortedByDescending { it.name }.orEmpty()
    }

    /** The crash file the user has not been told about yet, if any - null once [markSeen] ran. */
    fun unseenCrash(context: Context): File? {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_UNSEEN, null) ?: return null
        return File(File(context.filesDir, DIR), name).takeIf { it.isFile }
    }

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_UNSEEN).apply()
    }
}
