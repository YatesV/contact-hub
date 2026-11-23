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

enum class Route { MESSAGES_REPLY, MESSAGES_APP, SMS, MMS, NONE,
    /** Not sent yet: waiting until it can go over RCS (SendQueue keeps it and retries) */
    HELD }

/** [uncertain] = Send was tapped but not confirmed: never retry automatically, it could go out twice. */
data class SendResult(val ok: Boolean, val route: Route, val error: String = "", val uncertain: Boolean = false)

/**
 * Sends texts so they look exactly like they came from a normal Messages app.
 *
 * Google Messages mode (Google Messages is the default texting app):
 *  1. its own notification reply button (RCS when available),
 *  2. the accessibility service taps Send inside it, and checks the box emptied,
 *  3. if that fails while you're using Orbit: plain SMS for 1-on-1 chats, and you're told it fell back.
 *     From the background (schedules, notification replies, locked phone) it never falls back: the
 *     message is held in SendQueue until it can go over RCS.
 *
 * Orbit mode (Orbit is the default texting app): SMS for 1-on-1, MMS for groups and photos.
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

    fun addressesOf(convKey: String): List<String> = Phone.addresses(convKey)

    private fun fellBack(ctx: Context, why: String) {
        val msg = "RCS sender didn't work ($why), so it went as a regular text."
        Hub.banner.value = msg
        Notifier.status(ctx, 7100, "Sent as SMS instead", msg)
    }

    /**
     * @param allowDriving false = never open Google Messages on screen (used for auto-reply and
     * background sends, so it can't take over the phone while you're using it).
     */
    /**
     * @param replyTo text of the message this answers: sent as a real RCS reply when Google Messages
     * can be driven, otherwise just the text (never a "> quote" line, which looks like another app).
     * @param queued called by SendQueue, which can hold the message (Route.HELD) and retry later.
     */
    suspend fun send(ctx: Context, convKey: String, text: String, addresses: List<String> = addressesOf(convKey), allowDriving: Boolean = true,
                     forcedHide: String? = null, skipReplyButton: Boolean = false, replyTo: String? = null, queued: Boolean = false): SendResult {
        // Never drive Google Messages behind the lock screen: it can't find Send there, and the chat cover could show on the lock screen
        @Suppress("NAME_SHADOWING")
        val allowDriving = allowDriving && !ctx.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        val body = text.trim()
        if (body.isEmpty()) return SendResult(false, Route.NONE, "Empty message")
        val s = Hub.settings.value
        val dao = Hub.db.dao()
        HubLog.d("Send", "start conv=${convKey.length}ch members=${addresses.size} len=${body.length} defaultSms=${HubSms.isDefault(ctx)} engine=${s.engine} replyBtn=${Hub.replies.containsKey(convKey)} a11y=${MessagesAccessibility.running()} drive=$allowDriving")

        // Orbit mode: we are the texting app
        if (HubSms.isDefault(ctx)) {
            if (addresses.isEmpty()) return SendResult(false, Route.NONE, "Can't find the numbers for this chat")
            // Only the people who talked recently: an MMS to them would start a new, smaller group
            if (isPartialGroup(ctx, convKey)) return SendResult(false, Route.NONE, "Orbit only knows part of this group. Open it in Google Messages once so Orbit learns everyone in it.")
            val sim = Hub.orbit.simFor(convKey)
            val ok = if (addresses.size == 1) HubSms.sendSms(ctx, addresses[0], body, sim) else HubSms.sendMms(ctx, addresses, body, subId = sim)
            Hub.setRcsState(convKey, "sms")
            return if (ok) SendResult(true, if (addresses.size == 1) Route.SMS else Route.MMS) else SendResult(false, Route.NONE, "Couldn't send")
        }

        var why = ""
        if (s.engine == "auto") {
            // A real threaded reply needs Google Messages on screen; the notification button can only send plain text
            val wantNativeReply = replyTo != null && allowDriving && MessagesAccessibility.running() && addresses.isNotEmpty() && !isPartialGroup(ctx, convKey)
            // 1. Reply button from the Messages app notification
            Hub.replies[convKey]?.takeIf { !skipReplyButton && !wantNativeReply }?.let { h ->
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
                    val id = dao.addLocal(LocalMessage(convKey = convKey, body = body, date = System.currentTimeMillis(), outgoing = true, source = "sent"))
                    // Handing it to the button doesn't prove it went: watch for Google Messages to show it
                    ReplyWatch.expect(convKey, id, body)
                    Hub.changed()
                    return SendResult(true, Route.MESSAGES_REPLY)
                } else Hub.replies.remove(convKey)
            }

            // A group we only know part of: typing into a new chat or falling back to SMS would text the wrong people
            if (isPartialGroup(ctx, convKey)) {
                HubLog.d("Send", "partial group without a reply button: holding")
                val msg = "Waiting for the next message in this group (or open it in Google Messages once) so it goes to everyone."
                return if (queued) SendResult(false, Route.HELD, msg) else SendResult(false, Route.NONE, msg)
            }

            // Can't reach Google Messages right now (locked, in the background, or no number known yet):
            // hold it for RCS instead of sending a plain text people would notice
            if (!allowDriving || addresses.isEmpty()) {
                val msg = if (addresses.isEmpty()) "Waiting for a new message from this chat so Orbit can reply over RCS."
                          else "Waiting to send over RCS (it goes when you unlock or open Orbit)."
                HubLog.d("Send", "holding for RCS: drive=$allowDriving numbers=${addresses.size}")
                return if (queued) SendResult(false, Route.HELD, msg) else SendResult(false, Route.NONE, msg)
            }

            // 2. Drive the Messages app
            val pkg = messagesPkg(ctx, convKey)
            when {
                pkg == null -> why = "Google Messages isn't installed"
                addresses.isEmpty() -> why = "no phone numbers for this chat"
                !MessagesAccessibility.running() -> why = "the accessibility service is off"
                !allowDriving -> why = "no reply button from Google Messages yet"
                else -> {
                    val r = MessagesAccessibility.sendThrough(ctx, pkg, addresses, body, convKey = convKey, forcedHide = forcedHide, replyTo = replyTo)
                    if (r.chatType.isNotEmpty()) Hub.setRcsState(convKey, r.chatType)
                    if (r.uncertain) {
                        // Send was already tapped: never fall back to SMS here, it could go out twice
                        Notifier.status(ctx, 7102, "Check Google Messages", "Orbit tapped Send but couldn't confirm it went. Open Google Messages to check.")
                        return SendResult(false, Route.NONE, "Couldn't confirm it sent. Check Google Messages.", uncertain = true)
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
            val err = "Group text not sent: $why. Fix it in Settings > Setup, or switch to Orbit mode."
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
        val mime = ctx.contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase())
            ?: "image/jpeg"
        val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (!mime.startsWith("image/") || mime == "image/gif" || raw.size < 900_000) return Pdu.Part(mime, raw, "file${System.nanoTime() % 100000}.${mime.substringAfter('/')}")
        // Decode at a reduced size first: a 50 MP photo at full size can run Orbit out of memory
        val max = 1600
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
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

    /** Photos/videos: through the Messages app (Google Messages mode) or as MMS (Orbit mode). */
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    suspend fun sendMedia(ctx: Context, convKey: String, uris: List<Uri>, caption: String, allowDriving: Boolean = true, queued: Boolean = false): SendResult {
        val addresses = addressesOf(convKey)
        if (addresses.isEmpty()) return SendResult(false, Route.NONE, "Orbit doesn't know this chat's numbers yet. Send photos from Google Messages this time.")
        // Only the people who talked recently: sending to them would start a new, smaller group
        if (isPartialGroup(ctx, convKey)) return SendResult(false, Route.NONE, "Orbit only knows part of this group. Open it in Google Messages once so Orbit learns everyone in it.")
        if (HubSms.isDefault(ctx)) {
            // Videos over the carrier limit get re-encoded smaller first
            val ready = ArrayList<Uri>()
            for (u in uris) {
                val type = ctx.contentResolver.getType(u).orEmpty()
                val size = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } }.getOrNull() ?: -1L }
                if (type.startsWith("video/") && (size < 0 || size > MMS_LIMIT)) {
                    when (val r = VideoShrink.shrink(ctx, u)) {
                        is VideoShrink.Result.Ok -> ready += Uri.fromFile(r.file)
                        is VideoShrink.Result.TooLong -> return SendResult(false, Route.NONE, "That video is ${r.seconds / 60}:${"%02d".format(r.seconds % 60)} long, too long to fit in a text message even shrunk (about a minute max). Trim it or send a link.")
                        is VideoShrink.Result.Failed -> return SendResult(false, Route.NONE, "Couldn't shrink that video to fit a text message (${r.why}).")
                    }
                } else ready += u
            }
            val parts = withContext(Dispatchers.IO) { ready.mapNotNull { readForMms(ctx, it) } }
            if (parts.isEmpty()) return SendResult(false, Route.NONE, "Couldn't read that file")
            // Carriers reject MMS over about 1 MB; only photos can be shrunk
            parts.firstOrNull { it.data.size > MMS_LIMIT }?.let {
                return SendResult(false, Route.NONE, "That ${it.contentType.substringBefore('/').ifBlank { "file" }} is ${it.data.size / 1024} KB, too big for a text message (about 1 MB max). Trim it or send a link.")
            }
            return if (HubSms.sendMms(ctx, addresses, caption.trim(), parts, subId = Hub.orbit.simFor(convKey))) SendResult(true, Route.MMS) else SendResult(false, Route.NONE, "Couldn't send")
        }
        val pkg = messagesPkg(ctx, convKey) ?: return SendResult(false, Route.NONE, "Install Google Messages to send photos")
        if (!MessagesAccessibility.running()) return SendResult(false, Route.NONE, "Turn on the Orbit accessibility service to send photos")
        if (!allowDriving || ctx.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            return if (queued) SendResult(false, Route.HELD, "Waiting to send (it goes when you unlock or open Orbit).") else SendResult(false, Route.NONE, "Open Orbit to send photos")
        val r = MessagesAccessibility.sendThrough(ctx, pkg, addresses, caption, uris, convKey = convKey)
        if (r.chatType.isNotEmpty()) Hub.setRcsState(convKey, r.chatType)
        return when {
            r.clicked -> { Hub.changed(); SendResult(true, Route.MESSAGES_APP) }
            r.uncertain -> SendResult(false, Route.NONE, "Couldn't confirm the photo sent. Check Google Messages.", uncertain = true)
            else -> SendResult(false, Route.NONE, "Couldn't send the photo: ${r.why}")
        }
    }

    private const val MMS_LIMIT = 1_000_000

    /**
     * Reactions are only ever sent over RCS. If the chat isn't on RCS right now, the reaction
     * stays pending on this phone and gets retried later. It is never sent as a text.
     * Returns null when sent, or the reason it's still waiting.
     */
    suspend fun trySendReaction(ctx: Context, r: Reaction): String? {
        if (!r.pending || !r.fromMe) return null
        if (HubSms.isDefault(ctx)) return "Orbit mode has no RCS"
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

    /**
     * Takes back a reaction you already sent: tapping the same reaction again in Google Messages
     * removes it for everyone. Returns null when removed, or why it couldn't be.
     */
    suspend fun removeReaction(ctx: Context, r: Reaction): String? {
        if (HubSms.isDefault(ctx)) return null   // Orbit mode never sends reactions, so there's nothing to take back
        if (!MessagesAccessibility.running()) return "accessibility service is off"
        val pkg = messagesPkg(ctx, r.convKey) ?: return "Google Messages isn't installed"
        val addresses = addressesOf(r.convKey).ifEmpty { return "no numbers for this chat" }
        val res = MessagesAccessibility.reactThrough(ctx, pkg, addresses, r.msgBody, r.emoji, r.convKey)
        if (res.chatType.isNotEmpty()) Hub.setRcsState(r.convKey, res.chatType)
        return if (res.clicked) null else res.why.ifBlank { "couldn't tap the reaction" }
    }

    private val lastRetry = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Retries this chat's waiting reactions when you open it in Orbit and it's known to be on RCS.
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

}
