package com.kachat.app.services

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
 * Nothing sensitive is written: only the exception and where it happened.
 */
object CrashRecorder {
    private const val TAG = "CrashRecorder"
    private const val DIR = "crashes"
    private const val MAX_KEPT = 5
    private const val PREFS = "kachat_crash_recorder"
    private const val PREF_UNSEEN = "unseen_crash_file"

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
            "${info.versionName} ($code)"
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
