package com.yatesv.contacthub.engine

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.HubLog
import com.yatesv.contacthub.data.LocalMessage
import com.yatesv.contacthub.data.Pdu
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.data.Reaction
import com.yatesv.contacthub.data.Reactions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"
const val SAMSUNG_MESSAGES = "com.samsung.android.messaging"

enum class Route { MESSAGES_REPLY, MESSAGES_APP, SMS, MMS, NONE }

data class SendResult(val ok: Boolean, val route: Route, val error: String = "")

/**
 * Sends texts so they look exactly like they came from a normal Messages app.
 *
 * Google Messages mode (Google Messages is the default texting app):
 *  1. its own notification reply button (RCS when available),
 *  2. the accessibility service taps Send inside it, and checks the box emptied,
 *  3. if that fails: plain SMS for 1-on-1 chats, and you're told it fell back.
 *
 * Contact Hub mode (Contact Hub is the default texting app): SMS for 1-on-1, MMS for groups and photos.
 *
 * Never sends twice: it only falls back when the earlier step definitely didn't send.
 */
object Sender {
    fun installed(ctx: Context, pkg: String) = runCatching {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    /** Which Messages app to drive for this chat. */
    suspend fun messagesPkg(ctx: Context, convKey: String): String? {
        val meta = Hub.db.dao().meta(convKey)
        val wanted = when (meta?.engine) {
            "samsung" -> SAMSUNG_MESSAGES
            "google" -> GOOGLE_MESSAGES
            else -> Hub.replies[convKey]?.pkg
        }
        return listOfNotNull(wanted, GOOGLE_MESSAGES, SAMSUNG_MESSAGES).firstOrNull { installed(ctx, it) }
    }

    fun addressesOf(convKey: String): List<String> =
        if (convKey.startsWith("g:")) emptyList() else convKey.split(',').filter { it.isNotBlank() }

    private fun fellBack(ctx: Context, why: String) {
        val msg = "RCS sender didn't work ($why), so it went as a regular text."
        Hub.banner.value = msg
        Notifier.status(ctx, 7100, "Sent as SMS instead", msg)
    }

    /**
     * @param allowDriving false = never open Google Messages on screen (used for auto-reply and
     * background sends, so it can't take over the phone while you're using it).
     */
    suspend fun send(ctx: Context, convKey: String, text: String, addresses: List<String> = addressesOf(convKey), allowDriving: Boolean = true,
                     forcedHide: String? = null, skipReplyButton: Boolean = false): SendResult {
        val body = text.trim()
        if (body.isEmpty()) return SendResult(false, Route.NONE, "Empty message")
        val s = Hub.settings.value
        val dao = Hub.db.dao()
        HubLog.d("Send", "start conv=${convKey.length}ch members=${addresses.size} len=${body.length} defaultSms=${HubSms.isDefault(ctx)} engine=${s.engine} replyBtn=${Hub.replies.containsKey(convKey)} a11y=${MessagesAccessibility.running()} drive=$allowDriving")

        // Contact Hub mode: we are the texting app
        if (HubSms.isDefault(ctx)) {
            if (addresses.isEmpty()) return SendResult(false, Route.NONE, "Can't find the numbers for this chat")
            val ok = if (addresses.size == 1) HubSms.sendSms(ctx, addresses[0], body) else HubSms.sendMms(ctx, addresses, body)
            Hub.setRcsState(convKey, "sms")
            return if (ok) SendResult(true, if (addresses.size == 1) Route.SMS else Route.MMS) else SendResult(false, Route.NONE, "Couldn't send")
        }

        var why = ""
        if (s.engine == "auto") {
            // 1. Reply button from the Messages app notification
            Hub.replies[convKey]?.takeIf { !skipReplyButton }?.let { h ->
                val ok = runCatching {
                    val intent = Intent()
                    val results = Bundle()
                    h.inputs.forEach { results.putCharSequence(it.resultKey, body) }
                    RemoteInput.addResultsToIntent(h.inputs, intent, results)
                    h.reply.send(ctx, 0, intent)
                    true
                }.getOrElse { HubLog.w("Send", "reply button failed", it); false }
                if (ok) {
                    HubLog.d("Send", "sent via Messages reply button")
                    dao.addLocal(LocalMessage(convKey = convKey, body = body, date = System.currentTimeMillis(), outgoing = true, source = "sent"))
                    Hub.changed()
                    return SendResult(true, Route.MESSAGES_REPLY)
                } else Hub.replies.remove(convKey)
            }

            // 2. Drive the Messages app
            val pkg = messagesPkg(ctx, convKey)
            when {
                pkg == null -> why = "Google Messages isn't installed"
                addresses.isEmpty() -> why = "no phone numbers for this chat"
                !MessagesAccessibility.running() -> why = "the accessibility service is off"
                !allowDriving -> why = "no reply button from Google Messages yet"
                else -> {
                    val r = MessagesAccessibility.sendThrough(ctx, pkg, addresses, body, convKey = convKey, forcedHide = forcedHide)
                    if (r.chatType.isNotEmpty()) Hub.setRcsState(convKey, r.chatType)
                    if (r.uncertain) {
                        // Send was already tapped: never fall back to SMS here, it could go out twice
                        Notifier.status(ctx, 7102, "Check Google Messages", "Contact Hub tapped Send but couldn't confirm it went. Open Google Messages to check.")
                        return SendResult(false, Route.NONE, "Couldn't confirm it sent. Check Google Messages before trying again.")
                    }
                    if (r.clicked) {
                        dao.addLocal(LocalMessage(convKey = convKey, body = body, date = System.currentTimeMillis(), outgoing = true, source = "sent"))
                        Hub.changed()
                        return SendResult(true, Route.MESSAGES_APP)
                    }
                    why = r.why.ifBlank { "couldn't tap Send" }.lowercase()
                }
            }
        }

        HubLog.d("Send", "falling back: $why")
        // 3. Plain SMS, 1-on-1 only. Splitting a group into separate texts would look wrong.
        if (addresses.size != 1) {
            val err = "Group text not sent: $why. Fix it in Settings > Setup, or switch to Contact Hub mode."
            Notifier.status(ctx, 7101, "Group text not sent", err)
            return SendResult(false, Route.NONE, err)
        }
        if (ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return SendResult(false, Route.NONE, "Allow SMS permission first")
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val sm = ctx.getSystemService(SmsManager::class.java)
                val parts = sm.divideMessage(body)
                if (parts.size > 1) sm.sendMultipartTextMessage(addresses[0], null, parts, null, null)
                else sm.sendTextMessage(addresses[0], null, body, null, null)
                Hub.setRcsState(convKey, "sms")
                if (s.engine == "auto" && why.isNotBlank()) fellBack(ctx, why)
                Hub.changed()
                SendResult(true, Route.SMS)
            }.getOrElse { SendResult(false, Route.NONE, it.message ?: "SMS failed") }
        }
    }

    /** Shrinks photos so they fit carrier MMS limits (about 1 MB). */
    private fun readForMms(ctx: Context, uri: Uri): Pdu.Part? = runCatching {
        val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
        val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (!mime.startsWith("image/") || mime == "image/gif" || raw.size < 900_000) return Pdu.Part(mime, raw, "file${System.nanoTime() % 100000}.${mime.substringAfter('/')}")
        var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val max = 1600
        if (bmp.width > max || bmp.height > max) {
            val scale = max.toFloat() / maxOf(bmp.width, bmp.height)
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        }
        var q = 85
        var out: ByteArray
        do {
            out = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
            q -= 10
        } while (out.size > 900_000 && q > 30)
        Pdu.Part("image/jpeg", out, "photo${System.nanoTime() % 100000}.jpg")
    }.getOrNull()

    /** Photos/videos: through the Messages app (Google Messages mode) or as MMS (Contact Hub mode). */
    suspend fun sendMedia(ctx: Context, convKey: String, uris: List<Uri>, caption: String): SendResult {
        val addresses = addressesOf(convKey)
        if (addresses.isEmpty()) return SendResult(false, Route.NONE, "Can't find the numbers for this chat")
        if (HubSms.isDefault(ctx)) {
            val parts = withContext(Dispatchers.IO) { uris.mapNotNull { readForMms(ctx, it) } }
            if (parts.isEmpty()) return SendResult(false, Route.NONE, "Couldn't read that file")
            return if (HubSms.sendMms(ctx, addresses, caption.trim(), parts)) SendResult(true, Route.MMS) else SendResult(false, Route.NONE, "Couldn't send")
        }
        val pkg = messagesPkg(ctx, convKey) ?: return SendResult(false, Route.NONE, "Install Google Messages to send photos")
        if (!MessagesAccessibility.running()) return SendResult(false, Route.NONE, "Turn on the Contact Hub accessibility service to send photos")
        val r = MessagesAccessibility.sendThrough(ctx, pkg, addresses, caption, uris, convKey = convKey)
        return if (r.clicked) {
            Hub.changed(); SendResult(true, Route.MESSAGES_APP)
        } else SendResult(false, Route.NONE, "Couldn't send the photo: ${r.why}")
    }

    /**
     * Reactions are only ever sent over RCS. If the chat isn't on RCS right now, the reaction
     * stays pending on this phone and gets retried later. It is never sent as a text.
     * Returns null when sent, or the reason it's still waiting.
     */
    suspend fun trySendReaction(ctx: Context, r: Reaction): String? {
        if (!r.pending || !r.fromMe) return null
        if (HubSms.isDefault(ctx)) return "Contact Hub mode has no RCS"
        if (!MessagesAccessibility.running()) return "accessibility service is off"
        val pkg = messagesPkg(ctx, r.convKey) ?: return "Google Messages isn't installed"
        val addresses = addressesOf(r.convKey).ifEmpty { return "no numbers for this chat" }
        // Re-check it's still waiting (another attempt may have just sent it; tapping twice would remove it)
        if (Hub.db.dao().reaction(r.id)?.pending != true) return null
        val res = MessagesAccessibility.reactThrough(ctx, pkg, addresses, r.msgBody, r.emoji, r.convKey)
        if (res.chatType.isNotEmpty()) Hub.setRcsState(r.convKey, res.chatType)
        if (res.clicked) { Hub.db.dao().reactionSent(r.id); Hub.changed(); return null }
        return res.why.ifBlank { "couldn't react" }
    }

    private val lastRetry = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Retries this chat's waiting reactions when you open it in Contact Hub and it's known to be on RCS.
     * Never in the background, so Google Messages doesn't pop up out of nowhere.
     */
    suspend fun retryPendingFor(ctx: Context, convKey: String) {
        if (HubSms.isDefault(ctx) || Hub.rcsState(convKey) != "rcs") return
        if (System.currentTimeMillis() - (lastRetry[convKey] ?: 0) < 5 * 60_000) return
        lastRetry[convKey] = System.currentTimeMillis()
        Hub.db.dao().pendingReactions().filter { it.convKey == convKey }.take(3).forEach { trySendReaction(ctx, it) }
    }

    /** Text from someone that's really a reaction, e.g. `Liked “hey”`. */
    fun isReactionText(text: String) = Hub.settings.value.convertReactions && Reactions.parse(text) != null

    fun keyFor(number: String) = Phone.convKey(listOf(number))
}
