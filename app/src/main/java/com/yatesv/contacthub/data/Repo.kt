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
)

data class ContactInfo(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val number: String,
    val photo: String?,
    val starred: Boolean,
)

data class CallEntry(val id: Long, val number: String, val date: Long, val duration: Long, val type: Int, val name: String?)

private inline fun <T> Cursor?.rows(block: (Cursor) -> T): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>()
    use { while (it.moveToNext()) out += block(it) }
    return out
}

private fun Cursor.str(col: String): String = getColumnIndex(col).let { if (it < 0) "" else getString(it) ?: "" }
private fun Cursor.long(col: String): Long = getColumnIndex(col).let { if (it < 0) 0L else getLong(it) }
private fun Cursor.int(col: String): Int = getColumnIndex(col).let { if (it < 0) 0 else getInt(it) }

/** Reads texts from the phone's SMS/MMS database (the one Google/Samsung Messages writes to). */
class SmsRepo(private val ctx: Context) {
    private val cr get() = ctx.contentResolver
    private val sixMonths = 183L * 24 * 3600 * 1000

    @SuppressLint("Recycle")
    suspend fun conversations(includeOld: Boolean): List<Conv> = withContext(Dispatchers.IO) {
        val since = if (includeOld) 0L else System.currentTimeMillis() - sixMonths
        val result = runCatching { fromThreads(since) }.getOrNull()
        if (result.isNullOrEmpty()) runCatching { fromMessages(since) }.getOrDefault(emptyList()) else result
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

    /** Find the SMS thread for some phone numbers (for chats started from a contact). */
    fun threadFor(addresses: List<String>): Long =
        runCatching { Telephony.Threads.getOrCreateThreadId(ctx, addresses.toSet()) }.getOrDefault(0L)

    suspend fun messages(threadId: Long, limit: Int = 600): List<Msg> = withContext(Dispatchers.IO) {
        if (threadId <= 0) return@withContext emptyList()
        val sms = cr.query(
            Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "body", "date", "type", "status"),
            "thread_id = ?", arrayOf(threadId.toString()), "date DESC LIMIT $limit",
        ).rows { c ->
            val type = c.int("type")
            Msg(
                key = "s:${c.long("_id")}", body = c.str("body"), date = c.long("date"),
                outgoing = type != Telephony.Sms.MESSAGE_TYPE_INBOX, sender = c.str("address"),
                status = when (type) {
                    Telephony.Sms.MESSAGE_TYPE_FAILED -> 2
                    Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> 1
                    else -> 0
                },
            )
        }
        val mms = cr.query(
            Telephony.Mms.CONTENT_URI, arrayOf("_id", "date", "msg_box"),
            "thread_id = ?", arrayOf(threadId.toString()), "date DESC LIMIT $limit",
        ).rows { c -> Triple(c.long("_id"), c.long("date") * 1000, c.int("msg_box")) }
            .map { (id, date, box) -> mmsMessage(id, date, box) }
        (sms + mms).sortedBy { it.date }
    }

    private fun mmsMessage(id: Long, date: Long, box: Int): Msg {
        val texts = ArrayList<String>()
        val files = ArrayList<Attachment>()
        cr.query(Uri.parse("content://mms/part"), arrayOf("_id", "ct", "text"), "mid = ?", arrayOf(id.toString()), null)
            .rows { c ->
                val ct = c.str("ct")
                when {
                    ct == "text/plain" -> texts += c.str("text")
                    ct.startsWith("image/") || ct.startsWith("video/") || ct.startsWith("audio/") ->
                        files += Attachment(Uri.parse("content://mms/part/${c.long("_id")}"), ct)
                }
            }
        val from = if (box == 1) cr.query(
            Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), "type = 137", null, null,
        ).rows { it.str("address") }.firstOrNull().orEmpty() else ""
        return Msg("m:$id", texts.joinToString("\n").trim(), date, box != 1, from, files)
    }

    /** All photos/videos sent back and forth in a thread. */
    suspend fun media(threadId: Long): List<Pair<Long, Attachment>> = withContext(Dispatchers.IO) {
        if (threadId <= 0) return@withContext emptyList()
        cr.query(Telephony.Mms.CONTENT_URI, arrayOf("_id", "date"), "thread_id = ?", arrayOf(threadId.toString()), "date DESC")
            .rows { it.long("_id") to it.long("date") * 1000 }
            .flatMap { (id, date) ->
                cr.query(Uri.parse("content://mms/part"), arrayOf("_id", "ct"), "mid = ?", arrayOf(id.toString()), null)
                    .rows { c -> c.str("ct") to c.long("_id") }
                    .filter { it.first.startsWith("image/") || it.first.startsWith("video/") }
                    .map { date to Attachment(Uri.parse("content://mms/part/${it.second}"), it.first) }
            }
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

    data class SearchHit(val threadId: Long, val address: String, val msg: Msg)

    suspend fun search(q: String): List<SearchHit> = withContext(Dispatchers.IO) {
        if (q.isBlank()) return@withContext emptyList()
        cr.query(
            Telephony.Sms.CONTENT_URI, arrayOf("_id", "thread_id", "address", "body", "date", "type"),
            "body LIKE ?", arrayOf("%$q%"), "date DESC LIMIT 200",
        ).rows { c ->
            SearchHit(
                c.long("thread_id"), c.str("address"),
                Msg("s:${c.long("_id")}", c.str("body"), c.long("date"), c.int("type") != 1, c.str("address")),
            )
        }
    }

    /** Raw SMS dump for backups. */
    suspend fun allSmsForBackup(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        cr.query(Telephony.Sms.CONTENT_URI, arrayOf("address", "body", "date", "type", "read"), null, null, "date")
            .rows { c ->
                mapOf("address" to c.str("address"), "body" to c.str("body"), "date" to c.long("date"),
                    "type" to c.int("type"), "read" to c.int("read"))
            }
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
                )
            }
        }.getOrDefault(emptyList())
        index = list.associateBy { Phone.norm(it.number) }
        list
    }

    fun cached(number: String): ContactInfo? = index[Phone.norm(number)]

    /** Find a contact by name (used to match RCS notifications, which only show names). */
    fun byName(name: String): ContactInfo? = index.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun allNumbers(contactId: Long): List<String> = index.values.filter { it.id == contactId }.map { it.number }

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
            ).rows { c ->
                CallEntry(c.long(CallLog.Calls._ID), c.str(CallLog.Calls.NUMBER), c.long(CallLog.Calls.DATE),
                    c.long(CallLog.Calls.DURATION), c.int(CallLog.Calls.TYPE), c.str(CallLog.Calls.CACHED_NAME).ifBlank { null })
            }
        }.getOrDefault(emptyList())
            .let { list -> if (number == null) list else list.filter { Phone.norm(it.number) == Phone.norm(number) } }
            .take(limit)
    }

    fun isBlocked(number: String): Boolean =
        runCatching { BlockedNumberContract.isBlocked(ctx, number) }.getOrDefault(false)

    /** Only works while Contact Hub is the default phone app. */
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
