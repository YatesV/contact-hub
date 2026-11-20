package com.yatesv.contacthub.engine

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.Pdu
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.data.Reactions
import kotlinx.coroutines.launch
import java.io.File

/**
 * Everything Contact Hub does when it's the phone's default texting app ("Contact Hub mode"):
 * sending and receiving SMS and MMS itself and saving them to the phone's message database.
 * In this mode there's no RCS (Android only gives RCS to Google Messages).
 */
object HubSms {
    private const val ACTION_SMS_SENT = "com.yatesv.contacthub.SMS_SENT"
    private const val ACTION_MMS_SENT = "com.yatesv.contacthub.MMS_SENT"
    private const val ACTION_MMS_DOWNLOADED = "com.yatesv.contacthub.MMS_DOWNLOADED"
    private val mmsServicePackages = listOf("com.android.phone", "com.android.mms.service", "com.samsung.android.mms.service")

    fun isDefault(ctx: Context) = Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

    /** This phone's own number, to leave it out of group chat members. */
    @Suppress("DEPRECATION")
    fun myNumbers(ctx: Context): Set<String> = runCatching {
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        val tm = ctx.getSystemService(android.telephony.TelephonyManager::class.java)
        val subs = sm.activeSubscriptionInfoList.orEmpty()
        (subs.flatMap { info ->
            listOfNotNull(
                runCatching { if (android.os.Build.VERSION.SDK_INT >= 33) sm.getPhoneNumber(info.subscriptionId) else null }.getOrNull(),
                runCatching { info.number }.getOrNull(),
            )
        } + listOfNotNull(runCatching { tm.line1Number }.getOrNull()))
            .map { Phone.norm(it) }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())

    /** SmsManager for the SIM a message came in on (S24 can have two: physical SIM + eSIM). */
    private fun smsManager(ctx: Context, subId: Int = -1): SmsManager {
        val base = ctx.getSystemService(SmsManager::class.java)
        return if (subId >= 0) runCatching { base.createForSubscriptionId(subId) }.getOrDefault(base) else base
    }

    /** Deletes leftover MMS temp files older than an hour. */
    private fun cleanTemp(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 3600_000
        ctx.cacheDir.listFiles { f -> f.name.startsWith("mms-") && f.lastModified() < cutoff }?.forEach { it.delete() }
    }

    fun thread(ctx: Context, addresses: Collection<String>): Long =
        runCatching { Telephony.Threads.getOrCreateThreadId(ctx, addresses.toSet()) }.getOrDefault(0L)

    // ---------------- Sending ----------------

    fun sendSms(ctx: Context, address: String, body: String): Boolean = runCatching {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put(Telephony.Sms.THREAD_ID, thread(ctx, listOf(address)))
        }
        val row = cr.insert(Telephony.Sms.CONTENT_URI, values)
        val sm = smsManager(ctx)
        val parts = sm.divideMessage(body)
        val sent = ArrayList<PendingIntent>()
        parts.forEachIndexed { i, _ ->
            sent += PendingIntent.getBroadcast(ctx, (row?.lastPathSegment?.toIntOrNull() ?: 0) * 10 + i,
                Intent(ctx, SmsStatusReceiver::class.java).setAction(ACTION_SMS_SENT).setData(row)
                    .putExtra("last", i == parts.size - 1),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        if (parts.size > 1) sm.sendMultipartTextMessage(address, null, parts, sent, null)
        else sm.sendTextMessage(address, null, body, sent.first(), null)
        Hub.changed()
        true
    }.getOrDefault(false)

    private fun shareFile(ctx: Context, f: File, write: Boolean): Uri {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val mode = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        mmsServicePackages.forEach { runCatching { ctx.grantUriPermission(it, uri, mode) } }
        return uri
    }

    /** Group texts and photos go out as MMS. */
    fun sendMms(ctx: Context, addresses: List<String>, body: String, attachments: List<Pdu.Part> = emptyList()): Boolean = runCatching {
        cleanTemp(ctx)
        val pdu = Pdu.sendReq(addresses, body, attachments)
        val f = File(ctx.cacheDir, "mms-out-${System.currentTimeMillis()}.pdu").apply { writeBytes(pdu) }
        val msgUri = saveMms(ctx, Telephony.Mms.MESSAGE_BOX_OUTBOX, addresses, "", body, attachments, System.currentTimeMillis(), read = true, mType = Pdu.SEND_REQ)
        val sent = PendingIntent.getBroadcast(ctx, f.name.hashCode(),
            Intent(ctx, SmsStatusReceiver::class.java).setAction(ACTION_MMS_SENT).setData(msgUri).putExtra("file", f.absolutePath),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        smsManager(ctx).sendMultimediaMessage(ctx, shareFile(ctx, f, write = false), null, null, sent)
        Hub.changed()
        true
    }.getOrDefault(false)

    /** Saves an MMS to the phone's message database. Returns its uri. */
    fun saveMms(ctx: Context, box: Int, members: List<String>, from: String, body: String, attachments: List<Pdu.Part>,
                date: Long, read: Boolean, mType: Int, subject: String = "", messageId: String = "", transactionId: String = ""): Uri? {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(Telephony.Mms.THREAD_ID, thread(ctx, members))
            put(Telephony.Mms.DATE, date / 1000); put(Telephony.Mms.DATE_SENT, date / 1000)
            put(Telephony.Mms.MESSAGE_BOX, box); put(Telephony.Mms.READ, if (read) 1 else 0); put(Telephony.Mms.SEEN, if (read) 1 else 0)
            put(Telephony.Mms.MESSAGE_TYPE, mType); put(Telephony.Mms.MMS_VERSION, 0x12)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            put(Telephony.Mms.TEXT_ONLY, if (attachments.isEmpty()) 1 else 0)
            if (subject.isNotBlank()) put(Telephony.Mms.SUBJECT, subject)
            if (messageId.isNotBlank()) put(Telephony.Mms.MESSAGE_ID, messageId)
            if (transactionId.isNotBlank()) put(Telephony.Mms.TRANSACTION_ID, transactionId)
        }
        val uri = cr.insert(Telephony.Mms.CONTENT_URI, values) ?: return null
        val id = uri.lastPathSegment ?: return uri
        if (body.isNotEmpty()) {
            cr.insert(Uri.parse("content://mms/$id/part"), ContentValues().apply {
                put(Telephony.Mms.Part.CONTENT_TYPE, "text/plain"); put(Telephony.Mms.Part.CHARSET, 106)
                put(Telephony.Mms.Part.TEXT, body); put(Telephony.Mms.Part.CONTENT_LOCATION, "text.txt")
                put(Telephony.Mms.Part.MSG_ID, id.toLong())
            })
        }
        attachments.forEachIndexed { i, p ->
            val partUri = cr.insert(Uri.parse("content://mms/$id/part"), ContentValues().apply {
                put(Telephony.Mms.Part.CONTENT_TYPE, p.contentType); put(Telephony.Mms.Part.NAME, p.name.ifBlank { "part$i" })
                put(Telephony.Mms.Part.CONTENT_LOCATION, p.name.ifBlank { "part$i" }); put(Telephony.Mms.Part.MSG_ID, id.toLong())
            })
            if (partUri != null) runCatching { cr.openOutputStream(partUri)?.use { it.write(p.data) } }
        }
        // Addresses: 137 = from, 151 = to
        val fromAddr = if (box == Telephony.Mms.MESSAGE_BOX_INBOX) from else "insert-address-token"
        cr.insert(Uri.parse("content://mms/$id/addr"), ContentValues().apply {
            put(Telephony.Mms.Addr.ADDRESS, fromAddr); put(Telephony.Mms.Addr.TYPE, 137); put(Telephony.Mms.Addr.CHARSET, 106)
        })
        members.filter { Phone.norm(it) != Phone.norm(from) }.forEach { a ->
            cr.insert(Uri.parse("content://mms/$id/addr"), ContentValues().apply {
                put(Telephony.Mms.Addr.ADDRESS, a); put(Telephony.Mms.Addr.TYPE, 151); put(Telephony.Mms.Addr.CHARSET, 106)
            })
        }
        return uri
    }

    // ---------------- Receiving ----------------

    suspend fun onSmsReceived(ctx: Context, intent: Intent) {
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (msgs.isEmpty()) return
        val from = msgs[0].displayOriginatingAddress ?: msgs[0].originatingAddress ?: return
        val body = msgs.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
        val sent = msgs[0].timestampMillis
        runCatching {
            ctx.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
                put(Telephony.Sms.ADDRESS, from); put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.DATE_SENT, sent)
                put(Telephony.Sms.READ, 0); put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.THREAD_ID, thread(ctx, listOf(from)))
            })
        }
        val key = Phone.convKey(listOf(from))
        incoming(ctx, key, from, body, isGroup = false)
    }

    /** New message arrived (default-app mode): notify, auto-reply, refresh screens. */
    private suspend fun incoming(ctx: Context, key: String, from: String, body: String, isGroup: Boolean) {
        Hub.changed()
        val s = Hub.settings.value
        val name = Hub.contacts.cached(from)?.name ?: Phone.pretty(from)
        val isReaction = s.convertReactions && Reactions.parse(body) != null
        if (Hub.openConv != key && !isReaction) {
            Notifier.showMessage(ctx, key, Notifier.displayName(key), isGroup, listOf(name to body),
                mention = isGroup && s.myName.isNotBlank() && body.contains(s.myName, true))
        }
        if (s.autoReplyOn && !isGroup && !isReaction) {
            val prefs = ctx.getSharedPreferences("listener", Context.MODE_PRIVATE)
            if (System.currentTimeMillis() - prefs.getLong("auto:$key", 0) > 15 * 60_000) {
                prefs.edit().putLong("auto:$key", System.currentTimeMillis()).apply()
                sendSms(ctx, from, s.autoReplyText)
            }
        }
    }

    /** WAP push = "you have an MMS waiting". Download it. */
    fun onMmsNotification(ctx: Context, intent: Intent) {
        cleanTemp(ctx)
        val data = intent.getByteArrayExtra("data") ?: return
        val n = runCatching { Pdu.parse(data) }.getOrElse { com.yatesv.contacthub.data.HubLog.w("MMS", "bad push", it); null } ?: return
        if (n.type != Pdu.NOTIFICATION_IND || n.contentLocation.isBlank()) return
        // Carriers sometimes send the same notice twice; download each MMS once
        val seen = ctx.getSharedPreferences("mms", Context.MODE_PRIVATE)
        val id = n.transactionId.ifBlank { n.contentLocation }
        if (seen.contains(id)) return
        seen.edit().putLong(id, System.currentTimeMillis()).apply()
        val subId = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1))
        val f = File(ctx.cacheDir, "mms-in-${System.currentTimeMillis()}.pdu").apply { createNewFile() }
        val done = PendingIntent.getBroadcast(ctx, f.name.hashCode(),
            Intent(ctx, SmsStatusReceiver::class.java).setAction(ACTION_MMS_DOWNLOADED)
                .putExtra("file", f.absolutePath).putExtra("tid", n.transactionId).putExtra("loc", n.contentLocation).putExtra("sub", subId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        runCatching { smsManager(ctx, subId).downloadMultimediaMessage(ctx, n.contentLocation, shareFile(ctx, f, write = true), null, done) }
            .onFailure { com.yatesv.contacthub.data.HubLog.w("MMS", "download start failed", it) }
    }

    suspend fun onMmsDownloaded(ctx: Context, intent: Intent) {
        val f = File(intent.getStringExtra("file") ?: return)
        val bytes = runCatching { f.readBytes() }.getOrNull()
        f.delete()
        if (bytes == null || bytes.isEmpty()) {
            Notifier.status(ctx, 7001, "Couldn't download a picture message", "Check mobile data is on, then ask them to resend it.")
            return
        }
        val m = runCatching { Pdu.parse(bytes) }.getOrElse { com.yatesv.contacthub.data.HubLog.w("MMS", "couldn't read downloaded MMS", it); null }
        if (m == null) {
            Notifier.status(ctx, 7003, "Couldn't open a picture message", "Run Settings → Developer → self-test and send the log.")
            return
        }
        val me = myNumbers(ctx).toMutableSet()
        // If we don't know our own number and there's exactly one recipient, that recipient is us
        if (me.isEmpty() && (m.to.size + m.cc.size) == 1) (m.to + m.cc).firstOrNull()?.let { me += Phone.norm(it) }
        val members = (listOf(m.from) + m.to + m.cc).map { it.trim() }.filter { it.isNotBlank() && Phone.norm(it) !in me }.distinctBy { Phone.norm(it) }
        val text = m.parts.filter { it.contentType == "text/plain" }.joinToString("\n") { it.text() }.trim()
        val media = m.parts.filter { it.contentType.startsWith("image/") || it.contentType.startsWith("video/") || it.contentType.startsWith("audio/") }
        saveMms(ctx, Telephony.Mms.MESSAGE_BOX_INBOX, members, m.from, text, media,
            if (m.date > 0) m.date * 1000 else System.currentTimeMillis(), read = false, mType = Pdu.RETRIEVE_CONF,
            subject = m.subject, messageId = m.messageId, transactionId = intent.getStringExtra("tid").orEmpty())
        // Tell the carrier we got it
        intent.getStringExtra("tid")?.takeIf { it.isNotBlank() }?.let { tid ->
            runCatching {
                val ack = File(ctx.cacheDir, "mms-ack-${System.currentTimeMillis()}.pdu").apply { writeBytes(Pdu.notifyResp(tid)) }
                smsManager(ctx, intent.getIntExtra("sub", -1)).sendMultimediaMessage(ctx, shareFile(ctx, ack, write = false), null, null, null)
            }
        }
        val key = Phone.convKey(members)
        incoming(ctx, key, m.from, text.ifBlank { if (media.isNotEmpty()) "📷 Photo" else "" }, isGroup = members.size > 1)
    }

    fun onSent(ctx: Context, intent: Intent, resultCode: Int) {
        val uri = intent.data ?: return
        val ok = resultCode == android.app.Activity.RESULT_OK
        when (intent.action) {
            ACTION_SMS_SENT -> runCatching {
                if (!ok || intent.getBooleanExtra("last", true)) {
                    ctx.contentResolver.update(uri, ContentValues().apply {
                        put(Telephony.Sms.TYPE, if (ok) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED)
                    }, null, null)
                }
            }
            ACTION_MMS_SENT -> {
                runCatching {
                    ctx.contentResolver.update(uri, ContentValues().apply {
                        put(Telephony.Mms.MESSAGE_BOX, if (ok) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED)
                    }, null, null)
                }
                intent.getStringExtra("file")?.let { File(it).delete() }
            }
        }
        if (!ok) Notifier.status(ctx, 7002, "Message not sent", "Tap the chat to try again.")
        Hub.changed()
    }

    /** Default app: mark a thread read in the phone's database too. */
    fun markThreadRead(ctx: Context, threadId: Long) {
        if (threadId <= 0 || !isDefault(ctx)) return
        runCatching {
            val v = ContentValues().apply { put("read", 1); put("seen", 1) }
            ctx.contentResolver.update(Telephony.Sms.CONTENT_URI, v, "thread_id = ? AND read = 0", arrayOf(threadId.toString()))
            ctx.contentResolver.update(Telephony.Mms.CONTENT_URI, v, "thread_id = ? AND read = 0", arrayOf(threadId.toString()))
        }
    }
}

class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val p = goAsync()
        Hub.scope.launch { try { HubSms.onSmsReceived(ctx, intent) } finally { p.finish() } }
    }
}

class MmsPushReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val p = goAsync()
        Hub.scope.launch { try { HubSms.onMmsNotification(ctx, intent) } finally { p.finish() } }
    }
}

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val code = resultCode
        val p = goAsync()
        Hub.scope.launch {
            try {
                if (intent.action?.endsWith("MMS_DOWNLOADED") == true) HubSms.onMmsDownloaded(ctx, intent) else HubSms.onSent(ctx, intent, code)
            } finally { p.finish() }
        }
    }
}

/** "Reply with a message" when declining a call. Android requires this for default texting apps. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.data?.schemeSpecificPart?.substringBefore('?')
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!number.isNullOrBlank() && !text.isNullOrBlank()) {
            // A job, so the reply still goes out if Android closes this service right away
            ReplyWorker.enqueue(this, Phone.convKey(listOf(number)), text)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
