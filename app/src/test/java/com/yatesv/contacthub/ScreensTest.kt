package com.yatesv.contacthub

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yatesv.contacthub.data.Social
import com.yatesv.contacthub.ui.ChatScreen
import com.yatesv.contacthub.ui.ContactScreen
import com.yatesv.contacthub.ui.ContactsScreen
import com.yatesv.contacthub.ui.DialerScreen
import com.yatesv.contacthub.ui.HubTheme
import com.yatesv.contacthub.ui.InboxMode
import com.yatesv.contacthub.ui.InboxScreen
import com.yatesv.contacthub.ui.NewChatScreen
import com.yatesv.contacthub.ui.ScheduledScreen
import com.yatesv.contacthub.ui.SearchScreen
import com.yatesv.contacthub.ui.SettingsHome
import com.yatesv.contacthub.ui.SettingsPage
import com.yatesv.contacthub.ui.SetupWizard
import com.yatesv.contacthub.ui.ShareSocialScreen
import com.yatesv.contacthub.ui.StarredScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opens every screen in a simulated phone and makes sure it draws without crashing.
 * (Real texts, RCS and calls need a real phone; this catches layout and logic crashes.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HubApp::class)
class ScreensTest {
    @get:Rule val rule = createComposeRule()

    /**
     * Screens have looping animations (pulsing hints, live previews), so the test clock is stepped by
     * hand instead of waiting for "idle", which would never come.
     */
    private fun show(content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { HubTheme { content() } }
        settle()
    }

    private fun settle(ms: Long = 1500) {
        repeat((ms / 100).toInt()) { rule.mainClock.advanceTimeBy(100); Thread.sleep(15) }
    }

    private fun waitForText(text: String) {
        repeat(60) {
            if (rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            settle(100); Thread.sleep(40)
        }
        rule.onNodeWithText(text).assertExists()
    }

    @Test fun inbox() { show { InboxScreen() }; rule.onNodeWithText("Messages").assertExists() }

    /** Regression: chats must actually show up in the list (a stray line break once hid them all). */
    @Test fun inboxShowsChats() {
        kotlinx.coroutines.runBlocking {
            Hub.db.dao().addLocal(com.yatesv.contacthub.data.LocalMessage(convKey = "3375550101", body = "inbox row test", date = System.currentTimeMillis(), outgoing = false))
        }
        show { InboxScreen() }
        waitForText("inbox row test")
    }

    @Test fun chatShowsMessages() {
        kotlinx.coroutines.runBlocking {
            Hub.db.dao().addLocal(com.yatesv.contacthub.data.LocalMessage(convKey = "3375550109", body = "chat bubble test", date = System.currentTimeMillis(), outgoing = true, source = "sent"))
        }
        show { ChatScreen("3375550109") }
        waitForText("chat bubble test")
    }
    @Test fun inboxArchived() { show { InboxScreen(InboxMode.ARCHIVED) }; rule.onNodeWithText("Archived").assertExists() }
    @Test fun inboxHidden() { show { InboxScreen(InboxMode.HIDDEN) } }
    @Test fun chat() { show { ChatScreen("3375550101") } }
    @Test fun groupChat() { show { ChatScreen("3375550101,3375550102") } }
    @Test fun rcsOnlyChat() { show { ChatScreen("g:Friends") } }
    @Test fun contact() { show { ContactScreen("3375550101") }; rule.onNodeWithText("Socials").assertExists() }
    @Test fun contacts() { show { ContactsScreen() }; rule.onNodeWithText("Contacts").assertExists() }
    @Test fun dialer() { show { DialerScreen() }; rule.onNodeWithText("Keypad").assertExists() }
    @Test fun dialerTabs() {
        show { DialerScreen() }
        rule.onNodeWithText("Recents").performClick(); settle()
        rule.onNodeWithText("Favorites").performClick(); settle()
    }
    @Test fun newChat() { show { NewChatScreen() } }
    @Test fun search() { show { SearchScreen() } }
    @Test fun starred() { show { StarredScreen() } }
    @Test fun scheduled() { show { ScheduledScreen() } }
    @Test fun shareSocial() { show { ShareSocialScreen(Social.INSTAGRAM, "someone") } }
    @Test fun settingsHome() { show { SettingsHome() }; rule.onNodeWithText("Look & feel").assertExists() }

    /** Regression: opening Settings must not open any sub-page by itself. */
    @Test fun settingsHomeDoesNotNavigate() {
        com.yatesv.contacthub.ui.Nav.stack.retainAll { it == com.yatesv.contacthub.ui.Screen.Tabs }
        show { SettingsHome() }
        org.junit.Assert.assertEquals(1, com.yatesv.contacthub.ui.Nav.stack.size)
        rule.onNodeWithText("Look & feel").performClick(); settle()
        org.junit.Assert.assertEquals(com.yatesv.contacthub.ui.Screen.SettingsPage("look"), com.yatesv.contacthub.ui.Nav.top)
    }

    @Test fun everySettingsPage() {
        val pages = listOf("setup", "look", "motion", "messaging", "notifications", "privacy", "life360", "backup",
            "privacydata", "developer", "about", "licenses")
        var page by mutableStateHolder("setup")
        rule.mainClock.autoAdvance = false
        rule.setContent { HubTheme { SettingsPage(page.value) } }
        pages.forEach { p -> page.value = p; settle() }
    }

    @Test fun curveEditor() {
        Hub.settings.update { it.copy(motion = "curve") }
        show { SettingsPage("motion") }
        rule.onNodeWithText("Editing: ").assertExists()
        Hub.settings.update { it.copy(motion = "ios") }
    }

    @Test fun setupWizardWalkthrough() {
        show { SetupWizard(first = true) }
        rule.onNodeWithText("Welcome to Orbit").assertExists()
        repeat(6) {
            val next = rule.onAllNodesWithText("Next").fetchSemanticsNodes().isNotEmpty()
            if (next) rule.onNodeWithText("Next").performClick()
            else if (rule.onAllNodesWithText("Do this later").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithText("Do this later").performClick()
            settle()
        }
    }

    @Test fun setupWizardContactHubMode() {
        show { SetupWizard(first = true) }
        rule.onNodeWithText("Orbit mode").performClick()
        rule.onNodeWithText("Next").performClick(); settle()
    }
}

/** Tiny holder so the test can switch pages. */
private class Holder<T>(init: T) { val state = androidx.compose.runtime.mutableStateOf(init); var value: T get() = state.value; set(v) { state.value = v } }
private fun <T> mutableStateHolder(init: T) = Holder(init)
private operator fun <T> Holder<T>.getValue(thisRef: Any?, p: kotlin.reflect.KProperty<*>): Holder<T> = this
private operator fun <T> Holder<T>.setValue(thisRef: Any?, p: kotlin.reflect.KProperty<*>, v: Holder<T>) {}
