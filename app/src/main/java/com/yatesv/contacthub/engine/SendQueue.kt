package com.yatesv.contacthub.engine

import android.content.Context
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.HubLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Messages you've hit Send on, sent one at a time in order, in the background.
 * You can keep typing and sending; each shows faded until it's really sent.
 * If one fails, the ones after it in that chat wait (so nothing arrives out of order) until you
 * tap Retry or Delete on the failed one.
 */
object SendQueue {
    data class Item(
        val id: Long, val key: String, val body: String, val created: Long,
        /** waiting, sending, sent, failed */
        val state: String = "waiting", val error: String = "", val sentAt: Long = 0,
    )

    val items = MutableStateFlow<List<Item>>(emptyList())
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var started = false
    private var nextId = System.currentTimeMillis()

    fun add(ctx: Context, key: String, body: String): Item {
        val item = Item(nextId++, key, body, System.currentTimeMillis())
        items.update { it + item }
        start(ctx)
        wake.trySend(Unit)
        return item
    }

    fun retry(id: Long) { items.update { l -> l.map { if (it.id == id) it.copy(state = "waiting", error = "") else it } }; wake.trySend(Unit) }

    fun cancel(id: Long) { items.update { l -> l.filterNot { it.id == id } }; wake.trySend(Unit) }

    private fun set(id: Long, f: (Item) -> Item) = items.update { l -> l.map { if (it.id == id) f(it) else it } }

    @Synchronized private fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Hub.scope.launch {
            while (true) {
                // Drop "sent" items after a while (the real message has shown up by then)
                items.update { l -> l.filterNot { it.state == "sent" && System.currentTimeMillis() - it.sentAt > 15_000 } }
                val list = items.value
                val blocked = list.filter { it.state == "failed" }.map { it.key }.toSet()
                val next = list.firstOrNull { it.state == "waiting" && it.key !in blocked }
                if (next == null) {
                    kotlinx.coroutines.withTimeoutOrNull(15_000) { wake.receive() }
                    continue
                }
                set(next.id) { it.copy(state = "sending") }
                val r = runCatching { Sender.send(app, next.key, next.body) }.getOrElse { SendResult(false, Route.NONE, it.message ?: "Error") }
                if (r.ok) set(next.id) { it.copy(state = "sent", sentAt = System.currentTimeMillis()) }
                else {
                    HubLog.w("Queue", "send failed: ${r.error}")
                    set(next.id) { it.copy(state = "failed", error = r.error) }
                }
                Hub.changed()
            }
        }
    }
}
