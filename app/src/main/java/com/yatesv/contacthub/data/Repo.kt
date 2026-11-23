package com.yatesv.contacthub.data

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Conv(
    val key: String,
    val threadId: Long,
    val addresses: List<String>,
    val snippet: String,
    val date: Long,
    val lastIncoming: Long = 0,
    val outgoingLast: Boolean = false,
)

data class Attachment(val uri: Uri, val mime: String)

data class Msg(
    /** "s:<id>" SMS, "m:<id>" MMS, "l:<id>" local (RCS) */
    val key: String,
    val body: String,
    val date: Long,
    val outgoing: Boolean,
    val sender: String = "",
    val attachments: List<Attachment> = emptyList(),
    /** 0 = sent/received, 1 = sending, 2 = failed */
    val status: Int = 0,
    val rcs: Boolean = false,
    /** The carrier confirmed it reached their phone (SMS delivery report) */
    val delivered: Boolean = false,
    /** SIM it went through / came in on (dual SIM), -1 = unknown */
    val subId: Int = -1,
)

data class ContactInfo(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val number: String,
    val photo: String?,
    val starred: Boolean,
    val mobile: Boolean = false,
)

data class CallEntry(val id: Long, val number: String, val date: Long, val duration: Long, val type: Int, val name: String?)

private inline fun <T> Cursor?.rows(max: Int = Int.MAX_VALUE, block: (Cursor) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>()
    use { while (out.size < max && it.moveToNext()) out += block(it) }
    return out
}

private fun Cursor.str(col: String): String = getColumnIndex(col).let { if (it < 0) "" else getString(it) ?: "" }
private fun Cursor.long(col: String): Long = getColumnIndex(col).let { if (it < 0) 0L else getLong(it) }
private fun Cursor.int(col: String): Int = getColumnIndex(col).let { if (it < 0) 0 else getInt(it) }

/** Reads texts from the phone's SMS/MMS database (the one Google/Samsung Messages writes to). */
class SmsRepo(private val ctx: Context) {
    private val cr get() = ctx.contentResolver
    private val sixMonths = 183L * 24 * 3600 * 1000

    /** Conversation key -> SMS thread id, filled every time the inbox loads. */
    private val threadIds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @SuppressLint("Recycle")
    suspend fun conversations(includeOld: Boolean): List<Conv> = withContext(Dispatchers.IO) {
        val since = if (includeOld) 0L else System.currentTimeMillis() - sixMonths
        val result = runCatching { fromThreads(since) }.getOrNull()
        val list = if (result.isNullOrEmpty()) runCatching { fromMessages(since) }.getOrDefault(emptyList()) else result
        // Newest first: keep the newest thread when two share a key (that's the one the inbox shows)
        val fresh = LinkedHashMap<String, Long>()
        list.forEach { fresh.putIfAbsent(it.key, it.threadId) }
        threadIds.putAll(fresh)
        list
    }

    /**
     * The SMS thread for a conversation key. Looks it up from the real conversation list first,
     * because asking Android to "get or create" a thread with a reformatted number can return a
     * different, empty thread on some phones.
     */
    suspend fun threadIdFor(key: String): Long = withContext(Dispatchers.IO) {
        if (Phone.isNameKey(key) || key.isBlank()) return@withContext 0L
        threadIds[key]?.let { return@withContext it }
        conversations(includeOld = true)
        threadIds[key]?.let { return@withContext it }
        // Brand new chat: no thread yet (one gets made when the first message is sent)
        0L
    }

    private fun canonical(): Map<Long, String> =
        cr.query(Uri.parse("content://mms-sms/canonical-addresses"), null, null, null, null)
            .rows { it.long("_id") to it.str("address") }.toMap()

    private fun fromThreads(since: Long): List<Conv> {
        val addr = canonical()
        val lastIn = lastIncomingByThread(since)
        return cr.query(
            Uri.parse("content://mms-sms/conversations?simple=true"),
            arrayOf("_id", "date", "snippet", "recipient_ids", "message_count"),
            "date > ?", arrayOf(since.toString()), "date DESC",
        ).rows { c ->
            val ids = c.str("recipient_ids").split(' ').mapNotNull { it.toLongOrNull() }
            val addresses = ids.mapNotNull { addr[it] }
            val thread = c.long("_id")
            Conv(
                key = Phone.convKey(addresses),
                threadId = thread,
                addresses = addresses,
                snippet = c.str("snippet").ifBlank { "📷 Photo" },
                date = c.long("date"),
                lastIncoming = lastIn[thread] ?: 0,
            )
        }.filter { it.addresses.isNotEmpty() && it.key.isNotEmpty() }
    }

    /** Fallback for phones where the threads table can't be read. */
    private fun fromMessages(since: Long): List<Conv> {
        val sms = cr.query(
            Telephony.Sms.CONTENT_URI, arrayOf("thread_id", "address", "body", "date", "type"),
            "date > ?", arrayOf(since.toString()), "date DESC",
        ).rows { Triple(it.long("thread_id"), it.str("address"), Triple(it.str("body"), it.long("date"), it.int("type"))) }
        return sms.groupBy { it.first }.map { (thread, rows) ->
            val newest = rows.first()
            val addresses = rows.map { it.second }.filter { it.isNotBlank() }.distinct()
            Conv(
                key = Phone.convKey(addresses), threadId = thread, addresses = addresses,
                snippet = newest.third.first, date = newest.third.second,
                lastIncoming = rows.firstOrNull { it.third.third == 1 }?.third?.second ?: 0,
                outgoingLast = newest.third.third != 1,
            )
        }.filter { it.key.isNotEmpty() }.sortedByDescending { it.date }
    }

    private fun lastIncomingByThread(since: Long): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        cr.query(
            Telephony.Sms.CONTENT_URI, arrayOf("thread_id", "date"), "type = 1 AND date > ?",
            arrayOf(since.toString()), "date DESC",
        ).rows { it.long("thread_id") to it.long("date") }.forEach { (t, d) -> if (d > (out[t] ?: 0)) out[t] = d }
        cr.query(
            Telephony.Mms.CONTENT_URI, arrayOf("thread_id", "date"), "msg_box = 1 AND date > ?",
            arrayOf((since / 1000).toString()), null,
        ).rows { it.long("thread_id") to it.long("date") * 1000 }.forEach { (t, d) -> if (d > (out[t] ?: 0)) out[t] = d }
        return out
    }

    /**
     * Newest [limit] messages of a thread. Fast path: one query for SMS, one for MMS, ONE query for
     * all MMS parts together, and sender lookups only for group picture messages.
     */
    suspend fun messages(threadId: Long, limit: Int = 80, group: Boolean = false): List<Msg> = withContext(Dispatchers.IO) {
        if (threadId <= 0) return@withContext emptyList()
        val sms = runCatching { cr.query(
            Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "body", "date", "type", "status", "sub_id"),
            "thread_id = ?", arrayOf(threadId.toString()), "date DESC",
        ).rows(limit) { c ->
            val type = c.int("type")
            Msg(
                delivered = type == Telephony.Sms.MESSAGE_TYPE_SENT && c.int("status") == Telephony.Sms.STATUS_COMPLETE &&
                    c.getColumnIndex("status").let { it >= 0 && !c.isNull(it) },
                subId = c.getColumnIndex("sub_id").let { if (it >= 0 && !c.isNull(it)) c.getInt(it) else -1 },
                key = "s:${c.long("_id")}", body = c.str("body"), date = c.long("date"),
                outgoing = type != Telephony.Sms.MESSAGE_TYPE_INBOX, sender = c.str("address"),
                status = when (type) {
                    Telephony.Sms.MESSAGE_TYPE_FAILED -> 2
                    Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> 1
                    else -> 0
                },
            )
        } }.onFailure { HubLog.w("Repo", "SMS read failed", it) }.getOrDefault(emptyList())

        val heads = runCatching { cr.query(
            Telephony.Mms.CONTENT_URI, arrayOf("_id", "date", "msg_box"),
            "thread_id = ?", arrayOf(threadId.toString()), "date DESC",
        ).rows(limit) { c -> Triple(c.long("_id"), c.long("date") * 1000, c.int("msg_box")) } }
            .onFailure { HubLog.w("Repo", "MMS read failed", it) }.getOrDefault(emptyList())

        val texts = HashMap<Long, MutableList<String>>()
        val files = HashMap<Long, MutableList<Attachment>>()
        heads.map { it.first }.chunked(400).forEach { ids ->
            runCatching {
                cr.query(Uri.parse("content://mms/part"), arrayOf("_id", "mid", "ct", "text"),
                    "mid IN (${ids.joinToString(",")})", null, null).rows { c ->
                    val mid = c.long("mid"); val ct = c.str("ct")
                    when {
                        ct == "text/plain" -> texts.getOrPut(mid) { mutableListOf() } += c.str("text")
                        ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/") ->
                            files.getOrPut(mid) { mutableListOf() } += Attachment(Uri.parse("content://mms/part/${c.long("_id")}"), ct)
                    }
                }
            }.onFailure { HubLog.w("Repo", "MMS parts read failed", it) }
        }
        val mms = heads.map { (id, date, box) ->
            // Who sent it only matters in groups (in a 1-on-1 chat it's the other person)
            val from = if (box == 1 && group) runCatching {
                cr.query(Uri.parse("content://mms/$id/addr"), arrayOf("address"), "type = 137", null, null).rows { it.str("address") }.firstOrNull()
            }.getOrNull().orEmpty() else ""
            Msg("m:$id", texts[id].orEmpty().joinToString("\n").trim(), date, box != 1, from, files[id].orEmpty(),
                status = if (box == 4) 1 else if (box == 5) 2 else 0)
        }
        // Keep the newest [limit] overall
        (sms + mms).sortedByDescending { it.date }.take(limit).sortedBy { it.date }
    }

    /** All photos/videos sent back and forth in a thread. */
    suspend fun media(threadId: Long): List<Pair<Long, Attachment>> = withContext(Dispatchers.IO) {
        if (threadId <= 0) return@withContext emptyList()
        val dates = cr.query(Telephony.Mms.CONTENT_URI, arrayOf("_id", "date"), "thread_id = ?", arrayOf(threadId.toString()), "date DESC")
            .rows { it.long("_id") to it.long("date") * 1000 }.toMap(LinkedHashMap())
        if (dates.isEmpty()) return@withContext emptyList()
        // One query for every photo/video part in the chat (it used to be one query per message)
        val parts = dates.keys.chunked(500).flatMap { ids ->
            cr.query(Uri.parse("content://mms/part"), arrayOf("_id", "mid", "ct"),
                "(ct LIKE 'image/%' OR ct LIKE 'video/%') AND mid IN (${ids.joinToString(",")})", null, null)
                .rows { c -> Triple(c.long("mid"), c.long("_id"), c.str("ct")) }
        }
        parts.sortedByDescending { dates[it.first] ?: 0 }
            .map { (mid, id, ct) -> (dates[mid] ?: 0L) to Attachment(Uri.parse("content://mms/part/$id"), ct) }
    }

    /** Message count + days with messages, for contact stats. */
    suspend fun statsFor(threadId: Long): Pair<Int, Set<Long>> = withContext(Dispatchers.IO) {
        if (threadId <= 0) return@withContext 0 to emptySet()
        val tz = java.util.TimeZone.getDefault()
        val dates = cr.query(Telephony.Sms.CONTENT_URI, arrayOf("date"), "thread_id = ?", arrayOf(threadId.toString()), null)
            .rows { it.long("date") } +
            cr.query(Telephony.Mms.CONTENT_URI, arrayOf("date"), "thread_id = ?", arrayOf(threadId.toString()), null)
                .rows { it.long("date") * 1000 }
        dates.size to dates.map { (it + tz.getOffset(it)) / 86_400_000L }.toSet()
    }

    /** Every message's (date, sent by you) for the relationship card. */
    suspend fun history(threadId: Long): List<Pair<Long, Boolean>> = withContext(Dispatchers.IO) {
        if (threadId <= 0) return@withContext emptyList()
        runCatching {
            cr.query(Telephony.Sms.CONTENT_URI, arrayOf("date", "type"), "thread_id = ?", arrayOf(threadId.toString()), null)
                .rows { it.long("date") to (it.int("type") != Telephony.Sms.MESSAGE_TYPE_INBOX) } +
                cr.query(Telephony.Mms.CONTENT_URI, arrayOf("date", "msg_box"), "thread_id = ?", arrayOf(threadId.toString()), null)
                    .rows { it.long("date") * 1000 to (it.int("msg_box") != Telephony.Mms.MESSAGE_BOX_INBOX) }
        }.getOrDefault(emptyList())
    }

    /** [key] = the chat it's in, when known (group and picture messages). */
    data class SearchHit(val threadId: Long, val address: String, val msg: Msg, val key: String = "")

    suspend fun search(q: String): List<SearchHit> = withContext(Dispatchers.IO) {
        if (q.isBlank()) return@withContext emptyList()
        val pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val sms = cr.query(
            Telephony.Sms.CONTENT_URI, arrayOf("_id", "thread_id", "address", "body", "date", "type"),
            "body LIKE ? ESCAPE '\\'", arrayOf(pattern), "date DESC",
        ).rows(200) { c ->
            SearchHit(
                c.long("thread_id"), c.str("address"),
                Msg("s:${c.long("_id")}", c.str("body"), c.long("date"), c.int("type") != 1, c.str("address")),
            )
        }
        // Group texts and photo captions live in MMS text parts
        if (threadIds.isEmpty()) runCatching { conversations(includeOld = true) }
        val byThread = threadIds.entries.associate { (k, v) -> v to k }
        val mms = runCatching {
            cr.query(Uri.parse("content://mms/part"), arrayOf("mid", "text"), "ct = 'text/plain' AND text LIKE ? ESCAPE '\\'", arrayOf(pattern), null)
                .rows(200) { it.long("mid") to it.str("text") }
                .mapNotNull { (mid, text) ->
                    cr.query(Telephony.Mms.CONTENT_URI, arrayOf("thread_id", "date", "msg_box"), "_id = ?", arrayOf(mid.toString()), null)
                        .rows(1) { c -> Triple(c.long("thread_id"), c.long("date") * 1000, c.int("msg_box") != Telephony.Mms.MESSAGE_BOX_INBOX) }
                        .firstOrNull()?.let { (thread, date, out) ->
                            val key = byThread[thread] ?: return@let null
                            SearchHit(thread, "", Msg("m:$mid", text, date, out), key)
                        }
                }
        }.getOrDefault(emptyList())
        (sms + mms).sortedByDescending { it.msg.date }
    }

    /** Raw SMS dump for backups. */
    suspend fun allSmsForBackup(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        cr.query(Telephony.Sms.CONTENT_URI, arrayOf("address", "body", "date", "type", "read"), null, null, "date")
            .rows { c ->
                mapOf("address" to c.str("address"), "body" to c.str("body"), "date" to c.long("date"),
                    "type" to c.int("type"), "read" to c.int("read"))
            }
    }

    /** Puts backed-up texts that aren't on this phone back into its SMS database (default texting app only). Returns how many. */
    suspend fun restoreSms(list: List<org.json.JSONObject>): Int = withContext(Dispatchers.IO) {
        val have = cr.query(Telephony.Sms.CONTENT_URI, arrayOf("address", "body", "date"), null, null, null)
            .rows { c -> "${Phone.norm(c.str("address"))}|${c.long("date")}|${c.str("body")}" }.toHashSet()
        val threads = HashMap<String, Long>()
        var added = 0
        for (o in list) {
            val address = o.optString("address"); val body = o.optString("body"); val date = o.optLong("date")
            if (address.isBlank() || !have.add("${Phone.norm(address)}|$date|$body")) continue
            val thread = threads.getOrPut(Phone.norm(address)) { runCatching { Telephony.Threads.getOrCreateThreadId(ctx, address) }.getOrDefault(0L) }
            val ok = runCatching {
                cr.insert(Telephony.Sms.CONTENT_URI, android.content.ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body); put(Telephony.Sms.DATE, date)
                    put(Telephony.Sms.TYPE, o.optInt("type", Telephony.Sms.MESSAGE_TYPE_INBOX)); put(Telephony.Sms.READ, o.optInt("read", 1))
                    put(Telephony.Sms.SEEN, 1); if (thread > 0) put(Telephony.Sms.THREAD_ID, thread)
                }) != null
            }.getOrDefault(false)
            if (ok) added++
        }
        added
    }
}

class ContactsRepo(private val ctx: Context) {
    private val cr get() = ctx.contentResolver
    @Volatile private var index: Map<String, ContactInfo> = emptyMap()

    suspend fun all(): List<ContactInfo> = withContext(Dispatchers.IO) {
        val list = runCatching {
            cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
                    ContactsContract.CommonDataKinds.Phone.STARRED,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                ),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE",
            ).rows { c ->
                ContactInfo(
                    c.long(ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
                    c.str(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY),
                    c.str(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                    c.str(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    c.str(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI).ifBlank { null },
                    c.int(ContactsContract.CommonDataKinds.Phone.STARRED) == 1,
                    c.int(ContactsContract.CommonDataKinds.Phone.TYPE) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                )
            }
        }.getOrDefault(emptyList())
        index = list.associateBy { Phone.norm(it.number) }
        ready = true
        list
    }

    /** True once contacts have been read at least once (notifications wait for this before matching names). */
    @Volatile var ready = false
        private set

    fun cached(number: String): ContactInfo? = index[Phone.norm(number)]

    /** Find a contact by name (used to match RCS notifications, which only show names). */
    fun byName(name: String): ContactInfo? {
        val hits = index.values.filter { it.name.equals(name, ignoreCase = true) }
        // Two different people with the same name: don't guess (a reply could go to the wrong one)
        if (hits.map { it.id }.distinct().size != 1) return null
        // Their mobile number, not a work line or landline
        return hits.firstOrNull { it.mobile } ?: hits.first()
    }


    suspend fun birthday(contactId: Long): String? = withContext(Dispatchers.IO) {
        runCatching {
            cr.query(
                ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Event.START_DATE),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
                arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                    ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()), null,
            ).rows { it.getString(0) }.firstOrNull()
        }.getOrNull()
    }

    suspend fun calls(number: String? = null, limit: Int = 300): List<CallEntry> = withContext(Dispatchers.IO) {
        runCatching {
            cr.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
                null, null, CallLog.Calls.DATE + " DESC",
            ).rows(if (number == null) limit else 5000) { c ->
                CallEntry(c.long(CallLog.Calls._ID), c.str(CallLog.Calls.NUMBER), c.long(CallLog.Calls.DATE),
                    c.long(CallLog.Calls.DURATION), c.int(CallLog.Calls.TYPE), c.str(CallLog.Calls.CACHED_NAME).ifBlank { null })
            }
        }.getOrDefault(emptyList())
            .let { list -> if (number == null) list else list.filter { Phone.norm(it.number) == Phone.norm(number) } }
            .take(limit)
    }

    fun isBlocked(number: String): Boolean =
        runCatching { BlockedNumberContract.isBlocked(ctx, number) }.getOrDefault(false)

    /** Only works while Orbit is the default phone app. */
    fun setBlocked(number: String, blocked: Boolean): Boolean = runCatching {
        if (blocked) {
            val v = android.content.ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }
            cr.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, v)
        } else {
            BlockedNumberContract.unblock(ctx, number)
        }
        true
    }.getOrDefault(false)

    fun blockedList(): List<String> = runCatching {
        cr.query(BlockedNumberContract.BlockedNumbers.CONTENT_URI,
            arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER), null, null, null)
            .rows { it.getString(0) ?: "" }
    }.getOrDefault(emptyList())
}
