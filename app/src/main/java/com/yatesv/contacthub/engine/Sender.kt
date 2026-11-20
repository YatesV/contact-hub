package com.yatesv.contacthub.engine

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SmsManager
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.LocalMessage
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.data.Reaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"
const val SAMSUNG_MESSAGES = "com.samsung.android.messaging"

enum class Route { MESSAGES_REPLY, MESSAGES_APP, SMS, NONE }

data class SendResult(val ok: Boolean, val route: Route, val error: String = "")

/**
 * Sends texts so they look exactly like they came from the person's normal Messages app:
 * 1. through the Messages app's own reply button (RCS when available, SMS otherwise, handled by that app),
 * 2. through the Messages app itself, tapping Send for you (needs the accessibility service),
 * 3. plain SMS from this phone number as a last resort (1-on-1 chats only).
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

    suspend fun send(ctx: Context, convKey: String, text: String, addresses: List<String> = addressesOf(convKey)): SendResult {
        val body = text.trim()
        if (body.isEmpty()) return SendResult(false, Route.NONE, "Empty message")
        val s = Hub.settings.value
        val dao = Hub.db.dao()

        if (s.engine == "auto") {
            // 1. Reply button from the Messages app notification
            Hub.replies[convKey]?.let { h ->
                val ok = runCatching {
                    val intent = Intent()
                    val results = Bundle()
                    h.inputs.forEach { results.putCharSequence(it.resultKey, body) }
                    RemoteInput.addResultsToIntent(h.inputs, intent, results)
                    h.reply.send(ctx, 0, intent)
                    true
                }.getOrDefault(false)
                if (ok) {
                    dao.addLocal(LocalMessage(convKey = convKey, body = body, date = System.currentTimeMillis(), outgoing = true, source = "sent"))
                    Hub.changed()
                    return SendResult(true, Route.MESSAGES_REPLY)
                } else Hub.replies.remove(convKey)
            }

            // 2. Drive the Messages app
            val pkg = messagesPkg(ctx, convKey)
            if (pkg != null && addresses.isNotEmpty() && MessagesAccessibility.running()) {
                val r = MessagesAccessibility.sendThrough(ctx, pkg, addresses, body)
                if (r.clicked) {
                    if (r.chatType.isNotEmpty()) Hub.setRcsState(convKey, r.chatType)
                    dao.addLocal(LocalMessage(convKey = convKey, body = body, date = System.currentTimeMillis(), outgoing = true, source = "sent"))
                    Hub.changed()
                    return SendResult(true, Route.MESSAGES_APP)
                }
                Hub.banner.value = "Couldn't send through ${if (pkg == SAMSUNG_MESSAGES) "Samsung" else "Google"} Messages, sent as SMS instead."
            }
        }

        // 3. Plain SMS, 1-on-1 only. Splitting a group into separate texts would look wrong.
        if (addresses.size != 1) {
            return SendResult(false, Route.NONE, "Group texts need Google Messages + the Contact Hub accessibility service. Turn it on in Settings > Messaging.")
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
                Hub.changed()
                SendResult(true, Route.SMS)
            }.getOrElse { SendResult(false, Route.NONE, it.message ?: "SMS failed") }
        }
    }

    /** Photos/videos go through the Messages app so they arrive the same way (MMS or RCS). */
    suspend fun sendMedia(ctx: Context, convKey: String, uris: List<android.net.Uri>, caption: String): SendResult {
        val addresses = addressesOf(convKey)
        val pkg = messagesPkg(ctx, convKey) ?: return SendResult(false, Route.NONE, "Install Google Messages to send photos")
        if (addresses.isEmpty()) return SendResult(false, Route.NONE, "Can't find the numbers for this chat")
        if (!MessagesAccessibility.running()) return SendResult(false, Route.NONE, "Turn on the Contact Hub accessibility service to send photos")
        val r = MessagesAccessibility.sendThrough(ctx, pkg, addresses, caption, uris)
        return if (r.clicked) {
            Hub.changed(); SendResult(true, Route.MESSAGES_APP)
        } else SendResult(false, Route.NONE, "Couldn't send the photo through Messages")
    }

    /**
     * Reactions are only ever sent over RCS. If the chat isn't on RCS right now, the reaction
     * stays pending on this phone and gets retried later. It is never sent as a text.
     */
    suspend fun trySendReaction(ctx: Context, r: Reaction): Boolean {
        if (!r.pending || !r.fromMe) return true
        if (!MessagesAccessibility.running()) return false
        val pkg = messagesPkg(ctx, r.convKey) ?: return false
        val addresses = addressesOf(r.convKey).ifEmpty { return false }
        val res = MessagesAccessibility.reactThrough(ctx, pkg, addresses, r.msgBody, r.emoji)
        if (res.chatType.isNotEmpty()) Hub.setRcsState(r.convKey, res.chatType)
        if (res.clicked) Hub.db.dao().reactionSent(r.id)
        return res.clicked
    }

    suspend fun retryPendingReactions(ctx: Context) {
        Hub.db.dao().pendingReactions().forEach { r ->
            if (Hub.rcsState(r.convKey) != "sms") trySendReaction(ctx, r)
        }
    }

    /** Text from someone that's really a reaction, e.g. `Liked “hey”`. */
    fun isReactionText(text: String) = Hub.settings.value.convertReactions && com.yatesv.contacthub.data.Reactions.parse(text) != null

    fun keyFor(number: String) = Phone.convKey(listOf(number))
}
