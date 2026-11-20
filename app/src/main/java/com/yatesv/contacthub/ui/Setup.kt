package com.yatesv.contacthub.ui

import android.Manifest
import android.annotation.SuppressLint
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

/** One thing Android needs before Orbit works. Checked live, so the list updates as you come back. */
data class SetupItem(
    val id: String, val title: String, val why: String, val button: String,
    val done: Boolean, val optional: Boolean = false, val restricted: Boolean = false,
)

object SetupCheck {
    /**
     * Android 13+ blocks accessibility, notification access (and on Android 15, texts/call log) for apps
     * installed from a file until you tap ⋮ → "Allow restricted settings" on Orbit's App info.
     * true = unlocked, false = still locked, null = this phone doesn't say.
     */
    fun restrictedUnlocked(ctx: Context): Boolean? = runCatching {
        if (Build.VERSION.SDK_INT < 33) return true
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        when (ops.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), ctx.packageName)) {
            android.app.AppOpsManager.MODE_ALLOWED, android.app.AppOpsManager.MODE_DEFAULT -> true
            else -> false
        }
    }.getOrNull()

    fun batteryOk(ctx: Context) = ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    fun googleMessagesInstalled(ctx: Context) = com.yatesv.contacthub.engine.Sender.installed(ctx, com.yatesv.contacthub.engine.GOOGLE_MESSAGES)
    fun googleMessagesDefault(ctx: Context) = android.provider.Telephony.Sms.getDefaultSmsPackage(ctx) == com.yatesv.contacthub.engine.GOOGLE_MESSAGES

    /** Everything to do, in the order that works (unlock first, or the other switches stay greyed out). */
    fun items(ctx: Context, hubMode: Boolean): List<SetupItem> {
        val rm = ctx.getSystemService(RoleManager::class.java)
        val needsRestricted = if (hubMode) !permsGranted(ctx) else !(MessagesAccessibility.enabledInSettings(ctx) && listenerOn(ctx) && permsGranted(ctx))
        val unlocked = restrictedUnlocked(ctx)
        val out = ArrayList<SetupItem>()
        // Only shown when this phone says it's locked and something still needs it
        if (unlocked == false && needsRestricted) out += SetupItem("unlock", "Unlock Orbit (one time)",
            "Android locks some switches for apps installed from a file. This unlocks them.", "Unlock", false, restricted = true)
        out += SetupItem("perms", "Allow texts, contacts and calls", "So Orbit can show and send your texts, contacts and calls.", "Allow", permsGranted(ctx))
        if (!hubMode) {
            if (!googleMessagesInstalled(ctx)) out += SetupItem("gm_install", "Install Google Messages",
                "Orbit sends your texts through it, so they go out over RCS.", "Get Google Messages", false)
            else out += SetupItem("gm_default", "Google Messages as your texting app",
                "It handles RCS in the background. Orbit is what you use.", "Set it", googleMessagesDefault(ctx))
            out += SetupItem("access", "Turn on the RCS sender", "Lets Orbit tap Send inside Google Messages for you.", "Turn on",
                MessagesAccessibility.enabledInSettings(ctx))
            out += SetupItem("listener", "Turn on notification access", "Lets Orbit see RCS messages and reply through Google Messages.", "Turn on", listenerOn(ctx))
        } else {
            out += SetupItem("sms", "Make Orbit your texting app", "Orbit sends and receives all texts and picture messages itself.", "Set as default", HubSms.isDefault(ctx))
        }
        out += SetupItem("battery", "Keep Orbit running", "Stops Samsung from putting Orbit to sleep, so texts and the RCS sender keep working.", "Allow", batteryOk(ctx))
        if (rm.isRoleAvailable(RoleManager.ROLE_DIALER)) out += SetupItem("phone", "Orbit as your phone app", "Orbit's call screen, spam warnings and blocking.", "Set as default",
            rm.isRoleHeld(RoleManager.ROLE_DIALER), optional = true)
        return out
    }
}

private enum class Page { WELCOME, CHECKLIST, EXTRAS, DONE }

/**
 * Easy setup: pick a mode, then one screen with one big button that always does the next thing.
 * Every item checks itself when you come back, so there's nothing to remember.
 */
@Composable
fun SetupWizard(first: Boolean) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    var hubMode by remember { mutableStateOf(HubSms.isDefault(ctx)) }
    var page by remember { mutableStateOf(if (first) Page.WELCOME else Page.CHECKLIST) }
    var forward by remember { mutableStateOf(true) }
    fun go(p: Page) { forward = p.ordinal > page.ordinal; page = p }
    fun finish() {
        Hub.settings.update { it.copy(onboarded = true, setupVersion = SETUP_VERSION) }
        HubApp.scheduleWork(Hub.app)
        if (Nav.top == Screen.Setup) Nav.back()
    }
    var life360 by remember { mutableStateOf(false) }
    var friends by remember { mutableStateOf<com.yatesv.contacthub.data.Social?>(null) }
    BackHandler(page != Page.WELCOME || life360 || friends != null) {
        when {
            life360 -> life360 = false
            friends != null -> friends = null
            page == Page.CHECKLIST && first -> go(Page.WELCOME)
            page == Page.EXTRAS -> go(Page.CHECKLIST)
            page == Page.DONE -> go(Page.EXTRAS)
            else -> finish()
        }
    }
    if (life360) { Life360SignInScreen(onDone = { life360 = false }); return }
    friends?.let { f ->
        Column(Modifier.fillMaxSize()) {
            TextButton({ friends = null }, Modifier.statusBarsPadding()) { Text("← Back to setup") }
            Box(Modifier.weight(1f)) { FriendSyncScreen(f) }
        }
        return
    }

    @Suppress("UNUSED_EXPRESSION") tick
    val items = SetupCheck.items(ctx, hubMode)
    val required = items.filter { !it.optional }
    val doneCount = required.count { it.done }
    val next = items.firstOrNull { !it.done && !it.optional } ?: items.firstOrNull { !it.done }

    var permsBlocked by remember { mutableStateOf(false) }
    var unlockStage by remember { mutableIntStateOf(0) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permsBlocked = res.filterKeys { it.contains("SMS") || it.contains("CALL_LOG") }.values.any { !it }
        tick++; Hub.changed()
    }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val rm = ctx.getSystemService(RoleManager::class.java)

    fun run(item: SetupItem) {
        Haptics.tap(view)
        when (item.id) {
            "unlock" -> if (unlockStage == 0) { unlockStage = 1; openAccessibility(ctx) } else { unlockStage = 2; openAppInfo(ctx) }
            "perms" -> if (permsBlocked && SetupCheck.restrictedUnlocked(ctx) != true) openAppInfo(ctx) else perms.launch(setupPerms)
            "gm_install" -> runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${com.yatesv.contacthub.engine.GOOGLE_MESSAGES}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            "gm_default" -> runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "access" -> openAccessibility(ctx)
            "listener" -> openListener(ctx)
            "sms" -> if (rm.isRoleAvailable(RoleManager.ROLE_SMS)) role.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
            "battery" -> runCatching {
                @SuppressLint("BatteryLife")
                val i = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            "phone" -> role.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }
    }

    // Everything required done: buzz once and move on by itself
    LaunchedEffect(page, doneCount == required.size) {
        if (page == Page.CHECKLIST && doneCount == required.size && required.isNotEmpty()) { Haptics.confirm(view); delay(900); go(Page.EXTRAS) }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator({ (page.ordinal + if (page == Page.CHECKLIST) doneCount.toFloat() / required.size.coerceAtLeast(1) else 0f) / 3f },
                Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)))
            if (page != Page.DONE) TextButton({ finish() }) { Text(if (first) "Skip" else "Close") }
        }
        AnimatedContent(page, Modifier.weight(1f), label = "setup", transitionSpec = {
            val dir = if (forward) 1 else -1
            (slideInHorizontally(Motion.spec()) { it * dir } + fadeIn()) togetherWith (slideOutHorizontally(Motion.spec()) { -it * dir / 3 } + fadeOut())
        }) { p ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (p) {
                    Page.WELCOME -> Welcome(hubMode) { hubMode = it }
                    Page.CHECKLIST -> Checklist(items, next, doneCount, required.size, unlockStage, permsBlocked, ::run)
                    Page.EXTRAS -> AccountsStep({ life360 = true }, { friends = it })
                    Page.DONE -> DonePage()
                }
            }
        }
        // One big button that always does the right next thing
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            when (page) {
                Page.WELCOME -> BigButton("Get started") { go(Page.CHECKLIST) }
                Page.CHECKLIST -> {
                    if (next != null && !(next.optional && doneCount == required.size)) BigButton(
                        when {
                            next.id == "unlock" && unlockStage == 0 -> "Step 1: Try the switch"
                            next.id == "unlock" -> "Step 2: Unlock"
                            next.id == "perms" && permsBlocked -> "Unlock texts access"
                            else -> next.button + " · " + next.title.substringBefore(" (")
                        }) { run(next) }
                    else BigButton("Next") { go(Page.EXTRAS) }
                    TextButton({ go(Page.EXTRAS) }, Modifier.align(Alignment.CenterHorizontally)) {
                        Text(if (doneCount == required.size) "Continue" else "Do the rest later")
                    }
                }
                Page.EXTRAS -> BigButton("Next") { go(Page.DONE) }
                Page.DONE -> BigButton("Start using Orbit") { finish() }
            }
        }
    }
}

@Composable
private fun BigButton(label: String, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Checklist(items: List<SetupItem>, next: SetupItem?, done: Int, total: Int, unlockStage: Int, permsBlocked: Boolean, run: (SetupItem) -> Unit) {
    Spacer(Modifier.height(8.dp))
    // Progress ring
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
        val anim by androidx.compose.animation.core.animateFloatAsState(done.toFloat() / total.coerceAtLeast(1), Motion.spec(), label = "ring")
        androidx.compose.material3.CircularProgressIndicator({ anim }, Modifier.size(120.dp), strokeWidth = 10.dp,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
        if (done == total) Text("✓", fontSize = 48.sp, color = Color(0xFF34C759), fontWeight = FontWeight.Bold)
        else Text("$done/$total", fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
    Text(if (done == total) "All set!" else "Tap the big button. Orbit checks each one for you.",
        Modifier.padding(vertical = 12.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)

    items.forEach { it ->
        val isNext = it == next
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .background(if (isNext) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = !it.done) { run(it) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(if (it.done) Color(0xFF34C759) else if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                contentAlignment = Alignment.Center) {
                Text(if (it.done) "✓" else if (it.optional) "·" else "", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(it.title + if (it.optional) " (optional)" else "", style = MaterialTheme.typography.titleSmall,
                    color = if (it.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (!it.done) Text(it.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Picture + exact taps for the item you're on
        if (isNext && !it.done) Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (it.id) {
                "unlock" -> {
                    Text(if (unlockStage == 0) "Tap \"Step 1\". On the next screen, tap the switch. Android says \"Restricted setting\". Tap OK and come back."
                        else "Now tap \"Step 2\". Tap ⋮ in the top right → Allow restricted settings → confirm. Then come back.",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    if (unlockStage == 0) PhoneMock(listOf("Orbit RCS sender", "Use Orbit RCS sender"), highlight = 1, toggle = true)
                    else PhoneMock(listOf("App info", "Orbit"), menu = true)
                }
                "perms" -> if (permsBlocked) {
                    Text("Android blocked texts access. That's normal for apps installed from a file: tap the button, then ⋮ → Allow restricted settings, then come back.",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp)); PhoneMock(listOf("App info", "Orbit"), menu = true)
                } else PhoneMock(listOf("Allow Orbit to send and view SMS?"), dialog = true)
                "access" -> PhoneMock(listOf("Installed apps", "Orbit RCS sender"), highlight = 1, toggle = true)
                "listener" -> PhoneMock(listOf("Notification read, reply and control", "Orbit"), highlight = 1, toggle = true)
                "gm_default" -> PhoneMock(listOf("Default apps", "SMS app: Messages"), highlight = 1)
                "sms" -> PhoneMock(listOf("Set Orbit as your default SMS app?", "Google Messages", "Orbit"), highlight = 2, dialog = true)
                "battery" -> PhoneMock(listOf("Let app always run in background?", "Allow"), highlight = 1, dialog = true)
                "phone" -> PhoneMock(listOf("Set Orbit as your default phone app?", "Phone", "Orbit"), highlight = 2, dialog = true)
            }
        }
    }
}

@Composable
private fun Welcome(hubMode: Boolean, onMode: (Boolean) -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text("👋", fontSize = 56.sp)
    Text("Welcome to Orbit", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Text("Texts, contacts, calls, socials and Life360 in one place. Setup takes about a minute. Pick how texting works (the first one is best for most people):",
        Modifier.padding(vertical = 12.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ModeCard("Google Messages mode", "Recommended",
        "Keeps RCS: typing dots, read receipts, HD photos, reactions. Google Messages stays installed and does the sending in the background.",
        !hubMode) { onMode(false) }
    ModeCard("Orbit mode", null,
        "Orbit is your texting app and does everything itself. Most reliable, but plain SMS/MMS only (no RCS).",
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
            "2. Tap Unlock below. It opens Orbit's App info.\n" +
            "3. Tap ⋮ in the top right → Allow restricted settings → confirm with your fingerprint/PIN.\n" +
            "4. Come back here and tap Open again. The switch works now.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
        PhoneMock(listOf("App info", "Orbit"), menu = true)
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
    var locTick by remember { mutableIntStateOf(0) }
    val drive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) connectDrive(ctx, uri)
    }
    LaunchedEffect(Unit) { found = runCatching { SocialImport.scan(ctx) }.getOrDefault(emptyList()) }
    Spacer(Modifier.height(8.dp))
    Text("🔗", fontSize = 52.sp)
    Text("Connect your stuff", style = MaterialTheme.typography.headlineSmall)
    Text("All optional. You can do these later in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
    val loc = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locTick++ }
    @Suppress("UNUSED_EXPRESSION") locTick
    val hasLoc = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    AccountRow("🧭", "Location", if (hasLoc) "Allowed" else "For arrival texts, the mock location map and \"go to my location\"", hasLoc) {
        loc.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
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

/** Saves the Drive file Orbit may keep writing its daily backup to. */
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
