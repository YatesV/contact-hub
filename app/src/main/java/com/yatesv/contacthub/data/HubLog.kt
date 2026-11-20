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

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "hub.log")
        runCatching { file?.readLines()?.takeLast(MAX_LINES)?.let { synchronized(lines) { lines.addAll(it) } } }
    }

    fun d(tag: String, msg: String) = add("D", tag, msg)
    fun w(tag: String, msg: String, e: Throwable? = null) = add("W", tag, msg + (e?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun add(level: String, tag: String, msg: String) = runCatching {
        android.util.Log.println(if (level == "W") android.util.Log.WARN else android.util.Log.DEBUG, "ContactHub", "$tag: $msg")
        val (line, trim) = synchronized(lines) {
            val l = "${fmt.format(Date())} $level/$tag: $msg"
            lines.addLast(l)
            while (lines.size > MAX_LINES) lines.removeFirst()
            l to (lines.size % 200 == 0)
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
