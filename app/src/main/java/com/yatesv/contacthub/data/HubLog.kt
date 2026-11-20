package com.yatesv.contacthub.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small rolling log of what the app did (sends, RCS sender steps, errors). Message text is never
 * logged, only lengths and labels, so the file is safe to share for debugging.
 */
object HubLog {
    private const val MAX_LINES = 1500
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private var writes = 0
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "hub.log")
        file = f
        // Read only the end of the file, off the main thread (it used to read everything at every start)
        io.execute {
            runCatching {
                val tail = if (f.length() > 600_000) java.io.RandomAccessFile(f, "r").use { r ->
                    r.seek(f.length() - 600_000); val b = ByteArray(600_000); r.readFully(b); String(b).substringAfter('\n').lines()
                } else f.readLines()
                synchronized(lines) { val now = lines.toList(); lines.clear(); lines.addAll(tail.filter { it.isNotBlank() }.takeLast(MAX_LINES)); lines.addAll(now); while (lines.size > MAX_LINES) lines.removeFirst() }
                f.writeText(snapshot().joinToString("\n") + "\n")
            }
        }
    }

    fun d(tag: String, msg: String) = add("D", tag, msg)
    fun w(tag: String, msg: String, e: Throwable? = null) = add("W", tag, msg + (e?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    private fun add(level: String, tag: String, msg: String) = runCatching {
        android.util.Log.println(if (level == "W") android.util.Log.WARN else android.util.Log.DEBUG, "ContactHub", "$tag: $msg")
        val (line, trim) = synchronized(lines) {
            val l = "${fmt.format(Date())} $level/$tag: $msg"
            lines.addLast(l)
            while (lines.size > MAX_LINES) lines.removeFirst()
            // Rewrite the file every 200 lines so it stays the size of the in-memory log
            l to (++writes % 200 == 0)
        }
        // File writes happen off the main thread
        io.execute {
            runCatching { file?.appendText(line + "\n") }
            if (trim) runCatching { file?.writeText(snapshot().joinToString("\n") + "\n") }
        }
    }.let { }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() {
        synchronized(lines) { lines.clear() }
        runCatching { file?.writeText("") }
    }
}

/** Runs code Android calls in the background; a bug gets logged instead of closing the whole app. */
inline fun safely(tag: String, block: () -> Unit) {
    try { block() } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        HubLog.w(tag, "caught crash", e)
        com.yatesv.contacthub.engine.CrashLog.caught(tag, e)
    }
}
