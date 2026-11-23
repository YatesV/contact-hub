package com.yatesv.contacthub.engine

import android.content.Context
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.HubLog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every message Orbit sends goes through here, one at a time, in order.
 * You can keep typing and sending; each shows faded until it's really sent.
 *
 * States:
 *  - waiting / sending / sent
 *  - failed: definitely didn't send. Later messages in that chat wait (so nothing arrives out of
 *    order) until you tap Try again or Delete.
 *  - unconfirmed: Send was tapped in Google Messages but Orbit couldn't confirm it. Never retried
 *    automatically (it could go out twice); check Google Messages.
 *  - held: couldn't go over RCS right now (phone locked, sent from a notification or schedule, no
 *    reply button yet). It waits, and goes as soon as Google Messages can be reached: a new
 *    notification from that chat, unlocking the phone, or opening Orbit. Never sent as SMS instead.
 *
 * The queue is saved, so nothing is lost if Android closes Orbit.
 */
object SendQueue {
    data class Item(
        val id: Long, val key: String, val body: String, val created: Long,
        val state: String = "waiting", val error: String = "", val sentAt: Long = 0,
        /** May open Google Messages to send (only from the Orbit screen, never from the laptop page) */
        val drive: Boolean = true,
        /** Numbers to send to (empty = from the chat key) */
        val addresses: List<String> = emptyList(),
        /** Text of the message this replies to (sent as a real RCS reply when possible) */
        val replyTo: String? = null,
        /** Photos/videos/files (content: or file: uris) */
        val uris: List<String> = emptyList(),
        /** Woken by unlocking the phone / opening Orbit: may open Google Messages once */
        val forceDrive: Boolean = false,
        /** Came from the background (schedule, notification reply, auto-reply): tell the person if it's held */
        val background: Boolean = false,
    )

    val items = MutableStateFlow<List<Item>>(emptyList())
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var started = false
    private var restored = false
    private var nextId = System.currentTimeMillis()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("sendqueue", Context.MODE_PRIVATE)

    @Synchronized fun add(ctx: Context, key: String, body: String, drive: Boolean = true, addresses: List<String> = emptyList(),
                          replyTo: String? = null, uris: List<String> = emptyList(), background: Boolean = false): Item {
        restore(ctx)
        val item = Item(nextId++, key, body.trim(), System.currentTimeMillis(), drive = drive, addresses = addresses,
            replyTo = replyTo, uris = uris, background = background)
        items.update { it + item }
        save(ctx)
        start(ctx)
        wake.trySend(Unit)
        return item
    }

    fun retry(id: Long) { items.update { l -> l.map { if (it.id == id) it.copy(state = "waiting", error = "") else it } }; persist(); wake.trySend(Unit) }

    fun cancel(id: Long) { items.update { l -> l.filterNot { it.id == id } }; persist(); wake.trySend(Unit) }

    /**
     * Held messages get another go. [key] = only that chat (a fresh reply button arrived);
     * [drive] = the phone was just unlocked / Orbit opened, so Google Messages may be opened.
     */
    fun wakeHeld(ctx: Context, key: String? = null, drive: Boolean = false) {
        restore(ctx)
        var any = false
        items.update { l -> l.map { if (it.state == "held" && (key == null || it.key == key)) { any = true; it.copy(state = "waiting", forceDrive = drive || it.forceDrive) } else it } }
        if (!any) return
        save(ctx); start(ctx); wake.trySend(Unit)
    }

    fun heldCount() = items.value.count { it.state == "held" }

    private fun set(id: Long, f: (Item) -> Item) { items.update { l -> l.map { if (it.id == id) f(it) else it } }; persist() }

    private fun persist() { if (Hub.isReady()) save(Hub.app) }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        items.value.filter { it.state != "sent" }.forEach { i ->
            arr.put(JSONObject().put("id", i.id).put("key", i.key).put("body", i.body).put("created", i.created)
                .put("state", i.state).put("error", i.error).put("drive", i.drive).put("bg", i.background)
                .put("addresses", JSONArray(i.addresses)).put("replyTo", i.replyTo ?: JSONObject.NULL).put("uris", JSONArray(i.uris)))
        }
        prefs(ctx).edit().putString("items", arr.toString()).apply()
    }

    /** Loads what was waiting when Orbit was last closed, and starts sending it. */
    @Synchronized fun restore(ctx: Context) {
        if (restored) return
        restored = true
        val raw = prefs(ctx).getString("items", null) ?: return
        val list = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { n ->
                val o = arr.getJSONObject(n)
                fun strs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                val st = o.optString("state", "waiting")
                Item(o.getLong("id"), o.getString("key"), o.getString("body"), o.getLong("created"),
                    // Closed mid-send: it may or may not have gone. Don't send it again on our own.
                    state = if (st == "sending") "unconfirmed" else st,
                    error = if (st == "sending") "Orbit was closed while sending this. Check Google Messages." else o.optString("error"),
                    drive = o.optBoolean("drive", true), background = o.optBoolean("bg"),
                    addresses = strs("addresses"), replyTo = o.optString("replyTo").takeIf { !o.isNull("replyTo") && it.isNotEmpty() },
                    uris = strs("uris"))
            }
        }.getOrElse { HubLog.w("Queue", "couldn't restore", it); emptyList() }
        if (list.isEmpty()) return
        nextId = maxOf(nextId, list.maxOf { it.id } + 1)
        items.update { list + it }
        start(ctx)
        wake.trySend(Unit)
    }

    private fun updateHeldNote(ctx: Context) {
        val held = items.value.filter { it.state == "held" }
        if (held.isEmpty()) Notifier.cancelStatus(ctx, 7104)
        else if (held.any { it.background }) Notifier.status(ctx, 7104, "Waiting to send over RCS",
            "${held.size} message${if (held.size > 1) "s" else ""} will send as soon as Google Messages can be reached (unlock your phone or open Orbit). " +
                "${if (held.size > 1) "They" else "It"} won't go as a plain text.")
    }

    @Synchronized private fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        Hub.scope.launch {
            while (true) {
                // Drop "sent" items after a while (the real message has shown up by then)
                items.update { l -> l.filterNot { it.state == "sent" && System.currentTimeMillis() - it.sentAt > 15_000 } }
                val list = items.value
                // A failed or waiting-for-RCS message holds back the ones after it in the same chat
                val blocked = list.filter { it.state == "failed" || it.state == "held" }.map { it.key }.toSet()
                val next = list.firstOrNull { it.state == "waiting" && it.key !in blocked }
                if (next == null) {
                    kotlinx.coroutines.withTimeoutOrNull(15_000) { wake.receive() }
                    continue
                }
                set(next.id) { it.copy(state = "sending") }
                // Only drive Google Messages while you're looking at Orbit (not after you pressed Home)
                val drive = (next.drive && AppState.foreground) || next.forceDrive
                val r = runCatching {
                    if (next.uris.isNotEmpty())
                        Sender.sendMedia(app, next.key, next.uris.map { android.net.Uri.parse(it) }, next.body, allowDriving = drive, queued = true)
                    else Sender.send(app, next.key, next.body, next.addresses.ifEmpty { Sender.addressesOf(next.key) },
                        allowDriving = drive, replyTo = next.replyTo, queued = true)
                }.getOrElse { SendResult(false, Route.NONE, it.message ?: "Error") }
                when {
                    r.route == Route.HELD -> set(next.id) { it.copy(state = "held", error = r.error, forceDrive = false) }
                    r.ok -> set(next.id) { it.copy(state = "sent", sentAt = System.currentTimeMillis()) }
                    r.uncertain -> set(next.id) { it.copy(state = "unconfirmed", error = r.error) }
                    else -> {
                        HubLog.w("Queue", "send failed: ${r.error}")
                        set(next.id) { it.copy(state = "failed", error = r.error) }
                        if (next.background) Notifier.status(app, next.id.toInt(), "Message not sent", r.error)
                    }
                }
                updateHeldNote(app)
                Hub.changed()
            }
        }
    }
}

/** Whether an Orbit screen is in front right now. */
object AppState {
    @Volatile var foreground = false
}
