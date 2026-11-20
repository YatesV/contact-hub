package com.yatesv.contacthub.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.yatesv.contacthub.data.HubLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers why Orbit crashed, so the next time you open it you can see and share the report.
 * Java/Kotlin crashes are written to a file the moment they happen; native crashes and
 * "not responding" freezes come from Android's own record of how the app last closed.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private fun file(ctx: Context) = File(ctx.filesDir, FILE)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t: Thread, e: Throwable ->
            runCatching {
                val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
                file(ctx).writeText(buildString {
                    appendLine("Orbit crash · ${stamp(System.currentTimeMillis())} · version $ver · Android ${Build.VERSION.RELEASE} (${Build.MODEL})")
                    appendLine("Thread: ${t.name}")
                    appendLine(e.stackTraceToString().take(12_000))
                })
                prefs(ctx).edit().putLong("at", System.currentTimeMillis()).commit()
                HubLog.w("Crash", "uncaught on ${t.name}", e)
            }
            previous?.uncaughtException(t, e)
        }
    }

    private fun stamp(t: Long) = SimpleDateFormat("MMM d h:mm:ss a", Locale.US).format(Date(t))

    /** Newest crash you haven't dismissed yet, or null. */
    fun unseen(ctx: Context): String? {
        val seen = prefs(ctx).getLong("seen", 0)
        val own = file(ctx).takeIf { it.exists() && prefs(ctx).getLong("at", 0) > seen }?.readText()
        if (own != null) return own
        return systemRecord(ctx, after = seen)
    }

    /** Last crash report (seen or not), for the self-test. */
    fun last(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText() ?: systemRecord(ctx, 0)

    fun markSeen(ctx: Context) = prefs(ctx).edit().putLong("seen", System.currentTimeMillis()).apply()

    /** Android's record of crashes/freezes (catches native crashes and ANRs the file can't). */
    private fun systemRecord(ctx: Context, after: Long): String? = runCatching {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val bad = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)
        val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10).firstOrNull { it.reason in bad && it.timestamp > after } ?: return null
        val kind = when (info.reason) { ApplicationExitInfo.REASON_ANR -> "froze (not responding)"; ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"; else -> "crash" }
        buildString {
            appendLine("Orbit $kind · ${stamp(info.timestamp)} · process ${info.processName}")
            info.description?.let { appendLine(it) }
            // ANRs and native crashes come with a trace
            runCatching { info.traceInputStream?.bufferedReader()?.use { appendLine(it.readText().take(10_000)) } }
        }
    }.getOrNull()
}
