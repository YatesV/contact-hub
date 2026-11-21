package com.yatesv.contacthub.engine

/**
 * Reads Android's native crash record ("tombstone", a protobuf) into plain text.
 * Field numbers follow AOSP system/core/debuggerd/proto/tombstone.proto.
 * Pure Kotlin so it can be unit tested; never throws (bad data just gives fewer details).
 */
object Tombstone {
    data class Frame(val function: String, val offset: Long, val file: String, val relPc: Long)
    data class Thread(val id: Int, val name: String, val frames: List<Frame>)
    data class Log(val tag: String, val message: String, val priority: Int, val time: String)
    data class Info(
        val tid: Int = 0,
        val signal: String = "",
        val code: String = "",
        val faultAddress: Long? = null,
        val abort: String = "",
        val causes: List<String> = emptyList(),
        val threads: Map<Int, Thread> = emptyMap(),
        val logs: List<Log> = emptyList(),
    ) {
        val crashed: Thread? get() = threads[tid]
    }

    // ---------------- Minimal protobuf reader ----------------

    private class Reader(val b: ByteArray, var pos: Int = 0, val end: Int = b.size) {
        fun more() = pos < end
        fun varint(): Long {
            var shift = 0; var result = 0L
            while (pos < end) {
                val x = b[pos++].toInt()
                result = result or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return result
                shift += 7
                if (shift > 63) break
            }
            return result
        }
        fun bytes(): Reader { val n = varint().toInt(); val s = pos; pos = (pos + n).coerceAtMost(end); return Reader(b, s, pos) }
        fun string(): String { val r = bytes(); return String(b, r.pos, r.end - r.pos, Charsets.UTF_8) }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> bytes()
                5 -> pos += 4
                else -> pos = end // unknown/group: stop safely
            }
        }
        /** Calls [f] with (field, wireType) for each field; [f] must consume the value or call skip. */
        inline fun fields(f: Reader.(Int, Int) -> Unit) {
            while (more()) {
                val key = varint()
                val field = (key ushr 3).toInt(); val wire = (key and 7).toInt()
                if (field == 0) return
                val before = pos
                this.f(field, wire)
                if (pos == before) skip(wire)
            }
        }
    }

    fun parse(data: ByteArray): Info? = runCatching {
        var info = Info()
        val threads = LinkedHashMap<Int, Thread>()
        val causes = ArrayList<String>()
        val logs = ArrayList<Log>()
        Reader(data).fields { f, w ->
            val r = this
            when {
                f == 6 && w == 0 -> info = info.copy(tid = r.varint().toInt())
                f == 10 && w == 2 -> info = signal(r.bytes(), info)
                f == 14 && w == 2 -> info = info.copy(abort = r.string())
                f == 15 && w == 2 -> r.bytes().fields { cf, cw -> if (cf == 1 && cw == 2) causes += string() }
                f == 16 && w == 2 -> mapEntry(r.bytes())?.let { threads[it.id] = it }
                f == 18 && w == 2 -> logBuffer(r.bytes(), logs)
            }
        }
        info.copy(threads = threads, causes = causes, logs = logs)
    }.getOrNull()

    private fun signal(r: Reader, info: Info): Info {
        var i = info
        r.fields { f, w ->
            when {
                f == 2 && w == 2 -> i = i.copy(signal = string())
                f == 4 && w == 2 -> i = i.copy(code = string())
                f == 9 && w == 0 -> i = i.copy(faultAddress = varint())
            }
        }
        return i
    }

    private fun mapEntry(r: Reader): Thread? {
        var t: Thread? = null
        r.fields { f, w -> if (f == 2 && w == 2) t = thread(bytes()) }
        return t
    }

    private fun thread(r: Reader): Thread {
        var id = 0; var name = ""; val frames = ArrayList<Frame>()
        r.fields { f, w ->
            when {
                f == 1 && w == 0 -> id = varint().toInt()
                f == 2 && w == 2 -> name = string()
                f == 4 && w == 2 -> frames += frame(bytes())
            }
        }
        return Thread(id, name, frames)
    }

    private fun frame(r: Reader): Frame {
        var relPc = 0L; var fn = ""; var off = 0L; var file = ""
        r.fields { f, w ->
            when {
                f == 1 && w == 0 -> relPc = varint()
                f == 4 && w == 2 -> fn = string()
                f == 5 && w == 0 -> off = varint()
                f == 6 && w == 2 -> file = string()
            }
        }
        return Frame(fn, off, file, relPc)
    }

    private fun logBuffer(r: Reader, out: MutableList<Log>) {
        r.fields { f, w ->
            if (f == 2 && w == 2) {
                var time = ""; var prio = 0; var tag = ""; var msg = ""
                bytes().fields { lf, lw ->
                    when {
                        lf == 1 && lw == 2 -> time = string()
                        lf == 4 && lw == 0 -> prio = varint().toInt()
                        lf == 5 && lw == 2 -> tag = string()
                        lf == 6 && lw == 2 -> msg = string()
                    }
                }
                out += Log(tag, msg, prio, time)
            }
        }
    }

    // ---------------- Plain English ----------------

    /** Which part of the phone the crash came from, judged by the crashing thread's top frames. */
    fun explain(info: Info): String {
        val t = info.crashed
        val files = t?.frames.orEmpty().take(8).joinToString(" ") { it.file + " " + it.function }.lowercase()
        val name = t?.name.orEmpty().lowercase()
        val sig = listOf(info.signal, info.code).filter { it.isNotBlank() }.joinToString(" / ")
        val where = when {
            "predictor_jni" in files || "smartreply" in files ->
                "Google's on-device Smart Reply (reply suggestions above the keyboard and in notifications) crashed."
            "translate_jni" in files -> "Google's on-device translation (ML Kit) crashed."
            "language_id" in files -> "Google's on-device language detection (ML Kit) crashed."
            "graphics.path" in files -> "The AndroidX graphics library (used for animated shapes) crashed."
            "libhwui" in files || "renderthread" in name || "hwui" in name ->
                "Android's screen-drawing engine (RenderThread) crashed while drawing Orbit. This is usually a graphics bug in the phone's Android version, set off by an animation, blur or image."
            "libgui" in files || "surfacesync" in name || "libsurfaceflinger" in files ->
                "Android's window system crashed while syncing Orbit's screen (often while a window, keyboard or pop-up opened or closed)."
            "libvulkan" in files || "libgles" in files || "vulkan" in files || "libegl" in files ->
                "The phone's graphics driver (GPU) crashed while drawing Orbit."
            "libmedia" in files || "codec" in files || "libstagefright" in files || "libheif" in files ->
                "Android's media code crashed while decoding a photo, video or GIF."
            "libart" in files -> "The Android runtime itself crashed (often from running out of memory)."
            "libbinder" in files -> "A connection to Android's system services crashed."
            t == null -> "Android didn't say which thread crashed."
            else -> "Crashed in ${t.frames.firstOrNull()?.file?.substringAfterLast('/')?.ifBlank { null } ?: "native code"}."
        }
        return where + if (sig.isNotBlank()) " ($sig)" else ""
    }

    /** Readable report body for a native crash. */
    fun render(info: Info): String = buildString {
        val t = info.crashed
        appendLine("Signal: ${info.signal.ifBlank { "?" }} ${info.code}" + (info.faultAddress?.let { " · fault address 0x${java.lang.Long.toHexString(it)}" } ?: ""))
        if (info.abort.isNotBlank()) appendLine("Abort message: ${info.abort.take(1000)}")
        info.causes.forEach { appendLine("Cause: $it") }
        appendLine("Crashed thread: \"${t?.name ?: "?"}\" (tid ${info.tid})")
        val orbitFrame = t?.frames?.firstOrNull { it.function.startsWith("com.yatesv.contacthub") }
        orbitFrame?.let { appendLine("Closest Orbit code: ${it.function}") }
        val lib = t?.frames?.firstOrNull { it.file.contains("!lib") || (it.file.endsWith(".so") && !it.file.contains("/apex/")) }
        lib?.let { appendLine("Library: ${it.file.substringAfterLast('!').substringAfterLast('/')}" + (it.function.takeIf { f -> f.isNotBlank() }?.let { f -> " · ${f.substringBefore('(').take(120)}" } ?: "")) }
        appendLine()
        appendLine("CRASHED THREAD BACKTRACE")
        t?.frames.orEmpty().take(40).forEachIndexed { i, f ->
            val fn = if (f.function.isNotBlank()) " (${f.function}+${f.offset})" else ""
            appendLine("#%02d pc %08x  %s%s".format(i, f.relPc, f.file, fn))
        }
        if (t == null || t.frames.isEmpty()) appendLine("(none recorded)")
        val log = info.logs.takeLast(40)
        if (log.isNotEmpty()) {
            appendLine()
            appendLine("ANDROID LOG JUST BEFORE")
            log.forEach { appendLine("${it.time} ${"VDIWEF".getOrElse((it.priority - 2).coerceAtLeast(0)) { '?' }} ${it.tag}: ${it.message.take(300)}") }
        }
        val others = info.threads.values.filter { it.id != info.tid }
        if (others.isNotEmpty()) {
            appendLine()
            appendLine("OTHER THREADS (${others.size})")
            others.take(15).forEach { o ->
                appendLine("\"${o.name}\" ${o.frames.firstOrNull { it.function.isNotBlank() }?.function?.take(80) ?: ""}")
            }
        }
    }
}
