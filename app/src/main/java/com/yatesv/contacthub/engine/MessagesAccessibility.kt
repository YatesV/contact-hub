package com.yatesv.contacthub.engine

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.HubLog
import com.yatesv.contacthub.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What happened when we drove the Messages app.
 * [clicked] = definitely sent. [why] = short reason when it didn't, shown to the user.
 */
data class DriveResult(val clicked: Boolean, val chatType: String = "", val why: String = "", val uncertain: Boolean = false)

/**
 * Taps Send (or a reaction) inside Google/Samsung Messages for you, so messages go out over RCS
 * exactly as if you'd used that app. It only looks at those two apps.
 *
 * It checks the text box actually emptied after tapping Send, so it knows for sure whether the
 * message went. If it didn't, it clears the text box (so nothing gets sent later by accident)
 * and reports failure; the caller then falls back to SMS.
 */
class MessagesAccessibility : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    /** Every window of the Messages app (a dialog can sit on top of the chat). */
    private fun pkgRoots(pkg: String): List<AccessibilityNodeInfo> =
        runCatching { windows.mapNotNull { it.root }.filter { it.packageName == pkg } }.getOrDefault(emptyList())

    /** The chat's text box, from whichever window has it. Null if no window has one (e.g. a popup replaced the chat). */
    private fun chatInput(pkg: String): AccessibilityNodeInfo? =
        pkgRoots(pkg).ifEmpty { listOfNotNull(root(pkg)) }.firstNotNullOfOrNull { findInput(all(it)) }

    // ---------------- Cover: hide Google Messages while it sends ----------------

    private var cover: android.view.View? = null
    private var patch: android.view.View? = null
    private var mirrorOwner: OverlayOwner? = null
    private var shot: android.graphics.Bitmap? = null

    /** Which hiding method to use: your pick in settings, or the one the self-test found works best. */
    private fun method(forced: String?): String {
        val s = Hub.settings.value
        if (forced != null) return forced
        if (!s.coverWhileSending || android.os.Build.VERSION.SDK_INT < 30) return "none"
        return s.hideMethod.takeIf { it != "auto" } ?: s.hideWinner.ifBlank { "snapshot" }
    }

    private suspend fun takeShot(): android.graphics.Bitmap? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        runCatching {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val b = runCatching { android.graphics.Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }.getOrNull()
                    r.hardwareBuffer.close()
                    if (cont.isActive) cont.resumeWith(Result.success(b))
                }
                override fun onFailure(code: Int) { if (cont.isActive) cont.resumeWith(Result.success(null)) }
            })
        }.onFailure { if (cont.isActive) cont.resumeWith(Result.success(null)) }
    }

    private fun overlayParams(x: Int = 0, y: Int = 0, w: Int = android.view.WindowManager.LayoutParams.MATCH_PARENT, h: Int = android.view.WindowManager.LayoutParams.MATCH_PARENT) =
        android.view.WindowManager.LayoutParams(w, h,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            this.x = x; this.y = y
            windowAnimations = 0
        }

    private fun addOverlay(v: android.view.View, lp: android.view.WindowManager.LayoutParams) =
        runCatching { getSystemService(android.view.WindowManager::class.java).addView(v, lp); true }.getOrElse { HubLog.w("Cover", "overlay failed", it); false }

    private fun removeView(v: android.view.View?) { if (v != null) runCatching { getSystemService(android.view.WindowManager::class.java).removeView(v) } }

    /**
     * Snapshot: a screenshot of what you're looking at (Orbit, keyboard and all) laid over the
     * whole screen, so Google Messages working underneath is invisible. Touches are absorbed.
     */
    private suspend fun showCover(): Boolean {
        val bmp = takeShot() ?: run { HubLog.w("Cover", "screenshot failed"); return false }
        shot = bmp
        val v = android.widget.ImageView(this).apply { setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.FIT_XY; isClickable = true }
        return addOverlay(v, overlayParams()).also { if (it) cover = v }
    }

    /** Live mirror: an animated copy of the chat on top (your message pulses until it's sent). */
    private fun showMirror(convKey: String): Boolean {
        if (convKey.isBlank()) return false
        return runCatching {
            val (v, owner) = mirrorView(this, convKey)
            mirrorOwner = owner
            addOverlay(v, overlayParams()).also { if (it) cover = v }
        }.getOrElse { HubLog.w("Cover", "mirror failed", it); false }
    }

    /** Pop-up worked: drop the full cover so Orbit is live, and only hide the little window. */
    private fun shrinkCoverTo(r: android.graphics.Rect) {
        val bmp = shot ?: return
        val crop = runCatching {
            android.graphics.Bitmap.createBitmap(bmp, r.left.coerceIn(0, bmp.width - 1), r.top.coerceIn(0, bmp.height - 1),
                r.width().coerceAtMost(bmp.width - r.left.coerceAtLeast(0)).coerceAtLeast(1), r.height().coerceAtMost(bmp.height - r.top.coerceAtLeast(0)).coerceAtLeast(1))
        }.getOrNull() ?: return
        val v = android.widget.ImageView(this).apply { setImageBitmap(crop); scaleType = android.widget.ImageView.ScaleType.FIT_XY; isClickable = true }
        if (addOverlay(v, overlayParams(r.left, r.top, r.width(), r.height()))) patch = v
        removeView(cover); cover = null
    }

    /** Bounds of the Messages window, if it's on screen. */
    private fun messagesBounds(pkg: String): android.graphics.Rect? = runCatching {
        windows.firstOrNull { it.root?.packageName == pkg }?.let { w -> android.graphics.Rect().also { w.getBoundsInScreen(it) } }
    }.getOrNull()

    /** Last send's hiding result, for the self-test. */
    var lastHide = ""

    private suspend fun hideCover() {
        removeView(patch); patch = null
        mirrorOwner?.destroy(); mirrorOwner = null
        shot = null
        val v = cover ?: return
        cover = null
        // Wait until Orbit is really back on screen underneath, then fade the cover away
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 1500) {
            if (runCatching { rootInActiveWindow?.packageName == packageName }.getOrDefault(false)) break
            delay(50)
        }
        delay(60)
        runCatching {
            v.animate().alpha(0f).setDuration(120).withEndAction {
                runCatching { getSystemService(android.view.WindowManager::class.java).removeView(v) }
            }.start()
        }.onFailure { runCatching { getSystemService(android.view.WindowManager::class.java).removeView(v) } }
    }

    private fun root(pkg: String): AccessibilityNodeInfo? {
        runCatching { rootInActiveWindow?.takeIf { it.packageName == pkg }?.let { return it } }
        return runCatching { windows.mapNotNull { it.root }.firstOrNull { it.packageName == pkg } }.getOrNull()
    }

    private fun all(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo> = ArrayList(), depth: Int = 0): List<AccessibilityNodeInfo> {
        if (node == null || depth > 40 || out.size > 3000) return out
        out += node
        for (i in 0 until node.childCount) runCatching { all(node.getChild(i), out, depth + 1) }
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

    private fun label(n: AccessibilityNodeInfo) =
        (n.contentDescription?.toString() ?: n.text?.toString() ?: "").trim().lowercase()

    private fun findSend(root: AccessibilityNodeInfo, nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        for (id in sendIds) runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()?.firstOrNull()?.let { return it }
        val candidates = nodes.filter { n ->
            if (n.isEditable) return@filter false
            val l = label(n)
            val id = n.viewIdResourceName?.lowercase().orEmpty()
            val byLabel = l.startsWith("send") && listOf("sticker", "gif", "emoji", "photo", "location", "money", "file").none { it in l }
            val byId = id.substringAfterLast('/').let { it.contains("send") && !it.contains("sticker") }
            byLabel || byId
        }
        return candidates.firstOrNull { it.isVisibleToUser && (clickable(it)?.isEnabled ?: it.isEnabled) } ?: candidates.firstOrNull()
    }

    private fun findInput(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? =
        nodes.firstOrNull { it.isEditable && it.isVisibleToUser } ?: nodes.firstOrNull { it.className?.toString()?.contains("EditText") == true }

    /** The real text in the box (not the grey hint). */
    private fun inputText(n: AccessibilityNodeInfo?): String {
        if (n == null) return ""
        val t = n.text?.toString().orEmpty()
        return if (n.isShowingHintText || t == n.hintText?.toString()) "" else t
    }

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** "rcs" / "sms" from the Send button label or the text box hint ("RCS message", "Text message"). */
    private fun chatType(send: AccessibilityNodeInfo?, input: AccessibilityNodeInfo?): String {
        val labels = listOfNotNull(send?.contentDescription?.toString(), input?.hintText?.toString(),
            if (input?.isShowingHintText == true) input.text?.toString() else null).joinToString(" ").lowercase()
        return when {
            "rcs" in labels || "encrypted" in labels || "chat message" in labels -> "rcs"
            "sms" in labels || "mms" in labels || "text message" in labels -> "sms"
            else -> ""
        }
    }

    private fun setText(n: AccessibilityNodeInfo, text: String) =
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })

    /** Orbit was in front when this send started. If not, don't pull it up afterwards. */
    @Volatile private var returnToOrbit = true

    private fun backToHub(pkg: String) {
        if (!returnToOrbit) { if (root(pkg) != null) performGlobalAction(GLOBAL_ACTION_BACK); return }
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }.onFailure { if (root(pkg) != null) performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    private fun openChat(pkg: String, addresses: List<String>, body: String?, media: List<Uri>, popup: Boolean = false) {
        val to = addresses.joinToString(";")
        val intent = if (media.isEmpty()) {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to")).apply { if (!body.isNullOrEmpty()) putExtra("sms_body", body) }
        } else {
            Intent(if (media.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                // The real type (voice notes, GIFs, contact cards, videos), so Google Messages attaches it properly
                val types = media.map { contentResolver.getType(it) ?: "*/*" }.distinct()
                type = types.singleOrNull() ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
                putExtra("address", to)
                if (!body.isNullOrEmpty()) { putExtra("sms_body", body); putExtra(Intent.EXTRA_TEXT, body) }
                if (media.size == 1) putExtra(Intent.EXTRA_STREAM, media[0])
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(media))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        intent.setPackage(pkg)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (popup) {
            // Ask for a tiny window in the bottom corner (Samsung pop-up view). If the phone ignores it, it opens full screen under the cover.
            val m = resources.displayMetrics
            val size = (m.widthPixels * 0.18f).toInt()
            val opts = android.app.ActivityOptions.makeBasic().setLaunchBounds(android.graphics.Rect(m.widthPixels - size, m.heightPixels - size * 2, m.widthPixels, m.heightPixels - size))
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            runCatching { startActivity(intent, opts.toBundle()) }.onFailure { startActivity(intent) }
        } else startActivity(intent)
    }

    private suspend fun doSend(pkg: String, addresses: List<String>, body: String, media: List<Uri>, convKey: String, forced: String?): DriveResult {
        returnToOrbit = AppState.foreground
        val want = norm(body)
        val how = method(forced)
        delay(80)               // let the faded "sending" bubble appear first, so it's in the snapshot
        val covered = when (how) {
            "mirror" -> showMirror(convKey) || showCover()
            "snapshot", "popup" -> showCover()
            else -> false
        }
        var popupOk = false
        try {
            openChat(pkg, addresses, body, media, popup = how == "popup")
            if (how == "popup") {
                // Did Samsung open it as a small window? Then Orbit can stay live.
                val t = System.currentTimeMillis()
                while (System.currentTimeMillis() - t < 2500) {
                    val r = messagesBounds(pkg)
                    if (r != null) {
                        val m = resources.displayMetrics
                        popupOk = r.width() * r.height() < m.widthPixels * m.heightPixels * 0.4f
                        if (popupOk) shrinkCoverTo(r)
                        break
                    }
                    delay(80)
                }
                HubLog.d("Cover", "pop-up window ${if (popupOk) "worked" else "not supported, opened full screen under the cover"}")
                if (!popupOk && forced == null && Hub.settings.value.hideMethod == "auto") Hub.settings.update { it.copy(hideWinner = "snapshot") }
            }
            lastHide = "$how${if (how == "popup") (if (popupOk) " (small window OK)" else " (fell back to cover)") else ""}${if (!covered && how != "none") " (cover failed)" else ""}"
            val start = System.currentTimeMillis()
            var typedOurselves = false
            var type = ""
            // 1. Wait for the chat to open with our text in the box, and for Send to be tappable
            var send: AccessibilityNodeInfo? = null
            while (System.currentTimeMillis() - start < 9000) {
                // Poll fast at first (the chat usually opens in a few hundred ms), then back off to save battery
                delay(if (System.currentTimeMillis() - start < 1500) 50 else 150)
                val r = root(pkg) ?: continue
                val nodes = all(r)
                val input = findInput(nodes)
                if (media.isEmpty() && want.isNotEmpty()) {
                    val now = norm(inputText(input))
                    if (input != null && now != want) {
                        // Some versions ignore the pre-filled text; after a moment, type it in ourselves
                        if (!typedOurselves && System.currentTimeMillis() - start > 2000) { setText(input, body); typedOurselves = true }
                        continue
                    }
                }
                val s = findSend(r, nodes) ?: continue
                val target = clickable(s) ?: s
                if (!target.isEnabled) continue
                type = chatType(s, input)
                send = target
                break
            }
            if (send == null) {
                root(pkg)?.let { r ->
                    val nodes = all(r)
                    val input = findInput(nodes)
                    HubLog.w("RcsSender", "no Send button. window=${r.packageName} nodes=${nodes.size} input=${input != null} " +
                        "inputLen=${inputText(input).length} wantLen=${want.length} hasHint=${!input?.hintText.isNullOrEmpty()} " +
                        "ids=${nodes.mapNotNull { n -> n.viewIdResourceName?.substringAfterLast('/') }.distinct().take(30)} " +
                        "buttons=${nodes.filter { it.isClickable }.mapNotNull { it.className?.toString()?.substringAfterLast('.') }.groupingBy { it }.eachCount()}")
                } ?: HubLog.w("RcsSender", "Messages window never appeared (pkg=$pkg)")
                clearDraft(pkg)
                return DriveResult(false, type, "couldn't find the Send button")
            }
            // 2. Tap Send once, then make sure the chat's text box is there AND empty. That's the only
            //    thing we count as "sent". Never tap twice, and never fall back to SMS after tapping
            //    (Messages might still send it a moment later, and it would go out twice).
            HubLog.d("RcsSender", "tapping Send: id=${send.viewIdResourceName} class=${send.className} type=$type typedOurselves=$typedOurselves")
            send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 6000) {
                // Confirm the send landed as soon as the box clears, so the bubble fills in quickly
                delay(if (System.currentTimeMillis() - t0 < 1200) 50 else 150)
                val input = chatInput(pkg) ?: continue          // popup on top (SIM picker, "send as MMS?"): keep waiting
                val left = norm(inputText(input))
                val cleared = if (media.isEmpty()) left != want && left.isEmpty() else left.isEmpty() && System.currentTimeMillis() - t0 > 1500
                if (cleared) { HubLog.d("RcsSender", "sent OK ($type)"); return DriveResult(true, type) }
            }
            HubLog.w("RcsSender", "tapped Send but couldn't confirm it went (box not cleared or covered by a popup)")
            return DriveResult(false, type, "couldn't confirm it sent, check Google Messages", uncertain = true)
        } finally {
            withContext(NonCancellable) {
                if (popupOk) {
                    // Close the little window; Orbit was never left
                    repeat(3) { if (runCatching { rootInActiveWindow?.packageName == pkg }.getOrDefault(false)) { performGlobalAction(GLOBAL_ACTION_BACK); delay(150) } }
                    hideCover()
                } else { delay(if (covered) 120 else 350); backToHub(pkg); hideCover() }
            }
        }
    }

    /** Empties the Messages text box so a failed message doesn't get sent later by accident. */
    private fun clearDraft(pkg: String) {
        runCatching { root(pkg)?.let { r -> findInput(all(r))?.let { setText(it, "") } } }
    }

    private val emojiNames = mapOf(
        "❤️" to listOf("heart", "love"), "😂" to listOf("laugh", "tears of joy", "haha"),
        "👍" to listOf("thumbs up", "like"), "👎" to listOf("thumbs down", "dislike"),
        "😮" to listOf("surprised", "wow", "open mouth"), "😢" to listOf("crying face", "sad"),
        "😡" to listOf("angry", "pouting"), "‼️" to listOf("exclamation", "emphasize"), "❓" to listOf("question"),
    )

    private suspend fun doReact(pkg: String, addresses: List<String>, body: String, emoji: String, knownType: String, convKey: String): DriveResult {
        val want = norm(body)
        val covered = when (method(null)) { "mirror" -> showMirror(convKey) || showCover(); "none" -> false; else -> showCover() }
        try {
            openChat(pkg, addresses, null, emptyList())
            val start = System.currentTimeMillis()
            var type = ""
            var bubble: AccessibilityNodeInfo? = null
            while (System.currentTimeMillis() - start < 7000) {
                delay(250)
                val r = root(pkg) ?: continue
                val nodes = all(r)
                val input = findInput(nodes) ?: continue
                type = chatType(findSend(r, nodes), input).ifEmpty { knownType }
                // Only react on RCS. On SMS the Messages app sends it as a "Liked …" text, which looks fake.
                if (type == "sms") return DriveResult(false, "sms", "this chat is on SMS right now")
                if (type.isEmpty() && System.currentTimeMillis() - start < 3000) continue
                if (type.isEmpty()) return DriveResult(false, "", "couldn't tell if this chat is on RCS")
                bubble = nodes.lastOrNull { n ->
                    if (n.isEditable) return@lastOrNull false
                    val t = norm(n.text?.toString().orEmpty())
                    val d = norm(n.contentDescription?.toString().orEmpty())
                    t == want || (want.length >= 6 && (t.contains(want) || d.contains(want)))
                }
                if (bubble != null) break
            }
            HubLog.d("RcsReact", "chat type=$type bubbleFound=${bubble != null} emoji=$emoji")
            val b = bubble ?: return DriveResult(false, type, "couldn't find that message on screen")
            // Remember what was on screen before the long-press, so we only look at the reaction menu that appears
            fun sig(n: AccessibilityNodeInfo) = "${n.className}|${n.viewIdResourceName}|${n.text}|${n.contentDescription}"
            val before = pkgRoots(pkg).flatMap { all(it) }.map(::sig).toHashSet()
            val longTarget = generateSequence(b) { it.parent }.take(6).firstOrNull { it.isLongClickable } ?: clickable(b) ?: b
            longTarget.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            val names = emojiNames[emoji].orEmpty()
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 3000) {
                delay(200)
                val fresh = pkgRoots(pkg).flatMap { all(it) }.filter { sig(it) !in before }
                if (fresh.isEmpty()) continue
                fun label(n: AccessibilityNodeInfo) = n.text?.toString().orEmpty() to n.contentDescription?.toString()?.lowercase().orEmpty()
                val chip = fresh.firstOrNull { n -> val (t, d) = label(n); t == emoji || d == emoji }
                    ?: fresh.firstOrNull { n -> val (_, d) = label(n); d.length < 40 && names.any { d.contains(it) } } ?: continue
                val ok = (clickable(chip) ?: chip).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return DriveResult(ok, type, if (ok) "" else "couldn't tap the reaction")
            }
            HubLog.w("RcsReact", "reaction chip not found. ids=${root(pkg)?.let { r -> all(r).mapNotNull { it.viewIdResourceName?.substringAfterLast('/') }.distinct().take(30) }}")
            performGlobalAction(GLOBAL_ACTION_BACK) // close the menu we opened
            return DriveResult(false, type, "that reaction isn't in Messages' quick reactions")
        } finally {
            withContext(NonCancellable) { delay(if (covered) 120 else 350); backToHub(pkg); hideCover() }
        }
    }

    companion object {
        @Volatile private var instance: MessagesAccessibility? = null
        private val lock = Mutex()

        fun running() = instance != null

        fun enabledInSettings(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(ctx, MessagesAccessibility::class.java).flattenToString()
            val short = ComponentName(ctx, MessagesAccessibility::class.java).flattenToShortString()
            return flat.split(':').any { it.equals(me, ignoreCase = true) || it.equals(short, ignoreCase = true) }
        }

        suspend fun sendThrough(ctx: Context, pkg: String, addresses: List<String>, body: String, media: List<Uri> = emptyList(),
                                convKey: String = "", forcedHide: String? = null): DriveResult =
            lock.withLock {
                val svc = instance ?: return DriveResult(false, why = "Accessibility service is off")
                withContext(Dispatchers.Main) {
                    runCatching { svc.doSend(pkg, addresses, body, media, convKey, forcedHide) }.getOrElse { DriveResult(false, why = it.message ?: "Error") }
                }
            }

        fun lastHideResult() = instance?.lastHide.orEmpty()

        suspend fun reactThrough(ctx: Context, pkg: String, addresses: List<String>, body: String, emoji: String, convKey: String): DriveResult =
            lock.withLock {
                val svc = instance ?: return DriveResult(false, why = "Accessibility service is off")
                withContext(Dispatchers.Main) {
                    runCatching { svc.doReact(pkg, addresses, body, emoji, Hub.rcsState(convKey), convKey) }.getOrElse { DriveResult(false, why = it.message ?: "Error") }
                }
            }
    }
}
