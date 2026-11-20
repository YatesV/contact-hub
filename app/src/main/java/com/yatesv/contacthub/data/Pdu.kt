package com.yatesv.contacthub.data

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Minimal MMS PDU reader/writer (OMA MMS encapsulation), only what Contact Hub needs as the
 * default texting app: build a send-req for group texts/photos, read notification-ind and
 * retrieve-conf for incoming MMS. Pure Kotlin so it can be unit tested.
 */
object Pdu {
    const val SEND_REQ = 0x80
    const val SEND_CONF = 0x81
    const val NOTIFICATION_IND = 0x82
    const val NOTIFYRESP_IND = 0x83
    const val RETRIEVE_CONF = 0x84
    const val ACKNOWLEDGE_IND = 0x85
    const val DELIVERY_IND = 0x86

    // Header field ids (without the 0x80 bit)
    const val H_BCC = 0x01; const val H_CC = 0x02; const val H_CONTENT_LOCATION = 0x03; const val H_CONTENT_TYPE = 0x04
    const val H_DATE = 0x05; const val H_DELIVERY_REPORT = 0x06; const val H_EXPIRY = 0x08; const val H_FROM = 0x09
    const val H_MESSAGE_CLASS = 0x0A; const val H_MESSAGE_ID = 0x0B; const val H_MESSAGE_TYPE = 0x0C; const val H_VERSION = 0x0D
    const val H_MESSAGE_SIZE = 0x0E; const val H_READ_REPORT = 0x10; const val H_RESPONSE_STATUS = 0x12; const val H_STATUS = 0x15
    const val H_SUBJECT = 0x16; const val H_TO = 0x17; const val H_TRANSACTION_ID = 0x18

    private val wellKnownTypes = mapOf(
        0x00 to "*/*", 0x01 to "text/*", 0x02 to "text/html", 0x03 to "text/plain", 0x07 to "text/x-vcard",
        0x1D to "image/gif", 0x1E to "image/jpeg", 0x1F to "image/tiff", 0x20 to "image/png", 0x21 to "image/vnd.wap.wbmp",
        0x22 to "application/vnd.wap.multipart.*", 0x23 to "application/vnd.wap.multipart.mixed",
        0x26 to "application/vnd.wap.multipart.alternative", 0x33 to "application/vnd.wap.multipart.related",
    )
    private val typeCodes = wellKnownTypes.entries.associate { (k, v) -> v to k }
    private val charsets = mapOf(3 to "US-ASCII", 4 to "ISO-8859-1", 106 to "UTF-8", 1000 to "UTF-16", 1015 to "UTF-16")

    data class Part(val contentType: String, val data: ByteArray, val name: String = "", val charset: String = "UTF-8") {
        fun text(): String = runCatching { String(data, Charset.forName(charset)) }.getOrDefault(String(data))
    }

    data class Message(
        val type: Int,
        val transactionId: String = "",
        val from: String = "",
        val to: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
        val subject: String = "",
        val date: Long = 0,
        val contentLocation: String = "",
        val messageId: String = "",
        val size: Long = 0,
        val contentType: String = "",
        val responseStatus: Int = 0,
        val parts: List<Part> = emptyList(),
    )

    // ---------------- Writing ----------------

    private class Out {
        val b = ByteArrayOutputStream()
        fun byte(v: Int) = b.write(v and 0xFF)
        fun bytes(a: ByteArray) = b.write(a)
        fun text(s: String) { bytes(s.toByteArray(Charsets.UTF_8)); byte(0) }
        fun uintvar(v: Long) = bytes(encodeUintvar(v))
        fun valueLength(len: Int) { if (len < 31) byte(len) else { byte(31); uintvar(len.toLong()) } }
        fun longInt(v: Long) {
            var n = v; val tmp = ArrayList<Int>()
            do { tmp.add(0, (n and 0xFF).toInt()); n = n ushr 8 } while (n > 0)
            byte(tmp.size); tmp.forEach { byte(it) }
        }
        fun header(id: Int) = byte(0x80 or id)
        fun toByteArray(): ByteArray = b.toByteArray()
    }

    fun encodeUintvar(v: Long): ByteArray {
        var n = v
        val out = ArrayList<Int>()
        out.add((n and 0x7F).toInt()); n = n ushr 7
        while (n > 0) { out.add(0, ((n and 0x7F) or 0x80).toInt()); n = n ushr 7 }
        return ByteArray(out.size) { out[it].toByte() }
    }

    /** Phone numbers in MMS headers look like "+13375550101/TYPE=PLMN". */
    fun plmn(address: String): String {
        val a = address.trim()
        return if (a.contains('@') || a.contains("/TYPE=")) a else a.filter { it.isDigit() || it == '+' } + "/TYPE=PLMN"
    }

    private fun contentTypeBytes(type: String, charset: Int? = null, name: String? = null): ByteArray {
        val inner = Out()
        val code = typeCodes[type]
        if (code != null) inner.byte(0x80 or code) else inner.text(type)
        if (charset != null) { inner.byte(0x81); inner.byte(0x80 or charset) }   // Charset param, short-int (106 fits)
        if (!name.isNullOrBlank()) { inner.byte(0x85); inner.text(name) }        // Name param
        val body = inner.toByteArray()
        if (charset == null && name.isNullOrBlank() && code != null) return byteArrayOf((0x80 or code).toByte())
        val o = Out(); o.valueLength(body.size); o.bytes(body)
        return o.toByteArray()
    }

    /** m-send-req for a group text and/or photos. */
    fun sendReq(to: List<String>, text: String, attachments: List<Part> = emptyList(), transactionId: String = "T" + System.currentTimeMillis().toString(16)): ByteArray {
        val o = Out()
        o.header(H_MESSAGE_TYPE); o.byte(SEND_REQ)
        o.header(H_TRANSACTION_ID); o.text(transactionId)
        o.header(H_VERSION); o.byte(0x80 or 0x12)              // MMS 1.2
        o.header(H_DATE); o.longInt(System.currentTimeMillis() / 1000)
        o.header(H_FROM); o.byte(1); o.byte(0x81)            // insert-address-token: carrier fills in our number
        to.forEach { o.header(H_TO); o.text(plmn(it)) }
        o.header(H_MESSAGE_CLASS); o.byte(0x80)              // personal
        o.header(H_DELIVERY_REPORT); o.byte(0x81)            // no
        o.header(H_READ_REPORT); o.byte(0x81)                // no
        o.header(H_CONTENT_TYPE); o.bytes(contentTypeBytes("application/vnd.wap.multipart.mixed"))

        val parts = buildList {
            if (text.isNotEmpty()) add(Part("text/plain", text.toByteArray(Charsets.UTF_8), "text.txt"))
            addAll(attachments)
        }
        o.uintvar(parts.size.toLong())
        parts.forEachIndexed { i, p ->
            val headers = Out()
            headers.bytes(contentTypeBytes(p.contentType, if (p.contentType.startsWith("text/")) 106 else null, p.name.ifBlank { null }))
            val loc = p.name.ifBlank { "part$i" }
            headers.byte(0x8E); headers.text(loc)               // Content-Location
            headers.byte(0xC0); headers.byte('"'.code); headers.text("<$loc>") // Content-ID (quoted string)
            val h = headers.toByteArray()
            o.uintvar(h.size.toLong()); o.uintvar(p.data.size.toLong())
            o.bytes(h); o.bytes(p.data)
        }
        return o.toByteArray()
    }

    /** m-notifyresp-ind: tells the carrier we got the MMS so it doesn't keep re-sending the notice. */
    fun notifyResp(transactionId: String): ByteArray {
        val o = Out()
        o.header(H_MESSAGE_TYPE); o.byte(NOTIFYRESP_IND)
        o.header(H_TRANSACTION_ID); o.text(transactionId)
        o.header(H_VERSION); o.byte(0x80 or 0x12)
        o.header(H_STATUS); o.byte(0x81)                     // retrieved
        return o.toByteArray()
    }

    // ---------------- Reading ----------------

    private class In(val a: ByteArray, var p: Int = 0, val end: Int = a.size) {
        fun more() = p < end
        fun peek() = a[p].toInt() and 0xFF
        fun byte() = a[p++].toInt() and 0xFF
        fun uintvar(): Long { var v = 0L; while (true) { val c = byte(); v = (v shl 7) or (c and 0x7F).toLong(); if (c and 0x80 == 0) return v } }
        fun text(): String {
            if (peek() == 0x7F || peek() == 0x22) p++          // quote characters
            val s = p
            while (p < end && a[p].toInt() != 0) p++
            val str = String(a, s, p - s, Charsets.UTF_8)
            if (p < end) p++
            return str
        }
        fun valueLength(): Int { val c = byte(); return if (c < 31) c else if (c == 31) uintvar().toInt() else { p--; 0 } }
        fun longInt(): Long { val n = byte(); var v = 0L; repeat(n) { v = (v shl 8) or byte().toLong() }; return v }
        fun integer(): Long = if (peek() >= 0x80) (byte() and 0x7F).toLong() else longInt()
        /** Skips any header value: short-int, value-length block, or text. */
        fun skip() {
            val c = peek()
            when {
                c >= 0x80 -> p++
                c <= 30 -> { p++; p += c }
                c == 31 -> { p++; val n = uintvar().toInt(); p += n }
                else -> text()
            }
        }
        fun encodedString(): String {
            val c = peek()
            if (c > 31) return text()
            val len = valueLength(); val stop = p + len
            val cs = if (peek() >= 0x80) (byte() and 0x7F) else { val n = byte(); var v = 0; repeat(n) { v = (v shl 8) or byte() }; v }
            val s = p
            var e = p; while (e < stop && a[e].toInt() != 0) e++
            val str = runCatching { String(a, s, e - s, Charset.forName(charsets[cs] ?: "UTF-8")) }.getOrDefault(String(a, s, e - s))
            p = stop
            return str
        }
    }

    fun cleanAddress(a: String) = a.substringBefore("/TYPE=").trim()

    private fun readContentType(r: In): Pair<String, Map<Int, String>> {
        val c = r.peek()
        if (c >= 0x80) return (wellKnownTypes[r.byte() and 0x7F] ?: "application/octet-stream") to emptyMap()
        if (c > 31) return r.text() to emptyMap()
        val len = r.valueLength(); val stop = r.p + len
        val media = if (r.peek() >= 0x80) wellKnownTypes[r.byte() and 0x7F] ?: "application/octet-stream" else r.text()
        val params = HashMap<Int, String>()
        while (r.p < stop) {
            val key = r.byte()
            when (key) {
                0x81 -> params[0x01] = r.integer().toString()      // charset
                0x85, 0x97 -> params[0x05] = r.text()              // name
                0x89, 0x8A -> params[0x09] = r.text()              // start / type
                else -> if (r.p < stop) r.skip()
            }
        }
        r.p = stop
        return media to params
    }

    fun parse(bytes: ByteArray): Message {
        val r = In(bytes)
        var m = Message(type = 0)
        val to = ArrayList<String>(); val cc = ArrayList<String>()
        while (r.more()) {
            val h = r.byte()
            if (h < 0x80) { r.p--; r.text(); r.skip(); continue }   // application header: skip name + value
            when (h and 0x7F) {
                H_MESSAGE_TYPE -> m = m.copy(type = r.byte())
                H_TRANSACTION_ID -> m = m.copy(transactionId = r.text())
                H_MESSAGE_ID -> m = m.copy(messageId = r.text())
                H_CONTENT_LOCATION -> m = m.copy(contentLocation = r.text())
                H_DATE -> m = m.copy(date = r.longInt())
                H_MESSAGE_SIZE -> m = m.copy(size = r.longInt())
                H_SUBJECT -> m = m.copy(subject = r.encodedString())
                H_TO -> to += cleanAddress(r.encodedString())
                H_CC -> cc += cleanAddress(r.encodedString())
                H_RESPONSE_STATUS -> m = m.copy(responseStatus = r.byte())
                H_FROM -> {
                    val len = r.valueLength(); val stop = r.p + len
                    val token = r.byte()
                    val from = if (token == 0x80 && r.p < stop) cleanAddress(r.encodedString()) else ""
                    r.p = stop
                    m = m.copy(from = from)
                }
                H_CONTENT_TYPE -> {
                    val (ct, _) = readContentType(r)
                    m = m.copy(contentType = ct)
                    // Content-Type is always the last header; the body follows
                    if (ct.startsWith("application/vnd.wap.multipart")) m = m.copy(parts = parseMultipart(r))
                    else m = m.copy(parts = listOf(Part(ct, bytes.copyOfRange(r.p, r.end))))
                    r.p = r.end
                }
                else -> r.skip()
            }
        }
        return m.copy(to = to, cc = cc)
    }

    private fun parseMultipart(r: In): List<Part> {
        val count = r.uintvar().toInt()
        val parts = ArrayList<Part>()
        repeat(count) {
            if (!r.more()) return parts
            val hLen = r.uintvar().toInt()
            val dLen = r.uintvar().toInt()
            val hStart = r.p
            val hEnd = hStart + hLen
            val sub = In(r.a, r.p, hEnd)
            val (ct, params) = readContentType(sub)
            r.p = sub.p
            var name = params[0x05].orEmpty()
            // Remaining part headers: look for Content-Location (0x8E) for a file name
            while (r.p < hEnd) {
                val k = r.byte()
                if (k == 0x8E && name.isBlank()) name = r.text()
                else if (k >= 0x80) { if (r.p < hEnd) r.skip() }
                else { r.p--; r.text(); if (r.p < hEnd) r.text() }
            }
            r.p = hEnd
            val data = r.a.copyOfRange(r.p, minOf(r.p + dLen, r.end))
            r.p += dLen
            val cs = params[0x01]?.toIntOrNull()?.let { charsets[it] } ?: "UTF-8"
            parts += Part(ct, data, name, cs)
        }
        return parts
    }
}
