package com.yatesv.contacthub.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yatesv.contacthub.Hub
import com.yatesv.contacthub.R
import com.yatesv.contacthub.data.CatchUp
import com.yatesv.contacthub.data.Links
import com.yatesv.contacthub.data.Msg
import com.yatesv.contacthub.data.Otp
import com.yatesv.contacthub.data.Phone
import com.yatesv.contacthub.data.PinnedMsg
import com.yatesv.contacthub.data.Relationship
import com.yatesv.contacthub.data.Snooze
import com.yatesv.contacthub.data.Trail
import com.yatesv.contacthub.engine.Ai
import com.yatesv.contacthub.engine.SnoozeWorker
import kotlinx.coroutines.launch

// ---------------- App icon (#19) ----------------

object AppIcons {
    data class Icon(val id: String, val label: String, val res: Int)
    val all = listOf(
        Icon("Orbit", "Orbit", R.drawable.ic_orbit_orbit),
        Icon("Neon", "Neon", R.drawable.ic_orbit_neon),
        Icon("Sunset", "Sunset", R.drawable.ic_orbit_sunset),
        Icon("Mono", "Mono", R.drawable.ic_orbit_mono),
    )
    private fun cn(ctx: Context, id: String) = ComponentName(ctx.packageName, "com.yatesv.contacthub.Icon$id")

    fun current(ctx: Context): String = all.firstOrNull { i ->
        when (ctx.packageManager.getComponentEnabledSetting(cn(ctx, i.id))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> i.id == "Orbit"
            else -> false
        }
    }?.id ?: "Orbit"

    /** Turns the new icon on first, then the others off, so there's always one icon in the app drawer. */
    fun set(ctx: Context, id: String) {
        val pm = ctx.packageManager
        pm.setComponentEnabledSetting(cn(ctx, id), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        all.filter { it.id != id }.forEach { pm.setComponentEnabledSetting(cn(ctx, it.id), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) }
    }
}

@Composable
fun AppIconPicker() {
    val ctx = LocalContext.current
    var cur by remember { mutableStateOf(AppIcons.current(ctx)) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AppIcons.all.forEach { i ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable {
                if (cur != i.id) { AppIcons.set(ctx, i.id); cur = i.id }
            }.padding(6.dp)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Black)
                    .then(if (cur == i.id) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)) {
                    Image(painterResource(i.res), i.label, Modifier.size(64.dp))
                }
                Text(i.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp),
                    color = if (cur == i.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    Text("Your launcher may take a few seconds to update. If the home screen icon disappears, add Orbit again from the app drawer.",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---------------- AI settings ----------------

@Composable
fun AiPage() {
    val s by Hub.settings.flow.collectAsState()
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(s.aiKey) }
    var provider by remember { mutableStateOf(if (s.aiProvider == "off") "xai" else s.aiProvider) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    SwitchRow("Smart replies", "Reply suggestions above the keyboard and in notifications (also on your watch)", s.smartReplies) { v -> Hub.settings.update { it.copy(smartReplies = v) } }
    SwitchRow("Birthday reminders", "A notification at 9 AM with a one-tap birthday text", s.birthdayReminders) { v -> Hub.settings.update { it.copy(birthdayReminders = v) } }
    ChoiceRow("Translate messages into", listOf("" to "Phone language", "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "pt" to "Portuguese", "vi" to "Vietnamese", "zh" to "Chinese"),
        s.translateTo) { v -> Hub.settings.update { it.copy(translateTo = v) } }
    Text("Translation and smart replies run on your phone. Each language downloads once (about 30 MB).",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    SectionTitle("AI key (optional)")
    Text("Add a Grok (xAI) or OpenAI key to make smart replies sound like you and to get AI-written \"Catch me up\" summaries. " +
        "With a key, the messages you're replying to or summarizing are sent to that company. Without one, everything stays on your phone. You pay the provider for what you use (usually cents a month).",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ChoiceRow("Provider", listOf("xai" to "Grok (xAI)", "openai" to "OpenAI"), provider) { provider = it }
    OutlinedTextField(key, { key = it.trim() }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), label = { Text("API key") },
        singleLine = true, visualTransformation = PasswordVisualTransformation())
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({
            scope.launch {
                busy = true; status = ""
                runCatching { Ai.setup(provider, key) }
                    .onSuccess { m -> Hub.settings.update { it.copy(aiProvider = provider, aiKey = key, aiModel = m) }; status = "✓ Working. Using $m." }
                    .onFailure { status = it.message ?: "That key didn't work" }
                busy = false
            }
        }, enabled = key.isNotBlank() && !busy) { Text(if (busy) "Testing…" else "Save & test") }
        if (s.aiProvider != "off") OutlinedButton({ Hub.settings.update { it.copy(aiProvider = "off", aiKey = "", aiModel = "") }; key = ""; status = "AI key removed" }) { Text("Remove key") }
    }
    if (status.isNotBlank()) Text(status, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
        color = if (status.startsWith("✓") || status.startsWith("AI key removed")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    if (s.aiProvider != "off" && status.isBlank()) Text("Using ${if (s.aiProvider == "openai") "OpenAI" else "Grok"} · ${s.aiModel.ifBlank { "auto" }}",
        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
}

// ---------------- Smart replies (#1) ----------------

@Composable
fun SmartReplyRow(key: String, msgs: List<Msg>, onPick: (String) -> Unit) {
    val s by Hub.settings.flow.collectAsState()
    val last = msgs.lastOrNull { it.body.isNotBlank() }
    var replies by remember(key) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(key, last?.key, s.smartReplies, s.aiProvider) {
        replies = emptyList()
        if (!s.smartReplies || last == null || last.outgoing || Otp.find(last.body) != null) return@LaunchedEffect
        // Cached per message, so typing and clearing the box doesn't ask again (or cost AI credits)
        val cacheKey = "${last.key}|${s.aiProvider}"
        replies = ReplyCache.map[cacheKey] ?: Ai.replies(msgs) { m -> Hub.contacts.cached(m.sender)?.name?.substringBefore(' ') ?: "Them" }
            .also { ReplyCache.map[cacheKey] = it }
    }
    AnimatedVisibility(replies.isNotEmpty(), enter = expandVertically(Motion.spec()) + fadeIn(Motion.spec()), exit = shrinkVertically(Motion.spec()) + fadeOut(Motion.spec())) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (Ai.hasKey) Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            replies.forEach { r -> SuggestionChip({ onPick(r); replies = emptyList() }, { Text(r, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
        }
    }
}

// ---------------- Catch me up (#2) ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatchUpSheet(msgs: List<Msg>, isGroup: Boolean, chatName: String, onDone: () -> Unit) {
    val s = Hub.settings.value
    var ai by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(Ai.hasKey) }
    var err by remember { mutableStateOf("") }
    fun who(m: Msg) = if (m.outgoing) "You" else if (isGroup) Hub.contacts.cached(m.sender)?.name?.substringBefore(' ') ?: Phone.pretty(m.sender) else chatName.substringBefore(' ')
    // Since you last replied, or the last 60 messages
    val lastMine = msgs.indexOfLast { it.outgoing }
    val recent = (if (lastMine >= 0 && msgs.size - lastMine > 3) msgs.drop(lastMine) else msgs.takeLast(60)).filter { it.body.isNotBlank() }
    LaunchedEffect(Unit) {
        if (Ai.hasKey) runCatching { Ai.summary(recent.map { who(it) to it.body }) }.onSuccess { ai = it }.onFailure { err = it.message ?: "AI failed" }
        busy = false
    }
    ModalBottomSheet(onDone) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Text("  Catch me up", style = MaterialTheme.typography.titleLarge)
            }
            Text("${recent.size} recent messages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            when {
                busy -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  Summarizing…") }
                ai != null -> Text(ai!!, style = MaterialTheme.typography.bodyLarge)
                else -> {
                    if (err.isNotBlank()) Text("AI didn't work ($err). Here's the quick version:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    CatchUp.summarize(recent.map { CatchUp.Line(who(it), it.body) }, s.myName).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    if (!Ai.hasKey) Text("Add an AI key in Settings → AI & smart replies for a written summary.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

// ---------------- Snooze (#5) ----------------

@Composable
fun SnoozeDialog(key: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val current = Hub.orbit.snoozes()[key]
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Snooze chat") },
        text = {
            Column {
                Text("It leaves your inbox and comes back with a reminder. A new message from them brings it back right away.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Snooze.presets().forEach { (label, at) -> TextButton({ SnoozeWorker.snooze(ctx, key, at); onDone() }) { Text(label) } }
                if (current != null) TextButton({ SnoozeWorker.unsnooze(ctx, key); onDone() }) { Text("Unsnooze (was until ${fullTime(current.first)})") }
            }
        },
        confirmButton = { TextButton(onDone) { Text("Cancel") } },
    )
}

// ---------------- Search inside a chat (#6) ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatSearch(msgs: List<Msg>, onOpen: (Msg) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    val hits = remember(q, filter, msgs) {
        msgs.asReversed().filter { m ->
            val textOk = q.isBlank() || m.body.contains(q, true)
            textOk && when (filter) {
                "links" -> Links.find(m.body).any { it.kind == Links.Kind.URL }
                "photos" -> m.attachments.any { it.mime.startsWith("image") || it.mime.startsWith("video") }
                "codes" -> Otp.find(m.body) != null
                "mine" -> m.outgoing
                else -> q.isNotBlank()
            }
        }.take(200)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Search this chat") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                IconButton(onClose) { Icon(Icons.Default.Close, "Close search") }
            }
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to "Text", "links" to "Links", "photos" to "Photos", "codes" to "Codes", "mine" to "From me").forEach { (k, l) ->
                    FilterChip(filter == k, { filter = k }, { Text(l) })
                }
            }
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                if (hits.isEmpty() && (q.isNotBlank() || filter != "all")) Text("No matches in the loaded messages", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                hits.forEach { m ->
                    SettingRow(m.body.ifBlank { if (m.attachments.isNotEmpty()) "📷 Photo" else "" }.take(140),
                        (if (m.outgoing) "You · " else "") + fullTime(m.date), onClick = { onOpen(m) })
                }
            }
        }
    }
}

// ---------------- Pinned messages (#8) ----------------

@Composable
fun PinnedBar(key: String, onOpen: (PinnedMsg) -> Unit) {
    val v by Hub.orbit.version.collectAsState()
    val pins = remember(key, v) { Hub.orbit.pins(key) }
    var idx by remember(key) { mutableIntStateOf(0) }
    var list by remember { mutableStateOf(false) }
    AnimatedVisibility(pins.isNotEmpty(), enter = expandVertically(Motion.spec()) + fadeIn(Motion.spec()), exit = shrinkVertically(Motion.spec()) + fadeOut(Motion.spec())) {
        val p = pins.getOrNull(idx % pins.size.coerceAtLeast(1)) ?: return@AnimatedVisibility
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().clickable { onOpen(p); if (pins.size > 1) idx++ }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PushPin, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(if (pins.size > 1) "Pinned ${idx % pins.size + 1} of ${pins.size}" else "Pinned", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(p.body.ifBlank { "📷 Photo" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton({ list = true }) { Text("All") }
            }
        }
    }
    if (list) AlertDialog(
        onDismissRequest = { list = false },
        title = { Text("Pinned messages") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                pins.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.body.ifBlank { "📷 Photo" }, Modifier.weight(1f).clickable { list = false; onOpen(p) }.padding(vertical = 8.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        TextButton({ Hub.orbit.unpin(key, p.msgKey) }) { Text("Unpin") }
                    }
                }
                if (pins.isEmpty()) Text("Nothing pinned")
            }
        },
        confirmButton = { TextButton({ list = false }) { Text("Close") } },
    )
}

// ---------------- Voice messages and videos in bubbles (#4) ----------------

/** Play button + "Transcribe" for voice messages. */
@Composable
fun AudioAttachment(uri: Uri, fg: Color) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf(false) }
    var text by remember(uri) { mutableStateOf(Transcripts.map[uri.toString()]) }
    var busy by remember { mutableStateOf(false) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(uri) { onDispose { player[0]?.release(); player[0] = null } }
    fun transcribe() = scope.launch {
        busy = true
        Ai.transcribe(ctx, uri).onSuccess { t -> text = t; Transcripts.map[uri.toString()] = t }.onFailure { text = "⚠️ ${it.message}" }
        busy = false
    }
    // Android's speech recognizer needs the microphone permission even for a recording; Orbit never uses the mic.
    // Asking needs an app screen: the chat copy shown while sending through Google Messages has none (asking there crashed).
    val canAsk = androidx.activity.compose.LocalActivityResultRegistryOwner.current != null
    val mic = if (canAsk) androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) transcribe() else text = "⚠️ Allow the microphone permission to turn voice messages into text (Orbit doesn't record anything)"
    } else null
    Column(Modifier.widthIn(min = 200.dp, max = 300.dp).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({
                if (playing) { player[0]?.pause(); playing = false }
                else {
                    val p = player[0] ?: runCatching { MediaPlayer.create(ctx, uri) }.getOrNull()?.also { mp -> player[0] = mp; mp.setOnCompletionListener { playing = false } }
                    if (p == null) Hub.banner.value = "Can't play that audio" else { p.start(); playing = true }
                }
            }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", tint = fg) }
            Icon(Icons.Default.GraphicEq, null, tint = fg.copy(alpha = 0.7f))
            Text(" Voice message", color = fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (text == null) TextButton({
                if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) transcribe()
                else mic?.launch(android.Manifest.permission.RECORD_AUDIO) ?: run { text = "⚠️ Open the chat in Orbit to turn this into text" }
            }, enabled = !busy) { Text(if (busy) "…" else "Text", color = fg) }
        }
        text?.let { Text(it, color = fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) }
    }
}

object Transcripts { val map = HashMap<String, String>() }
object ReplyCache { val map = java.util.concurrent.ConcurrentHashMap<String, List<String>>() }

@Composable
fun VideoAttachment(uri: Uri, mime: String) {
    val ctx = LocalContext.current
    Box(Modifier.size(220.dp, 150.dp).background(Color.Black).clickable {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Hub.banner.value = "No app on this phone plays this video" }
    }, contentAlignment = Alignment.Center) {
        coil.compose.AsyncImage(uri, null, Modifier.size(220.dp, 150.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, "Play video", tint = Color.White)
        }
        Icon(Icons.Default.Videocam, null, Modifier.align(Alignment.BottomStart).padding(6.dp).size(18.dp), tint = Color.White)
    }
}

// ---------------- Relationship card (#11) ----------------

@Composable
fun RelationshipCard(number: String) {
    var r by remember { mutableStateOf<Relationship?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(number) {
        val key = Phone.convKey(listOf(number))
        val thread = Hub.sms.threadIdFor(key)
        val all = Hub.sms.history(thread) + Hub.db.dao().local(key).map { it.date to it.outgoing }
        r = Relationship.compute(all); loaded = true
    }
    val x = r ?: run { if (loaded) Text("No messages yet.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    HubCard {
        Text("Texting since ${java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(x.first))}",
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MiniStat("${x.longestStreak} 🔥", "best streak")
            MiniStat("%.1f".format(x.perDay), "per day")
            MiniStat(hourLabel(x.topHour), "busiest hour")
        }
        Spacer(Modifier.height(10.dp))
        val minePct = if (x.total == 0) 50 else x.mine * 100 / x.total
        Text("You send $minePct% · they send ${100 - minePct}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(minePct / 100f).height(8.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}

private fun hourLabel(h: Int) = when { h == 0 -> "12 AM"; h < 12 -> "$h AM"; h == 12 -> "12 PM"; else -> "${h - 12} PM" }

@Composable
private fun MiniStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------- Check-in nudges (#10) ----------------

@Composable
fun NudgeRow(number: String) {
    val v by Hub.orbit.version.collectAsState()
    val days = remember(v) { Hub.orbit.nudgeDays(Phone.norm(number)) }
    ChoiceRow("Remind me if we haven't talked in", listOf("0" to "Off", "7" to "1 week", "14" to "2 weeks", "30" to "1 month"), days.toString()) {
        Hub.orbit.setNudge(Phone.norm(number), it.toInt())
    }
}

// ---------------- Call notes (#14) ----------------

@Composable
fun CallNotesSection(number: String) {
    val v by Hub.orbit.version.collectAsState()
    val notes = remember(v, number) { Hub.orbit.callNotes(number) }
    var adding by remember { mutableStateOf(com.yatesv.contacthub.engine.CallNotes.pendingFor?.let { Phone.norm(it) == Phone.norm(number) } == true) }
    LaunchedEffect(Unit) { if (adding) com.yatesv.contacthub.engine.CallNotes.pendingFor = null }
    SectionTitle("Call notes")
    HubCard {
        if (notes.isEmpty()) Text("Jot down what you talked about. Orbit offers this after each call.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        notes.take(10).forEach { n ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(n.text, style = MaterialTheme.typography.bodyMedium)
                    Text(fullTime(n.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ Hub.orbit.deleteCallNote(number, n.time) }, Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Delete note", Modifier.size(16.dp)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton({ adding = true }) { Text("Add note") } }
    }
    if (adding) {
        var t by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Call note") },
            text = { OutlinedTextField(t, { t = it }, placeholder = { Text("What did you talk about?") }, minLines = 3) },
            confirmButton = { TextButton({ if (t.isNotBlank()) Hub.orbit.addCallNote(number, t.trim()); adding = false }) { Text("Save") } },
            dismissButton = { TextButton({ adding = false }) { Text("Cancel") } },
        )
    }
}

// ---------------- Life360 trail (#17) ----------------

@Composable
fun TrailCard(memberId: String) {
    val v by Hub.orbit.version.collectAsState()
    val pts = remember(v, memberId) { Trail.today(Hub.orbit.trail(memberId)) }
    if (pts.size < 2) {
        Text("Today's trail shows up after a few Life360 updates (Settings → Life360 → refresh every 15 min).",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val accent = MaterialTheme.colorScheme.primary
    val mapRef = remember { arrayOfNulls<org.osmdroid.views.MapView>(1) }
    DisposableEffect(Unit) { onDispose { mapRef[0]?.onDetach() } }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(180.dp).clip(RoundedCornerShape(14.dp))) {
        androidx.compose.ui.viewinterop.AndroidView(factory = { c ->
            org.osmdroid.views.MapView(c).also { mapRef[0] = it }.apply {
                setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            }
        }, update = { map ->
            map.overlays.clear()
            val gp = pts.map { org.osmdroid.util.GeoPoint(it.lat, it.lng) }
            map.overlays.add(org.osmdroid.views.overlay.Polyline(map).apply {
                setPoints(gp); outlinePaint.color = android.graphics.Color.argb(255, (accent.red * 255).toInt(), (accent.green * 255).toInt(), (accent.blue * 255).toInt())
                outlinePaint.strokeWidth = 6f * map.context.resources.displayMetrics.density
            })
            map.post { runCatching { map.zoomToBoundingBox(org.osmdroid.util.BoundingBox.fromGeoPointsSafe(gp).increaseByScale(1.4f), false) } }
            map.invalidate()
        }, modifier = Modifier.matchParentSizeCompat())
    }
    Text("${pts.size} updates today · from ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(pts.first().t))}",
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun Modifier.matchParentSizeCompat() = this.then(Modifier.fillMaxWidth().height(180.dp))

// ---------------- Panic hide (#23) ----------------

/** Shake the phone while Orbit is open: it locks and jumps to your home screen. */
@Composable
fun PanicShake(activity: android.app.Activity) {
    val s by Hub.settings.flow.collectAsState()
    if (!s.panicShake) return
    DisposableEffect(Unit) {
        val sm = activity.getSystemService(SensorManager::class.java)
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var lastShake = 0L; var count = 0
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val g = Math.sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()) / SensorManager.GRAVITY_EARTH
                if (g > 2.7) {
                    val now = System.currentTimeMillis()
                    count = if (now - lastShake < 600) count + 1 else 1
                    lastShake = now
                    if (count >= 2) { count = 0; panic(activity) }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
}

fun panic(activity: android.app.Activity) {
    Nav.hiddenUnlocked = false
    Nav.stack.retainAll { it == Screen.Tabs }
    if (Hub.settings.value.appLock) Nav.locked = true
    activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------------- Crash report ----------------

/** After a crash: shows what happened, with a button to share the report. */
@Composable
fun CrashReportDialog() {
    val ctx = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.yatesv.contacthub.engine.CrashLog.unseen(ctx) } }
    val r = report ?: return
    AlertDialog(
        onDismissRequest = { com.yatesv.contacthub.engine.CrashLog.markSeen(ctx); report = null },
        title = { Text("Orbit closed unexpectedly") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text("Here's what went wrong. Share it so it can be fixed.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(r.take(3000), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton({
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r), "Share crash report")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                com.yatesv.contacthub.engine.CrashLog.markSeen(ctx); report = null
            }) { Text("Share report") }
        },
        dismissButton = { TextButton({ com.yatesv.contacthub.engine.CrashLog.markSeen(ctx); report = null }) { Text("Dismiss") } },
    )
}
