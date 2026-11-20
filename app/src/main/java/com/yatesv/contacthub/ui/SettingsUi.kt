package com.yatesv.contacthub.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yatesv.contacthub.BuildConfig
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.HubApp
import com.yatesv.contacthub.data.Settings
import com.yatesv.contacthub.engine.Backup
import com.yatesv.contacthub.engine.GOOGLE_MESSAGES
import com.yatesv.contacthub.engine.MessagesAccessibility
import com.yatesv.contacthub.engine.SAMSUNG_MESSAGES
import com.yatesv.contacthub.engine.Sender
import kotlinx.coroutines.launch

private fun set(block: (Settings) -> Settings) = Hub.settings.update(block)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHome() {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            SettingRow("Setup & permissions", "What Contact Hub needs to work fully", Icons.Default.CheckCircle) { Nav.go(Screen.SettingsPage("setup")) }
            SettingRow("Look & feel", "Theme, colors, fonts, bubbles, layout, wallpaper", Icons.Default.Palette) { Nav.go(Screen.SettingsPage("look")) }
            SettingRow("Messaging", "RCS, reactions, auto-reply, @mentions", Icons.AutoMirrored.Filled.Chat) { Nav.go(Screen.SettingsPage("messaging")) }
            SettingRow("Notifications", "Previews, sounds, priority contacts", Icons.Default.Notifications) { Nav.go(Screen.SettingsPage("notifications")) }
            SettingRow("Privacy & security", "App lock, hidden chats, blocked numbers", Icons.Default.Lock) { Nav.go(Screen.SettingsPage("privacy")) }
            SettingRow("Life360", "Sign in, refresh rate", Icons.Default.LocationOn) { Nav.go(Screen.SettingsPage("life360")) }
            SettingRow("Backup", "Daily backup, export, restore", Icons.Default.Backup) { Nav.go(Screen.SettingsPage("backup")) }
            SettingRow("About", "Version ${BuildConfig.VERSION_NAME}", Icons.Default.Info) { Nav.go(Screen.SettingsPage("about")) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(page: String) {
    val title = mapOf("setup" to "Setup & permissions", "look" to "Look & feel", "messaging" to "Messaging", "notifications" to "Notifications",
        "privacy" to "Privacy & security", "life360" to "Life360", "backup" to "Backup", "about" to "About")[page] ?: "Settings"
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            when (page) {
                "setup" -> SetupContent()
                "look" -> LookPage()
                "messaging" -> MessagingPage()
                "notifications" -> NotificationsPage()
                "privacy" -> PrivacyPage()
                "life360" -> Life360Page()
                "backup" -> BackupPage()
                else -> AboutPage()
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

// ---------------- Setup ----------------

private val runtimePerms = arrayOf(
    Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
    Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
    Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG,
    Manifest.permission.POST_NOTIFICATIONS,
)

private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

@Composable
fun SetupContent() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++; Hub.changed() }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    @Suppress("UNUSED_EXPRESSION") tick

    val allPerms = runtimePerms.all { granted(ctx, it) }
    val listener = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    val access = MessagesAccessibility.enabledInSettings(ctx)
    val dialer = isDefaultDialer(ctx)
    val gm = Sender.installed(ctx, GOOGLE_MESSAGES) || Sender.installed(ctx, SAMSUNG_MESSAGES)

    fun open(action: String) = runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    Text("Do these in order. Each one turns on more of the app.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Step(1, "Allow permissions", "Texts, contacts, calls and notifications", allPerms) { perms.launch(runtimePerms) }
    Step(2, "Allow restricted settings", "Android blocks steps 3–4 for apps installed outside the Play Store. Open App info → ⋮ (top right) → Allow restricted settings.", false, doneLabel = "Open App info") {
        ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Step(3, "Notification access", "Lets Contact Hub see RCS messages and reply through Google Messages", listener) { open(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
    Step(4, "Accessibility: Contact Hub RCS sender", "Taps Send inside Google/Samsung Messages for you so texts and reactions go over RCS. Settings → Accessibility → Installed apps → Contact Hub.", access) { open(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS) }
    Step(5, "Default phone app", "Custom call screen and call blocking", dialer) {
        role.launch(ctx.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER))
    }
    if (!gm) Text("⚠️ Google Messages isn't installed. Without it, Contact Hub can only send plain SMS to one person at a time.",
        Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
    Text("Keep Google Messages as your default texting app. It handles RCS in the background, and everything people get from you still comes from Google Messages, so nobody can tell you use Contact Hub.",
        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Step(n: Int, title: String, body: String, done: Boolean, doneLabel: String = "Turn on", onClick: () -> Unit) {
    SettingRow("$n. $title", body, if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, onClick = onClick) {
        if (!done) TextButton(onClick) { Text(doneLabel) }
    }
}

@Composable
fun Onboarding() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 48.dp)) {
        Text("Welcome to Contact Hub", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineMedium)
        Text("Texts, contacts, calls, socials and Life360 in one place.", Modifier.padding(16.dp))
        SetupContent()
        Button({ set { it.copy(onboarded = true) }; HubApp.scheduleWork(Hub.app) }, Modifier.fillMaxWidth().padding(16.dp)) { Text("Continue") }
        Text("You can finish setup later in Settings → Setup & permissions.", Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(40.dp))
    }
}

// ---------------- Look ----------------

@Composable
private fun LookPage() {
    val ctx = LocalContext.current
    val s by Hub.settings.flow.collectAsState()
    val pickWallpaper = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            set { it.copy(wallpaper = uri.toString()) }
        }
    }
    SectionTitle("Theme")
    ChoiceRow("Style preset", listOf("google" to "Google Messages", "samsung" to "Samsung", "imessage" to "iMessage", "custom" to "Custom"), s.preset) { v ->
        set { it.copy(preset = v, bubbleShape = "", sentColor = 0, recvColor = 0, sentText = 0, recvText = 0) }
    }
    ChoiceRow("Dark mode", listOf("system" to "System", "light" to "Light", "dark" to "Dark", "amoled" to "Pure black"), s.darkMode) { v -> set { it.copy(darkMode = v) } }
    if (s.preset == "google") SwitchRow("Wallpaper colors (Material You)", "Match your phone's wallpaper", s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }
    if (s.preset == "custom" || !s.dynamicColor) ColorRow("Accent color", s.accent, allowAuto = false) { v -> set { it.copy(accent = v, preset = if (it.preset == "google") "custom" else it.preset) } }

    SectionTitle("Chat bubbles")
    PreviewBubbles()
    ColorRow("Your bubbles", s.sentColor) { v -> set { it.copy(sentColor = v) } }
    ColorRow("Their bubbles", s.recvColor) { v -> set { it.copy(recvColor = v) } }
    ColorRow("Your text color", s.sentText) { v -> set { it.copy(sentText = v) } }
    ColorRow("Their text color", s.recvText) { v -> set { it.copy(recvText = v) } }
    ChoiceRow("Bubble shape", listOf("" to "Preset", "round" to "Rounded", "tail" to "Tail", "square" to "Square"), s.bubbleShape) { v -> set { it.copy(bubbleShape = v) } }
    if (s.bubbleShape.isNotBlank() && s.bubbleShape != "square")
        SliderRow("Corner roundness", s.bubbleRadius.toFloat(), 4f..28f, "${s.bubbleRadius}") { v -> set { it.copy(bubbleRadius = v.toInt()) } }
    SliderRow("Emoji-only message size", s.emojiSize, 1f..3f, "%.1fx".format(s.emojiSize)) { v -> set { it.copy(emojiSize = v) } }
    ChoiceRow("Timestamps", listOf("tap" to "Tap to show", "always" to "Always", "groups" to "End of each group"), s.showTimestamps) { v -> set { it.copy(showTimestamps = v) } }

    SectionTitle("Text")
    ChoiceRow("Font", listOf("default" to "Default", "serif" to "Serif", "mono" to "Mono", "condensed" to "Sans", "cursive" to "Handwritten"), s.font) { v -> set { it.copy(font = v) } }
    SliderRow("Text size", s.fontScale, 0.8f..1.4f, "${(s.fontScale * 100).toInt()}%") { v -> set { it.copy(fontScale = v) } }

    SectionTitle("Layout")
    ChoiceRow("Chat list density", listOf("comfy" to "Comfy", "compact" to "Compact"), s.listDensity) { v -> set { it.copy(listDensity = v) } }
    ChoiceRow("Profile picture shape", listOf("circle" to "Circle", "squircle" to "Squircle", "square" to "Square"), s.avatarShape) { v -> set { it.copy(avatarShape = v) } }
    SwitchRow("Show profile pictures", null, s.showAvatars) { v -> set { it.copy(showAvatars = v) } }
    SwitchRow("Category tabs in inbox", "Personal / Groups / Codes / Deliveries / Spam + folders", s.showCategories) { v -> set { it.copy(showCategories = v) } }
    val swipe = listOf("archive" to "Archive", "pin" to "Pin", "read" to "Mark read", "mute" to "Mute", "none" to "Nothing")
    ChoiceRow("Swipe right", swipe, s.swipeRight) { v -> set { it.copy(swipeRight = v) } }
    ChoiceRow("Swipe left", swipe, s.swipeLeft) { v -> set { it.copy(swipeLeft = v) } }
    SwitchRow("Labels on bottom tabs", null, s.navLabels) { v -> set { it.copy(navLabels = v) } }
    ChoiceRow("Open to", listOf("messages" to "Messages", "contacts" to "Contacts", "phone" to "Phone"), s.startTab) { v -> set { it.copy(startTab = v) } }

    SectionTitle("Wallpaper")
    SettingRow(if (s.wallpaper.isBlank()) "Choose chat wallpaper" else "Change chat wallpaper", "Used in every chat unless a chat has its own. Also used on the call screen.",
        onClick = { pickWallpaper.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
    if (s.wallpaper.isNotBlank()) {
        SliderRow("Darken wallpaper", s.wallpaperDim, 0f..0.8f, "${(s.wallpaperDim * 100).toInt()}%") { v -> set { it.copy(wallpaperDim = v) } }
        SettingRow("Remove wallpaper", onClick = { set { it.copy(wallpaper = "") } })
    }
}

@Composable
private fun PreviewBubbles() {
    val st = LocalChatStyle.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
            Text("Hey, you coming tonight?", color = st.recvText, modifier = Modifier
                .clip(st.bubble(false, true, true)).background(st.recv).padding(horizontal = 14.dp, vertical = 9.dp))
        }
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.CenterEnd) {
            Text("Yeah, on my way 🚗", color = st.sentText, modifier = Modifier
                .clip(st.bubble(true, true, true)).background(st.sent).padding(horizontal = 14.dp, vertical = 9.dp))
        }
    }
}


// ---------------- Messaging ----------------

@Composable
private fun MessagingPage() {
    val s by Hub.settings.flow.collectAsState()
    var autoText by remember { mutableStateOf(s.autoReplyText) }
    var myName by remember { mutableStateOf(s.myName) }
    SectionTitle("Sending")
    ChoiceRow("Send texts through", listOf("auto" to "Google/Samsung Messages (RCS), SMS backup", "sms" to "Plain SMS only"), s.engine) { v -> set { it.copy(engine = v) } }
    Text("Stealth rule: nothing Contact Hub sends looks different from your normal Messages app. No branding, no duplicate texts, reactions and edits only over RCS, and group names/photos stay on your phone.",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SectionTitle("Reactions")
    SwitchRow("Turn \"Liked …\" texts into reactions", "When iPhone or SMS contacts react, show a real reaction instead of the text", s.convertReactions) { v -> set { it.copy(convertReactions = v) } }
    SectionTitle("History")
    SwitchRow("Show chats older than 6 months", "Off = faster loading", s.olderThan6Months) { v -> set { it.copy(olderThan6Months = v) } }
    SectionTitle("@mentions")
    OutlinedTextField(myName, { myName = it; set { st -> st.copy(myName = it.trim()) } }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        label = { Text("Your name (highlighted + priority notification in groups)") }, singleLine = true)
    SectionTitle("Auto-reply")
    SwitchRow("Auto-reply", "Also on the Quick Settings tile and widget. Max once every 15 min per person, 1-on-1 chats only.", s.autoReplyOn) { v ->
        set { it.copy(autoReplyOn = v) }; HubWidget.refresh(Hub.app)
    }
    OutlinedTextField(autoText, { autoText = it; set { st -> st.copy(autoReplyText = it) } }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        label = { Text("Auto-reply message") }, minLines = 2)
}

@Composable
private fun NotificationsPage() {
    val ctx = LocalContext.current
    val s by Hub.settings.flow.collectAsState()
    SwitchRow("Contact Hub shows message notifications", "Replaces the Google Messages notification with Contact Hub's (needs notification access)", s.handleNotifications) { v ->
        set { it.copy(handleNotifications = v) }
    }
    SwitchRow("Hide message previews", "Notifications just say \"New message\"", s.hidePreviews) { v -> set { it.copy(hidePreviews = v) } }
    SettingRow("Priority contacts", "Turn on per contact from their contact page. Their messages come through Do Not Disturb. In Android settings, allow the \"Priority contacts\" channel to override Do Not Disturb.")
    SettingRow("Notification settings in Android", "Sounds, vibration, and per-contact channels", onClick = {
        ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    })
    SettingRow("Per-contact sounds", "Open a chat → ⋮ → Notification sound")
}

@Composable
private fun PrivacyPage() {
    val ctx = LocalContext.current
    val s by Hub.settings.flow.collectAsState()
    var pinDialog by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) { blocked = Hub.contacts.blockedList() }
    SwitchRow("App lock", "Ask for fingerprint or PIN to open Contact Hub", s.appLock, Icons.Default.Lock) { v ->
        if (v && s.pinHash.isBlank() && !(ctx is MainActivity && canUseBiometric(ctx))) pinDialog = true
        set { it.copy(appLock = v) }
    }
    SwitchRow("Use fingerprint / face", null, s.useBiometric) { v -> set { it.copy(useBiometric = v) } }
    SettingRow(if (s.pinHash.isBlank()) "Set PIN" else "Change PIN", "Backup when fingerprint isn't available", Icons.Default.Security, onClick = { pinDialog = true })
    ChoiceRow("Lock after leaving the app", listOf("0" to "Right away", "30" to "30 sec", "60" to "1 min", "300" to "5 min"), s.lockAfterSec.toString()) { v ->
        set { it.copy(lockAfterSec = v.toInt()) }
    }
    SectionTitle("Hidden chats")
    SettingRow("Open hidden chats", "Long-press any chat → Hide. Hidden chats always need your fingerprint or PIN.", onClick = {
        (ctx as? MainActivity)?.let { unlockHidden(it) { Nav.go(Screen.Hidden) } }
    })
    SectionTitle("Blocked numbers (${blocked.size})")
    blocked.forEach { n ->
        SettingRow(Hub.contacts.cached(n)?.name ?: com.yatesv.contacthub.data.Phone.pretty(n)) {
            TextButton({ Hub.contacts.setBlocked(n, false); blocked = Hub.contacts.blockedList() }) { Text("Unblock") }
        }
    }
    if (pinDialog) {
        var pin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pinDialog = false },
            title = { Text("Set PIN") },
            text = {
                OutlinedTextField(pin, { if (it.all(Char::isDigit) && it.length <= 12) pin = it }, label = { Text("4–12 digits") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
            },
            confirmButton = { TextButton({ if (pin.length >= 4) { set { it.copy(pinHash = hashPin(pin)) }; pinDialog = false } }) { Text("Save") } },
            dismissButton = { TextButton({ pinDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Life360Page() {
    val scope = rememberCoroutineScope()
    val s by Hub.settings.flow.collectAsState()
    val members by Hub.l360.members.collectAsState()
    val status by Hub.l360.status.collectAsState()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    Text("Life360 has no official way for other apps to connect. Contact Hub uses the same unofficial connection Home Assistant uses, so it can break if Life360 changes things. The Open Life360 button always works. Your login stays on this phone.",
        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (status.isNotBlank()) Banner(status) {}
    if (s.l360Token.isNotBlank()) {
        SettingRow("Signed in", "${members.size} people · last update ${if (Hub.l360.lastSync == 0L) "never" else com.yatesv.contacthub.data.Geo.ago(Hub.l360.lastSync)}") {
            TextButton({ scope.launch { busy = true; Hub.l360.refresh(s.l360Token); busy = false } }, enabled = !busy) { Text("Refresh") }
        }
        ChoiceRow("Refresh location", listOf("0" to "When I open a contact", "15" to "Every 15 min", "30" to "Every 30 min", "60" to "Every hour"), s.l360RefreshMin.toString()) { v ->
            set { it.copy(l360RefreshMin = v.toInt()) }; HubApp.scheduleWork(Hub.app)
        }
        Text("Android doesn't allow background refresh more often than every 15 minutes.", Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle("People")
        members.forEach { m -> SettingRow(m.name + if (m.id == Hub.l360.meId) " (you)" else "", listOf(m.place, m.phone).filter { it.isNotBlank() }.joinToString(" · ")) }
        SettingRow("Sign out of Life360", onClick = { Hub.l360.signOut(); set { it.copy(l360Token = "") }; HubApp.scheduleWork(Hub.app) })
    } else {
        SectionTitle("Option 1: Email/phone + password")
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("Life360 email or phone") }, singleLine = true)
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        Button({
            scope.launch {
                busy = true; msg = ""
                runCatching { Hub.l360.login(user.trim(), pass) }
                    .onSuccess { t -> set { it.copy(l360Token = t) }; Hub.l360.refresh(t); HubApp.scheduleWork(Hub.app); pass = "" }
                    .onFailure { msg = it.message ?: "Login failed" }
                busy = false
            }
        }, Modifier.padding(16.dp), enabled = !busy && user.isNotBlank() && pass.isNotBlank()) { Text("Sign in") }
        SectionTitle("Option 2: Paste an access token")
        Text("If Life360 blocks password login (it often does now): on your PC, sign in at life360.com, press F12 → Network, refresh, click any request to api-cloudfront.life360.com and copy the value after \"Authorization: Bearer\".",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth().padding(16.dp), label = { Text("Access token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        Button({
            scope.launch {
                busy = true; msg = ""
                val t = token.trim()
                Hub.l360.refresh(t).onSuccess { set { st -> st.copy(l360Token = t) }; HubApp.scheduleWork(Hub.app); token = "" }
                    .onFailure { msg = it.message ?: "That token didn't work" }
                busy = false
            }
        }, Modifier.padding(horizontal = 16.dp), enabled = !busy && token.isNotBlank()) { Text("Connect") }
        if (msg.isNotBlank()) Text(msg, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun BackupPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Hub.settings.flow.collectAsState()
    var msg by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { runCatching { Backup.exportTo(ctx, uri) }.onSuccess { msg = "Exported" }.onFailure { msg = "Export failed: ${it.message}" } }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { msg = runCatching { Backup.restore(ctx, uri) }.getOrElse { "Restore failed: ${it.message}" } }
    }
    SwitchRow("Daily backup", "Overnight while charging. Keeps the last 7 days on this phone.", s.backupDaily) { v ->
        set { it.copy(backupDaily = v) }; HubApp.scheduleWork(Hub.app)
    }
    SettingRow("Last backup", if (s.lastBackup == 0L) "Never" else fullTime(s.lastBackup))
    SettingRow("Back up now", "Saves to Android/data/com.yatesv.contacthub/files/backups", onClick = {
        scope.launch { msg = runCatching { "Saved ${Backup.writeDaily(ctx).name}" }.getOrElse { "Backup failed: ${it.message}" } }
    })
    SettingRow("Export backup file…", "Save it anywhere, including Google Drive (pick Drive in the file picker)", onClick = {
        export.launch("contacthub-backup.json")
    })
    SettingRow("Restore from file…", "Brings back your socials, notes, settings, folders, reactions and RCS history", onClick = { restore.launch(arrayOf("application/json", "*/*")) })
    Text("Backups include a copy of your texts. Texts themselves stay in your phone's messages, which Google Messages already backs up.",
        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (msg.isNotBlank()) Text(msg, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun AboutPage() {
    SettingRow("Contact Hub", "Version ${BuildConfig.VERSION_NAME}")
    HorizontalDivider()
    SettingRow("How RCS works here", "Google Messages stays your default texting app. Contact Hub sends through its reply button or taps Send in it for you, so everything really comes from Google Messages. If that fails for a 1-on-1 chat, it sends a normal SMS. Group texts are never split into separate texts.")
    SettingRow("Reactions", "Only sent over RCS. If a chat is on SMS, your reaction waits on your phone (🕓) and sends once RCS is back. Never sent as a \"Liked…\" text.")
    SettingRow("What can break", "Google/Samsung Messages updates can break the RCS sender, and Life360 changes can break live location. The rest of the app keeps working, and a banner tells you.")
    SettingRow("Not in this version", "Call recording (Android blocks other apps from recording the other person), visual voicemail, and the unified Instagram/Snapchat inbox (planned).")
}
