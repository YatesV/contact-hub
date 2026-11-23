package com.yatesv.contacthub.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.location.LocationManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.ArrivalRule
import com.yatesv.contacthub.data.ContactGroup
import com.yatesv.contacthub.data.ContactInfo
import com.yatesv.contacthub.data.LatLng
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.engine.Arrivals
import com.yatesv.contacthub.engine.MockApi
import com.yatesv.contacthub.engine.WebLink
import kotlinx.coroutines.launch

// ---------------- Contact groups (#12) ----------------

private val GROUP_COLORS = listOf(0xFF3D7BFF, 0xFFFF453A, 0xFF34C759, 0xFFFF9F0A, 0xFFAF52DE, 0xFFFF2D55, 0xFF5AC8FA, 0xFFFFD60A).map { it.toInt() }

/** Group filter chips for the Contacts tab. "" = everyone. */
@Composable
fun GroupChips(selected: String, onSelect: (String) -> Unit, onManage: () -> Unit) {
    val v by Hub.orbit.version.collectAsState()
    val groups = remember(v) { Hub.orbit.groups() }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected == "", { onSelect("") }, { Text("Everyone") })
        groups.forEach { g ->
            FilterChip(selected == g.id, { onSelect(if (selected == g.id) "" else g.id) }, { Text(g.name) },
                leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(g.color))) })
        }
        TextButton(onManage) { Text(if (groups.isEmpty()) "+ Make a group" else "Edit groups") }
    }
}

@Composable
fun GroupsDialog(contacts: List<ContactInfo>, onDone: () -> Unit) {
    val v by Hub.orbit.version.collectAsState()
    val groups = remember(v) { Hub.orbit.groups() }
    var editing by remember { mutableStateOf<ContactGroup?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Contact groups") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Groups get their own color and notification sound (unless a chat has its own sound).", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                groups.forEach { g ->
                    Row(Modifier.fillMaxWidth().clickable { editing = g }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(Color(g.color)))
                        Text("  ${g.name}", Modifier.weight(1f))
                        Text("${g.members.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton({ editing = ContactGroup(java.util.UUID.randomUUID().toString(), "", GROUP_COLORS[groups.size % GROUP_COLORS.size], "", emptyList()) }) { Text("+ New group") }
            }
        },
        confirmButton = { TextButton(onDone) { Text("Done") } },
    )
    editing?.let { g -> GroupEditor(g, contacts) { editing = null } }
}

@Composable
private fun GroupEditor(start: ContactGroup, contacts: List<ContactInfo>, onDone: () -> Unit) {
    var g by remember { mutableStateOf(start) }
    var filter by remember { mutableStateOf("") }
    val pickSound = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        @Suppress("DEPRECATION") val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        g = g.copy(soundUri = uri?.toString().orEmpty())
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (start.name.isBlank()) "New group" else "Edit group") },
        text = {
            Column {
                OutlinedTextField(g.name, { g = g.copy(name = it) }, label = { Text("Name (Family, Work…)") }, singleLine = true)
                Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GROUP_COLORS.forEach { c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).clickable { g = g.copy(color = c) }, contentAlignment = Alignment.Center) {
                            if (g.color == c) Text("✓", color = Color.White)
                        }
                    }
                }
                TextButton({
                    pickSound.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false))
                }) { Text(if (g.soundUri.isBlank()) "Pick a notification sound" else "Sound picked · change") }
                OutlinedTextField(filter, { filter = it }, placeholder = { Text("Find people") }, singleLine = true)
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    val sorted = contacts.sortedWith(compareByDescending<ContactInfo> { Phone.norm(it.number) in g.members }.thenBy { it.name })
                    sorted.filter { filter.isBlank() || it.name.contains(filter, true) }.take(300).forEach { c ->
                        val k = Phone.norm(c.number)
                        Row(Modifier.fillMaxWidth().clickable { g = g.copy(members = if (k in g.members) g.members - k else g.members + k) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(k in g.members, { on -> g = g.copy(members = if (on) g.members + k else g.members - k) })
                            Text(c.name)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ if (g.name.isNotBlank()) Hub.orbit.saveGroup(g); onDone() }, enabled = g.name.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                if (start.name.isNotBlank()) TextButton({ Hub.orbit.deleteGroup(g.id); onDone() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onDone) { Text("Cancel") }
            }
        },
    )
}

// ---------------- Arrival texts (#16) ----------------

@Composable
fun ArrivalsPage() {
    val ctx = LocalContext.current
    val v by Hub.orbit.version.collectAsState()
    val rules = remember(v) { Hub.orbit.arrivals() }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    @Suppress("UNUSED_EXPRESSION") tick
    var adding by remember { mutableStateOf(false) }
    val fine = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++; Arrivals.syncAll(ctx) }
    val bg = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Text("Orbit sends your text when you really arrive somewhere (a running mock location doesn't count). It sends once per visit, at most every 2 hours.",
        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!Arrivals.canWatch(ctx)) Banner("Allow location so Orbit can tell when you arrive.", "Allow", {
        fine.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }) {}
    else if (!Arrivals.canWatchInBackground(ctx)) Banner("Pick \"Allow all the time\" so it works when Orbit is closed.", "Fix", {
        bg.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }) {}
    rules.forEach { r ->
        val name = Hub.contacts.cached(r.to)?.name ?: Phone.pretty(r.to)
        SettingRow("${r.place} → $name", "\"${r.text}\" · ${if (r.everyTime) "every time" else "once"} · ${r.radiusM} m", Icons.Default.Place) {
            Switch(r.on, { on -> r.copy(on = on).let { n -> Hub.orbit.saveArrival(n); Arrivals.register(ctx, n) } })
            IconButton({ Arrivals.remove(ctx, r.id) }) { Icon(Icons.Default.Close, "Delete") }
        }
    }
    if (rules.isEmpty()) Text("No arrival texts yet.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button({ adding = true }, Modifier.padding(16.dp)) { Text("+ New arrival text") }
    if (adding) ArrivalEditor { r -> adding = false; if (r != null) { Hub.orbit.saveArrival(r); Arrivals.register(ctx, r) } }
}

@SuppressLint("MissingPermission")
@Composable
private fun ArrivalEditor(onDone: (ArrivalRule?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var place by remember { mutableStateOf<Pair<String, LatLng>?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<MockApi.Place>()) }
    var radius by remember { mutableIntStateOf(150) }
    var who by remember { mutableStateOf<ContactInfo?>(null) }
    var whoQuery by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf(emptyList<ContactInfo>()) }
    var text by remember { mutableStateOf("I'm here!") }
    var everyTime by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { contacts = Hub.contacts.all().distinctBy { Phone.norm(it.number) } }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("Text when I arrive") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("1. Where", fontWeight = FontWeight.SemiBold)
                if (place != null) Text("📍 ${place!!.first}", color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search a place or address") }, singleLine = true,
                    trailingIcon = { TextButton({ scope.launch { busy = "Searching…"; results = runCatching { MockApi.search(query, null) }.getOrDefault(emptyList()); busy = "" } }) { Text("Find") } })
                TextButton({
                    val lm = ctx.getSystemService(LocationManager::class.java)
                    val l = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
                    if (l != null) place = "Where I am now" to LatLng(l.latitude, l.longitude) else busy = "Location not known yet"
                }, enabled = Arrivals.canWatch(ctx)) { Text("Use where I am now") }
                results.take(5).forEach { r -> Text(r.name, Modifier.fillMaxWidth().clickable { place = r.name.substringBefore(',') to r.point; results = emptyList() }.padding(vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(100, 150, 300, 500).forEach { m -> FilterChip(radius == m, { radius = m }, { Text("$m m") }) }
                }
                Spacer(Modifier.size(8.dp))
                Text("2. Who to text", fontWeight = FontWeight.SemiBold)
                if (who != null) Text("👤 ${who!!.name}", color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(whoQuery, { whoQuery = it }, placeholder = { Text("Name") }, singleLine = true)
                if (whoQuery.isNotBlank()) contacts.filter { it.name.contains(whoQuery, true) }.take(5).forEach { c ->
                    Text("${c.name} · ${Phone.pretty(c.number)}", Modifier.fillMaxWidth().clickable { who = c; whoQuery = "" }.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.size(8.dp))
                Text("3. Message", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(text, { text = it }, minLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(everyTime, { everyTime = it }); Text(if (everyTime) "  Every time I arrive" else "  Just the next time")
                }
                if (busy.isNotBlank()) Text(busy, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton({
                val p = place!!; val c = who!!
                onDone(ArrivalRule(java.util.UUID.randomUUID().toString(), p.first, p.second.lat, p.second.lng, radius, c.number, text.trim(), everyTime))
            }, enabled = place != null && who != null && text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton({ onDone(null) }) { Text("Cancel") } },
    )
}

// ---------------- Text from your laptop (#21) ----------------

@Composable
fun LaptopPage() {
    val ctx = LocalContext.current
    val st by WebLink.state.collectAsState()
    Text("Turn this on, then open the address below in a browser on your laptop (same Wi-Fi). Type the code to sign in. " +
        "It turns itself off after 30 minutes without use. Use it on your home Wi-Fi, not public Wi-Fi.",
        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SwitchRow("Laptop access", if (st.on) "On" else "Off", st.on) { on -> if (on) WebLink.start(ctx) else WebLink.stop(ctx) }
    if (st.on) HubCard {
        if (st.url.isBlank()) Text("Connect to Wi-Fi to use this.", color = MaterialTheme.colorScheme.error)
        else {
            Text("On your laptop, open:", style = MaterialTheme.typography.bodySmall)
            Text(st.url, fontFamily = FontFamily.Monospace, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(10.dp))
            Text("Code:", style = MaterialTheme.typography.bodySmall)
            Text(st.pin.chunked(3).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            if (st.clients > 0) Text("✓ Laptop connected", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.size(10.dp))
            if (st.secure) Text("🔒 Encrypted. Your browser will warn that the certificate isn't trusted the first time; that's normal for a phone. " +
                "Pick Advanced → Continue. To be sure it's your phone, the certificate's SHA-256 fingerprint starts with " +
                st.fingerprint.take(23) + ".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text("Not encrypted on this phone: only use it on your home Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
