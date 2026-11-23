package com.yatesv.contacthub.engine

import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.HubLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Checks that a message sent through Google Messages' notification reply button really went.
 *
 * After a reply, Google Messages either updates its notification with your message or removes the
 * notification. If neither happens within [WAIT_MS], the message is marked "not confirmed" in the
 * chat so you know to check Google Messages. It's never re-sent automatically (it might have gone).
 */
object ReplyWatch {
    private const val WAIT_MS = 25_000L
    private data class Pending(val localId: Long, val body: String, val at: Long)
    private val pending = ConcurrentHashMap<String, MutableList<Pending>>()

    fun expect(convKey: String, localId: Long, body: String) {
        val p = Pending(localId, body.trim(), System.currentTimeMillis())
        pending.getOrPut(convKey) { java.util.Collections.synchronizedList(ArrayList()) }.add(p)
        Hub.scope.launch {
            delay(WAIT_MS)
            val list = pending[convKey] ?: return@launch
            if (list.remove(p)) {
                HubLog.w("Send", "reply button send not confirmed by Google Messages")
                runCatching { Hub.db.dao().setLocalSource(localId, "unconfirmed") }
                Hub.changed()
            }
        }
    }

    /** Google Messages showed your message in its notification: that one went. */
    fun sawOutgoing(convKey: String, text: String) {
        val t = text.trim()
        pending[convKey]?.removeAll { it.body == t }
    }

    /** Google Messages took its notification away after the reply: treat waiting replies as sent. */
    fun notificationRemoved(convKey: String) {
        pending[convKey]?.removeAll { System.currentTimeMillis() - it.at < WAIT_MS }
    }
}
