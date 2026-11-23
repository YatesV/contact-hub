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
import kotlinx.coroutines.sync.withLock

/**
 * Watches Google/Samsung Messages notifications. This is how Orbit:
 *  - sees RCS messages (they aren't in the phone's SMS database),
 *  - gets the Messages app's reply button, so replies go out over RCS,
 *  - shows its own notifications instead of the Messages app's (optional),
 *  - runs auto-reply.
 */
/** Groups built from recent senders only: sending must go through Google Messages' own reply button. */
fun isPartialGroup(ctx: android.content.Context, key: String) =
    ctx.getSharedPreferences("listener", android.content.Context.MODE_PRIVATE).getStringSet("partial", emptySet())!!.contains(key)

class MessagesListener : NotificationListenerService() {

    private val prefs by lazy { getSharedPreferences("listener", Context.MODE_PRIVATE) }

    override fun onListenerConnected() { connected = true }

    /** Google Messages notification -> chat key, to know which chat a removed notification was. */
    private val notifKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != GOOGLE_MESSAGES && sbn.packageName != SAMSUNG_MESSAGES) return
        notifKeys.remove(sbn.key)?.let { ReplyWatch.notificationRemoved(it) }
    }
    override fun onListenerDisconnected() { connected = false }

    /** Notifications that arrived before contacts / the chat list were loaded, handled in order. */
    private val prepLock = kotlinx.coroutines.sync.Mutex()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg != GOOGLE_MESSAGES && pkg != SAMSUNG_MESSAGES) return
        // Matching names to numbers needs contacts, and matching groups needs the chat list. Right
        // after Orbit starts in the background neither is loaded yet: guessing then used to lock in
        // wrong keys (a group or a person keyed by name forever), so load them first.
        val group = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)?.isGroupConversation }.getOrNull() == true
        val needsPrep = !Hub.contacts.ready || (group && com.yatesv.contacthub.ui.InboxData.cache == null)
        if (!needsPrep && !prepLock.isLocked) { handle(sbn); return }
        Hub.scope.launch {
            prepLock.withLock {
                if (!Hub.contacts.ready) runCatching { Hub.contacts.all() }
                if (group && com.yatesv.contacthub.ui.InboxData.cache == null)
                    runCatching { com.yatesv.contacthub.ui.InboxData.cache = com.yatesv.contacthub.ui.InboxData.load() }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { handle(sbn) }
            }
        }
    }

    private fun handle(sbn: StatusBarNotification) { com.yatesv.contacthub.data.safely("MessagesListener") {
        val pkg = sbn.packageName
        val n = sbn.notification
        if (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) ?: return
        val title = (style.conversationTitle ?: n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))?.toString().orEmpty()
        val msgs = style.messages
        if (msgs.isEmpty()) return

        val isGroup = style.isGroupConversation
        val key = convKeyFor(n.shortcutId, title, msgs.map { it.person }, isGroup)
        if (key.isEmpty()) return
        notifKeys[sbn.key] = key
        // Your own messages in Google Messages' notification = a reply-button send that really went
        msgs.filter { it.person == null || it.person?.name == style.user.name }.forEach { m ->
            m.text?.toString()?.let { ReplyWatch.sawOutgoing(key, it) }
        }

        val handle = n.replyHandle(pkg, title)
        handle?.let {
            Hub.replies[key] = it
            // Anything waiting to go over RCS in this chat can go now, through this reply button
            SendQueue.wakeHeld(this, key)
        }
        HubLog.d("Listener", "notif from $pkg group=$isGroup msgs=${msgs.size} keyType=${key.substringBefore(':', "num")} replyBtn=${handle != null}")

        val lastSeen = prefs.getLong("seen:$key", 0)
        val newOnes = msgs.filter { it.timestamp > lastSeen }
        if (newOnes.isEmpty()) return
        prefs.edit().putLong("seen:$key", msgs.maxOf { it.timestamp }).apply()

        val s = Hub.settings.value
        val incoming = newOnes.filter { it.person != null && it.person?.name != style.user.name }
        // Google Messages often doesn't put the RCS file in the notification, but does include the photo as the
        // notification's big-picture preview. Grab that as a fallback so photo messages show the real image.
        val bigPicture = runCatching {
            @Suppress("DEPRECATION")
            n.extras.getParcelable(android.app.Notification.EXTRA_PICTURE) as? android.graphics.Bitmap
        }.getOrNull()
        var pictureUsed = false
        HubLog.d("Listener", "media sources: bigPicture=${bigPicture != null} msgsWithFile=${incoming.count { it.dataUri != null }}")
        Hub.scope.launch {
            val dao = Hub.db.dao()
            for (m in incoming) {
                val text = m.text?.toString().orEmpty()
                val looksLikeMedia = m.dataMimeType != null || com.yatesv.contacthub.data.IncomingMedia.isPlaceholder(text)
                // A photo/video/file in the notification: copy it now (the link stops working once the notification is gone)
                var file = m.dataUri?.let { com.yatesv.contacthub.data.IncomingMedia.copy(this@MessagesListener, it, m.dataMimeType) }
                // No file given, but the notification carries a photo preview: use that
                if (file == null && !pictureUsed && bigPicture != null && looksLikeMedia) {
                    file = com.yatesv.contacthub.data.IncomingMedia.saveBitmap(this@MessagesListener, bigPicture)
                    if (file != null) pictureUsed = true
                }
                if (file != null) {
                    val body = if (com.yatesv.contacthub.data.IncomingMedia.isPlaceholder(text)) "" else text
                    val id = dao.addLocal(LocalMessage(convKey = key, body = body, date = m.timestamp, outgoing = false,
                        sender = m.person?.name?.toString().orEmpty(), source = "rcs"))
                    Hub.orbit.setFiles("l:$id", listOf(file.first.absolutePath to file.second))
                    HubLog.d("Listener", "saved attachment ${file.second} (${file.first.length() / 1024} KB)")
                    continue
                }
                if (text.isBlank()) continue
                if (looksLikeMedia)
                    HubLog.d("Listener", "attachment Orbit couldn't pull in (mime=${m.dataMimeType}, uri=${m.dataUri != null}, picture=${bigPicture != null})")
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
            // A new message brings an archived chat back to the inbox (like Google Messages)
            if (incoming.any { val t = it.text?.toString().orEmpty(); Reactions.parse(t) == null })
                dao.meta(key)?.takeIf { it.archived }?.let { dao.putMeta(it.copy(archived = false)) }
            Hub.changed()

            // Swap Google Messages' notification for Orbit's, only after any files were copied
            // (hiding it first can take away Orbit's access to the photo)
            if (s.handleNotifications && incoming.isNotEmpty()) {
                notifKeys.remove(sbn.key)   // Orbit removing it isn't Google Messages confirming a reply
                runCatching { cancelNotification(sbn.key) }
                if (Hub.openConv != key) Notifier.showMessage(this@MessagesListener, key, title, isGroup,
                    msgs.takeLast(6).map { (it.person?.name?.toString() ?: "You") to (it.text?.toString().orEmpty()) }
                        .filter { !Sender.isReactionText(it.second) },
                    mention = isGroup && s.myName.isNotBlank() && incoming.any { it.text?.toString()?.contains(s.myName, ignoreCase = true) == true })
            }

            // Auto-reply: plain text, no branding, at most once every 15 minutes per chat.
            // Not to reactions ("Liked …"), and not to short codes (banks, 2FA, carriers).
            val realText = incoming.any { val t = it.text?.toString().orEmpty(); t.isNotBlank() && Reactions.parse(t) == null }
            val shortCode = key.all { it.isDigit() } && key.length < 7
            if (s.autoReplyOn && realText && !shortCode && !isGroup) {
                val last = prefs.getLong("auto:$key", 0)
                if (System.currentTimeMillis() - last > 15 * 60_000) {
                    prefs.edit().putLong("auto:$key", System.currentTimeMillis()).apply()
                    SendQueue.add(this@MessagesListener, key, s.autoReplyText, drive = false)
                }
            }
        }

    }
    }

    /** A group whose full member list we don't know (only who talked recently). */
    private fun markPartial(key: String) = prefs.edit().putStringSet("partial", prefs.getStringSet("partial", emptySet())!! + key).apply()

    /**
     * Match a notification to a conversation.
     *
     * Google Messages gives every conversation its own shortcut id, so that's the identity: the same
     * id is always the same chat, even when two groups (or two people) share a name. Orbit's key for
     * it is a phone-number key when it knows the numbers, otherwise a name key ("g:" group, "n:"
     * person). Keys get upgraded later (name -> number, recent-senders -> full group) and the chat's
     * messages move along with them.
     */
    private fun convKeyFor(shortcut: String?, title: String, people: List<androidx.core.app.Person?>, isGroup: Boolean): String {
        val sc = shortcut?.takeIf { it.isNotBlank() }
        val known = sc?.let { prefs.getString("sc:$it", null) }
        if (known != null) {
            val better = upgrade(known, sc, title, people, isGroup)
            if (better != known) remember(sc, better, tentative = better.contains(',') && isPartialGroup(this, better))
            return better
        }
        var key = fresh(title, people, isGroup, useLegacy = true, keepTitle = sc == null)
        // Legacy keys from before shortcut ids were used: one-on-ones keyed "g:Name" are really "n:Name"
        if (!isGroup && key.startsWith("n:")) migrateLater("g:${title}", key)
        if (sc != null) {
            // Already taken by a different conversation with the same name: give this one its own key
            fun takenByOther(k: String) = prefs.getString("claim:$k", null).let { it != null && it != sc }
            if (takenByOther(key) && !Phone.isNameKey(key)) key = fresh(title, people, isGroup, useLegacy = false, keepTitle = false)
            if (takenByOther(key) && Phone.isNameKey(key)) key = key + "#sc" + Integer.toHexString(sc.hashCode())
            remember(sc, key, tentative = key.contains(',') && isPartialGroup(this, key))
        }
        return key
    }

    private fun remember(sc: String, key: String, tentative: Boolean) {
        prefs.edit().putString("sc:$sc", key).putString("claim:$key", sc).putBoolean("tent:$sc", tentative).apply()
    }

    /** Best key for a new conversation. */
    private fun fresh(title: String, people: List<androidx.core.app.Person?>, isGroup: Boolean, useLegacy: Boolean, keepTitle: Boolean): String {
        if (isGroup) {
            // Old title mapping (from before shortcut ids): a real group wins outright; a guess is kept only
            // when nothing better is known, so the chat doesn't split from its history
            val legacy = if (useLegacy && title.isNotBlank()) prefs.getString("grp:$title", null) else null
            if (legacy != null && !isPartialGroup(this, legacy) && !legacy.startsWith("g:")) return legacy
            val key = resolveGroup(title, people) ?: legacy ?: run {
                val numbers = people.mapNotNull { p -> numberOf(p) }.distinct()
                if (numbers.size >= 2) Phone.convKey(numbers).also { markPartial(it) } else "g:$title"
            }
            // No shortcut id to remember it by: remember by title (as before) so it doesn't split
            if (keepTitle && title.isNotBlank()) prefs.edit().putString("grp:$title", key).apply()
            return key
        }
        people.firstNotNullOfOrNull { numberOf(it) }?.let { return Phone.convKey(listOf(it)) }
        Hub.contacts.byName(title)?.let { return Phone.convKey(listOf(it.number)) }
        val digits = title.filter { it.isDigit() }
        if (digits.length >= 5 && title.none { it.isLetter() }) return Phone.convKey(listOf(digits))
        return if (title.isNotBlank()) "n:$title" else ""
    }

    /** The real group text that has everyone who's talking in it, if there's exactly one. */
    private fun resolveGroup(title: String, people: List<androidx.core.app.Person?>): String? {
        val numbers = people.mapNotNull { p -> numberOf(p) }.distinct()
        if (numbers.size < 2) return null
        val wanted = numbers.map { Phone.norm(it) }.toSet()
        return com.yatesv.contacthub.ui.InboxData.cache?.filter { it.isGroup && it.addresses.map { a -> Phone.norm(a) }.containsAll(wanted) }
            ?.singleOrNull()?.key
    }

    /** A remembered key that can now be improved. */
    private fun upgrade(known: String, sc: String, title: String, people: List<androidx.core.app.Person?>, isGroup: Boolean): String {
        val better = when {
            isGroup && prefs.getBoolean("tent:$sc", false) -> resolveGroup(title, people) ?: known
            isGroup && known.startsWith("g:") -> resolveGroup(title, people) ?: known   // now we know who's in it
            !isGroup && Phone.isNameKey(known) -> people.firstNotNullOfOrNull { numberOf(it) }?.let { Phone.convKey(listOf(it)) }
                ?: Hub.contacts.byName(title)?.let { Phone.convKey(listOf(it.number)) }
                ?: if (known.startsWith("g:")) "n:" + known.drop(2) else known
            else -> known
        }
        if (better != known) migrateLater(known, better)
        return better
    }

    /** Moves a chat's Orbit-side messages, reactions and settings to its new key. */
    private fun migrateLater(from: String, to: String) {
        if (from == to) return
        Hub.replies[from]?.let { Hub.replies.putIfAbsent(to, it) }
        val seen = prefs.getLong("seen:$from", 0)
        if (seen > prefs.getLong("seen:$to", 0)) prefs.edit().putLong("seen:$to", seen).apply()
        Hub.scope.launch {
            runCatching {
                val dao = Hub.db.dao()
                dao.moveLocal(from, to)
                dao.moveReactions(from, to)
                dao.meta(from)?.let { m -> if (dao.meta(to) == null) dao.putMeta(m.copy(key = to)) }
                HubLog.d("Listener", "moved chat ${from.substringBefore(':', "num")} -> ${to.substringBefore(':', "num")}")
                Hub.changed()
            }.onFailure { HubLog.w("Listener", "move failed", it) }
        }
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
