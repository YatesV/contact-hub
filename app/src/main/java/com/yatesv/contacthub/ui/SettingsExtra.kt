package com.yatesv.contacthub.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.yatesv.contacthub.BuildConfig
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.HubLog
import com.yatesv.contacthub.data.Settings
import com.yatesv.contacthub.engine.Backup
import com.yatesv.contacthub.engine.Diagnostics
import com.yatesv.contacthub.engine.HubSms
import com.yatesv.contacthub.engine.Notifier
import com.yatesv.contacthub.engine.Sender
import com.yatesv.contacthub.engine.Updater
import kotlinx.coroutines.launch

private fun upd(block: (Settings) -> Settings) = Hub.settings.update(block)

// ---------------- Motion & feel ----------------

@Composable
fun MotionPage() {
    val s by Hub.settings.flow.collectAsState()
    val view = LocalView.current
    var replay by remember { mutableIntStateOf(0) }
    SectionTitle("Animation style")
    ChoiceRow("Feel", Motion.labels.toList(), s.motion) { v ->
        upd { st ->
            val p = Motion.presets[v]
            if (v == "custom") st.copy(motion = v) else st.copy(motion = v, springDamping = p?.damping ?: st.springDamping, springStiffness = p?.stiffness ?: st.springStiffness)
        }
        replay++; Haptics.tap(view)
    }
    // Graph of the spring + live preview
    HubCard {
        Text("How it moves", style = MaterialTheme.typography.labelLarge)
        SpringGraph(Motion.params(s), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.outlineVariant,
            Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp).semantics { contentDescription = "Spring curve graph" })
        Text("Time →   (the line is where it ends up; bumps above it are bounce)", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        MotionPreview(s, replay)
        TextButton({ replay++; Haptics.tap(view) }) { Text("Replay") }
    }
    SectionTitle("Fine-tune (Custom)")
    SliderRow("Bounciness", 1.2f - s.springDamping, 0.05f..0.85f, "%.0f%%".format((1.2f - s.springDamping) / 0.85f * 100)) { v ->
        upd { it.copy(motion = "custom", springDamping = 1.2f - v) }; replay++
    }
    SliderRow("Speed", s.springStiffness, 80f..1500f, s.springStiffness.toInt().toString()) { v ->
        upd { it.copy(motion = "custom", springStiffness = v) }; replay++
    }
    SectionTitle("Screens & menus")
    ChoiceRow("Screen transition", listOf("slide" to "iOS slide", "fade" to "Fade", "zoom" to "Zoom"), s.transition) { v -> upd { it.copy(transition = v) } }
    ChoiceRow("Long-press menu", listOf("ios" to "iOS (lift + blur)", "sheet" to "Bottom sheet"), s.menuStyle) { v -> upd { it.copy(menuStyle = v) } }
    SectionTitle("Extras")
    SwitchRow("Vibration", "Light ticks on send, react, swipe, long-press", s.haptics) { v -> upd { it.copy(haptics = v) }; if (v) Haptics.confirm(view) }
    SwitchRow("Animated reactions", "Reactions pop and wiggle when they appear", s.animatedReactions) { v -> upd { it.copy(animatedReactions = v) } }
    SwitchRow("Send sound", "A little whoosh when a message sends", s.sendSound) { v -> upd { it.copy(sendSound = v) }; if (v) SendSound.play() }
    SwitchRow("Screen effects", "Confetti for \"happy birthday\", fireworks for \"congrats\", hearts for \"love you\". Only on your screen, never sent.", s.screenEffects) { v ->
        upd { it.copy(screenEffects = v) }; if (v) Effects.current.value = Effect.CONFETTI
    }
}

@Composable
private fun MotionPreview(s: Settings, replay: Int) {
    val st = LocalChatStyle.current
    val x = remember { Animatable(0f) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(replay, s.motion, s.springDamping, s.springStiffness) {
        x.snapTo(0f); pop.snapTo(0f)
        launch { x.animateTo(1f, Motion.spec(s)) }
        launch { pop.animateTo(1f, Motion.popSpec(s)) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(70.dp)) {
        val travel = maxWidth - 150.dp
        Text("Sliding in", color = st.sentText, modifier = Modifier.offset(x = travel * x.value).align(Alignment.TopStart)
            .clip(st.bubble(true, true, true)).background(st.sent).padding(horizontal = 14.dp, vertical = 8.dp))
        Text("❤️", fontSize = 26.sp, modifier = Modifier.align(Alignment.BottomEnd).graphicsLayer { scaleX = pop.value; scaleY = pop.value })
    }
}

// ---------------- Messaging mode ----------------

@Composable
fun ModeSwitch() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    @Suppress("UNUSED_EXPRESSION") tick
    val hub = HubSms.isDefault(ctx)
    SectionTitle("Texting mode")
    ChoiceRow("Who handles your texts", listOf("google" to "Google Messages mode (RCS)", "hub" to "Contact Hub mode (SMS/MMS)"), if (hub) "hub" else "google") { v ->
        if (v == "hub" && !hub) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_SMS)) role.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
        } else if (v == "google" && hub) {
            // Android doesn't let apps give the role away; open the screen where you pick Google Messages
            runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
    Text(if (hub) "Contact Hub sends and receives everything itself. No RCS (typing dots, read receipts, RCS reactions). To go back, pick Google Messages as your SMS app on the next screen."
        else "Google Messages stays your texting app and handles RCS in the background. Contact Hub sends through it so nothing looks different to anyone.",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---------------- Updates ----------------

@Composable
fun UpdateCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsState()
    val s by Hub.settings.flow.collectAsState()
    SettingRow("Version", "Build ${BuildConfig.VERSION_CODE}" + when {
        st.available -> " · build ${st.latest} is ready"
        st.latest > 0 -> " · up to date"
        else -> ""
    })
    if (st.progress in 0f..0.999f) LinearProgressIndicator({ st.progress }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    if (st.error.isNotBlank()) Text(st.error, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ scope.launch { Updater.check(force = true) } }) { Text("Check now") }
        if (st.available) Button({
            if (!Updater.canInstall(ctx)) ctx.startActivity(Updater.allowInstallsIntent(ctx))
            else Hub.scope.launch { Updater.install(ctx) }
        }) { Text("Update to build ${st.latest}") }
    }
    SwitchRow("Check for updates automatically", null, s.autoUpdate) { v -> upd { it.copy(autoUpdate = v) } }
}

/** Banner at the top of the inbox when a new build is out. */
@Composable
fun UpdateBanner() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Updater.state.collectAsState()
    var hidden by remember { mutableStateOf(false) }
    if (!st.available || hidden) return
    Banner(if (st.progress in 0f..0.999f) "Downloading update… ${(st.progress * 100).toInt()}%" else "Update ready: build ${st.latest}", "Update", {
        if (!Updater.canInstall(ctx)) ctx.startActivity(Updater.allowInstallsIntent(ctx)) else Hub.scope.launch { Updater.install(ctx) }
    }) { hidden = true }
}

// ---------------- Developer ----------------

@Composable
fun DeveloperPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var checks by remember { mutableStateOf<List<Diagnostics.Check>>(emptyList()) }
    val extra = remember { mutableListOf<String>() }
    var report by remember { mutableStateOf("") }
    var selfTest by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray()) } }
    }
    fun rebuild() { report = Diagnostics.report(checks, extra) }

    SectionTitle("Self-test")
    Text("Checks every feature it safely can. Nothing is sent to anyone. The report never includes what your messages say.",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button({
        running = true; checks = emptyList()
        scope.launch {
            checks = Diagnostics.run(ctx) { progress = it }
            rebuild(); running = false; Haptics.confirm(view)
        }
    }, Modifier.fillMaxWidth().padding(16.dp), enabled = !running) { Text(if (running) "Testing…" else "Run full self-test") }
    if (running) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp))
        Text(progress, style = MaterialTheme.typography.bodySmall)
    }
    if (checks.isNotEmpty()) {
        val p = checks.count { it.status == Diagnostics.Status.PASS }
        val w = checks.count { it.status == Diagnostics.Status.WARN }
        val f = checks.count { it.status == Diagnostics.Status.FAIL }
        HubCard {
            Text("✅ $p passed   ⚠️ $w warnings   ❌ $f failed", style = MaterialTheme.typography.titleMedium)
            checks.filter { it.status == Diagnostics.Status.FAIL || it.status == Diagnostics.Status.WARN }.forEach { c ->
                Text("${if (c.status == Diagnostics.Status.FAIL) "❌" else "⚠️"} ${c.group} · ${c.name}: ${c.detail}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (report.isNotBlank()) Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ save.launch("contacthub-selftest.txt") }) { Text("Download .txt") }
        OutlinedButton({ shareText(ctx, report) }) { Text("Share") }
    }

    SectionTitle("Try things by hand")
    SettingRow("Test RCS sender", "Sends \"Contact Hub test\" to YOUR OWN number through Google Messages") { TextButton({ selfTest = true }) { Text("Run") } }
    SettingRow("Test notification", "Shows a sample message notification") {
        TextButton({ scope.launch {
            Notifier.showMessage(ctx, "selftest", "Contact Hub", false, listOf("Contact Hub" to "This is a test notification"))
            extra += "Notification: shown"; rebuild()
        } }) { Text("Run") }
    }
    SettingRow("Test vibration") { TextButton({ val old = Hub.settings.value.haptics; upd { it.copy(haptics = true) }; Haptics.confirm(view); upd { it.copy(haptics = old) }; extra += "Vibration: fired"; rebuild() }) { Text("Run") } }
    SettingRow("Test send sound") { TextButton({ val old = Hub.settings.value.sendSound; upd { it.copy(sendSound = true) }; SendSound.play(); upd { it.copy(sendSound = old) }; extra += "Send sound: played"; rebuild() }) { Text("Run") } }
    SettingRow("Test screen effects") { TextButton({ Effects.current.value = Effect.FIREWORKS; extra += "Effects: fireworks shown"; rebuild() }) { Text("Run") } }
    SettingRow("Re-run setup wizard") { TextButton({ Nav.go(Screen.Setup) }) { Text("Open") } }

    SectionTitle("Activity log")
    SettingRow("Download activity log", "${HubLog.snapshot().size} lines: what the RCS sender saw and did, errors") {
        TextButton({ report = Diagnostics.report(checks, extra); save.launch("contacthub-log.txt") }) { Text("Save") }
    }
    SettingRow("Clear activity log") { TextButton({ HubLog.clear() }) { Text("Clear") } }

    if (selfTest) SelfSendDialog(onDone = { line -> selfTest = false; if (line != null) { extra += line; rebuild() } })
}

@Composable
private fun SelfSendDialog(onDone: (String?) -> Unit) {
    val ctx = LocalContext.current
    val mine = remember { HubSms.myNumbers(ctx).firstOrNull().orEmpty() }
    var number by remember { mutableStateOf(mine) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone(null) },
        title = { Text("Test the RCS sender") },
        text = {
            Column {
                Text("This sends one real text, \"Contact Hub test\", to the number below. Use your own number.")
                androidx.compose.material3.OutlinedTextField(number, { number = it }, label = { Text("Your number") }, singleLine = true)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton({
                busy = true
                Hub.scope.launch {
                    val key = com.yatesv.contacthub.data.Phone.convKey(listOf(number))
                    val r = Sender.send(ctx, key, "Contact Hub test", listOf(number))
                    onDone("RCS sender test: ok=${r.ok} route=${r.route} ${r.error}")
                }
            }, enabled = !busy && number.count { it.isDigit() } >= 7) { Text("Send test") }
        },
        dismissButton = { TextButton({ onDone(null) }, enabled = !busy) { Text("Cancel") } },
    )
}

fun shareText(ctx: Context, text: String) {
    val f = Diagnostics.writeFile(ctx, text)
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------------- Privacy & data, licenses ----------------

@Composable
fun PrivacyDataPage() {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    HubCard {
        Text("What Contact Hub reads", style = MaterialTheme.typography.titleMedium)
        Text("Your texts, contacts, call log and Google/Samsung Messages notifications, so it can show and send your messages. It only reads; in Google Messages mode it never changes your texts.",
            style = MaterialTheme.typography.bodyMedium)
    }
    HubCard {
        Text("What stays on your phone", style = MaterialTheme.typography.titleMedium)
        Text("Everything Contact Hub saves: socials, notes, birthdays, settings, folders, reactions, the activity log and backups. There are no Contact Hub servers, accounts, ads, analytics or trackers.",
            style = MaterialTheme.typography.bodyMedium)
    }
    HubCard {
        Text("What leaves your phone", style = MaterialTheme.typography.titleMedium)
        Text("• Life360 (only if you sign in): your Life360 login token, to fetch your circle's locations.\n" +
            "• OpenStreetMap: map tiles for the location card (it sees which area is being shown).\n" +
            "• GitHub: checking for and downloading updates.\n" +
            "• Google Drive: your backup file, only if you connect it.\n" +
            "• Social search: what you search goes to Google or the social site.",
            style = MaterialTheme.typography.bodyMedium)
    }
    HubCard {
        Text("Good to know", style = MaterialTheme.typography.titleMedium)
        Text("Life360 doesn't offer an official way for other apps to connect. Contact Hub uses an unofficial one, which may go against Life360's terms. Life360 could block it. You can sign out anytime in Settings → Life360.",
            style = MaterialTheme.typography.bodyMedium)
    }
    SectionTitle("Delete")
    SettingRow("Delete all Contact Hub data", "Removes socials, notes, settings, logs, Life360 sign-in and backups. Your texts, contacts and calls stay.",
        onClick = { confirm = true })
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete all Contact Hub data?") },
        text = { Text("This can't be undone. Contact Hub will close and start fresh. Your texts, contacts and call history are not touched.") },
        confirmButton = { TextButton({ wipeAll(ctx) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}

private fun wipeAll(ctx: Context) {
    runCatching { Hub.db.close() }
    ctx.deleteDatabase("hub.db")
    listOf("settings", "life360", "rcs", "listener").forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    runCatching { Backup.dir(ctx).deleteRecursively() }
    HubLog.clear()
    ctx.contentResolver.persistedUriPermissions.forEach { runCatching { ctx.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
    android.os.Process.killProcess(android.os.Process.myPid())
}

@Composable
fun LicensesPage() {
    val libs = listOf(
        "AndroidX (Compose, Room, WorkManager, Biometric, WebKit, Core)" to "Apache License 2.0 · Google",
        "Kotlin & kotlinx.coroutines" to "Apache License 2.0 · JetBrains",
        "Coil (image loading)" to "Apache License 2.0 · Coil contributors",
        "osmdroid (maps)" to "Apache License 2.0 · osmdroid contributors",
        "Map data" to "© OpenStreetMap contributors, available under the Open Database License (ODbL)",
        "Material Icons" to "Apache License 2.0 · Google",
        "Social app icons" to "Shown from the apps installed on your phone; they belong to their owners. Not copied into Contact Hub.",
    )
    libs.forEach { (n, l) -> SettingRow(n, l) }
    Text("Full license texts: apache.org/licenses/LICENSE-2.0 and opendatacommons.org/licenses/odbl",
        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

@Suppress("unused") private val keepShapes = listOf(CircleShape, RoundedCornerShape(1.dp))
@Suppress("unused") private val keepColor = Color.Unspecified
@Suppress("unused") private fun Modifier.keepClick() = clickable { }
@Suppress("unused") private val keepBox: @Composable () -> Unit = { Box(Modifier) }
