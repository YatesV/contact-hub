package com.yatesv.contacthub.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.HubApp
import com.yatesv.contacthub.engine.HubSms
import com.yatesv.contacthub.engine.MessagesAccessibility
import com.yatesv.contacthub.engine.MessagesListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val setupPerms = arrayOf(
    Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
    Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
    Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.CALL_PHONE,
    Manifest.permission.READ_CALL_LOG, Manifest.permission.POST_NOTIFICATIONS,
)

fun permsGranted(ctx: Context) = setupPerms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
fun listenerOn(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

fun openAccessibility(ctx: Context) {
    val comp = ComponentName(ctx, MessagesAccessibility::class.java).flattenToString()
    val direct = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, comp)
    runCatching { ctx.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun openListener(ctx: Context) {
    val comp = ComponentName(ctx, MessagesListener::class.java).flattenToString()
    val direct = Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(AndroidSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, comp)
    runCatching { ctx.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun openAppInfo(ctx: Context) = ctx.startActivity(
    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

private enum class Step { WELCOME, PERMS, ACCESS, LISTENER, DEFAULT_SMS, PHONE, ACCOUNTS, DONE }

/**
 * Full-screen, one-step-at-a-time setup. Each step shows a picture of where to tap,
 * notices when you come back with it done, and moves on by itself.
 */
@Composable
fun SetupWizard(first: Boolean) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    var hubMode by remember { mutableStateOf(HubSms.isDefault(ctx)) }
    val steps = remember(hubMode) {
        if (hubMode) listOf(Step.WELCOME, Step.PERMS, Step.DEFAULT_SMS, Step.PHONE, Step.ACCOUNTS, Step.DONE)
        else listOf(Step.WELCOME, Step.PERMS, Step.ACCESS, Step.LISTENER, Step.PHONE, Step.ACCOUNTS, Step.DONE)
    }
    var index by remember { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    val step = steps[index.coerceIn(0, steps.lastIndex)]
    val rm = ctx.getSystemService(RoleManager::class.java)

    @Suppress("UNUSED_EXPRESSION") tick
    fun done(s: Step): Boolean = when (s) {
        Step.PERMS -> permsGranted(ctx)
        Step.ACCESS -> MessagesAccessibility.running()
        Step.LISTENER -> listenerOn(ctx)
        Step.DEFAULT_SMS -> HubSms.isDefault(ctx)
        Step.PHONE -> rm.isRoleHeld(RoleManager.ROLE_DIALER)
        else -> false
    }
    fun next() { forward = true; if (index < steps.lastIndex) index++ }
    fun prev() { forward = false; if (index > 0) index-- }
    fun finish() {
        Hub.settings.update { it.copy(onboarded = true, setupVersion = SETUP_VERSION) }
        HubApp.scheduleWork(Hub.app)
        if (Nav.top == Screen.Setup) Nav.back()
    }
    var life360 by remember { mutableStateOf(false) }
    var friends by remember { mutableStateOf<com.yatesv.contacthub.data.Social?>(null) }
    BackHandler(index > 0 || life360 || friends != null) { if (life360) life360 = false else if (friends != null) friends = null else prev() }
    if (life360) { Life360SignInScreen(onDone = { life360 = false }); return }
    friends?.let { f ->
        // Inside the wizard there's no back stack yet, so show sync here and come back after
        Column(Modifier.fillMaxSize()) {
            TextButton({ friends = null }, Modifier.statusBarsPadding()) { Text("← Back to setup") }
            Box(Modifier.weight(1f)) { FriendSyncScreen(f) }
        }
        return
    }

    var permsBlocked by remember { mutableStateOf(false) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        // Android 15 blocks SMS/call-log permissions for apps installed from a file until "restricted settings" are allowed
        permsBlocked = res.filterKeys { it.contains("SMS") || it.contains("CALL_LOG") }.values.any { !it }
        tick++; Hub.changed()
    }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    // Came back with the step done: tick, then move on by itself
    val isDone = done(step)
    LaunchedEffect(step, isDone) {
        if (isDone && step !in setOf(Step.WELCOME, Step.ACCOUNTS, Step.DONE)) {
            Haptics.confirm(view); delay(800); next()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator({ (index + 1f) / steps.size }, Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)))
            if (step != Step.DONE) TextButton({ finish() }) { Text(if (first) "Skip setup" else "Close") }
        }
        AnimatedContent(step, Modifier.weight(1f), label = "setup", transitionSpec = {
            val dir = if (forward) 1 else -1
            (slideInHorizontally(Motion.spec()) { it * dir } + fadeIn()) togetherWith (slideOutHorizontally(Motion.spec()) { -it * dir / 3 } + fadeOut())
        }) { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (s) {
                    Step.WELCOME -> Welcome(hubMode) { hubMode = it }
                    Step.PERMS -> StepPage("Allow access", "So Contact Hub can show your texts, contacts and calls. Tap the button and choose Allow on each one.",
                        done(s), { PhoneMock(listOf("Allow Contact Hub to send and view SMS?"), dialog = true) }) {
                        Button({ perms.launch(setupPerms) }, Modifier.fillMaxWidth()) { Text("Allow") }
                        if (permsBlocked) {
                            Text("Android blocked texts/calls access (\"App was denied access to SMS\"). That's normal for apps installed from a file. Unlock it once:",
                                Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            Text("Tap Unlock → ⋮ (top right) → Allow restricted settings → confirm → come back and tap Allow again.",
                                Modifier.padding(vertical = 8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                            PhoneMock(listOf("App info", "Contact Hub"), menu = true)
                            OutlinedButton({ openAppInfo(ctx) }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Unlock") }
                        }
                    }
                    Step.ACCESS -> StepPage("Turn on the RCS sender",
                        "This lets Contact Hub tap Send inside Google Messages, so your texts go over RCS. Tap Open, then switch on \"Contact Hub RCS sender\".",
                        done(s), { PhoneMock(listOf("Installed apps", "Contact Hub RCS sender"), highlight = 1, toggle = true) }) {
                        Button({ openAccessibility(ctx) }, Modifier.fillMaxWidth()) { Text("Open") }
                        RestrictedHelp(ctx)
                    }
                    Step.LISTENER -> StepPage("Turn on notification access",
                        "Lets Contact Hub see RCS messages and reply through Google Messages. Tap Open and switch on Contact Hub.",
                        done(s), { PhoneMock(listOf("Notification read, reply and control", "Contact Hub"), highlight = 1, toggle = true) }) {
                        Button({ openListener(ctx) }, Modifier.fillMaxWidth()) { Text("Open") }
                        RestrictedHelp(ctx)
                    }
                    Step.DEFAULT_SMS -> StepPage("Make Contact Hub your texting app",
                        "In Contact Hub mode it sends and receives all texts and picture messages itself. There's no RCS in this mode.",
                        done(s), { PhoneMock(listOf("Set Contact Hub as your default SMS app?", "Google Messages", "Contact Hub"), highlight = 2, dialog = true) }) {
                        Button({
                            if (rm.isRoleAvailable(RoleManager.ROLE_SMS)) role.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
                        }, Modifier.fillMaxWidth()) { Text("Set as default") }
                    }
                    Step.PHONE -> StepPage("Default phone app (optional)",
                        "Gives you Contact Hub's call screen and lets you block numbers. You can skip this.",
                        done(s), { PhoneMock(listOf("Set Contact Hub as your default phone app?", "Phone", "Contact Hub"), highlight = 2, dialog = true) }) {
                        if (rm.isRoleAvailable(RoleManager.ROLE_DIALER)) Button({ role.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)) }, Modifier.fillMaxWidth()) { Text("Set as default") }
                        TextButton({ next() }) { Text("Skip") }
                    }
                    Step.ACCOUNTS -> AccountsStep({ life360 = true }, { friends = it })
                    Step.DONE -> DonePage()
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            if (index > 0) OutlinedButton({ prev() }) { Text("Back") } else Spacer(Modifier.width(1.dp))
            when (step) {
                Step.DONE -> Button({ finish() }) { Text("Start using Contact Hub") }
                Step.WELCOME, Step.ACCOUNTS -> Button({ next() }) { Text("Next") }
                else -> Button({ next() }) { Text(if (done(step)) "Next" else "Do this later") }
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") scope
}

@Composable
private fun Welcome(hubMode: Boolean, onMode: (Boolean) -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text("👋", fontSize = 56.sp)
    Text("Welcome to Contact Hub", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Text("Texts, contacts, calls, socials and Life360 in one place. First, pick how texting works:",
        Modifier.padding(vertical = 12.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ModeCard("Google Messages mode", "Recommended",
        "Keeps RCS: typing dots, read receipts, HD photos, reactions. Google Messages stays installed and does the sending in the background.",
        !hubMode) { onMode(false) }
    ModeCard("Contact Hub mode", null,
        "Contact Hub is your texting app and does everything itself. Most reliable, but plain SMS/MMS only (no RCS).",
        hubMode) { onMode(true) }
    Text("You can switch anytime in Settings → Messaging.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ModeCard(title: String, badge: String?, body: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(20.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (badge != null) Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (selected) Icon(Icons.Default.CheckCircle, null, Modifier.padding(start = 8.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun StepPage(title: String, body: String, done: Boolean, picture: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Box(contentAlignment = Alignment.Center) {
        picture()
        androidx.compose.animation.AnimatedVisibility(done, enter = scaleIn(Motion.popSpec()) + fadeIn()) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF34C759)), contentAlignment = Alignment.Center) {
                Text("✓", color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Text(if (done) "Done! Moving on…" else body, Modifier.padding(vertical = 10.dp), textAlign = TextAlign.Center,
        color = if (done) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant)
    if (!done) Column(horizontalAlignment = Alignment.CenterHorizontally) { actions() }
}

/** Samsung blocks these switches for apps installed from a file until you unlock them. */
@Composable
private fun RestrictedHelp(ctx: Context) {
    var open by remember { mutableStateOf(false) }
    TextButton({ open = !open }) { Text(if (open) "Hide help" else "Switch is greyed out / says \"Restricted setting\"?") }
    if (open) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp)) {
        Text("That's normal for apps installed from a file. To unlock it:", fontWeight = FontWeight.SemiBold)
        Text("1. Tap the switch once so Android shows \"Restricted setting\", then tap OK. (Already did this for text permissions? Skip to 4.)\n" +
            "2. Tap Unlock below. It opens Contact Hub's App info.\n" +
            "3. Tap ⋮ in the top right → Allow restricted settings → confirm with your fingerprint/PIN.\n" +
            "4. Come back here and tap Open again. The switch works now.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        PhoneMock(listOf("App info", "Contact Hub"), menu = true)
        Spacer(Modifier.height(8.dp))
        OutlinedButton({ openAppInfo(ctx) }, Modifier.fillMaxWidth()) { Text("Unlock") }
    }
}

@Composable
private fun AccountsStep(openLife360: () -> Unit, openFriends: (com.yatesv.contacthub.data.Social) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by Hub.settings.flow.collectAsState()
    var found by remember { mutableStateOf<List<SocialImport.Found>?>(null) }
    var imported by remember { mutableIntStateOf(-1) }
    val drive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) connectDrive(ctx, uri)
    }
    LaunchedEffect(Unit) { found = runCatching { SocialImport.scan(ctx) }.getOrDefault(emptyList()) }
    Spacer(Modifier.height(8.dp))
    Text("🔗", fontSize = 52.sp)
    Text("Connect your stuff", style = MaterialTheme.typography.headlineSmall)
    Text("All optional. You can do these later in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
    AccountRow("📍", "Life360", if (s.l360Token.isNotBlank()) "Connected" else "Sign in on Life360's own page, right here", s.l360Token.isNotBlank()) { openLife360() }
    AccountRow("👥", "Socials from your contacts",
        when { imported >= 0 -> "Added $imported"; found == null -> "Looking…"; found!!.isEmpty() -> "None saved in your contacts"; else -> "Found ${found!!.size}. Tap to add them" },
        imported >= 0) {
        found?.takeIf { it.isNotEmpty() }?.let { list -> scope.launch { imported = SocialImport.apply(list) } }
    }
    AccountRow("📸", "Instagram friends", if (FriendStore.syncedAt(ctx, com.yatesv.contacthub.data.Social.INSTAGRAM) > 0) "Synced" else "Sign in to match who you follow with your contacts",
        FriendStore.syncedAt(ctx, com.yatesv.contacthub.data.Social.INSTAGRAM) > 0) { openFriends(com.yatesv.contacthub.data.Social.INSTAGRAM) }
    AccountRow("🎵", "TikTok friends", if (FriendStore.syncedAt(ctx, com.yatesv.contacthub.data.Social.TIKTOK) > 0) "Synced" else "Same for TikTok",
        FriendStore.syncedAt(ctx, com.yatesv.contacthub.data.Social.TIKTOK) > 0) { openFriends(com.yatesv.contacthub.data.Social.TIKTOK) }
    AccountRow("☁️", "Google Drive backup", if (s.driveUri.isNotBlank()) "Connected" else "Pick a spot in Drive once; it backs up there daily", s.driveUri.isNotBlank()) {
        drive.launch("contacthub-backup.json")
    }
}

/** Saves the Drive file Contact Hub may keep writing its daily backup to. */
fun connectDrive(ctx: Context, uri: Uri) {
    runCatching {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    Hub.settings.update { it.copy(driveUri = uri.toString()) }
    Hub.scope.launch { runCatching { com.yatesv.contacthub.engine.Backup.writeDaily(ctx) } }
}

@Composable
private fun AccountRow(emoji: String, title: String, sub: String, done: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 26.sp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (done) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF34C759)) else Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DonePage() {
    Spacer(Modifier.height(16.dp))
    Text("🎉", fontSize = 56.sp)
    Text("You're all set", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    listOf(
        "💬" to "Long-press a message to react, copy or star it.",
        "👤" to "Tap a name at the top of a chat to open their contact page.",
        "📸" to "On a contact page, tap Instagram, Snapchat or TikTok, then search their name to link them.",
        "↔️" to "Swipe chats left or right to archive or pin.",
        "🧪" to "Something not working? Settings → Developer → Run full self-test, then send me the log.",
    ).forEach { (e, t) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(e, fontSize = 22.sp); Spacer(Modifier.width(12.dp)); Text(t, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Simple drawing of a phone screen showing where to tap (rows, a switch, a dialog, or the ⋮ menu).
 * Drawn by the app, so it matches your theme.
 */
@Composable
fun PhoneMock(rows: List<String>, highlight: Int = -1, toggle: Boolean = false, dialog: Boolean = false, menu: Boolean = false) {
    val pulse = rememberInfiniteTransition(label = "tap").animateFloat(0.6f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
    val surface = MaterialTheme.colorScheme.surfaceContainer
    Box(Modifier.width(230.dp).height(200.dp).clip(RoundedCornerShape(26.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .border(4.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(26.dp)).padding(14.dp)) {
        Column(Modifier.fillMaxSize()) {
            if (dialog) {
                Spacer(Modifier.height(20.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(surface).padding(12.dp)) {
                    Text(rows.first(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    rows.drop(1).forEachIndexed { i, r ->
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                .background(if (i + 1 == highlight) MaterialTheme.colorScheme.primary else Color.Transparent))
                            Text(r, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    if (rows.size == 1) Text("Allow", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.End).padding(top = 10.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f * pulse.value)).padding(horizontal = 10.dp, vertical = 4.dp))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", fontSize = 18.sp); Spacer(Modifier.width(6.dp))
                    Text(rows.first(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (menu) Text("⋮", fontSize = 18.sp, modifier = Modifier.clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f * pulse.value)).padding(horizontal = 8.dp))
                }
                Spacer(Modifier.height(10.dp))
                rows.drop(1).forEachIndexed { i, r ->
                    val hi = i + 1 == highlight
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (hi) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f * pulse.value) else surface).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(r, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        if (toggle && hi) Box(Modifier.width(28.dp).height(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) {
                            Box(Modifier.padding(2.dp).size(12.dp).offset(x = 12.dp).clip(CircleShape).background(Color.White))
                        }
                    }
                }
                if (menu) Column(Modifier.align(Alignment.End).clip(RoundedCornerShape(10.dp)).background(surface).padding(8.dp)) {
                    Text("Allow restricted settings", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
                repeat(3 - rows.size.coerceAtMost(3)) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(30.dp).clip(RoundedCornerShape(10.dp)).background(surface))
                }
            }
        }
    }
}


@Suppress("unused") private val keepBuild = Build.VERSION.SDK_INT
