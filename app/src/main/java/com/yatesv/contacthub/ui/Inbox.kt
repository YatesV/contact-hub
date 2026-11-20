package com.yatesv.contacthub.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.Categorize
import com.yatesv.contacthub.data.Category
import com.yatesv.contacthub.data.ConvMeta
import com.yatesv.contacthub.data.Folder
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.data.Reactions
import com.yatesv.contacthub.engine.ActionReceiver
import com.yatesv.contacthub.engine.MessagesListener
import kotlinx.coroutines.launch

data class ConvRow(
    val key: String,
    val threadId: Long,
    val addresses: List<String>,
    val name: String,
    val photo: String?,
    val snippet: String,
    val date: Long,
    val unread: Boolean,
    val meta: ConvMeta,
    val category: Category,
    val isGroup: Boolean,
    val lastIncoming: Long = 0,
)

object InboxData {
    /** Last inbox, shown instantly while it refreshes. */
    @Volatile var cache: List<ConvRow>? = null

    private val installTime by lazy {
        runCatching { Hub.app.packageManager.getPackageInfo(Hub.app.packageName, 0).firstInstallTime }.getOrDefault(0L)
    }

    fun nameFor(key: String, addresses: List<String>, meta: ConvMeta?, groupName: String?): String {
        if (!groupName.isNullOrBlank()) return groupName
        if (!meta?.customName.isNullOrBlank()) return meta!!.customName
        if (key.startsWith("g:")) return key.removePrefix("g:")
        val list = addresses.ifEmpty { key.split(',') }
        return list.joinToString(", ") { a -> Hub.contacts.cached(a)?.name?.let { if (list.size > 1) it.substringBefore(' ') else it } ?: Phone.pretty(a) }
    }

    fun snippetText(raw: String): String {
        val r = if (Hub.settings.value.convertReactions) Reactions.parse(raw) else null
        return if (r != null) "Reacted ${r.emoji} to “${r.target.take(40)}”" else raw
    }

    suspend fun load(): List<ConvRow> {
        val s = Hub.settings.value
        val dao = Hub.db.dao()
        val convs = Hub.sms.conversations(s.olderThan6Months)
        val metas = dao.allMeta().associateBy { it.key }
        val groups = dao.allGroups().associateBy { it.convKey }
        val locals = dao.allLocal().groupBy { it.convKey }
        val blocked = Hub.contacts.blockedList().map { Phone.norm(it) }.toSet()
        val seen = HashSet<String>()

        fun row(key: String, thread: Long, addresses: List<String>, snippet: String, date: Long, lastIn: Long): ConvRow {
            val meta = metas[key] ?: ConvMeta(key)
            val localLast = locals[key]?.lastOrNull()
            val (snip, d) = if (localLast != null && localLast.date > date) localLast.body to localLast.date else snippet to date
            val localIn = locals[key]?.lastOrNull { !it.outgoing }?.date ?: 0
            val lastIncoming = maxOf(lastIn, localIn)
            val isContact = addresses.any { Hub.contacts.cached(it) != null }
            val isGroup = Phone.isGroupKey(key)
            return ConvRow(
                key, thread, addresses, nameFor(key, addresses, meta, groups[key]?.name),
                groups[key]?.photoUri?.ifBlank { null } ?: meta.photoUri.ifBlank { null }
                    ?: if (!isGroup) Hub.contacts.cached(addresses.firstOrNull().orEmpty())?.photo else null,
                snippetText(snip), d,
                unread = lastIncoming > maxOf(meta.lastRead, installTime),
                meta = meta,
                category = Categorize.of(key, snip, isContact, addresses.size == 1 && Phone.norm(addresses[0]) in blocked),
                isGroup = isGroup,
                lastIncoming = lastIncoming,
            )
        }

        val rows = ArrayList<ConvRow>()
        for (c in convs) {
            if (!seen.add(c.key)) continue
            rows += row(c.key, c.threadId, c.addresses, c.snippet, c.date, c.lastIncoming)
        }
        for ((key, list) in locals) {
            if (key in seen) continue
            val last = list.last()
            rows += row(key, 0, if (key.startsWith("g:")) emptyList() else key.split(','), last.body, last.date, 0)
        }
        return rows.sortedWith(compareByDescending<ConvRow> { it.meta.pinned }.thenByDescending { it.date })
    }

    suspend fun updateMeta(key: String, block: (ConvMeta) -> ConvMeta) {
        val dao = Hub.db.dao()
        dao.putMeta(block(dao.meta(key) ?: ConvMeta(key)))
        Hub.changed()
    }
}

enum class InboxMode { NORMAL, HIDDEN, ARCHIVED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(mode: InboxMode = InboxMode.NORMAL) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by Hub.version.collectAsState()
    val settings by Hub.settings.flow.collectAsState()
    val banner by Hub.banner.collectAsState()
    val l360Status by Hub.l360.status.collectAsState()
    val folders by Hub.db.dao().foldersFlow().collectAsState(emptyList())
    var rows by remember { mutableStateOf(InboxData.cache) }
    var filter by remember { mutableStateOf("all") }
    var menu by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<ConvRow?>(null) }
    var folderFor by remember { mutableStateOf<ConvRow?>(null) }
    var snoozeFor by remember { mutableStateOf<ConvRow?>(null) }
    var showSnoozed by remember { mutableStateOf(false) }
    val orbitV by Hub.orbit.version.collectAsState()
    val snoozes = remember(version, orbitV) { Hub.orbit.snoozes() }
    fun snoozed(r: ConvRow) = snoozes[r.key]?.let { (until, at) -> com.yatesv.contacthub.data.Snooze.hidden(until, at, r.lastIncoming) } == true
    val view = androidx.compose.ui.platform.LocalView.current

    LaunchedEffect(version, settings.olderThan6Months) {
        if (rows != null) kotlinx.coroutines.delay(150)
        // Off the main thread: it reads contacts/blocked numbers and runs text checks for every chat
        rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { InboxData.load() }.getOrNull() } ?: rows ?: emptyList()
        InboxData.cache = rows
    }

    val visible = rows.orEmpty().filter { r ->
        when (mode) {
            InboxMode.HIDDEN -> r.meta.hidden
            InboxMode.ARCHIVED -> r.meta.archived && !r.meta.hidden
            InboxMode.NORMAL -> !r.meta.hidden && !r.meta.archived && (showSnoozed || !snoozed(r))
        }
    }.filter { r ->
        when {
            mode != InboxMode.NORMAL || filter == "all" -> true
            filter.startsWith("f:") -> r.meta.folderId == filter.removePrefix("f:").toLong()
            else -> r.category.name == filter
        }
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pinned = if (mode == InboxMode.NORMAL && filter == "all") visible.filter { it.meta.pinned } else emptyList()
    val list = visible.filter { it !in pinned }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(when (mode) { InboxMode.HIDDEN -> "Hidden"; InboxMode.ARCHIVED -> "Archived"; else -> "Messages" }) },
                navigationIcon = { if (mode != InboxMode.NORMAL) IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f)),
                actions = {
                    if (mode == InboxMode.NORMAL) {
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Default.MoreHoriz, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Mark all as read") }, {
                                    menu = false
                                    val unread = rows.orEmpty().filter { it.unread }.map { it.key }
                                    scope.launch {
                                        unread.forEach { k -> runCatching { ActionReceiver.markRead(k); com.yatesv.contacthub.engine.Notifier.cancel(ctx, k) } }
                                        Hub.banner.value = if (unread.isEmpty()) "Nothing unread" else "Marked ${unread.size} chat${if (unread.size == 1) "" else "s"} as read"
                                    }
                                }, leadingIcon = { Icon(androidx.compose.material.icons.Icons.Default.DoneAll, null) })
                                DropdownMenuItem({ Text("Archived") }, { menu = false; Nav.go(Screen.Archived) }, leadingIcon = { Icon(Icons.Default.Archive, null) })
                                DropdownMenuItem({ Text("Starred messages") }, { menu = false; Nav.go(Screen.Starred) }, leadingIcon = { Icon(Icons.Default.Star, null) })
                                DropdownMenuItem({ Text("Scheduled") }, { menu = false; Nav.go(Screen.Scheduled) }, leadingIcon = { Icon(Icons.Default.Schedule, null) })
                                DropdownMenuItem({ Text("Hidden chats") }, {
                                    menu = false
                                    (ctx as? MainActivity)?.let { act -> unlockHidden(act) { Nav.go(Screen.Hidden) } }
                                })
                                DropdownMenuItem({ Text("New folder") }, { menu = false; folderFor = ConvRow("", 0, emptyList(), "", null, "", 0, false, ConvMeta(""), Category.ALL, false) })
                            }
                        }
                        IconButton({ Nav.go(Screen.NewChat) }) { Icon(Icons.Default.EditNote, "New message", tint = MaterialTheme.colorScheme.primary) }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (mode == InboxMode.NORMAL) UpdateBanner()
            androidx.compose.animation.AnimatedVisibility(banner.isNotBlank()) { Banner(banner) { Hub.banner.value = "" } }
            if (l360Status.isNotBlank() && mode == InboxMode.NORMAL) Banner(l360Status, "Fix", { Nav.go(Screen.SettingsPage("life360")) }) {}
            if (mode == InboxMode.NORMAL && settings.engine == "auto" && !MessagesListener.connected && !com.yatesv.contacthub.engine.HubSms.isDefault(ctx)) {
                Banner("Turn on notification access so RCS chats and replies work.", "Open", { openListener(ctx) }) { Hub.banner.value = "" }
            }
            when {
                rows == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (mode == InboxMode.NORMAL) item(key = "search") { SearchPill { Nav.go(Screen.Search) } }
                    if (mode == InboxMode.NORMAL && settings.showCategories) item(key = "chips") {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Category.entries.forEach { c ->
                                val id = if (c == Category.ALL) "all" else c.name
                                val count = if (c == Category.ALL) 0 else rows.orEmpty().count { it.category == c && it.unread && !it.meta.hidden && !it.meta.archived }
                                Pill(if (count > 0) "${c.label} $count" else c.label, filter == id) { filter = id }
                            }
                            folders.forEach { f -> Pill(f.name, filter == "f:${f.id}") { filter = "f:${f.id}" } }
                        }
                    }
                    val snoozedCount = if (mode == InboxMode.NORMAL) rows.orEmpty().count { !it.meta.hidden && !it.meta.archived && snoozed(it) } else 0
                    if (snoozedCount > 0 || showSnoozed) item(key = "snoozed") {
                        TextButton({ showSnoozed = !showSnoozed }, Modifier.padding(horizontal = 8.dp)) {
                            Text(if (showSnoozed) "Hide snoozed chats" else "💤 $snoozedCount snoozed · show")
                        }
                    }
                    if (pinned.isNotEmpty()) item(key = "pinned") {
                        PinnedGrid(pinned, onOpen = { Nav.go(Screen.Chat(it.key)) }, onLong = { Haptics.long(view); actionsFor = it })
                    }
                    if (list.isEmpty() && pinned.isEmpty()) item(key = "empty") {
                        EmptyState(Icons.Default.ChatBubbleOutline, "Nothing here", when (mode) {
                            InboxMode.HIDDEN -> "Long-press a chat and pick Hide to move it here."
                            InboxMode.ARCHIVED -> "Archived chats show up here."
                            else -> "Tap the pencil to start a conversation."
                        })
                    }
                    items(list, key = { it.key }) { r ->
                        Box(Modifier.animateItem(fadeInSpec = Motion.spec(), fadeOutSpec = Motion.spec(), placementSpec = Motion.spec())) {
                          SwipeRow(r, settings.swipeLeft, settings.swipeRight) {
                            ConvItem(r, onClick = { Nav.go(Screen.Chat(r.key)) }, onLong = { Haptics.long(view); actionsFor = r })
                        } }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    snoozeFor?.let { r -> SnoozeDialog(r.key) { snoozeFor = null } }
    actionsFor?.let { r ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(r.name) },
            text = {
                Column {
                    fun act(label: String, block: suspend () -> Unit) = @Composable {
                        TextButton({ actionsFor = null; scope.launch { block() } }, Modifier.fillMaxWidth()) { Text(label, Modifier.fillMaxWidth()) }
                    }
                    act(if (r.meta.pinned) "Unpin" else "Pin") { InboxData.updateMeta(r.key) { it.copy(pinned = !it.pinned) } }()
                    act(if (r.meta.archived) "Unarchive" else "Archive") { InboxData.updateMeta(r.key) { it.copy(archived = !it.archived) } }()
                    act(if (r.meta.muted) "Unmute" else "Mute") { InboxData.updateMeta(r.key) { it.copy(muted = !it.muted) } }()
                    act(if (r.meta.hidden) "Unhide" else "Hide (move to hidden chats)") { InboxData.updateMeta(r.key) { it.copy(hidden = !it.hidden) } }()
                    act("Mark as read") { ActionReceiver.markRead(r.key) }()
                    TextButton({ snoozeFor = r; actionsFor = null }, Modifier.fillMaxWidth()) { Text(if (snoozes.containsKey(r.key)) "Snoozed · change…" else "Snooze…", Modifier.fillMaxWidth()) }
                    TextButton({ folderFor = r; actionsFor = null }, Modifier.fillMaxWidth()) { Text("Move to folder…", Modifier.fillMaxWidth()) }
                    if (!r.isGroup && r.addresses.isNotEmpty()) {
                        val blocked = remember(r.key, r.category) { r.category == Category.SPAM && runCatching { Hub.contacts.isBlocked(r.addresses[0]) }.getOrDefault(false) }
                        act(if (blocked) "Unblock" else "Block") {
                            if (!Hub.contacts.setBlocked(r.addresses[0], !blocked)) Hub.banner.value = "Make Orbit your default phone app to block numbers."
                            Hub.changed()
                        }()
                    }
                }
            },
            confirmButton = { TextButton({ actionsFor = null }) { Text("Close") } },
        )
    }

    folderFor?.let { r -> FolderDialog(r.key.ifBlank { null }, folders) { folderFor = null } }
}

@Composable
private fun FolderDialog(key: String?, folders: List<Folder>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (key == null) "Folders" else "Move to folder") },
        text = {
            Column {
                if (key != null) {
                    TextButton({ scope.launch { InboxData.updateMeta(key) { it.copy(folderId = 0) }; onDone() } }) { Text("No folder") }
                    folders.forEach { f ->
                        TextButton({ scope.launch { InboxData.updateMeta(key) { it.copy(folderId = f.id) }; onDone() } }) { Text("📁 ${f.name}") }
                    }
                } else folders.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📁 ${f.name}", Modifier.weight(1f))
                        TextButton({ scope.launch { Hub.db.dao().deleteFolder(f.id) } }) { Text("Delete") }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("New folder name") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({
                scope.launch {
                    if (name.isNotBlank()) {
                        val id = Hub.db.dao().addFolder(Folder(name = name.trim()))
                        if (key != null) InboxData.updateMeta(key) { it.copy(folderId = id) }
                    }
                    onDone()
                }
            }) { Text(if (name.isBlank()) "Done" else "Create") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(r: ConvRow, left: String, right: String, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    fun run(action: String) {
        Haptics.confirm(view)
        scope.launch {
            when (action) {
                "archive" -> InboxData.updateMeta(r.key) { it.copy(archived = !it.archived) }
                "pin" -> InboxData.updateMeta(r.key) { it.copy(pinned = !it.pinned) }
                "mute" -> InboxData.updateMeta(r.key) { it.copy(muted = !it.muted) }
                "read" -> ActionReceiver.markRead(r.key)
            }
        }
    }
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> run(right)
            SwipeToDismissBoxValue.EndToStart -> run(left)
            else -> {}
        }
        false
    })
    SwipeToDismissBox(
        state,
        enableDismissFromStartToEnd = right != "none",
        enableDismissFromEndToStart = left != "none",
        backgroundContent = {
            val action = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) right else left
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 24.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd) {
                Text(action.replaceFirstChar { it.uppercase() }, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        },
    ) { Box(Modifier.background(MaterialTheme.colorScheme.background)) { content() } }
}

/** Search field that opens full search (sits under the big title, like a phone's own apps). */
@Composable
fun SearchPill(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text("Search", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by androidx.compose.animation.animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, Motion.spec(), label = "pill")
    Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp))
}

/** Pinned chats as big circles at the top. */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PinnedGrid(rows: List<ConvRow>, onOpen: (ConvRow) -> Unit, onLong: (ConvRow) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, maxItemsInEachRow = 3,
    ) {
        rows.take(9).forEach { r ->
            Column(Modifier.width(110.dp).padding(vertical = 8.dp).clip(RoundedCornerShape(16.dp))
                .combinedClickable(onClick = { onOpen(r) }, onLongClick = { onLong(r) }).padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    Avatar(r.name, r.photo, 76.dp, group = r.isGroup && r.photo == null, sharedKey = r.key)
                    if (r.unread) Box(Modifier.align(Alignment.TopStart).size(16.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.background).padding(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
                Text(r.name.substringBefore(' '), Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium, fontWeight = if (r.unread) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConvItem(r: ConvRow, onClick: () -> Unit, onLong: () -> Unit) {
    val style = LocalChatStyle.current
    val vPad = if (style.compact) 8.dp else 11.dp
    val avatar = if (style.compact) 42.dp else 52.dp
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().pressScale(press, 0.985f)
            .combinedClickable(press, androidx.compose.material3.ripple(), onClick = onClick, onLongClick = onLong).padding(start = 6.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Unread dot lives in the left margin, so names stay aligned
        Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(r.unread, enter = androidx.compose.animation.scaleIn(Motion.popSpec()), exit = androidx.compose.animation.scaleOut()) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            }
        }
        if (style.showAvatars) {
            Avatar(r.name, r.photo, avatar, group = r.isGroup && r.photo == null, sharedKey = r.key)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Column(Modifier.padding(vertical = vPad)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    if (r.meta.muted) Icon(Icons.Default.NotificationsOff, "Muted", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(shortTime(r.date), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                }
                Text(r.snippet, maxLines = if (style.compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
            androidx.compose.material3.HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

// ---------------- New chat ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen() {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var contacts by remember { mutableStateOf(emptyList<com.yatesv.contacthub.data.ContactInfo>()) }
    LaunchedEffect(Unit) { contacts = Hub.contacts.all() }
    val matches = contacts.filter { query.isBlank() || it.name.contains(query, true) || Phone.norm(it.number).contains(Phone.norm(query)) }
        .distinctBy { Phone.norm(it.number) }.take(200)

    fun start() {
        val numbers = picked.map { it.second }.ifEmpty { if (query.any { it.isDigit() }) listOf(query) else emptyList() }
        if (numbers.isEmpty()) return
        Nav.replace(Screen.Chat(Phone.convKey(numbers)))
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("New chat") }, navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { TextButton(::start) { Text(if (picked.size > 1) "Start group" else "Start") } })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            if (picked.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                picked.forEach { p -> androidx.compose.material3.InputChip(true, { picked = picked - p }, { Text(p.first) }) }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true,
                label = { Text("Name or number") })
            if (query.count { it.isDigit() } >= 3) {
                SettingRow("Text ${Phone.pretty(query)}", onClick = { picked = picked + (Phone.pretty(query) to query); query = "" })
            }
            LazyColumn {
                items(matches, key = { it.id.toString() + it.number }) { c ->
                    Row(Modifier.fillMaxWidth().clickable {
                        picked = picked + (c.name to c.number); query = ""
                    }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name, c.photo, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Column { Text(c.name); Text(Phone.pretty(c.number), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

// ---------------- Search / Starred / Scheduled ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen() {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(listOf<Triple<String, String, com.yatesv.contacthub.data.Msg>>()) }
    LaunchedEffect(q) {
        if (q.length < 2) { hits = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        val sms = runCatching { Hub.sms.search(q) }.getOrDefault(emptyList()).map { Triple(Phone.convKey(listOf(it.address)), it.address, it.msg) }
        val local = Hub.db.dao().allLocal().filter { it.body.contains(q, true) }
            .map { Triple(it.convKey, it.convKey, com.yatesv.contacthub.data.Msg("l:${it.id}", it.body, it.date, it.outgoing)) }
        hits = (sms + local).sortedByDescending { it.third.date }
    }
    Scaffold(topBar = {
        TopAppBar(title = {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search messages") })
        }, navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            items(hits) { (key, addr, m) ->
                SettingRow(InboxData.nameFor(key, listOf(addr), null, null), "${if (m.outgoing) "You: " else ""}${m.body}",
                    onClick = { Nav.go(Screen.Chat(key)) }) { Text(shortTime(m.date), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen() {
    val scope = rememberCoroutineScope()
    val starred by Hub.db.dao().starredFlow().collectAsState(emptyList())
    Scaffold(topBar = {
        TopAppBar(title = { Text("Starred") }, navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        if (starred.isEmpty()) Box(Modifier.padding(pad)) { EmptyState(Icons.Default.Star, "No starred messages", "Long-press a message and tap Star.") }
        LazyColumn(Modifier.padding(pad)) {
            items(starred) { s ->
                SettingRow(InboxData.nameFor(s.convKey, emptyList(), null, null), s.body, onClick = { Nav.go(Screen.Chat(s.convKey)) }) {
                    TextButton({ scope.launch { Hub.db.dao().unstar(s.msgKey) } }) { Text("Unstar") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen() {
    val scope = rememberCoroutineScope()
    val list by Hub.db.dao().scheduledFlow().collectAsState(emptyList())
    Scaffold(topBar = {
        TopAppBar(title = { Text("Scheduled") }, navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        if (list.isEmpty()) Box(Modifier.padding(pad)) { EmptyState(Icons.Default.Schedule, "Nothing scheduled", "In a chat, tap the clock next to Send.") }
        LazyColumn(Modifier.padding(pad)) {
            items(list) { s ->
                SettingRow(InboxData.nameFor(s.convKey, emptyList(), null, null),
                    "${fullTime(s.sendAt)}" + Hub.orbit.repeatOf(s.id).let { r -> if (r.isBlank()) "" else " · ${com.yatesv.contacthub.data.Recurring.label(r).lowercase()}" } + "\n${s.body}",
                    onClick = { Nav.go(Screen.Chat(s.convKey)) }) {
                    TextButton({ scope.launch { Hub.db.dao().deleteScheduled(s.id); Hub.orbit.setRepeat(s.id, "") } }) { Text("Cancel") }
                }
            }
        }
    }
}
