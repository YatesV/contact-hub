package com.yatesv.contacthub.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yatesv.contacthub.BuildConfig
import com.yatesv.contacthub.data.HubLog
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full crash and bug reports.
 *  - Crashes (Orbit closed): written the moment they happen, shown the next time you open Orbit.
 *  - Bugs Orbit caught and survived: saved too, with a notification so you know right away.
 *  - Freezes and native crashes: read from Android's own record of how Orbit last closed.
 * Every report says in plain words why it happened, where (screen / background job),
 * what Orbit was doing just before (activity log), and the full technical trace.
 */
object CrashLog {
    data class Report(val file: File, val kind: String, val title: String, val why: String, val at: Long, val text: String)

    /** Bumped when a new report is saved, so the in-app dialog can pop up. */
    val version = MutableStateFlow(0)

    private fun dir(ctx: Context) = File(ctx.filesDir, "reports").apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)
    private fun stamp(t: Long) = SimpleDateFormat("MMM d, h:mm:ss a", Locale.US).format(Date(t))
    private const val NOTIF = 7650

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t: Thread, e: Throwable ->
            runCatching {
                save(ctx, "crash", build(ctx, crashed = true, where = "Thread \"${t.name}\"", e = e))
                HubLog.w("Crash", "uncaught on ${t.name}", e)
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** A bug that was caught (Orbit kept running). */
    fun caught(tag: String, e: Throwable) {
        runCatching {
            val ctx = com.yatesv.contacthub.Hub.app
            // Same bug over and over: one report + one notification per hour
            val sig = signature(e)
            val p = prefs(ctx)
            val last = p.getLong("sig:$sig", 0)
            if (System.currentTimeMillis() - last < 3600_000L) {
                p.edit().putInt("count:$sig", p.getInt("count:$sig", 1) + 1).apply()
                return
            }
            p.edit().putLong("sig:$sig", System.currentTimeMillis()).putInt("count:$sig", 1).apply()
            val text = build(ctx, crashed = false, where = "Background: $tag (thread \"${Thread.currentThread().name}\")", e = e)
            val f = save(ctx, "bug", text)
            notifyBug(ctx, explain(e).first, f)
        }
    }

    // ---------------- Building a report ----------------

    private fun build(ctx: Context, crashed: Boolean, where: String, e: Throwable): String {
        val root = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.last()
        val (why, fix) = explain(e)
        val orbitLine = (e.stackTrace + root.stackTrace).firstOrNull { it.className.startsWith("com.yatesv.contacthub") }
        val screen = runCatching { com.yatesv.contacthub.ui.Nav.top.toString().take(120) }.getOrDefault("?")
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576; val maxMb = rt.maxMemory() / 1_048_576
        return buildString {
            appendLine(if (crashed) "ORBIT CRASH REPORT — Orbit closed" else "ORBIT BUG REPORT — Orbit caught this and kept running")
            appendLine("When: ${stamp(System.currentTimeMillis())}")
            appendLine("Build: ${BuildConfig.VERSION_CODE} (${BuildConfig.VERSION_NAME}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Where: $where")
            appendLine("Screen: $screen · app ${if (AppState.foreground) "open" else "in background"}")
            appendLine("Memory: $usedMb of $maxMb MB")
            appendLine()
            appendLine("WHY IT HAPPENED")
            appendLine(why)
            if (fix.isNotBlank()) appendLine(fix)
            appendLine("Error: ${e.javaClass.name}: ${e.message?.take(500)}")
            if (root !== e) appendLine("Root cause: ${root.javaClass.name}: ${root.message?.take(500)}")
            orbitLine?.let { appendLine("Orbit code: ${it.fileName}:${it.lineNumber} (${it.className.substringAfterLast('.')}.${it.methodName})") }
            appendLine()
            appendLine("WHAT ORBIT WAS DOING JUST BEFORE")
            HubLog.snapshot().takeLast(40).forEach { appendLine(it) }
            appendLine()
            appendLine("FULL TECHNICAL TRACE")
            appendLine(e.stackTraceToString().take(14_000))
        }
    }

    /** One line of plain English, plus what usually fixes it. */
    fun explain(e: Throwable): Pair<String, String> {
        val all = generateSequence(e) { it.cause.takeIf { c -> c !== it } }.toList()
        val names = all.map { it.javaClass.name }
        val msg = all.joinToString(" | ") { it.message.orEmpty() }
        fun has(n: String) = names.any { it.endsWith(n) }
        return when {
            has("OutOfMemoryError") -> "Orbit ran out of memory, usually from a very large photo, video or GIF." to "Fix: load a smaller copy of the image."
            msg.contains("was already used", true) && msg.contains("key", true) -> "Two items in a list had the same ID (for example the same message showed up twice)." to "Fix: remove duplicates before showing the list."
            has("SecurityException") -> "Android blocked something because a permission is missing or was turned off: ${all.first { it is SecurityException }.message?.take(200)}" to "Fix: check the permission before doing this, or ask for it."
            has("ForegroundServiceStartNotAllowedException") || has("BackgroundServiceStartNotAllowedException") ->
                "Android doesn't let apps start that kind of background service right now (Orbit was in the background)." to "Fix: start it while the app is open, or use a scheduled job."
            has("TransactionTooLargeException") -> "Orbit tried to pass too much data to Android at once (a big list or image)." to "Fix: send less data at a time."
            has("DeadObjectException") || has("DeadSystemException") -> "The other side of a connection (Android or another app) went away mid-call." to "Usually harmless; Orbit should retry."
            has("NetworkOnMainThreadException") -> "Orbit tried to use the internet on the screen's thread." to "Fix: move it to a background thread."
            has("SQLiteException") || has("SQLiteFullException") || has("SQLiteConstraintException") -> "Orbit's database had a problem: ${msg.take(200)}" to "Fix: check the data being saved, or free up storage if the phone is full."
            has("NullPointerException") -> "Something the code expected to be there was missing (empty)." to "Fix: handle the missing case."
            has("IndexOutOfBoundsException") -> "Code tried to read past the end of a list." to "Fix: check the list's size first."
            has("ClassCastException") -> "Data came in a different shape than the code expected." to ""
            has("ActivityNotFoundException") -> "Orbit tried to open another app that isn't installed or can't handle that." to "Fix: check before opening, or show a message."
            has("FileNotFoundException") || has("IOException") -> "Reading or writing a file failed: ${msg.take(200)}" to ""
            has("NumberFormatException") -> "Some text that should have been a number wasn't." to ""
            has("IllegalArgumentException") -> "A value given to Android (or a library) wasn't allowed: ${msg.take(200)}" to ""
            has("IllegalStateException") -> "Something happened at a moment the code didn't expect: ${msg.take(200)}" to ""
            has("UnsupportedOperationException") -> "This phone or Android version doesn't support something Orbit tried." to ""
            else -> "Unexpected error (${e.javaClass.simpleName}): ${msg.take(200)}" to ""
        }
    }

    private fun signature(e: Throwable): String {
        val line = e.stackTrace.firstOrNull { it.className.startsWith("com.yatesv.contacthub") }
        return (e.javaClass.simpleName + "@" + (line?.let { "${it.fileName}:${it.lineNumber}" } ?: e.stackTrace.firstOrNull()?.toString())).hashCode().toString()
    }

    // ---------------- Storage ----------------

    private fun save(ctx: Context, kind: String, text: String): File {
        val f = File(dir(ctx), "$kind-${System.currentTimeMillis()}.txt")
        f.writeText(text)
        // Keep the newest 40
        dir(ctx).listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(40).forEach { it.delete() }
        version.value++
        return f
    }

    private fun parse(f: File): Report? = runCatching {
        val text = f.readText()
        val kind = f.name.substringBefore('-')
        val why = text.substringAfter("WHY IT HAPPENED\n", "").substringBefore('\n')
        val title = when (kind) { "crash" -> "Orbit closed unexpectedly"; "bug" -> "Orbit hit a bug (it kept running)"; "freeze" -> "Orbit froze"; else -> "Orbit problem" }
        Report(f, kind, title, why, f.lastModified(), text)
    }.getOrNull()

    /** Every saved report, newest first. */
    fun all(ctx: Context): List<Report> {
        importSystemRecord(ctx)
        return dir(ctx).listFiles().orEmpty().sortedByDescending { it.lastModified() }.mapNotNull { parse(it) }
    }

    /** Reports you haven't looked at yet, newest first. */
    fun unseen(ctx: Context): List<Report> {
        val seen = prefs(ctx).getLong("seen", 0)
        return all(ctx).filter { it.at > seen }
    }

    fun markSeen(ctx: Context) {
        prefs(ctx).edit().putLong("seen", System.currentTimeMillis()).apply()
        runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIF) }
    }

    fun clear(ctx: Context) { dir(ctx).listFiles().orEmpty().forEach { it.delete() }; markSeen(ctx); version.value++ }

    /** Newest report text (for the self-test). */
    fun last(ctx: Context): String? = all(ctx).firstOrNull()?.text

    /**
     * Freezes and native crashes can't be caught by Orbit itself; Android keeps a record with a trace.
     * Plain Java crashes are already saved by our own handler, so only add those if we somehow missed them.
     */
    private fun importSystemRecord(ctx: Context) = runCatching {
        val p = prefs(ctx)
        val after = p.getLong("sysImported", 0)
        val am = ctx.getSystemService(ActivityManager::class.java)
        val bad = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)
        val infos = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10).filter { it.reason in bad && it.timestamp > after }
        if (infos.isEmpty()) return@runCatching
        p.edit().putLong("sysImported", infos.maxOf { it.timestamp }).apply()
        val ours = dir(ctx).listFiles().orEmpty().filter { it.name.startsWith("crash-") }.map { it.lastModified() }
        for (info in infos.sortedBy { it.timestamp }) {
            // Our own handler already wrote this one (within a few seconds)
            if (info.reason == ApplicationExitInfo.REASON_CRASH && ours.any { kotlin.math.abs(it - info.timestamp) < 15_000 }) continue
            val (kind, why) = when (info.reason) {
                ApplicationExitInfo.REASON_ANR -> "freeze" to "Orbit stopped responding: the screen's thread was busy for more than 5 seconds, so Android offered to close it."
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash" to "Crash inside native code (a built-in library such as the map, ML Kit, or Android itself), not Orbit's own Kotlin code."
                else -> "crash" to "Orbit closed, but no details were saved. That happens on versions from before crash reports were added (build 17)."
            }
            val text = buildString {
                appendLine(if (kind == "freeze") "ORBIT FREEZE REPORT" else "ORBIT CRASH REPORT — Orbit closed")
                appendLine("When: ${stamp(info.timestamp)}")
                appendLine("Build now: ${BuildConfig.VERSION_CODE} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Process: ${info.processName} · importance ${info.importance} (100 = on screen)")
                appendLine()
                appendLine("WHY IT HAPPENED")
                appendLine(why)
                info.description?.takeIf { it.isNotBlank() }?.let { appendLine("Android says: $it") }
                appendLine()
                runCatching { info.traceInputStream?.bufferedReader()?.use { appendLine("FULL TECHNICAL TRACE"); appendLine(it.readText().take(14_000)) } }
            }
            val f = File(dir(ctx), "$kind-${info.timestamp}.txt")
            if (!f.exists()) { f.writeText(text); f.setLastModified(info.timestamp) }
        }
    }

    // ---------------- Telling you ----------------

    private fun notifyBug(ctx: Context, why: String, f: File) = runCatching {
        val open = PendingIntent.getActivity(ctx, 7651,
            Intent(ctx, com.yatesv.contacthub.ui.MainActivity::class.java).putExtra("crashReport", f.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        if (nm.getNotificationChannel("bugs") == null) nm.createNotificationChannel(
            android.app.NotificationChannel("bugs", "Crash & bug reports", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(false) })
        val n = NotificationCompat.Builder(ctx, "bugs")
            .setSmallIcon(com.yatesv.contacthub.R.drawable.ic_tile)
            .setContentTitle("Orbit hit a bug (it kept running)")
            .setContentText(why)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$why\n\nTap to see the full report."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIF, n)
    }
}
