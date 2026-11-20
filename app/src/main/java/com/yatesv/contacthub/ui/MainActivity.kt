package com.yatesv.contacthub.ui

import android.content.Intent
import android.os.Bundle
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
    data object Setup : Screen()
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
    fun back(): Boolean = if (stack.size > 1) { forward = false; stack.removeAt(stack.lastIndex); true } else false
    fun replace(s: Screen) { forward = true; if (stack.size > 1) stack[stack.lastIndex] = s else stack.add(s) }
    /** Text to put in the box when a chat opens (from another app's "send SMS" link). */
    val drafts = mutableMapOf<String, String>()
    val top get() = stack.last()
}

class MainActivity : FragmentActivity() {
    private var pausedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) Nav.tab = Hub.settings.value.startTab
        handle(intent)
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
                        else -> AppRoot()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onPause() { super.onPause(); pausedAt = System.currentTimeMillis() }

    override fun onResume() {
        super.onResume()
        val s = Hub.settings.value
        if (s.appLock && pausedAt > 0 && System.currentTimeMillis() - pausedAt > s.lockAfterSec * 1000L) {
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

const val SETUP_VERSION = 2

@Composable
fun AppRoot() {
    BackHandler(Nav.stack.size > 1) { Nav.back() }
    val s by Hub.settings.flow.collectAsState()
    val effect by Effects.current.collectAsState()
    AnimatedContent(
        targetState = Nav.top,
        transitionSpec = { screenTransition(s, Nav.forward) },
        label = "screens",
        modifier = Modifier.fillMaxSize(),
    ) { top ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
            Screen.Setup -> SetupWizard(first = false)
        }
        }
    }
    effect?.let { e -> EffectOverlay(e) { Effects.current.value = null } }
}

/** iOS-style push: new screen slides in from the right, old one slides a bit left and dims. */
private fun AnimatedContentTransitionScope<Screen>.screenTransition(s: com.yatesv.contacthub.data.Settings, forward: Boolean): ContentTransform {
    val p = Motion.params(s) ?: return fadeIn(snap()) togetherWith fadeOut(snap())
    val offset = spring<IntOffset>(p.damping.coerceAtLeast(0.9f), p.stiffness)
    val fade = spring<Float>(1f, p.stiffness)
    return when (s.transition) {
        "fade" -> fadeIn(fade) togetherWith fadeOut(fade)
        "zoom" -> (scaleIn(spring(p.damping, p.stiffness), 0.92f) + fadeIn(fade)) togetherWith (scaleOut(spring(p.damping, p.stiffness), 1.05f) + fadeOut(fade))
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
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple("messages", "Messages", Icons.Default.Chat),
                    Triple("contacts", "Contacts", Icons.Default.Contacts),
                    Triple("phone", "Phone", Icons.Default.Phone),
                    Triple("settings", "Settings", Icons.Default.Settings),
                ).forEach { (id, label, icon) ->
                    NavigationBarItem(
                        selected = Nav.tab == id, onClick = { Nav.tab = id },
                        icon = { Icon(icon, label) },
                        label = if (s.navLabels) ({ Text(label) }) else null,
                    )
                }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (Nav.tab) {
                "contacts" -> ContactsScreen()
                "phone" -> DialerScreen()
                "settings" -> SettingsHome()
                else -> InboxScreen()
            }
        }
    }
}

fun numberKey(number: String) = Phone.convKey(listOf(number))
