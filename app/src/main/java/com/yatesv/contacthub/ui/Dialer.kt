package com.yatesv.contacthub.ui

import android.app.role.RoleManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.CallEntry
import com.yatesv.contacthub.data.ContactInfo
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.phone.CallManager

object DialerState {
    var number by mutableStateOf("")
}

fun isDefaultDialer(ctx: Context) = ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialerScreen() {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var isDefault by remember { mutableStateOf(isDefaultDialer(ctx)) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { isDefault = isDefaultDialer(ctx) }
    LaunchedEffect(DialerState.number) { if (DialerState.number.isNotEmpty()) tab = 0 }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(36.dp))
        var defaultClosed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        if (!isDefault && !defaultClosed) Banner("Orbit isn't your default phone app yet, so it can't show its call screen or block numbers.", "Set default", {
            roleLauncher.launch(ctx.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }) { defaultClosed = true }
        PrimaryTabRow(tab) {
            listOf("Keypad", "Recents", "Favorites").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> KeypadTab()
            1 -> RecentsTab()
            else -> FavoritesTab()
        }
    }
}

@Composable
private fun KeypadTab() {
    val ctx = LocalContext.current
    var contacts by remember { mutableStateOf(emptyList<ContactInfo>()) }
    LaunchedEffect(Unit) { contacts = Hub.contacts.all() }
    val digits = Phone.norm(DialerState.number)
    val t9 = mapOf('2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl", '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz")
    fun nameMatches(name: String): Boolean {
        val d = DialerState.number.filter { it in '2'..'9' }
        if (d.length < 2) return false
        return name.lowercase().split(' ').any { w -> w.length >= d.length && d.indices.all { i -> t9[d[i]]?.contains(w[i]) == true } }
    }
    val matches = if (DialerState.number.length < 2) emptyList()
    else contacts.filter { Phone.norm(it.number).contains(digits) || nameMatches(it.name) }.distinctBy { Phone.norm(it.number) }.take(4)

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(matches) { c ->
                Row(Modifier.fillMaxWidth().clickable { DialerState.number = c.number }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.name, c.photo, 36.dp); Spacer(Modifier.width(12.dp))
                    Column { Text(c.name); Text(Phone.pretty(c.number), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(Phone.pretty(DialerState.number).ifEmpty { " " }, fontSize = 32.sp, modifier = Modifier.weight(1f), maxLines = 1)
            if (DialerState.number.isNotEmpty()) IconButton({ DialerState.number = DialerState.number.dropLast(1) }) {
                Icon(Icons.AutoMirrored.Filled.Backspace, "Delete")
            }
        }
        val name = Hub.contacts.cached(DialerState.number)?.name
        Text(name ?: " ", color = MaterialTheme.colorScheme.primary)
        com.yatesv.contacthub.phone.Keypad { DialerState.number += it }
        Spacer(Modifier.height(8.dp))
        FloatingActionButton({
            if (DialerState.number.isNotBlank()) { CallManager.place(ctx, DialerState.number); DialerState.number = "" }
        }, Modifier.size(72.dp), containerColor = androidx.compose.ui.graphics.Color(0xFF43A047)) {
            Icon(Icons.Default.Call, "Call", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RecentsTab() {
    val ctx = LocalContext.current
    val version by Hub.version.collectAsState()
    var calls by remember { mutableStateOf<List<CallEntry>?>(null) }
    var options by remember { mutableStateOf<CallEntry?>(null) }
    LaunchedEffect(version) { calls = Hub.contacts.calls() }
    if (calls?.isEmpty() == true) EmptyState(Icons.Default.Phone, "No recent calls", "Calls you make and get will show here.")
    val sp = rememberSpeedScroll(Hub.settings.flow.collectAsState().value.scrollSpeed)
    LazyColumn(Modifier.fillMaxSize().then(sp.modifier), state = sp.state, userScrollEnabled = sp.native) {
        items(calls.orEmpty(), key = { it.id }) { c ->
            CallRow(c, onClick = { CallManager.place(ctx, c.number) }, onLong = { options = c })
        }
    }
    options?.let { c ->
        AlertDialog(
            onDismissRequest = { options = null },
            title = { Text(Hub.contacts.cached(c.number)?.name ?: Phone.pretty(c.number)) },
            text = {
                Column {
                    TextButton({ options = null; Nav.go(Screen.Contact(c.number)) }) { Text("Contact page") }
                    TextButton({ options = null; Nav.go(Screen.Chat(Phone.convKey(listOf(c.number)))) }) { Text("Send a text") }
                    TextButton({ options = null; DialerState.number = c.number }) { Text("Edit number before calling") }
                    TextButton({
                        options = null
                        if (!Hub.contacts.setBlocked(c.number, true)) Hub.banner.value = "Make Orbit your default phone app to block numbers."
                    }) { Text("Block number") }
                }
            },
            confirmButton = { TextButton({ options = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun FavoritesTab() {
    val ctx = LocalContext.current
    var favs by remember { mutableStateOf(emptyList<ContactInfo>()) }
    LaunchedEffect(Unit) {
        val extras = Hub.db.dao().allExtras().filter { it.priority }.map { it.phoneKey }.toSet()
        favs = Hub.contacts.all().filter { it.starred || Phone.norm(it.number) in extras }.distinctBy { it.id }
    }
    if (favs.isEmpty()) EmptyState(Icons.Default.Call, "No favorites", "Star contacts or mark them as priority to see them here.")
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(favs, key = { it.id }) { c ->
            SettingRow(c.name, Phone.pretty(c.number), onClick = { CallManager.place(ctx, c.number) }) {
                TextButton({ Nav.go(Screen.Contact(c.number)) }) { Text("Info") }
            }
        }
    }
}
