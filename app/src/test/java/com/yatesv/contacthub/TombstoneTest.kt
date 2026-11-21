package com.yatesv.contacthub

import com.yatesv.contacthub.engine.Tombstone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Builds a small tombstone protobuf by hand (same field numbers as Android's) and checks it decodes. */
class TombstoneTest {
    private class Pb {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (true) { if (x and 0x7F.inv().toLong() == 0L) { out.write(x.toInt()); return }; out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
        fun int(f: Int, v: Long) = apply { varint((f shl 3).toLong()); varint(v) }
        fun str(f: Int, s: String) = bytes(f, s.toByteArray())
        fun msg(f: Int, m: Pb) = bytes(f, m.out.toByteArray())
        fun bytes(f: Int, b: ByteArray) = apply { varint(((f shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b) }
        fun build(): ByteArray = out.toByteArray()
    }

    private fun frame(file: String, fn: String) = Pb().int(1, 0x1234).str(4, fn).int(5, 16).str(6, file)
    private fun thread(id: Int, name: String, vararg frames: Pb) = Pb().int(1, id.toLong()).str(2, name).apply { frames.forEach { msg(4, it) } }
    private fun entry(id: Int, t: Pb) = Pb().int(1, id.toLong()).msg(2, t)

    private fun sample(): ByteArray = Pb()
        .str(2, "samsung/e1q:17")              // build fingerprint (ignored)
        .int(6, 4242)                           // crashing tid
        .msg(10, Pb().int(1, 11).str(2, "SIGSEGV").int(3, 1).str(4, "SEGV_MAPERR").int(8, 1).int(9, 0xdead))
        .str(14, "")
        .msg(15, Pb().str(1, "null pointer dereference"))
        .msg(16, entry(1, thread(1, "main", frame("/system/lib64/libc.so", "__epoll_pwait"))))
        .msg(16, entry(4242, thread(4242, "RenderThread", frame("/system/lib64/libhwui.so", "android::uirenderer::draw"), frame("/system/lib64/libc.so", "__start_thread"))))
        .msg(18, Pb().str(1, "main").msg(2, Pb().str(1, "19:28:55.100").int(4, 6).str(5, "OpenGLRenderer").str(6, "Device lost")))
        .build()

    @Test fun decodesCrashedThreadSignalAndLog() {
        val t = Tombstone.parse(sample())!!
        assertEquals(4242, t.tid)
        assertEquals("SIGSEGV", t.signal)
        assertEquals("SEGV_MAPERR", t.code)
        assertEquals(0xdeadL, t.faultAddress)
        assertEquals(listOf("null pointer dereference"), t.causes)
        assertEquals("RenderThread", t.crashed!!.name)
        assertEquals("/system/lib64/libhwui.so", t.crashed!!.frames[0].file)
        assertEquals("Device lost", t.logs.single().message)
        assertTrue(Tombstone.explain(t), Tombstone.explain(t).contains("screen-drawing"))
        val text = Tombstone.render(t)
        assertTrue(text, text.contains("Crashed thread: \"RenderThread\""))
        assertTrue(text, text.contains("libhwui.so (android::uirenderer::draw+16)"))
        assertTrue(text, text.contains("E OpenGLRenderer: Device lost"))
    }

    @Test fun mlKitCrashIsNamed() {
        val b = Pb().int(6, 7).msg(16, entry(7, thread(7, "pool-3", frame("/data/app/x/lib/arm64/libtranslate_jni.so", "")))).build()
        assertTrue(Tombstone.explain(Tombstone.parse(b)!!).contains("ML Kit"))
    }

    @Test fun garbageDoesNotThrow() {
        val r = Tombstone.parse(byteArrayOf(0x0a, 0x7f, 0x01, 0x02))
        // Either nothing or an empty result, never a crash
        assertTrue(r == null || r.crashed == null)
        assertNull(Tombstone.parse(ByteArray(0))?.crashed)
    }
}
