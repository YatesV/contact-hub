package com.yatesv.contacthub.engine

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.yatesv.contacthub.BuildConfig
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.Folder
import com.yatesv.contacthub.data.HubLog
import com.yatesv.contacthub.data.Otp
import com.yatesv.contacthub.data.Pdu
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.data.Reactions
import com.yatesv.contacthub.data.Social
import com.yatesv.contacthub.ui.SocialImport
import com.yatesv.contacthub.ui.installedPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Developer self-test: checks every feature it safely can and writes a report.
 * Nothing is sent to anyone. Message text is never included, only counts.
 */
object Diagnostics {
    enum class Status { PASS, WARN, FAIL, INFO }
    data class Check(val group: String, val name: String, val status: Status, val detail: String)

    private suspend fun timed(block: suspend () -> String): Pair<String, Long> {
        val t = System.currentTimeMillis()
        val r = block()
        return r to (System.currentTimeMillis() - t)
    }

    suspend fun run(ctx: Context, progress: (String) -> Unit = {}): List<Check> = withContext(Dispatchers.IO) {
        val out = ArrayList<Check>()
        suspend fun check(group: String, name: String, block: suspend () -> Pair<Status, String>) {
            progress("$group: $name")
            out += runCatching { val (s, d) = block(); Check(group, name, s, d) }
                .getOrElse { Check(group, name, Status.FAIL, "${it.javaClass.simpleName}: ${it.message}") }
        }
        fun pass(d: String) = Status.PASS to d
        fun warn(d: String) = Status.WARN to d
        fun fail(d: String) = Status.FAIL to d
        fun info(d: String) = Status.INFO to d

        // ---- Device & app
        check("App", "Version") { info("Orbit ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})") }
        check("App", "Device") { info("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), One UI ${runCatching { Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null) }.getOrNull() ?: "?"}") }
        check("App", "Texting mode") {
            val def = Telephony.Sms.getDefaultSmsPackage(ctx).orEmpty()
            info(if (def == ctx.packageName) "Orbit mode (Orbit is the default texting app, SMS/MMS, no RCS)" else "Google Messages mode (default texting app: $def)")
        }
        check("App", "Network") {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) pass("Online") else warn("No internet")
        }

        // ---- Permissions
        val perms = listOf(
            Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        perms.forEach { p ->
            check("Permissions", p.substringAfterLast('.')) {
                if (ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) pass("Granted") else fail("Not granted")
            }
        }
        check("Permissions", "Notification access") {
            val enabled = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
            when {
                enabled && MessagesListener.connected -> pass("On and connected")
                enabled -> warn("On in settings but not connected yet (toggle it off and on)")
                else -> fail("Off: RCS chats and notification replies won't work")
            }
        }
        check("Permissions", "Accessibility (RCS sender)") {
            val on = MessagesAccessibility.enabledInSettings(ctx)
            when {
                on && MessagesAccessibility.running() -> pass("On and running")
                on -> warn("On in settings but not running (Samsung may have stopped it; toggle it off and on)")
                else -> fail("Off: texts can't go through Google Messages")
            }
        }
        check("Permissions", "Default phone app") {
            val rm = ctx.getSystemService(RoleManager::class.java)
            when {
                !rm.isRoleAvailable(RoleManager.ROLE_DIALER) -> warn("Phone role not available on this device")
                rm.isRoleHeld(RoleManager.ROLE_DIALER) -> pass("Orbit is the default phone app")
                else -> warn("Not default: no custom call screen or blocking")
            }
        }
        check("Permissions", "Install updates") { if (Updater.canInstall(ctx)) pass("Allowed") else warn("Not allowed yet (asked the first time you update)") }

        // ---- Messages apps
        check("Messages apps", "Google Messages") {
            Sender.installed(ctx, GOOGLE_MESSAGES).let { if (it) pass("Installed, v" + versionOf(ctx, GOOGLE_MESSAGES)) else warn("Not installed") }
        }
        check("Messages apps", "Samsung Messages") {
            Sender.installed(ctx, SAMSUNG_MESSAGES).let { if (it) info("Installed, v" + versionOf(ctx, SAMSUNG_MESSAGES)) else info("Not installed") }
        }
        check("Messages apps", "Reply buttons captured") { info("${Hub.replies.size} chats have a Google Messages reply button ready") }

        // ---- Reading texts
        var firstThread = 0L
        var convKeys = emptyList<Pair<String, Long>>()
        check("Texts", "Read conversation list") {
            val (r, ms) = timed {
                val list = Hub.sms.conversations(includeOld = false)
                convKeys = list.take(8).map { it.key to it.threadId }
                firstThread = list.firstOrNull()?.threadId ?: 0
                "${list.size} conversations (last 6 months)"
            }
            if (convKeys.isEmpty()) warn("$r in $ms ms") else pass("$r in $ms ms")
        }
        check("Texts", "Open newest chat") {
            if (firstThread == 0L) return@check warn("No chats to open")
            val (r, ms) = timed { val m = Hub.sms.messages(firstThread); "${m.size} messages (${m.count { it.attachments.isNotEmpty() }} with media)" }
            if (r.startsWith("0 ")) fail("$r in $ms ms: chat would look empty") else pass("$r in $ms ms")
        }
        check("Texts", "Thread lookup matches inbox") {
            val bad = convKeys.count { (k, t) -> Hub.sms.threadIdFor(k) != t }
            if (bad == 0) pass("${convKeys.size}/${convKeys.size} chats open the right thread") else fail("$bad of ${convKeys.size} chats would open the wrong thread")
        }
        check("Texts", "Search") { val (r, ms) = timed { "${Hub.sms.search("the").size} results for a test word" }; pass("$r in $ms ms") }
        check("Texts", "RCS messages seen") {
            val local = Hub.db.dao().allLocal()
            info("${local.count { it.source == "rcs" }} received + ${local.count { it.source == "sent" }} sent through Google Messages saved")
        }
        check("Texts", "Chat types known") {
            val prefs = ctx.getSharedPreferences("rcs", Context.MODE_PRIVATE).all
            info("${prefs.values.count { it == "rcs" }} RCS, ${prefs.values.count { it == "sms" }} SMS")
        }
        check("Texts", "Waiting reactions") { info("${Hub.db.dao().pendingReactions().size} reactions waiting for RCS") }

        // ---- Contacts & calls
        check("Contacts", "Read contacts") { val (r, ms) = timed { "${Hub.contacts.all().size} phone numbers" }; pass("$r in $ms ms") }
        check("Contacts", "Call log") { val (r, ms) = timed { "${Hub.contacts.calls().size} recent calls" }; pass("$r in $ms ms") }
        check("Contacts", "Blocked numbers") {
            runCatching { pass("${Hub.contacts.blockedList().size} blocked") }.getOrElse { warn("Can't read (needs default phone app)") }
        }
        check("Contacts", "Socials saved") {
            val e = Hub.db.dao().allExtras()
            info("${e.count { it.instagram.isNotBlank() }} Instagram, ${e.count { it.snapchat.isNotBlank() }} Snapchat, ${e.count { it.tiktok.isNotBlank() }} TikTok")
        }
        check("Contacts", "Socials found in your contacts") { info("${SocialImport.scan(ctx).size} could be imported") }
        Social.entries.forEach { s ->
            check("Contacts", "${s.label} app") { s.installedPackage(ctx)?.let { pass("Installed ($it), real icon shown") } ?: info("Not installed, generic icon shown") }
        }

        // ---- Life360
        check("Life360", "Signed in") { if (Hub.settings.value.l360Token.isNotBlank()) pass("Yes") else warn("Not signed in") }
        if (Hub.settings.value.l360Token.isNotBlank()) {
            check("Life360", "Refresh") {
                val (r, ms) = timed {
                    Hub.l360.refresh(Hub.settings.value.l360Token).fold({ "${it.size} people, ${it.count { m -> m.lat != null }} sharing location" }, { "ERROR ${it.message}" })
                }
                if (r.startsWith("ERROR")) fail(r) else pass("$r in $ms ms")
            }
            check("Life360", "Matched to contacts") {
                val contacts = Hub.contacts.all()
                val n = Hub.l360.members.value.count { m -> m.id != Hub.l360.meId && contacts.any { Phone.norm(it.number) == Phone.norm(m.phone) } }
                info("$n of ${Hub.l360.members.value.size - 1} people matched by phone number")
            }
            check("Life360", "Your location (for distance)") { if (Hub.l360.me()?.lat != null) pass("Found") else warn("Not found: distances won't show") }
        }

        // ---- Mock location
        MockStore.load()
        check("Mock location", "Developer options") { if (MockSetup.devOptionsOn(ctx)) pass("On") else warn("Off: About phone → Software information → tap Build number 7 times") }
        check("Mock location", "Selected mock app") {
            if (MockSetup.isMockApp(ctx)) pass("Orbit is the mock location app") else warn("Not selected: Developer options → Select mock location app → Orbit")
        }
        check("Mock location", "Take over a test provider") {
            if (!MockSetup.isMockApp(ctx)) info("Skipped (not the mock app yet)")
            else MockSetup.canAddProvider(ctx).let { if (it == "ok") pass("Added and removed a throwaway provider") else fail(it) }
        }
        check("Mock location", "Providers on this phone") {
            val lm = ctx.getSystemService(android.location.LocationManager::class.java)
            info(lm.allProviders.joinToString() + " · will mock: " + MockSetup.providers.joinToString())
        }
        check("Mock location", "Running now") {
            val st = MockStore.state.value
            when {
                st.active -> info("${st.item?.type} \"${st.item?.name}\"${if (st.paused) " (paused)" else ""}, progress ${st.progressSec.toInt()}/${st.totalSec.toInt()} s")
                MockStore.session() != null -> warn("A session is saved but the service isn't running (it resumes after a restart, or tap Start again)")
                else -> info("Off")
            }
        }
        check("Mock location", "Route playback math") {
            val a = com.yatesv.contacthub.data.LatLng(30.8466, -93.2891)
            val path = listOf(a, com.yatesv.contacthub.data.MockMath.offset(a, 500.0, 0.0), com.yatesv.contacthub.data.MockMath.offset(a, 500.0, 500.0))
            val plan = com.yatesv.contacthub.data.RoutePlan(path, 35.0)
            val corner = plan.at(plan.totalSeconds * 0.5).speedMps
            val mid = plan.at(plan.totalSeconds * 0.25).speedMps
            val end = plan.play(plan.totalSeconds + 5, "stay")
            if (end.done && corner < mid && plan.at(0.0).speedMps == 0f) pass("1 km L-route: ${plan.totalSeconds.toInt()} s, ${"%.0f".format(mid * 2.237)} mph straight, ${"%.0f".format(corner * 2.237)} mph at the corner")
            else fail("Unexpected: corner=$corner mid=$mid done=${end.done}")
        }
        check("Mock location", "Location permission") { if (MockSetup.hasLocation(ctx)) pass("Granted (blue dot works)") else warn("Not granted: no blue dot / start from real location") }
        check("Mock location", "Last real fix") { MockStore.lastReal()?.let { pass(stamp(it.second)) } ?: info("None yet") }
        check("Mock location", "Exact alarms (schedules)") { if (MockSetup.canExactAlarm(ctx)) pass("Allowed") else warn("Not allowed: scheduled starts may be late") }
        check("Mock location", "Saved") { info("${MockStore.favorites.value.size} favorites, ${MockStore.history.value.size} recent, ${MockStore.schedules.value.size} scheduled") }
        check("Mock location", "Map tiles") {
            val (r, ms) = timed { MockApi.ping(MockApi.tileTestUrl()) }
            val src = if (MockApi.hasMapbox) "Mapbox" else "OpenStreetMap (no Mapbox token)"
            if (r == "HTTP 200") pass("$src $r in $ms ms") else fail("$src $r")
        }
        check("Mock location", "Snap to roads") {
            val a = com.yatesv.contacthub.data.LatLng(30.8466, -93.2891)
            val (r, ms) = timed {
                val (line, err) = MockApi.snap(listOf(a, com.yatesv.contacthub.data.MockMath.offset(a, 800.0, 400.0)), "car")
                err ?: "${line.size} points"
            }
            if (r.endsWith("points")) pass("${if (MockApi.hasMapbox) "Mapbox" else "OSRM"}: $r in $ms ms") else warn(r)
        }
        check("Mock location", "Elevation") {
            val (r, ms) = timed { MockApi.elevation(listOf(com.yatesv.contacthub.data.LatLng(30.8466, -93.2891)))?.firstOrNull()?.let { "%.0f m".format(it) } ?: "failed" }
            if (r != "failed") pass("$r in $ms ms") else warn("Open-Meteo didn't answer: altitude won't be set")
        }

        // ---- Crashes
        check("App", "Last crash") {
            val c = CrashLog.last(ctx)
            if (c == null) pass("None recorded") else warn(c.take(4000))
        }

        // ---- Orbit extras
        check("Orbit", "App icon") { info(runCatching { com.yatesv.contacthub.ui.AppIcons.current(ctx) }.getOrDefault("?")) }
        check("Orbit", "Smart replies (on-device)") {
            if (!Hub.settings.value.smartReplies) info("Off")
            else if (!Ai.smartReplyHealthy) fail("Turned itself off: it closed Orbit twice on this build. Comes back with the next update.")
            else {
                val (r, ms) = timed { Ai.onDeviceReplies(listOf(com.yatesv.contacthub.data.Msg("t", "Are you coming to dinner tonight?", System.currentTimeMillis(), false, sender = "a"))).joinToString(" | ") }
                if (r.isNotBlank()) pass("\"$r\" in $ms ms") else warn("No suggestions (Smart Reply only works in English)")
            }
        }
        check("Orbit", "AI key") {
            val st = Hub.settings.value
            if (!Ai.hasKey) info("None (replies and summaries stay on the phone)") else info("${st.aiProvider} · model ${st.aiModel.ifBlank { "auto" }}")
        }
        check("Orbit", "Translation") {
            val (r, ms) = timed { Ai.translate("Hola, ¿cómo estás?").fold({ "${it.first} (from ${it.second})" }, { "ERROR ${it.message}" }) }
            if (r.startsWith("ERROR")) warn("$r (needs internet the first time to download the language)") else pass("$r in $ms ms")
        }
        check("Orbit", "Birthday + check-in reminders") {
            val wm = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("daily").get()
            val nudges = Hub.orbit.nudges().size
            if (wm.any { !it.state.isFinished }) pass("Scheduled daily at 9 AM · ${if (Hub.settings.value.birthdayReminders) "birthdays on" else "birthdays off"} · $nudges check-ins")
            else warn("Daily reminder job isn't scheduled (reopen Orbit)")
        }
        check("Orbit", "Snoozed chats") { info("${Hub.orbit.snoozes().size}") }
        check("Orbit", "Contact groups") { info(Hub.orbit.groups().joinToString { "${it.name} (${it.members.size})" }.ifBlank { "None" }) }
        check("Orbit", "Arrival texts") {
            val n = Hub.orbit.arrivals().count { it.on }
            when {
                n == 0 -> info("None set up")
                !Arrivals.canWatch(ctx) -> fail("$n set up, but location isn't allowed")
                !Arrivals.canWatchInBackground(ctx) -> warn("$n set up; only works while Orbit is open (allow location all the time)")
                else -> pass("$n watching")
            }
        }
        check("Orbit", "Spam numbers reported") { info("${Hub.orbit.spamList().size}") }
        check("Orbit", "Person widgets") {
            val ids = android.appwidget.AppWidgetManager.getInstance(ctx).getAppWidgetIds(android.content.ComponentName(ctx, com.yatesv.contacthub.ui.PersonWidget::class.java))
            info("${ids.size} on the home screen" + if (ids.any { Hub.orbit.widgetNumber(it) == null }) " (some not set up)" else "")
        }
        check("Orbit", "Laptop access") { WebLink.state.value.let { info(if (it.on) "On at ${it.url.ifBlank { "(no Wi-Fi)" }}" else "Off") } }
        check("Orbit", "Voice-to-text") {
            if (Build.VERSION.SDK_INT < 33) warn("Needs Android 13+")
            else if (android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) pass("On-device speech recognition available")
            else warn("On-device speech recognition not available")
        }

        // ---- Database
        check("Database", "Read/write") {
            val dao = Hub.db.dao()
            val id = dao.addFolder(Folder(name = "__selftest__"))
            val ok = dao.allFolders().any { it.id == id }
            dao.deleteFolder(id)
            if (ok) pass("OK") else fail("Write didn't read back")
        }
        check("Database", "Sizes") {
            val d = Hub.db.dao()
            info("chats=${d.allMeta().size} rcs=${d.allLocal().size} reactions=${d.allReactions().size} starred=${d.allStarred().size} scheduled=${d.allScheduled().count { it.status == "waiting" }} folders=${d.allFolders().size}")
        }

        // ---- Notifications
        check("Notifications", "Allowed") { if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) pass("Yes") else fail("Notifications are off for Orbit") }
        check("Notifications", "Channels") {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            info(nm.notificationChannels.joinToString { "${it.name}(imp ${it.importance}${if (it.canBypassDnd()) ", DND" else ""})" })
        }

        // ---- Logic & MMS
        check("Logic", "Reaction parsing") {
            val r = Reactions.parse("Loved “see you soon”")
            if (r?.emoji == "❤️" && r.target == "see you soon") pass("OK") else fail("Got $r")
        }
        check("Logic", "Code detection") { if (Otp.find("Your code is 482913") == "482913") pass("OK") else fail("Wrong") }
        check("Logic", "MMS encode/decode") {
            val bytes = Pdu.sendReq(listOf("+13375550101", "+13375550102"), "self test", listOf(Pdu.Part("image/jpeg", ByteArray(500) { it.toByte() }, "a.jpg")))
            val m = Pdu.parse(bytes)
            if (m.type == Pdu.SEND_REQ && m.to.size == 2 && m.parts.size == 2 && m.parts[0].text() == "self test" && m.parts[1].data.size == 500)
                pass("${bytes.size} bytes, round trip OK") else fail("Mismatch: $m")
        }
        check("Logic", "My phone number") { HubSms.myNumbers(ctx).let { if (it.isEmpty()) warn("Unknown (group MMS may include you as a member)") else pass("${it.size} found") } }

        // ---- Backup, updates, background work
        check("Backup", "Build backup") { val (r, ms) = timed { "${Backup.build(ctx).toString().length / 1024} KB" }; pass("$r in $ms ms") }
        check("Backup", "Last backup") { val t = Hub.settings.value.lastBackup; if (t == 0L) warn("Never") else pass(stamp(t)) }
        check("Backup", "Google Drive") {
            val u = Hub.settings.value.driveUri
            if (u.isBlank()) info("Not connected")
            else if (ctx.contentResolver.persistedUriPermissions.any { it.uri.toString() == u && it.isWritePermission }) pass("Connected") else fail("Permission lost, reconnect Drive")
        }
        check("Updates", "Check GitHub") {
            val s = Updater.check(force = true)
            when {
                s.error.isNotBlank() -> warn(s.error)
                s.available -> info("Update available: build ${s.latest} (you have ${BuildConfig.VERSION_CODE})")
                else -> pass("Up to date (build ${BuildConfig.VERSION_CODE}, newest ${s.latest})")
            }
        }
        listOf("backup", "life360").forEach { name ->
            check("Background", "Job: $name") {
                val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(name).get()
                info(infos.joinToString { it.state.name }.ifBlank { "not scheduled" })
            }
        }

        // ---- Settings (no secrets)
        check("Settings", "Current") {
            val s = Hub.settings.value
            info("preset=${s.preset} dark=${s.darkMode} motion=${s.motion} menu=${s.menuStyle} engine=${s.engine} handleNotifs=${s.handleNotifications} " +
                "lock=${s.appLock} hidePreviews=${s.hidePreviews} autoReply=${s.autoReplyOn} l360Refresh=${s.l360RefreshMin} effects=${s.screenEffects} sound=${s.sendSound}")
        }
        out
    }

    private fun versionOf(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: "?"
    private fun stamp(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

    fun report(checks: List<Check>, extra: List<String> = emptyList()): String = buildString {
        appendLine("Orbit self-test, ${stamp(System.currentTimeMillis())}")
        appendLine("PASS ${checks.count { it.status == Status.PASS }} · WARN ${checks.count { it.status == Status.WARN }} · FAIL ${checks.count { it.status == Status.FAIL }}")
        appendLine("Message text is never included in this file.")
        appendLine()
        checks.groupBy { it.group }.forEach { (g, list) ->
            appendLine("== $g ==")
            list.forEach { c -> appendLine("[${c.status}] ${c.name}: ${c.detail}") }
            appendLine()
        }
        if (extra.isNotEmpty()) { appendLine("== Interactive tests =="); extra.forEach { appendLine(it) }; appendLine() }
        appendLine("== Activity log (last ${HubLog.snapshot().size} lines) ==")
        HubLog.snapshot().forEach { appendLine(it) }
    }

    fun writeFile(ctx: Context, text: String): File =
        File(ctx.cacheDir, "contacthub-selftest-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt").apply { writeText(text) }
}
