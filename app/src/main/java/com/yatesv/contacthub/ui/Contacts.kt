package com.yatesv.contacthub.ui

import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.Attachment
import com.yatesv.contacthub.data.CallEntry
import com.yatesv.contacthub.data.ContactExtra
import com.yatesv.contacthub.data.ContactInfo
import com.yatesv.contacthub.data.Geo
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.data.Social
import com.yatesv.contacthub.data.Socials
import com.yatesv.contacthub.data.Stats
import com.yatesv.contacthub.life360.L360Member
import com.yatesv.contacthub.phone.CallManager
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<ContactInfo>?>(null) }
    var q by remember { mutableStateOf("") }
    val version by Hub.version.collectAsState()
    LaunchedEffect(version) { list = Hub.contacts.all().distinctBy { it.id } }
    var groupFilter by remember { mutableStateOf("") }
    var manageGroups by remember { mutableStateOf(false) }
    val orbitV by Hub.orbit.version.collectAsState()
    val allGroups = remember(orbitV) { Hub.orbit.groups() }
    val groupMembers = remember(groupFilter, allGroups) { allGroups.firstOrNull { it.id == groupFilter }?.members?.toSet() }
    val groupsByNumber = remember(allGroups) { allGroups.flatMap { g -> g.members.map { it to g } }.groupBy({ it.first }, { it.second }) }
    val shown = list.orEmpty().filter { q.isBlank() || it.name.contains(q, true) || Phone.norm(it.number).contains(Phone.norm(q).ifEmpty { "~" }) }
        .filter { groupMembers == null || Phone.norm(it.number) in groupMembers }
    val favorites = shown.filter { it.starred }

    var cMenu by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var showDupes by remember { mutableStateOf(false) }
    if (showQr) MyQrDialog { showQr = false }
    if (showDupes) DuplicatesDialog { showDupes = false; Hub.changed() }
    val scroll = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { androidx.compose.material3.LargeTopAppBar(title = { Text("Contacts") }, scrollBehavior = scroll,
            actions = {
                IconButton({ showQr = true }) { Icon(androidx.compose.material.icons.Icons.Default.QrCode2, "My contact QR code") }
                androidx.compose.foundation.layout.Box {
                    IconButton({ cMenu = true }) { Icon(androidx.compose.material.icons.Icons.Default.MoreVert, "More") }
                    androidx.compose.material3.DropdownMenu(cMenu, { cMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem({ Text("Merge duplicates") }, { cMenu = false; showDupes = true })
                        androidx.compose.material3.DropdownMenuItem({ Text("My QR code") }, { cMenu = false; showQr = true })
                    }
                }
            },
            colors = androidx.compose.material3.TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f))) },
        floatingActionButton = {
            FloatingActionButton({
                ctx.startActivity(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Icon(Icons.Default.PersonAdd, "Add contact") }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search ${list?.size ?: ""} contacts") })
            GroupChips(groupFilter, { groupFilter = it }) { manageGroups = true }
            if (manageGroups) GroupsDialog(list.orEmpty()) { manageGroups = false }
            LazyColumn(Modifier.fillMaxSize(), flingBehavior = rememberScrollFling(Hub.settings.flow.collectAsState().value.scrollSpeed)) {
                if (q.isBlank() && groupFilter.isBlank()) item { FriendSyncCard() }
                if (favorites.isNotEmpty() && q.isBlank() && groupFilter.isBlank()) {
                    item { SectionTitle("Favorites") }
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            favorites.forEach { c ->
                                Column(Modifier.width(72.dp).clickable { Nav.go(Screen.Contact(c.number)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Avatar(c.name, c.photo, 60.dp)
                                    Text(c.name.substringBefore(' '), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    item { SectionTitle("All contacts") }
                }
                items(shown, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { Nav.go(Screen.Contact(c.number)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name, c.photo, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name)
                            Text(Phone.pretty(c.number), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        groupsByNumber[Phone.norm(c.number)].orEmpty().take(3).forEach { g -> Box(Modifier.padding(start = 4.dp).size(8.dp).clip(CircleShape).background(Color(g.color))) }
                        if (c.starred) Icon(Icons.Default.Star, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactScreen(number: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = Hub.db.dao()
    val key = Phone.norm(number)
    val contact = Hub.contacts.cached(number)
    val name = contact?.name ?: Phone.pretty(number)
    val settings by Hub.settings.flow.collectAsState()
    val members by Hub.l360.members.collectAsState()
    var extra by remember { mutableStateOf(ContactExtra(key)) }
    var birthday by remember { mutableStateOf<String?>(null) }
    var calls by remember { mutableStateOf(emptyList<CallEntry>()) }
    var media by remember { mutableStateOf(emptyList<Pair<Long, Attachment>>()) }
    var msgCount by remember { mutableStateOf(0) }
    var streak by remember { mutableStateOf(0) }
    var editSocials by remember { mutableStateOf(false) }
    var editNotes by remember { mutableStateOf(false) }
    var linkL360 by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(number) {
        blocked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Hub.contacts.isBlocked(number) }
        extra = dao.extra(key) ?: ContactExtra(key)
        birthday = contact?.let { Hub.contacts.birthday(it.id) }
        calls = Hub.contacts.calls(number)
        val thread = Hub.sms.threadIdFor(Phone.convKey(listOf(number)))
        media = Hub.sms.media(thread)
        val (count, days) = Hub.sms.statsFor(thread)
        val tz = java.util.TimeZone.getDefault()
        val now = System.currentTimeMillis()
        msgCount = count + dao.local(Phone.convKey(listOf(number))).size
        streak = Stats.streak(days, (now + tz.getOffset(now)) / 86_400_000L)
        // Fresh Life360 info when the page opens
        if (settings.l360Token.isNotBlank() && settings.l360Enabled && now - Hub.l360.lastSync > 60_000) {
            refreshing = true; Hub.l360.refresh(settings.l360Token); refreshing = false
        }
    }
    fun save(e: ContactExtra) { extra = e; scope.launch { dao.putExtra(e); Hub.changed() } }

    val member = members.let { Hub.l360.memberFor(number, extra.life360Id) }

    Scaffold(topBar = {
        TopAppBar(title = {}, navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (contact != null) IconButton({
                    ctx.startActivity(Intent(Intent.ACTION_EDIT, ContactsContract.Contacts.getLookupUri(contact.id, contact.lookupKey))
                        .putExtra("finishActivityOnSaveCompleted", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Icon(Icons.Default.Edit, "Edit contact") }
                else IconButton({
                    ctx.startActivity(Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                        .putExtra(ContactsContract.Intents.Insert.PHONE, number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Icon(Icons.Default.PersonAdd, "Save contact") }
            })
    }) { pad ->
        val scroll = rememberScrollState()
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(scroll)) {
            // Header: soft tinted backdrop from their photo/color, big photo, name, quick actions
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(memberColor(name).copy(alpha = 0.35f), MaterialTheme.colorScheme.background))))
                if (contact?.photo != null) AsyncImage(contact.photo, null, Modifier.matchParentSize().blur(40.dp).graphicsLayer { alpha = 0.35f },
                    contentScale = ContentScale.Crop)
                Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Photo shrinks and fades a little as you scroll down
                    val shrink = (scroll.value / 600f).coerceIn(0f, 0.35f)
                    Avatar(name, contact?.photo, 112.dp, sharedKey = Phone.convKey(listOf(number)),
                        modifier = Modifier.graphicsLayer { scaleX = 1f - shrink; scaleY = 1f - shrink; alpha = 1f - shrink; translationY = scroll.value * 0.3f })
                    Spacer(Modifier.height(12.dp))
                    Text(name, style = MaterialTheme.typography.headlineMedium)
                    Text(Phone.pretty(number), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (member != null && member.place.isNotBlank()) Text("📍 ${member.place}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickAction(Icons.AutoMirrored.Filled.Chat, "message") { Nav.go(Screen.Chat(Phone.convKey(listOf(number)))) }
                        QuickAction(Icons.Default.Call, "call") { CallManager.place(ctx, number) }
                        QuickAction(Icons.Default.Videocam, "video") { CallManager.place(ctx, number, video = true) }
                        QuickAction(Icons.Default.Directions, "directions", enabled = member?.lat != null) {
                            // Google Maps if installed, otherwise any maps app or the browser
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${member?.lat},${member?.lng}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .recoverCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${member?.lat},${member?.lng}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                }
            }

            // Socials
            SectionTitle("Socials")
            HubCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SocialButton(Social.INSTAGRAM, extra.instagram) { Nav.go(Screen.SocialSearch(number, Social.INSTAGRAM)) }
                    SocialButton(Social.SNAPCHAT, extra.snapchat) { Nav.go(Screen.SocialSearch(number, Social.SNAPCHAT)) }
                    SocialButton(Social.TIKTOK, extra.tiktok) { Nav.go(Screen.SocialSearch(number, Social.TIKTOK)) }
                }
                if (extra.instagram.isBlank() && extra.snapchat.isBlank() && extra.tiktok.isBlank())
                    Text("Tap an app to search for $name and link their profile.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.align(Alignment.End)) {
                    TextButton({ Nav.go(Screen.SocialSearch(number, Social.INSTAGRAM)) }) { Text("Search") }
                    TextButton({ editSocials = true }) { Text("Type or paste") }
                }
            }

            // Life360
            SectionTitle("Location")
            HubCard {
                Life360Card(member, settings.l360Token.isNotBlank(), refreshing,
                    onOpen = {
                        val i = ctx.packageManager.getLaunchIntentForPackage("com.life360.android.safetymapd")
                            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.life360.android.safetymapd"))
                        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    onRefresh = {
                        scope.launch {
                            refreshing = true
                            member?.let { Hub.l360.requestUpdate(settings.l360Token, it) }
                            Hub.l360.refresh(settings.l360Token)
                            refreshing = false
                        }
                    },
                    onLink = { linkL360 = true },
                    onSetup = { Nav.go(Screen.SettingsPage("life360")) },
                )
            }

            // Today's Life360 trail
            if (member != null) TrailCard(member.id)

            // Stats
            SectionTitle("Stats")
            HubCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat("$msgCount", "messages")
                    Stat("$streak 🔥", "day streak")
                    Stat("${calls.size}", "calls")
                    Stat(talkTime(calls.sumOf { it.duration }), "talk time")
                }
            }

            SectionTitle("Your history")
            RelationshipCard(number)
            NudgeRow(number)

            // Birthday + notes
            SectionTitle("Birthday & notes")
            HubCard {
                val bday = extra.birthday.ifBlank { birthday.orEmpty() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Cake, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(if (bday.isBlank()) "No birthday saved" else birthdayText(bday))
                }
                Spacer(Modifier.height(8.dp))
                Text(extra.notes.ifBlank { "No notes yet. Only you can see these." }, color = if (extra.notes.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                TextButton({ editNotes = true }, Modifier.align(Alignment.End)) { Text("Edit") }
            }

            CallNotesSection(number)

            // Recent calls
            if (calls.isNotEmpty()) {
                SectionTitle("Recent calls")
                HubCard { calls.take(5).forEach { CallRow(it, showName = false) } }
            }

            // Media
            SectionTitle("Shared photos & videos")
            if (media.isEmpty()) Text("Nothing shared yet", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else MediaGrid(media.map { it.second })

            com.yatesv.contacthub.phone.CallRecordingsList(number)

            // Settings for this person
            SectionTitle("Options")
            SwitchRow("Priority contact", "Their messages come through Do Not Disturb and show on the widget", extra.priority, Icons.Default.Star) { save(extra.copy(priority = it)) }
            SwitchRow("Block", if (blocked) "Blocked calls and texts" else "Block calls and texts from this number", blocked, Icons.Default.Block) {
                if (Hub.contacts.setBlocked(number, it)) blocked = it else Hub.banner.value = "Make Orbit your default phone app to block numbers."
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (editSocials) SocialsDialog(extra, onDone = { editSocials = false }) { save(it); editSocials = false }
    if (editNotes) NotesDialog(extra, birthday, onDone = { editNotes = false }) { save(it); editNotes = false }
    if (linkL360) AlertDialog(
        onDismissRequest = { linkL360 = false },
        title = { Text("Link Life360 member") },
        text = {
            Column {
                TextButton({ save(extra.copy(life360Id = "")); linkL360 = false }) { Text("Match automatically by phone number") }
                members.filter { it.id != Hub.l360.meId }.forEach { m ->
                    TextButton({ save(extra.copy(life360Id = m.id)); linkL360 = false }) { Text(m.name + if (m.id == extra.life360Id) "  ✓" else "") }
                }
                if (members.isEmpty()) Text("Sign in to Life360 in Settings first.")
            }
        },
        confirmButton = { TextButton({ linkL360 = false }) { Text("Close") } },
    )
}

@Composable
private fun RowScope.QuickAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        Modifier.weight(1f).pressScale(src).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(src, null, enabled = enabled, onClick = onClick).padding(vertical = 10.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SocialButton(s: Social, stored: String, onAdd: () -> Unit) {
    val ctx = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
        if (stored.isBlank()) onAdd()
        else {
            val uri = Uri.parse(Socials.profileUrl(s, stored))
            val app = Intent(Intent.ACTION_VIEW, uri).setPackage(s.installedPackage(ctx) ?: s.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(app) }.onFailure {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }.padding(10.dp)) {
        SocialIcon(s, 46.dp)
        Text(s.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        Text(if (stored.isBlank()) "Add" else if (stored.startsWith("http")) "Link" else "@$stored",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun Life360Card(m: L360Member?, signedIn: Boolean, refreshing: Boolean, onOpen: () -> Unit, onRefresh: () -> Unit, onLink: () -> Unit, onSetup: () -> Unit) {
    if (m?.lat != null && m.lng != null) {
        val mockSt by com.yatesv.contacthub.engine.MockStore.state.collectAsState()
        val mockHere = mockSt.here.takeIf { mockSt.active }
        val mapRef = remember { arrayOfNulls<MapView>(1) }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { mapRef[0]?.onDetach() } }
        Box {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(15.5)
                    mapRef[0] = this
                }
            },
            update = { map ->
                val p = GeoPoint(m.lat, m.lng)
                map.controller.setCenter(p)
                map.overlays.removeAll { it is Marker }
                map.overlays.add(Marker(map).apply { position = p; title = m.place.ifBlank { m.address }; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
                // You, at your mock location
                mockHere?.let { y -> map.overlays.add(Marker(map).apply {
                    position = GeoPoint(y.lat, y.lng); title = "You"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(android.graphics.Color.rgb(10, 132, 255))
                        val d = map.context.resources.displayMetrics.density
                        setStroke((2.5f * d).toInt(), android.graphics.Color.WHITE); setSize((16 * d).toInt(), (16 * d).toInt())
                    }
                }) }
                map.invalidate()
            },
            modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)),
        )
        Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.Black,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f)).padding(horizontal = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                // While a mock location is running, you're shown at the mock spot
                val mock = mockHere
                val me = Hub.l360.me()
                val dist = if (mock != null) Geo.miles(mock.lat, mock.lng, m.lat, m.lng)
                    else if (me?.lat != null && me.lng != null) Geo.miles(me.lat, me.lng, m.lat, m.lng) else null
                Text(listOfNotNull(m.place.ifBlank { m.address.ifBlank { null } }, dist?.let { "%.1f mi away".format(it) }, if (m.driving) "Driving" else null)
                    .joinToString(" · ").ifBlank { "Location shared" }, fontWeight = FontWeight.SemiBold)
                Text("Updated ${Geo.ago(m.updated)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            m.battery?.let {
                Icon(Icons.Default.Battery4Bar, null, Modifier.size(18.dp))
                Text("$it%", style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else if (signedIn && m != null) {
        Text("${m.firstName} isn't sharing their location right now.")
    } else if (signedIn) {
        Text("No Life360 member matches this number.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text("Sign in to Life360 to see their location here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onOpen) { Text("Open Life360") }
        if (signedIn) {
            IconButton(onRefresh, enabled = !refreshing) { Icon(Icons.Default.Refresh, "Refresh") }
            TextButton(onLink) { Text("Link") }
        } else TextButton(onSetup) { Text("Set up") }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CallRow(c: CallEntry, showName: Boolean = true, onClick: (() -> Unit)? = null, onLong: (() -> Unit)? = null) {
    val (icon, color) = when (c.type) {
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> Icons.AutoMirrored.Filled.CallMissed to MaterialTheme.colorScheme.error
        CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Filled.CallMade to MaterialTheme.colorScheme.primary
        CallLog.Calls.BLOCKED_TYPE -> Icons.Default.Block to MaterialTheme.colorScheme.error
        else -> Icons.AutoMirrored.Filled.CallReceived to MaterialTheme.colorScheme.tertiary
    }
    Row(Modifier.fillMaxWidth()
        .then(if (onClick != null) Modifier.combinedClickableSafe(onClick, onLong) else Modifier)
        .padding(vertical = 8.dp, horizontal = if (showName) 16.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showName) {
            val n = Hub.contacts.cached(c.number)?.name ?: c.name ?: Phone.pretty(c.number)
            Avatar(n, Hub.contacts.cached(c.number)?.photo, 40.dp); Spacer(Modifier.width(12.dp))
        }
        Icon(icon, null, Modifier.size(18.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            if (showName) Text(Hub.contacts.cached(c.number)?.name ?: c.name ?: Phone.pretty(c.number),
                color = if (c.type == CallLog.Calls.MISSED_TYPE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(fullTime(c.date) + if (c.duration > 0) " · ${talkTime(c.duration)}" else "", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.combinedClickableSafe(onClick: () -> Unit, onLong: (() -> Unit)?) =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLong))

@Composable
private fun MediaGrid(items: List<Attachment>) {
    val ctx = LocalContext.current
    Column(Modifier.padding(horizontal = 12.dp)) {
        items.take(30).chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                row.forEach { a ->
                    AsyncImage(a.uri, null, Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(a.uri, a.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) },
                        contentScale = ContentScale.Crop)
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        if (items.size > 30) Text("+${items.size - 30} more", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun talkTime(sec: Long): String = when {
    sec <= 0 -> "0m"
    sec < 3600 -> "${(sec + 59) / 60}m"
    else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
}

/** "--05-14" / "1999-05-14" / "05-14" -> "May 14 · in 23 days" */
fun birthdayText(raw: String): String {
    val parts = raw.removePrefix("--").split('-').mapNotNull { it.toIntOrNull() }
    val (month, day) = when (parts.size) { 3 -> parts[1] to parts[2]; 2 -> parts[0] to parts[1]; else -> return raw }
    val now = java.util.Calendar.getInstance()
    val next = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.MONTH, month - 1); set(java.util.Calendar.DAY_OF_MONTH, day)
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        if (before(now) && !(get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR))) add(java.util.Calendar.YEAR, 1)
    }
    val days = ((next.timeInMillis - now.timeInMillis) / 86_400_000L).coerceAtLeast(0)
    val label = java.text.SimpleDateFormat("MMMM d", java.util.Locale.getDefault()).format(next.time)
    val age = if (parts.size == 3 && parts[0] > 1900) " · turns ${next.get(java.util.Calendar.YEAR) - parts[0]}" else ""
    return "$label · " + (if (days == 0L) "today! 🎉" else "in $days days") + age
}

@Composable
private fun SocialsDialog(e: ContactExtra, onDone: () -> Unit, onSave: (ContactExtra) -> Unit) {
    var ig by remember { mutableStateOf(e.instagram) }
    var sc by remember { mutableStateOf(e.snapchat) }
    var tt by remember { mutableStateOf(e.tiktok) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Socials") },
        text = {
            Column {
                Text("Type a @username or paste a profile link. You can also tap Share on a profile in the app and pick Orbit.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(ig, { ig = it }, label = { Text("Instagram") }, singleLine = true)
                OutlinedTextField(sc, { sc = it }, label = { Text("Snapchat") }, singleLine = true)
                OutlinedTextField(tt, { tt = it }, label = { Text("TikTok") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({
                onSave(e.copy(instagram = Socials.clean(Social.INSTAGRAM, ig), snapchat = Socials.clean(Social.SNAPCHAT, sc), tiktok = Socials.clean(Social.TIKTOK, tt)))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
    )
}

@Composable
private fun NotesDialog(e: ContactExtra, contactBirthday: String?, onDone: () -> Unit, onSave: (ContactExtra) -> Unit) {
    var notes by remember { mutableStateOf(e.notes) }
    var bday by remember { mutableStateOf(e.birthday.ifBlank { contactBirthday.orEmpty() }) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Birthday & notes") },
        text = {
            Column {
                OutlinedTextField(bday, { bday = it }, label = { Text("Birthday (YYYY-MM-DD or MM-DD)") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Private notes") }, minLines = 4)
            }
        },
        confirmButton = { TextButton({ onSave(e.copy(notes = notes.trim(), birthday = bday.trim())) }) { Text("Save") } },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
    )
}

/** Shown after sharing an Instagram/Snapchat/TikTok profile into Orbit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSocialScreen(social: Social, value: String) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(emptyList<ContactInfo>()) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { list = Hub.contacts.all().distinctBy { it.id } }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Add ${social.label} to…") }, navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            Text(if (value.startsWith("http")) value else "@$value", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true, placeholder = { Text("Search contacts") })
            LazyColumn {
                items(list.filter { q.isBlank() || it.name.contains(q, true) }, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clickable {
                        scope.launch {
                            val k = Phone.norm(c.number)
                            val e = Hub.db.dao().extra(k) ?: ContactExtra(k)
                            Hub.db.dao().putExtra(when (social) {
                                Social.INSTAGRAM -> e.copy(instagram = value)
                                Social.SNAPCHAT -> e.copy(snapchat = value)
                                Social.TIKTOK -> e.copy(tiktok = value)
                            })
                            Nav.replace(Screen.Contact(c.number))
                        }
                    }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name, c.photo, 40.dp); Spacer(Modifier.width(12.dp)); Text(c.name)
                    }
                }
            }
        }
    }
}

