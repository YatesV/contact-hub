package com.yatesv.contacthub.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.yatesv.contacthub.R
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.data.Social
import com.yatesv.contacthub.data.Socials
import kotlinx.coroutines.launch

sealed class Screen {
    data object Tabs : Screen()
    data class Chat(val key: String) : Screen()
    data class Contact(val number: String) : Screen()
    data object NewChat : Screen()
    data object Search : Screen()
    data object Starred : Screen()
    data object Scheduled : Screen()
    data object Hidden : Screen()
    data object Archived : Screen()
    data class SettingsPage(val page: String) : Screen()
    /** A shared Instagram/Snapchat/TikTok link waiting to be attached to a contact. */
    data class ShareSocial(val social: Social, val value: String) : Screen()
    data class SocialSearch(val number: String, val social: Social) : Screen()
    data object Life360SignIn : Screen()
    data class FriendSync(val social: Social) : Screen()
    data object Setup : Screen()
    data object MockLocation : Screen()
}

/** Simple back stack. */
object Nav {
    val stack = mutableStateListOf<Screen>(Screen.Tabs)
    var tab by mutableStateOf("messages")
    var hiddenUnlocked by mutableStateOf(false)
    /** App lock. Kept here (not on the Activity) so rotating the phone can't skip the lock screen. */
    var locked by mutableStateOf(Hub.settings.value.appLock)
    /** Waiting to open hidden chats after the PIN is entered. */
    var openHiddenAfterUnlock = false
    /** true = last move went deeper (slide in from the right), false = went back. */
    var forward by mutableStateOf(true)
    fun go(s: Screen) { forward = true; stack.add(s) }
    /** Open a chat scrolled to one message (from Search / Starred): msg key, text and time to find it by. */
    data class Jump(val msgKey: String, val body: String, val date: Long)
    val jumps = java.util.concurrent.ConcurrentHashMap<String, Jump>()
    /** People already picked when New chat opens (changing a group's members starts a new group). */
    var newChatPrefill: List<String> = emptyList()
    fun openAt(convKey: String, msgKey: String, body: String, date: Long) { jumps[convKey] = Jump(msgKey, body, date); go(Screen.Chat(convKey)) }
    fun back(): Boolean = if (stack.size > 1) { forward = false; stack.removeAt(stack.lastIndex); true } else false
    fun replace(s: Screen) { forward = true; if (stack.size > 1) stack[stack.lastIndex] = s else stack.add(s) }
    /** Text to put in the box when a chat opens (from another app's "send SMS" link). */
    val drafts = mutableMapOf<String, String>()
    val top get() = stack.last()
    /** When Orbit last went to the background. Kept here so a dark-mode/rotation restart can't skip the lock. */
    var pausedAt = 0L
}

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Fold the app away when it closes (back/swipe), matching the in-app Fold transition. API 34+ only.
        if (Build.VERSION.SDK_INT >= 34 && Hub.settings.value.transition == "fold" && Motion.enabled())
            runCatching { overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, R.anim.fold_close_exit) }
        if (savedInstanceState == null) Nav.tab = Hub.settings.value.startTab
        // Only the first time: after a rotation/dark-mode restart Android hands the same intent back,
        // which used to throw you into that chat again
        if (savedInstanceState == null) handle(intent)
        setContent {
            HubTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val s by Hub.settings.flow.collectAsState()
                    when {
                        !s.onboarded || s.setupVersion < SETUP_VERSION -> SetupWizard(first = !s.onboarded)
                        Nav.locked -> LockScreen(this) {
                            Nav.locked = false
                            if (Nav.openHiddenAfterUnlock) { Nav.openHiddenAfterUnlock = false; Nav.hiddenUnlocked = true; Nav.go(Screen.Hidden) }
                        }
                        else -> { AppRoot(); PanicShake(this@MainActivity); CrashReportDialog() }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onPause() {
        super.onPause()
        Nav.pausedAt = System.currentTimeMillis()
        com.yatesv.contacthub.engine.AppState.foreground = false
        // You left: new messages in the chat that was open should notify again
        Hub.openConv = ""
    }

    override fun onResume() {
        super.onResume()
        val s = Hub.settings.value
        com.yatesv.contacthub.engine.AppState.foreground = true
        com.yatesv.contacthub.engine.MockStore.resumeIfNeeded(this)
        // Messages waiting to go over RCS can go now that Orbit is open
        com.yatesv.contacthub.engine.SendQueue.wakeHeld(this, drive = true)
        if (s.appLock && Nav.pausedAt > 0 && System.currentTimeMillis() - Nav.pausedAt > s.lockAfterSec * 1000L) {
            Nav.locked = true
            Nav.hiddenUnlocked = false
            if (Nav.top == Screen.Hidden) Nav.back()
        }
        Hub.changed()
        Hub.scope.launch {
            runCatching { Hub.contacts.all() }
            if (Hub.settings.value.autoUpdate) com.yatesv.contacthub.engine.Updater.check()
        }
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        if (intent.getStringExtra("open") == "laptop") {
            intent.removeExtra("open")
            Nav.stack.retainAll { it == Screen.Tabs }
            Nav.go(Screen.SettingsPage("laptop"))
            return
        }
        if (intent.getStringExtra("open") == "mock") {
            intent.removeExtra("open")
            Nav.stack.retainAll { it == Screen.Tabs }
            Nav.go(Screen.MockLocation)
            return
        }
        intent.getStringExtra("callnote")?.let { number ->
            intent.removeExtra("callnote")
            com.yatesv.contacthub.engine.CallNotes.pendingFor = number
            Nav.stack.retainAll { it == Screen.Tabs }
            Nav.go(Screen.Contact(number))
            return
        }
        intent.getStringExtra("draft")?.let { d -> intent.getStringExtra("conv")?.let { Nav.drafts[it] = d }; intent.removeExtra("draft") }
        intent.getStringExtra("conv")?.let { key ->
            // Hidden chats still need your fingerprint/PIN, even when opened from a notification or another app
            Hub.scope.launch {
                val hidden = Hub.db.dao().meta(key)?.hidden == true
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Nav.stack.retainAll { it == Screen.Tabs }
                    if (hidden && !Nav.hiddenUnlocked) unlockHidden(this@MainActivity) { Nav.go(Screen.Chat(key)) }
                    else Nav.go(Screen.Chat(key))
                }
            }
            return
        }
        when (intent.action) {
            Intent.ACTION_DIAL, Intent.ACTION_VIEW -> {
                Nav.tab = "phone"
                Nav.stack.retainAll { it == Screen.Tabs }
                DialerState.number = intent.data?.schemeSpecificPart?.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }.orEmpty()
            }
            Intent.ACTION_SENDTO -> openSmsLink(intent)
            Intent.ACTION_SEND -> {
                if (intent.data?.scheme?.startsWith("sms") == true || intent.data?.scheme?.startsWith("mms") == true) { openSmsLink(intent); return }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val social = Socials.detect(text)
                if (social != null) Nav.go(Screen.ShareSocial(social, Socials.clean(social, text)))
            }
        }
    }

    /** "smsto:3375550101?body=hi" from another app. */
    private fun openSmsLink(intent: Intent) {
        val data = intent.data ?: return
        val numbers = data.schemeSpecificPart.substringBefore('?').split(',', ';').map { Uri.decode(it).trim() }.filter { it.isNotBlank() }
        if (numbers.isEmpty()) return
        val key = Phone.convKey(numbers)
        val body = intent.getStringExtra("sms_body") ?: intent.getStringExtra(Intent.EXTRA_TEXT) ?: data.getQueryParameterSafe("body")
        if (!body.isNullOrBlank()) Nav.drafts[key] = body
        Nav.stack.retainAll { it == Screen.Tabs }
        Nav.go(Screen.Chat(key))
    }
}

private fun Uri.getQueryParameterSafe(k: String): String? =
    runCatching { Uri.parse("x://y?" + schemeSpecificPart.substringAfter('?', "")).getQueryParameter(k) }.getOrNull()

const val SETUP_VERSION = 3

/** Lets photos fly between screens (shared element transitions). */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
val LocalSharedScope = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.animation.SharedTransitionScope?> { null }
val LocalScreenScope = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.animation.AnimatedVisibilityScope?> { null }

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot() {
    BackHandler(Nav.stack.size > 1) { Nav.back() }
    val s by Hub.settings.flow.collectAsState()
    val effect by Effects.current.collectAsState()
    AppOpenFold(enabled = s.transition == "fold" && Motion.enabled(s), intensity = s.foldIntensity) {
    // Shared elements (flying avatars) need the root placed once first. A chat opened as the very
    // first screen (notification tap on cold start, app unlock, widget, smsto: link) would otherwise
    // measure its avatar before the root has coordinates -> "Uninitialized LayoutCoordinates" crash.
    var sharedReady by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.compose.animation.SharedTransitionLayout(Modifier.fillMaxSize().onPlaced { if (!sharedReady) sharedReady = true }) {
    val shared = this
    AnimatedContent(
        targetState = Nav.top,
        transitionSpec = { screenTransition(s, Nav.forward) },
        label = "screens",
        modifier = Modifier.fillMaxSize(),
    ) { top ->
        androidx.compose.runtime.CompositionLocalProvider(LocalSharedScope provides (if (sharedReady) shared else null), LocalScreenScope provides this) {
        val foldMod = if (s.transition == "fold" && Motion.enabled(s)) Modifier.foldScreen(this, Nav.forward, s.foldIntensity) else Modifier
        Box(Modifier.fillMaxSize().then(foldMod).background(MaterialTheme.colorScheme.background)) {
        when (top) {
            Screen.Tabs -> Tabs()
            is Screen.Chat -> ChatScreen(top.key)
            is Screen.Contact -> ContactScreen(top.number)
            Screen.NewChat -> NewChatScreen()
            Screen.Search -> SearchScreen()
            Screen.Starred -> StarredScreen()
            Screen.Scheduled -> ScheduledScreen()
            Screen.Hidden -> InboxScreen(mode = InboxMode.HIDDEN)
            Screen.Archived -> InboxScreen(mode = InboxMode.ARCHIVED)
            is Screen.SettingsPage -> SettingsPage(top.page)
            is Screen.ShareSocial -> ShareSocialScreen(top.social, top.value)
            is Screen.SocialSearch -> SocialSearchScreen(top.number, top.social)
            Screen.Life360SignIn -> Life360SignInScreen()
            is Screen.FriendSync -> FriendSyncScreen(top.social)
            Screen.Setup -> SetupWizard(first = false)
            Screen.MockLocation -> MockScreen()
        }
        }
        }
    }
    }
    }
    effect?.let { e -> EffectOverlay(e) { Effects.current.value = null } }
    MediaViewerOverlay()
}

/** iOS-style push: new screen slides in from the right, old one slides a bit left and dims. */
private fun AnimatedContentTransitionScope<Screen>.screenTransition(s: com.yatesv.contacthub.data.Settings, forward: Boolean): ContentTransform {
    if (!Motion.enabled(s)) return fadeIn(snap()) togetherWith fadeOut(snap())
    val p = Motion.params(s)
    // Pages shouldn't wobble sideways, so slides use a calmer version of the spring
    val offset: androidx.compose.animation.core.FiniteAnimationSpec<IntOffset> = p?.let { spring(it.damping.coerceAtLeast(0.9f), it.stiffness) } ?: Motion.spec(Motion.Cat.PAGES, s)
    val fade: androidx.compose.animation.core.FiniteAnimationSpec<Float> = p?.let { spring(1f, it.stiffness) } ?: Motion.spec(Motion.Cat.PAGES, s)
    val scale: androidx.compose.animation.core.FiniteAnimationSpec<Float> = Motion.spec(Motion.Cat.PAGES, s)
    return when (s.transition) {
        // Fold: the fade here gives the timing + see-through; the 3D tilt + blur is added per-screen by Modifier.foldScreen
        "fold" -> {
            val f = androidx.compose.animation.core.tween<Float>(FOLD_DURATION_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            (fadeIn(f) togetherWith fadeOut(f)).apply { targetContentZIndex = if (forward) 1f else 0f }
        }
        "fade" -> fadeIn(fade) togetherWith fadeOut(fade)
        "zoom" -> (scaleIn(scale, 0.92f) + fadeIn(fade)) togetherWith (scaleOut(scale, 1.05f) + fadeOut(fade))
        else -> if (forward) {
            slideInHorizontally(offset) { it } togetherWith (slideOutHorizontally(offset) { -it / 3 } + fadeOut(fade, 0.6f))
        } else {
            (slideInHorizontally(offset) { -it / 3 } + fadeIn(fade, 0.6f)) togetherWith slideOutHorizontally(offset) { it }
        }.apply { targetContentZIndex = if (forward) 1f else 0f }
    }
}

@Composable
private fun Tabs() {
    val s by Hub.settings.flow.collectAsState()
    val view = androidx.compose.ui.platform.LocalView.current
    Scaffold(
        bottomBar = {
            Column {
            androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f), tonalElevation = 0.dp) {
                listOf(
                    Triple("messages", "Messages", Icons.Default.Chat),
                    Triple("contacts", "Contacts", Icons.Default.Contacts),
                    Triple("phone", "Phone", Icons.Default.Phone),
                    Triple("settings", "Settings", Icons.Default.Settings),
                ).forEach { (id, label, icon) ->
                    NavigationBarItem(
                        selected = Nav.tab == id, onClick = { if (Nav.tab != id) Haptics.tap(view); Nav.tab = id },
                        icon = {
                            if (id == "settings") Box { Icon(icon, label); MockIndicatorDot(Modifier.align(androidx.compose.ui.Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp)) }
                            else Icon(icon, label)
                        },
                        label = if (s.navLabels) ({ Text(label, style = MaterialTheme.typography.labelSmall) }) else null,
                        colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                            indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        val order = listOf("messages", "contacts", "phone", "settings")
        AnimatedContent(Nav.tab, Modifier.fillMaxSize().padding(pad), label = "tabs", transitionSpec = {
            // Slide a little in the direction of the tab you picked, with a cross-fade
            val dir = if (order.indexOf(targetState) > order.indexOf(initialState)) 1 else -1
            if (!Motion.enabled()) fadeIn(snap()) togetherWith fadeOut(snap())
            else (slideInHorizontally(Motion.spec()) { it / 10 * dir } + fadeIn(Motion.spec()) + scaleIn(Motion.spec(), 0.98f)) togetherWith
                (slideOutHorizontally(Motion.spec()) { -it / 10 * dir } + fadeOut(Motion.spec()))
        }) { tab ->
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                when (tab) {
                    "contacts" -> ContactsScreen()
                    "phone" -> DialerScreen()
                    "settings" -> SettingsHome()
                    else -> InboxScreen()
                }
            }
        }
    }
}

fun numberKey(number: String) = Phone.convKey(listOf(number))
