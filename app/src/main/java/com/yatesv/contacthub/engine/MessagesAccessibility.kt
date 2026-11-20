package com.yatesv.contacthub.engine

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.yatesv.contacthub.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class DriveResult(val clicked: Boolean, val chatType: String = "")

/**
 * Taps Send (or a reaction) inside Google/Samsung Messages for you, so messages go out over RCS
 * exactly as if you'd used that app. It only looks at those two apps.
 *
 * This depends on how those apps' screens are built, so a Messages update can break it.
 * When it can't find the Send button it reports "not sent" and nothing is sent twice.
 */
class MessagesAccessibility : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    private fun root(pkg: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.takeIf { it.packageName == pkg }?.let { return it }
        return windows.mapNotNull { it.root }.firstOrNull { it.packageName == pkg }
    }

    private fun all(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo> = ArrayList()): List<AccessibilityNodeInfo> {
        if (node == null) return out
        out += node
        for (i in 0 until node.childCount) all(node.getChild(i), out)
        return out
    }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) return cur
            cur = cur.parent; depth++
        }
        return null
    }

    private val sendIds = listOf(
        "com.google.android.apps.messaging:id/send_message_button_icon",
        "com.google.android.apps.messaging:id/send_message_button_container",
        "com.google.android.apps.messaging:id/send_message_button",
        "com.samsung.android.messaging:id/send_button1",
        "com.samsung.android.messaging:id/send_button",
    )

    private fun findSend(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in sendIds) root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return it }
        return all(root).firstOrNull { n ->
            val d = n.contentDescription?.toString()?.lowercase().orEmpty()
            d.startsWith("send") && !d.contains("sticker") && !d.contains("gif") && !d.contains("emoji")
        }
    }

    /** "rcs" / "sms" from the Send button's label ("Send RCS message", "Send end-to-end encrypted message", "Send SMS"). */
    private fun chatType(send: AccessibilityNodeInfo?): String {
        val d = send?.contentDescription?.toString()?.lowercase().orEmpty()
        return when {
            "rcs" in d || "encrypted" in d || "chat" in d -> "rcs"
            "sms" in d || "mms" in d || "text" in d -> "sms"
            else -> ""
        }
    }

    private fun backToHub() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }

    private fun openChat(pkg: String, addresses: List<String>, body: String?, media: List<Uri>) {
        val to = addresses.joinToString(";")
        val intent = if (media.isEmpty()) {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to")).apply { if (!body.isNullOrEmpty()) putExtra("sms_body", body) }
        } else {
            Intent(if (media.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putExtra("address", to)
                if (!body.isNullOrEmpty()) { putExtra("sms_body", body); putExtra(Intent.EXTRA_TEXT, body) }
                if (media.size == 1) putExtra(Intent.EXTRA_STREAM, media[0])
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(media))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        intent.setPackage(pkg)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        startActivity(intent)
    }

    private suspend fun doSend(pkg: String, addresses: List<String>, body: String, media: List<Uri>): DriveResult {
        openChat(pkg, addresses, body, media)
        var clicked = false
        var type = ""
        withTimeoutOrNull(9000) {
            while (true) {
                delay(250)
                val r = root(pkg) ?: continue
                // Make sure our text is actually in the box before tapping Send
                if (body.isNotEmpty() && media.isEmpty()) {
                    val hasText = all(r).any { it.isEditable && it.text?.toString()?.trim() == body }
                    if (!hasText) continue
                }
                val send = findSend(r) ?: continue
                val target = clickable(send) ?: send
                if (!target.isEnabled) continue
                type = chatType(send)
                clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                break
            }
        }
        delay(500)
        backToHub()
        return DriveResult(clicked, type)
    }

    private val emojiNames = mapOf(
        "❤️" to listOf("heart", "love"), "😂" to listOf("laugh", "tears of joy", "haha"),
        "👍" to listOf("thumbs up", "like"), "👎" to listOf("thumbs down", "dislike"),
        "😮" to listOf("surprised", "wow", "open mouth"), "😢" to listOf("crying", "sad", "tear"),
        "😡" to listOf("angry", "pouting"), "‼️" to listOf("exclamation", "emphasize"), "❓" to listOf("question"),
    )

    private suspend fun doReact(pkg: String, addresses: List<String>, body: String, emoji: String): DriveResult {
        openChat(pkg, addresses, null, emptyList())
        var result = DriveResult(false)
        withTimeoutOrNull(9000) {
            var bubble: AccessibilityNodeInfo? = null
            while (bubble == null) {
                delay(300)
                val r = root(pkg) ?: continue
                val send = findSend(r)
                val type = chatType(send)
                // Only react on RCS. On SMS the Messages app would send it as text ("Liked ..."), which looks fake.
                if (type == "sms") { result = DriveResult(false, "sms"); return@withTimeoutOrNull }
                if (type.isEmpty()) continue
                result = DriveResult(false, type)
                bubble = all(r).lastOrNull { n ->
                    !n.isEditable && (n.text?.toString()?.trim() == body.trim() || n.contentDescription?.toString()?.contains(body.trim()) == true)
                }
            }
            val target = clickable(bubble!!) ?: bubble!!
            target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            while (true) {
                delay(250)
                val r = root(pkg) ?: continue
                val names = emojiNames[emoji].orEmpty()
                val chip = all(r).firstOrNull { n ->
                    val t = n.text?.toString().orEmpty()
                    val d = n.contentDescription?.toString()?.lowercase().orEmpty()
                    t == emoji || d == emoji || names.any { d.contains(it) }
                } ?: continue
                result = result.copy(clicked = (clickable(chip) ?: chip).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                break
            }
        }
        delay(400)
        backToHub()
        return result
    }

    companion object {
        @Volatile private var instance: MessagesAccessibility? = null
        private val lock = Mutex()

        fun running() = instance != null

        fun enabledInSettings(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(ctx, MessagesAccessibility::class.java).flattenToString()
            return flat.split(':').any { it.equals(me, ignoreCase = true) }
        }

        suspend fun sendThrough(ctx: Context, pkg: String, addresses: List<String>, body: String, media: List<Uri> = emptyList()): DriveResult =
            lock.withLock {
                val svc = instance ?: return DriveResult(false)
                withContext(Dispatchers.Main) { runCatching { svc.doSend(pkg, addresses, body, media) }.getOrDefault(DriveResult(false)) }
            }

        suspend fun reactThrough(ctx: Context, pkg: String, addresses: List<String>, body: String, emoji: String): DriveResult =
            lock.withLock {
                val svc = instance ?: return DriveResult(false)
                withContext(Dispatchers.Main) { runCatching { svc.doReact(pkg, addresses, body, emoji) }.getOrDefault(DriveResult(false)) }
            }
    }
}
