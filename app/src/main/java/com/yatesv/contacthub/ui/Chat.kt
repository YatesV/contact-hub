package com.yatesv.contacthub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.data.ContactExtra
import com.yatesv.contacthub.data.ConvMeta
import com.yatesv.contacthub.data.GroupInfo
import com.yatesv.contacthub.data.HubLog
import com.yatesv.contacthub.data.Msg
import com.yatesv.contacthub.data.Otp
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.data.Reaction
import com.yatesv.contacthub.data.Reactions
import com.yatesv.contacthub.data.Scheduled
import com.yatesv.contacthub.data.Starred
import com.yatesv.contacthub.engine.ActionReceiver
import com.yatesv.contacthub.engine.Notifier
import com.yatesv.contacthub.engine.ScheduleWorker
import com.yatesv.contacthub.engine.Sender
import com.yatesv.contacthub.phone.CallManager
import kotlinx.coroutines.launch
import java.util.Calendar

data class ShownReaction(val emoji: String, val fromMe: Boolean, val pending: Boolean, val id: Long)
data class ChatData(val msgs: List<Msg>, val reactions: Map<String, List<ShownReaction>>, val threadId: Long, val hasMore: Boolean = false)

/** Last loaded copy of each chat, so reopening a chat shows instantly while it refreshes. */
object ChatCache { val map = java.util.concurrent.ConcurrentHashMap<String, ChatData>() }

object ChatData0 {
    /** Puts together texts from the phone's database and RCS messages, and turns "Liked …" texts into reactions. */
    suspend fun load(key: String, limit: Int = 80): ChatData {
        val dao = Hub.db.dao()
        val thread = Hub.sms.threadIdFor(key)
        val provider = Hub.sms.messages(thread, limit, group = Phone.isGroupKey(key))
        val locals = dao.local(key)
        val merged = ArrayList(provider)
        for (l in locals) {
            val dup = provider.any { p -> p.outgoing == l.outgoing && p.body.trim() == l.body.trim() && kotlin.math.abs(p.date - l.date) < 180_000 }
            if (!dup) merged += Msg("l:${l.id}", l.body, l.date, l.outgoing, l.sender, rcs = true,
                status = if (l.source == "failed") 2 else 0)
        }
        merged.sortBy { it.date }

        val convert = Hub.settings.value.convertReactions
        val shown = ArrayList<Msg>()
        val reactions = HashMap<String, MutableList<ShownReaction>>()
        fun attach(target: String, before: Long, r: ShownReaction, removed: Boolean = false) {
            val m = shown.lastOrNull { it.date <= before && Reactions.matches(target, it.body) } ?: shown.lastOrNull { Reactions.matches(target, it.body) } ?: return
            val list = reactions.getOrPut(m.key) { mutableListOf() }
            if (removed) list.removeAll { it.emoji == r.emoji && it.fromMe == r.fromMe } else list += r
        }
        for (m in merged) {
            val p = if (convert && m.attachments.isEmpty()) Reactions.parse(m.body) else null
            if (p != null) attach(p.target, m.date, ShownReaction(p.emoji, m.outgoing, false, 0), p.removed)
            else shown += m
        }
        for (r in dao.reactionsFlowOnce(key)) {
            val sr = ShownReaction(r.emoji, r.fromMe, r.pending, r.id)
            if (r.msgKey.startsWith("q:")) attach(r.msgBody, r.date, sr)
            else reactions.getOrPut(r.msgKey) { mutableListOf() }.let { list ->
                if (list.none { it.emoji == sr.emoji && it.fromMe == sr.fromMe }) list += sr
            }
        }
        return ChatData(shown, reactions, thread, hasMore = provider.size >= limit)
    }
}

private suspend fun com.yatesv.contacthub.data.HubDao.reactionsFlowOnce(key: String): List<Reaction> =
    allReactions().filter { it.convKey == key }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(key: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = Hub.db.dao()
    val version by Hub.version.collectAsState()
    val settings by Hub.settings.flow.collectAsState()
    val style = LocalChatStyle.current
    val snack = remember { SnackbarHostState() }
    var data by remember { mutableStateOf(ChatCache.map[key]) }
    var limit by remember(key) { androidx.compose.runtime.mutableIntStateOf(80) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var meta by remember { mutableStateOf(ConvMeta(key)) }
    var group by remember { mutableStateOf<GroupInfo?>(null) }
    var extras by remember { mutableStateOf(emptyMap<String, ContactExtra>()) }
    var text by remember { mutableStateOf(Nav.drafts.remove(key).orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Msg?>(null) }
    var showTimes by remember { mutableStateOf(setOf<String>()) }
    var scheduling by remember { mutableStateOf(false) }
    var groupDialog by remember { mutableStateOf(false) }
    var colorDialog by remember { mutableStateOf(false) }
    /** Messages you just sent that haven't shown up in the real list yet (shown greyed as "Sending…"). */
    var pending by remember { mutableStateOf(listOf<Msg>()) }
    /** Where each bubble is on screen, for the iOS-style long-press menu. */
    val bounds = remember { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    /** Messages already on screen when the chat opened (no entrance animation for those). */
    val seen = remember(key) { HashSet<String>() }
    var initialDone by remember(key) { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    val starred by dao.starredFlow().collectAsState(emptyList())
    val addresses = Sender.addressesOf(key)
    val isGroup = Phone.isGroupKey(key)
    val name = InboxData.nameFor(key, addresses, meta, group?.name)
    val chatType = Hub.rcsState(key)

    DisposableEffect(key) {
        Hub.openConv = key
        onDispose { if (Hub.openConv == key) Hub.openConv = "" }
    }
    LaunchedEffect(key, version, limit) {
        meta = dao.meta(key) ?: ConvMeta(key)
        group = dao.allGroups().firstOrNull { it.convKey == key }
        extras = dao.allExtras().associateBy { it.phoneKey }
        if (data != null) kotlinx.coroutines.delay(150)   // coalesce bursts of updates
        val fresh = runCatching { ChatData0.load(key, limit) }.getOrElse { HubLog.w("Chat", "load failed", it); null }
        if (fresh != null) { data = fresh; ChatCache.map[key] = fresh }
        else if (data == null) data = ChatData(emptyList(), emptyMap(), 0)
    }
    // Scrolled up to the oldest loaded message: load 200 more
    LaunchedEffect(listState, data?.hasMore) {
        androidx.compose.runtime.snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (data?.hasMore == true && last != null && total > 0 && last >= total - 5) limit += 200 }
    }
    LaunchedEffect(key) {
        ActionReceiver.markRead(key); Notifier.cancel(ctx, key)
        Sender.retryPendingFor(ctx, key)
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val r = Sender.sendMedia(ctx, key, uris, text)
            if (r.ok) text = "" else snack.showSnackbar(r.error)
        }
    }
    val pickWallpaper = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch { InboxData.updateMeta(key) { it.copy(wallpaperUri = uri.toString()) } }
        }
    }
    val pickSound = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        @Suppress("DEPRECATION")
        val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        scope.launch { InboxData.updateMeta(key) { it.copy(soundUri = uri?.toString().orEmpty()) } }
    }

    fun send() {
        val body = text.trim()
        if (body.isEmpty()) return
        text = ""
        Haptics.tap(view)
        SendSound.play()
        Effects.trigger(body)
        val temp = Msg("p:${System.nanoTime()}", body, System.currentTimeMillis(), true, status = 1)
        pending = pending + temp
        // App-wide scope: leaving the chat mid-send must not cancel it
        Hub.scope.launch {
            val r = Sender.send(ctx, key, body)
            kotlinx.coroutines.delay(400)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                pending = pending - temp
                if (!r.ok) {
                    if (text.isBlank()) text = body      // don't wipe anything typed since
                    Haptics.reject(view)
                    scope.launch { runCatching { snack.showSnackbar(r.error) } }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            CenterAlignedTopAppBar(
                expandedHeight = 76.dp,
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)),
                navigationIcon = { IconButton({ Nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", tint = MaterialTheme.colorScheme.primary) } },
                title = {
                    // Photo on top, name underneath: tap for their contact card
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                        if (isGroup) groupDialog = true else addresses.firstOrNull()?.let { Nav.go(Screen.Contact(it)) }
                    }.padding(horizontal = 10.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(name, group?.photoUri?.ifBlank { null } ?: if (!isGroup) Hub.contacts.cached(addresses.firstOrNull().orEmpty())?.photo else null,
                            40.dp, group = isGroup && group?.photoUri.isNullOrBlank())
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Text(name, maxLines = 1, style = MaterialTheme.typography.labelMedium, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (!isGroup && addresses.isNotEmpty()) IconButton({ CallManager.place(ctx, addresses[0]) }) { Icon(Icons.Default.Call, "Call", tint = MaterialTheme.colorScheme.primary) }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreHoriz, "More", tint = MaterialTheme.colorScheme.primary) }
                        DropdownMenu(menu, { menu = false }) {
                            if (isGroup) DropdownMenuItem({ Text("Group name, photo & colors") }, { menu = false; groupDialog = true })
                            else DropdownMenuItem({ Text("Contact page") }, { menu = false; addresses.firstOrNull()?.let { Nav.go(Screen.Contact(it)) } })
                            DropdownMenuItem({ Text(if (meta.muted) "Unmute" else "Mute") }, { menu = false; scope.launch { InboxData.updateMeta(key) { it.copy(muted = !it.muted) } } })
                            DropdownMenuItem({ Text(if (meta.pinned) "Unpin" else "Pin") }, { menu = false; scope.launch { InboxData.updateMeta(key) { it.copy(pinned = !it.pinned) } } })
                            DropdownMenuItem({ Text("Chat wallpaper") }, { menu = false; pickWallpaper.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                            if (meta.wallpaperUri.isNotBlank()) DropdownMenuItem({ Text("Remove chat wallpaper") }, { menu = false; scope.launch { InboxData.updateMeta(key) { it.copy(wallpaperUri = "") } } })
                            DropdownMenuItem({ Text("Bubble color for this chat") }, { menu = false; colorDialog = true })
                            DropdownMenuItem({ Text("Notification sound") }, {
                                menu = false
                                pickSound.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false))
                            })
                            DropdownMenuItem({ Text("Send through: ${when (meta.engine) { "google" -> "Google Messages"; "samsung" -> "Samsung Messages"; else -> "Auto" }}") }, {
                                menu = false
                                val next = when (meta.engine) { "auto" -> "google"; "google" -> "samsung"; else -> "auto" }
                                scope.launch { InboxData.updateMeta(key) { it.copy(engine = next) } }
                            })
                            DropdownMenuItem({ Text(if (meta.hidden) "Unhide chat" else "Hide chat") }, { menu = false; scope.launch { InboxData.updateMeta(key) { it.copy(hidden = !it.hidden) } } })
                        }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val wp = meta.wallpaperUri.ifBlank { settings.wallpaper }
            if (wp.isNotBlank()) {
                AsyncImage(wp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.wallpaperDim)))
            }
            val iosMenu = selected != null && settings.menuStyle == "ios"
            Column(Modifier.fillMaxSize().imePadding().then(if (iosMenu) Modifier.blur(14.dp) else Modifier)) {
                val real = data?.msgs.orEmpty()
                val waiting = pending.filter { p -> real.none { it.outgoing && it.body.trim() == p.body && it.date >= p.date - 60_000 } }
                val msgs = real + waiting
                LaunchedEffect(data) {
                    if (data != null && !initialDone) { seen.addAll(real.map { it.key }); initialDone = true }
                }
                val starredKeys = starred.map { it.msgKey }.toSet()
                // Drag left anywhere in the chat to peek at every message's time; it springs back when you let go
                val reveal = remember { androidx.compose.animation.core.Animatable(0f) }
                val maxReveal = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
                val lastOutKey = msgs.lastOrNull { it.outgoing }?.key
                LazyColumn(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { scope.launch { reveal.animateTo(0f, Motion.spec()) } },
                        onDragCancel = { scope.launch { reveal.animateTo(0f, Motion.spec()) } },
                    ) { change, dx -> change.consume(); scope.launch { reveal.snapTo((reveal.value - dx * 0.6f).coerceIn(0f, maxReveal)) } }
                }, state = listState, reverseLayout = true, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                    val reversed = msgs.asReversed()
                    items(reversed.size, key = { reversed[it].key }) { i ->
                        val m = reversed[i]
                        val isNew = initialDone && m.key !in seen
                        val older = reversed.getOrNull(i + 1)
                        val newer = reversed.getOrNull(i - 1)
                        val first = older == null || older.outgoing != m.outgoing || older.sender != m.sender || m.date - older.date > 300_000
                        val last = newer == null || newer.outgoing != m.outgoing || newer.sender != m.sender || newer.date - m.date > 300_000
                        Column(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = Motion.spec())) {
                            if (older == null || !sameDay(older.date, m.date)) DateChip(m.date)
                            Box(Modifier.fillMaxWidth()) {
                            if (reveal.value > 1f) Text(java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(m.date)),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).graphicsLayer { alpha = reveal.value / maxReveal })
                            Box(Modifier.graphicsLayer { translationX = -reveal.value }) {
                            EnterAnim(isNew, m.outgoing, onShown = {
                                seen.add(m.key)
                                if (!m.outgoing) Effects.trigger(m.body)
                            }) { Bubble(
                                m, first, last, isGroup, data?.reactions?.get(m.key).orEmpty(),
                                showTime = style.timestamps == "always" || m.key in showTimes || (style.timestamps == "groups" && last),
                                starred = m.key in starredKeys,
                                colorOverride = meta.bubbleColor,
                                memberColor = if (isGroup && !m.outgoing) memberColor(m.sender, extras[Phone.norm(m.sender)]?.groupColor ?: 0) else null,
                                mention = isGroup && !m.outgoing && settings.myName.isNotBlank() && m.body.contains(settings.myName, true),
                                onClick = { showTimes = if (m.key in showTimes) showTimes - m.key else showTimes + m.key },
                                onLong = { Haptics.long(view); selected = m },
                                onBounds = { bounds[m.key] = it },
                            ) }
                            }
                            }
                            // Status under your last message
                            if (m.key == lastOutKey) Text(
                                when {
                                    m.status == 1 -> "Sending…"
                                    m.status == 2 -> "Not delivered"
                                    chatType == "sms" || (!m.rcs && m.key.startsWith("s:")) -> "Sent as text message"
                                    else -> "Sent"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (m.status == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.End).padding(end = 16.dp, top = 2.dp),
                            )
                        }
                    }
                }

                // @mention suggestions in groups
                val mentionQuery = Regex("@(\\w*)$").find(text)?.groupValues?.get(1)
                if (isGroup && mentionQuery != null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        addresses.mapNotNull { Hub.contacts.cached(it)?.name }.filter { it.contains(mentionQuery, true) }.forEach { n ->
                            androidx.compose.material3.SuggestionChip({ text = text.dropLast(mentionQuery.length + 1) + "@" + n.substringBefore(' ') + " " }, { Text(n) })
                        }
                    }
                }

                Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f)) {
                    var plus by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                        Box {
                            IconButton({ plus = true }, Modifier.padding(bottom = 2.dp).size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Icon(Icons.Default.Add, "Photos and more", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(plus, { plus = false }) {
                                DropdownMenuItem({ Text("Photos") }, { plus = false; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                                    leadingIcon = { Icon(Icons.Default.AddPhotoAlternate, null) })
                                DropdownMenuItem({ Text("Schedule message") }, { plus = false; if (text.isNotBlank()) scheduling = true else scope.launch { snack.showSnackbar("Type a message first, then schedule it") } },
                                    leadingIcon = { Icon(Icons.Default.Schedule, null) })
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        // Pill-shaped text box with the send arrow inside it
                        Row(Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.background).heightIn(min = 40.dp, max = 160.dp),
                            verticalAlignment = Alignment.Bottom) {
                            androidx.compose.foundation.text.BasicTextField(
                                text, { text = it },
                                Modifier.weight(1f).padding(start = 14.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    imeAction = if (settings.sendOnEnter) androidx.compose.ui.text.input.ImeAction.Send else androidx.compose.ui.text.input.ImeAction.Default,
                                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
                                decorationBox = { inner ->
                                    Box {
                                        if (text.isEmpty()) Text(if (chatType == "rcs") "RCS message" else "Text message",
                                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        inner()
                                    }
                                },
                            )
                            androidx.compose.animation.AnimatedVisibility(text.isNotBlank(),
                                enter = androidx.compose.animation.scaleIn(Motion.popSpec()) + androidx.compose.animation.fadeIn(),
                                exit = androidx.compose.animation.scaleOut(Motion.spec()) + androidx.compose.animation.fadeOut()) {
                                Box(Modifier.padding(4.dp).size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                                    .clickable(onClickLabel = "Send") { send() }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.ArrowUpward, "Send", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { m ->
        val isStarred = m.key in starred.map { it.msgKey }
        val onReact: (String) -> Unit = { emoji ->
            selected = null
            Haptics.confirm(view)
            scope.launch {
                val existing = dao.findReaction(key, m.key, true, emoji)
                if (existing != null) { dao.deleteReaction(existing.id); Hub.changed(); return@launch }
                val id = dao.addReaction(Reaction(convKey = key, msgKey = m.key, msgBody = m.body, emoji = emoji, fromMe = true, pending = true))
                Hub.changed()
                val r = Reaction(id, key, m.key, m.body, emoji, true, true)
                val waiting = if (Hub.rcsState(key) != "sms") Sender.trySendReaction(ctx, r) else "this chat is on SMS"
                Hub.changed()
                if (waiting != null) snack.showSnackbar("Reaction saved on your phone 🕓 ($waiting). It'll send over RCS later, never as a text.")
            }
        }
        val onStar: (Boolean) -> Unit = { on ->
            selected = null
            scope.launch { if (on) dao.star(Starred(m.key, key, m.body, m.date)) else dao.unstar(m.key) }
        }
        val onCopy: (String) -> Unit = { s -> selected = null; copy(ctx, s); Haptics.tap(view) }
        if (settings.menuStyle == "ios") {
            IosMessageMenu(m, bounds[m.key], isStarred, if (m.outgoing && meta.bubbleColor != 0) Color(meta.bubbleColor.toLong() and 0xFFFFFFFFL)
                else if (m.outgoing) style.sent else style.recv,
                if (m.outgoing) style.sentText else style.recvText,
                onDismiss = { selected = null }, onReact = onReact, onStar = onStar, onCopy = onCopy)
        } else {
            MessageActions(m, isStarred, onDismiss = { selected = null }, onReact = onReact, onStar = onStar, onCopy = onCopy)
        }
    }

    if (scheduling) ScheduleDialog(onDismiss = { scheduling = false }) { at ->
        scheduling = false
        val body = text.trim()
        text = ""
        scope.launch {
            dao.schedule(Scheduled(convKey = key, addresses = addresses.joinToString(","), body = body, sendAt = at))
            ScheduleWorker.enqueue(ctx, at - System.currentTimeMillis())
            snack.showSnackbar("Scheduled for ${fullTime(at)}")
        }
    }

    if (colorDialog) AlertDialog(
        onDismissRequest = { colorDialog = false },
        title = { Text("Your bubble color here") },
        text = { ColorRow("Color", meta.bubbleColor) { c -> scope.launch { InboxData.updateMeta(key) { it.copy(bubbleColor = c) } }; colorDialog = false } },
        confirmButton = { TextButton({ colorDialog = false }) { Text("Done") } },
    )

    if (groupDialog) GroupDialog(key, addresses, group, extras) { groupDialog = false }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun DateChip(date: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(java.text.SimpleDateFormat("EEEE, MMM d", java.util.Locale.getDefault()).format(java.util.Date(date)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

private fun isOnlyEmoji(s: String): Boolean {
    val t = s.trim()
    if (t.isEmpty() || t.length > 16) return false
    return t.codePoints().toArray().all { cp ->
        Character.getType(cp) == Character.OTHER_SYMBOL.toInt() || Character.getType(cp) == Character.SURROGATE.toInt() ||
            cp == 0x200D || cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Msg, first: Boolean, last: Boolean, isGroup: Boolean, reactions: List<ShownReaction>, showTime: Boolean, starred: Boolean,
    colorOverride: Int, memberColor: Color?, mention: Boolean, onClick: () -> Unit, onLong: () -> Unit,
    onBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
) {
    val style = LocalChatStyle.current
    val ctx = LocalContext.current
    val bg = when {
        m.outgoing && colorOverride != 0 -> Color(colorOverride.toLong() and 0xFFFFFFFFL)
        m.outgoing -> style.sent
        memberColor != null -> memberColor.copy(alpha = 0.85f)
        else -> style.recv
    }
    val fg = if (m.outgoing && colorOverride == 0) style.sentText else if (!m.outgoing && memberColor == null) style.recvText else onColor(bg)
    val emojiOnly = m.attachments.isEmpty() && isOnlyEmoji(m.body)
    val otp = if (!m.outgoing) Otp.find(m.body) else null

    Column(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = if (first) 6.dp else 1.dp, bottom = if (reactions.isNotEmpty()) 12.dp else 1.dp),
        horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start,
    ) {
        if (isGroup && !m.outgoing && first) {
            Text(Hub.contacts.cached(m.sender)?.name ?: m.sender.ifBlank { "Unknown" }.let { Phone.pretty(it) },
                style = MaterialTheme.typography.labelSmall, color = memberColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
        }
        Box {
            Column(
                Modifier.widthIn(max = 300.dp)
                    .onGloballyPositioned { onBounds(it.boundsInRoot()) }
                    .graphicsLayer { alpha = if (m.status == 1 && m.key.startsWith("p:")) 0.6f else 1f }
                    .clip(style.bubble(m.outgoing, first, last))
                    .background(if (emojiOnly) Color.Transparent else bg)
                    .then(if (mention) Modifier.border(2.dp, MaterialTheme.colorScheme.tertiary, style.bubble(m.outgoing, first, last)) else Modifier)
                    .combinedClickable(onClick = onClick, onLongClick = onLong)
                    .padding(if (emojiOnly) 2.dp else 0.dp),
            ) {
                m.attachments.forEach { a ->
                    AsyncImage(a.uri, null, Modifier.widthIn(max = 300.dp).heightIn(max = 340.dp)
                        .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(a.uri, a.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) },
                        contentScale = ContentScale.Crop)
                }
                if (m.body.isNotBlank()) {
                    Text(m.body, color = if (emojiOnly) MaterialTheme.colorScheme.onSurface else fg,
                        fontSize = if (emojiOnly) (16 * style.emojiScale * 1.5f).sp else 16.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
                }
                if (otp != null) TextButton({ copy(ctx, otp) }, Modifier.padding(start = 6.dp)) { Text("Copy code $otp", color = fg, fontWeight = FontWeight.SemiBold) }
            }
            if (reactions.isNotEmpty()) {
                Row(
                    Modifier.align(if (m.outgoing) Alignment.BottomStart else Alignment.BottomEnd).offset(y = 14.dp)
                        .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(1.dp, MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    reactions.groupBy { it.emoji }.forEach { (e, list) ->
                        PopIn(e + m.key) {
                            Text(e + (if (list.size > 1) "${list.size}" else "") + (if (list.any { it.pending }) "🕓" else ""), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        if (showTime || m.status != 0 || starred) {
            Text(
                listOfNotNull(
                    if (starred) "★" else null,
                    shortTime(m.date),
                    when (m.status) { 1 -> "Sending…"; 2 -> "Not sent"; else -> null },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (m.status == 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun MessageActions(m: Msg, isStarred: Boolean, onDismiss: () -> Unit, onReact: (String) -> Unit, onStar: (Boolean) -> Unit, onCopy: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Reactions.picker.forEach { e ->
                        Text(e, fontSize = 30.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onReact(e) }.padding(6.dp))
                    }
                }
                Spacer(Modifier.size(8.dp))
                Text(m.body.take(200), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                TextButton({ onCopy(m.body) }) { Text("Copy text") }
                Otp.find(m.body)?.let { code -> TextButton({ onCopy(code) }) { Text("Copy code $code") } }
                TextButton({ onStar(!isStarred) }) { Text(if (isStarred) "Unstar" else "Star") }
                Text(fullTime(m.date) + (if (m.rcs) " · RCS" else ""), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

fun copy(ctx: Context, s: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", s))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleDialog(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    var pickedDay by remember { mutableStateOf<Long?>(null) }
    fun at(daysAhead: Int, hour: Int, min: Int = 0) = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, daysAhead); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0)
    }.timeInMillis
    if (!custom) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Schedule message") },
            text = {
                Column {
                    TextButton({ onPick(System.currentTimeMillis() + 3_600_000) }) { Text("In 1 hour") }
                    val tonight = at(0, 20)
                    if (tonight > System.currentTimeMillis()) TextButton({ onPick(tonight) }) { Text("Tonight, 8:00 PM") }
                    TextButton({ onPick(at(1, 9)) }) { Text("Tomorrow, 9:00 AM") }
                    TextButton({ onPick(at(1, 13)) }) { Text("Tomorrow, 1:00 PM") }
                    TextButton({ custom = true }) { Text("Pick date & time…") }
                }
            },
            confirmButton = { TextButton(onDismiss) { Text("Cancel") } },
        )
    } else if (pickedDay == null) {
        val state = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        DatePickerDialog(onDismissRequest = onDismiss, confirmButton = {
            TextButton({ pickedDay = state.selectedDateMillis ?: System.currentTimeMillis() }) { Text("Next") }
        }) { DatePicker(state) }
    } else {
        val t = rememberTimePickerState(initialHour = 9, initialMinute = 0)
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { TimePicker(t) },
            confirmButton = {
                TextButton({
                    // DatePicker gives UTC midnight; convert to the local day
                    val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = pickedDay!! }
                    val local = Calendar.getInstance().apply {
                        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), t.hour, t.minute, 0)
                    }
                    onPick(local.timeInMillis)
                }) { Text("Schedule") }
            },
            dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GroupDialog(key: String, addresses: List<String>, group: GroupInfo?, extras: Map<String, ContactExtra>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(group?.name.orEmpty()) }
    var photo by remember { mutableStateOf(group?.photoUri.orEmpty()) }
    var colorFor by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            photo = uri.toString()
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Group") },
        text = {
            Column {
                Text("Name and photo are only saved on your phone. Nothing is sent to the group.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text("Group name") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Avatar(name.ifBlank { "Group" }, photo.ifBlank { null }, 48.dp, group = photo.isBlank())
                    TextButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose photo") }
                    if (photo.isNotBlank()) TextButton({ photo = "" }) { Text("Remove") }
                }
                Text("Member colors", style = MaterialTheme.typography.labelLarge)
                addresses.forEach { a ->
                    val k = Phone.norm(a)
                    Row(Modifier.fillMaxWidth().clickable { colorFor = k }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(memberColor(a, extras[k]?.groupColor ?: 0)))
                        Text(Hub.contacts.cached(a)?.name ?: Phone.pretty(a), Modifier.padding(start = 10.dp))
                    }
                }
                colorFor?.let { k ->
                    ColorRow("Color for ${Hub.contacts.cached(k)?.name ?: Phone.pretty(k)}", extras[k]?.groupColor ?: 0) { c ->
                        scope.launch {
                            val e = Hub.db.dao().extra(k) ?: ContactExtra(k)
                            Hub.db.dao().putExtra(e.copy(groupColor = c)); Hub.changed()
                        }
                        colorFor = null
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                // App-wide scope: closing the dialog would cancel its own scope before the save finished
                Hub.scope.launch { Hub.db.dao().putGroup(GroupInfo(key, name.trim(), photo)); Hub.changed() }
                onDone()
            }) { Text("Save") }
        },
    )
}
