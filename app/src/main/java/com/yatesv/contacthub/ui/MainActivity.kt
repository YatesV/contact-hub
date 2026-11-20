package com.yatesv.contacthub.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.yatesv.contacthub.engine.Sender
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
}

/** Simple back stack. */
object Nav {
    val stack = mutableStateListOf<Screen>(Screen.Tabs)
    var tab by mutableStateOf("messages")
    var hiddenUnlocked by mutableStateOf(false)
    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun replace(s: Screen) { if (stack.size > 1) stack[stack.lastIndex] = s else stack.add(s) }
    val top get() = stack.last()
}

class MainActivity : FragmentActivity() {
    private var pausedAt = 0L
    var locked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        locked = Hub.settings.value.appLock
        Nav.tab = Hub.settings.value.startTab
        handle(intent)
        setContent {
            HubTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val s by Hub.settings.flow.collectAsState()
                    when {
                        !s.onboarded -> Onboarding()
                        locked -> LockScreen(this) { locked = false }
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
            locked = true
            Nav.hiddenUnlocked = false
        }
        Hub.changed()
        Hub.scope.launch {
            runCatching { Hub.contacts.all() }
            Sender.retryPendingReactions(this@MainActivity)
        }
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra("conv")?.let { key ->
            Nav.stack.retainAll { it == Screen.Tabs }
            Nav.go(Screen.Chat(key))
            return
        }
        when (intent.action) {
            Intent.ACTION_DIAL, Intent.ACTION_VIEW -> {
                Nav.tab = "phone"
                Nav.stack.retainAll { it == Screen.Tabs }
                DialerState.number = intent.data?.schemeSpecificPart?.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }.orEmpty()
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val social = Socials.detect(text)
                if (social != null) Nav.go(Screen.ShareSocial(social, Socials.clean(social, text)))
            }
        }
    }
}

@Composable
fun AppRoot() {
    BackHandler(Nav.stack.size > 1) { Nav.back() }
    Box(Modifier.fillMaxSize()) {
        when (val top = Nav.top) {
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
        }
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
