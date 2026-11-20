package com.yatesv.contacthub.engine

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.HubLog
import com.yatesv.contacthub.data.LocalMessage
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.data.Reaction
import com.yatesv.contacthub.data.Reactions
import com.yatesv.contacthub.replyHandle
import kotlinx.coroutines.launch

/**
 * Watches Google/Samsung Messages notifications. This is how Contact Hub:
 *  - sees RCS messages (they aren't in the phone's SMS database),
 *  - gets the Messages app's reply button, so replies go out over RCS,
 *  - shows its own notifications instead of the Messages app's (optional),
 *  - runs auto-reply.
 */
class MessagesListener : NotificationListenerService() {

    private val prefs by lazy { getSharedPreferences("listener", Context.MODE_PRIVATE) }

    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg != GOOGLE_MESSAGES && pkg != SAMSUNG_MESSAGES) return
        val n = sbn.notification
        if (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return
        val title = (style.conversationTitle ?: n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))?.toString().orEmpty()
        val msgs = style.messages
        if (msgs.isEmpty()) return

        val isGroup = style.isGroupConversation
        val key = convKeyFor(title, msgs.map { it.person }, isGroup)
        if (key.isEmpty()) return

        val handle = n.replyHandle(pkg, title)
        handle?.let { Hub.replies[key] = it }
        HubLog.d("Listener", "notif from $pkg group=$isGroup msgs=${msgs.size} keyType=${key.substringBefore(':', "num")} replyBtn=${handle != null}")

        val lastSeen = prefs.getLong("seen:$key", 0)
        val newOnes = msgs.filter { it.timestamp > lastSeen }
        if (newOnes.isEmpty()) return
        prefs.edit().putLong("seen:$key", msgs.maxOf { it.timestamp }).apply()

        val s = Hub.settings.value
        val incoming = newOnes.filter { it.person != null && it.person?.name != style.user.name }
        Hub.scope.launch {
            val dao = Hub.db.dao()
            for (m in incoming) {
                val text = m.text?.toString().orEmpty()
                if (text.isBlank()) continue
                val parsed = if (s.convertReactions) Reactions.parse(text) else null
                if (parsed != null) {
                    // Saved against the quoted text; the chat screen attaches it to the right bubble
                    if (!parsed.removed) dao.addReaction(Reaction(convKey = key, msgKey = "q:${parsed.target}", msgBody = parsed.target,
                        emoji = parsed.emoji, fromMe = false, date = m.timestamp))
                    continue
                }
                if (dao.countSimilar(key, text, false, m.timestamp - 60_000) == 0) {
                    dao.addLocal(LocalMessage(convKey = key, body = text, date = m.timestamp, outgoing = false,
                        sender = m.person?.name?.toString().orEmpty(), source = "rcs"))
                }
            }
            Hub.changed()

            // Auto-reply: plain text, no branding, at most once every 15 minutes per chat
            if (s.autoReplyOn && incoming.isNotEmpty() && !isGroup) {
                val last = prefs.getLong("auto:$key", 0)
                if (System.currentTimeMillis() - last > 15 * 60_000) {
                    prefs.edit().putLong("auto:$key", System.currentTimeMillis()).apply()
                    Sender.send(this@MessagesListener, key, s.autoReplyText, allowDriving = false)
                }
            }
        }

        if (s.handleNotifications && incoming.isNotEmpty()) {
            runCatching { cancelNotification(sbn.key) }
            if (Hub.openConv != key) Hub.scope.launch {
                Notifier.showMessage(this@MessagesListener, key, title, isGroup,
                    msgs.takeLast(6).map { (it.person?.name?.toString() ?: "You") to (it.text?.toString().orEmpty()) }
                        .filter { !Sender.isReactionText(it.second) },
                    mention = isGroup && s.myName.isNotBlank() && incoming.any { it.text?.toString()?.contains(s.myName, ignoreCase = true) == true })
            }
        }
    }

    /** Match a notification to a conversation: by phone number when we have one, by contact name otherwise. */
    private fun convKeyFor(title: String, people: List<androidx.core.app.Person?>, isGroup: Boolean): String {
        if (isGroup) {
            val numbers = people.mapNotNull { p -> numberOf(p) }.distinct()
            return if (numbers.size >= 2) Phone.convKey(numbers) else "g:$title"
        }
        people.firstNotNullOfOrNull { numberOf(it) }?.let { return Phone.convKey(listOf(it)) }
        Hub.contacts.byName(title)?.let { return Phone.convKey(listOf(it.number)) }
        val digits = title.filter { it.isDigit() }
        if (digits.length >= 5) return Phone.convKey(listOf(digits))
        return if (title.isNotBlank()) "g:$title" else ""
    }

    private fun numberOf(p: androidx.core.app.Person?): String? {
        if (p == null) return null
        p.uri?.let { uri ->
            if (uri.startsWith("tel:")) return uri.removePrefix("tel:")
        }
        p.name?.toString()?.let { name -> Hub.contacts.byName(name)?.let { return it.number } }
        return null
    }

    companion object {
        @Volatile var connected = false
    }
}
